package io.github.denhaiz.exmail.ews

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import okio.ByteString.Companion.encodeUtf8
import io.github.denhaiz.exmail.auth.NtlmAuthenticator
import io.github.denhaiz.exmail.auth.NtlmCredentials
import io.github.denhaiz.exmail.ews.Xml.esc
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Параметры подключения; [basic] — Basic вместо NTLM (только по HTTPS). */
data class EwsConfig(val url: String, val credentials: NtlmCredentials, val basic: Boolean = false)

/** Текущие параметры подключения; читаются NTLM-аутентификатором и клиентом. */
class EwsSession {
    @Volatile
    var config: EwsConfig? = null

    /** Адрес ящика текущей учётной записи (для привязки подписок к серверу ящика). */
    @Volatile
    var mailbox: String? = null
}

/**
 * Клиент Exchange Web Services (схема Exchange 2013 SP1 и новее).
 *
 * Все запросы сериализуются мьютексом: NTLM аутентифицирует TCP-соединение, и последовательные
 * запросы через одно соединение избавляют от повторных рукопожатий.
 */
class EwsClient(
    private val http: OkHttpClient,
    private val session: EwsSession,
    private val windowsTimeZoneId: () -> String? = { null },
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
    /** Отправлять X-AnchorMailbox (клиент подписки на новые письма). */
    private val affinity: Boolean = false,
) {
    private val mutex = Mutex()

    // region Транспорт

    /**
     * Закрывает открытые соединения. NTLM аутентифицирует TCP-соединение целиком, поэтому после выхода
     * или перед входом под другой учётной записью старые соединения использовать нельзя.
     */
    fun evictConnections() = http.connectionPool.evictAll()

    /** Сервер отверг часовой пояс телефона — дальше работаем без TimeZoneContext (в UTC). */
    @Volatile
    private var timeZoneRejected = false

    /** Время последнего ответа сервера (System.nanoTime); 0 — запросов ещё не было. */
    @Volatile
    private var lastCallAt = 0L

    /** Часовой пояс Windows, который отправляется серверу; null — время в UTC. */
    val activeTimeZone: String? get() = if (timeZoneRejected) null else windowsTimeZoneId()

    suspend fun call(body: String, withTimeZone: Boolean = true): XmlNode {
        val tz = if (withTimeZone) activeTimeZone else null
        return try {
            send(body, tz)
        } catch (e: EwsException) {
            if (tz == null || !isTimeZoneError(e)) throw e
            // Пояс телефона неизвестен серверу (старая база поясов Windows): повторяем в UTC — время и так передаётся с «Z»
            timeZoneRejected = true
            send(body, null)
        }
    }

    private suspend fun send(body: String, timeZone: String?): XmlNode {
        val cfg = session.config ?: throw EwsAuthException()
        val envelope = envelope(body, timeZone)
        val request = Request.Builder()
            .url(cfg.url)
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("Accept", "text/xml")
            .applyAffinity()
            .post(envelope.toRequestBody(XML))
            .build()
        return mutex.withLock {
            // NTLM проверяет подлинность TCP-соединения, а рукопожатие повторяет запрос целиком (до трёх раз).
            // Перед крупным запросом (вложения) на возможно закрытом сервером соединении сначала проходим
            // рукопожатие коротким запросом — тогда вложение уходит один раз.
            if (envelope.length > LARGE_BODY && (lastCallAt == 0L || System.nanoTime() - lastCallAt > IDLE_NANOS)) warmUp(cfg.url)
            val result = http.newCall(request).await().use { response -> withContext(Dispatchers.IO) { handle(response) } }
            lastCallAt = System.nanoTime()
            result
        }
    }

    /**
     * Долгий запрос с потоковым ответом (GetStreamingEvents): сервер присылает несколько SOAP-конвертов
     * в одном HTTP-ответе; каждый передаётся в [onEnvelope] сразу по приходу. Мьютекс не берётся —
     * такой запрос держит соединение до получаса, поэтому для него нужен отдельный клиент.
     */
    suspend fun stream(body: String, onEnvelope: suspend (XmlNode) -> Unit) {
        val cfg = session.config ?: throw EwsAuthException()
        val request = Request.Builder()
            .url(cfg.url)
            .header("Content-Type", "text/xml; charset=utf-8")
            .header("Accept", "text/xml")
            .applyAffinity()
            .post(envelope(body, null).toRequestBody(XML))
            .build()
        val call = http.newCall(request)
        coroutineScope {
            // Отмена (выход, выключение доставки) закрывает сокет — блокирующее чтение прерывается сразу,
            // а не когда сервер сам закроет поток через полчаса
            val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    call.cancel()
                }
            }
            try {
                streamResponse(call, onEnvelope)
            } finally {
                watcher.cancel()
            }
        }
    }

    private suspend fun streamResponse(call: Call, onEnvelope: suspend (XmlNode) -> Unit) {
        withContext(Dispatchers.IO) {
            call.execute().use { response ->
                if (!response.isSuccessful) handle(response)
                val source = response.body?.source() ?: throw EwsHttpException(response.code, "Пустой ответ сервера")
                val pending = Buffer()
                while (source.read(pending, 8192) != -1L) {
                    ensureActive()
                    while (true) {
                        val end = pending.indexOf(ENVELOPE_END)
                        if (end < 0) break
                        val text = pending.readUtf8(end + ENVELOPE_END.size)
                        val start = text.indexOf('<')
                        if (start < 0) continue
                        val root = runCatching { Xml.parse(text.substring(start)) }.getOrNull() ?: continue
                        val soapBody = root.child("Body") ?: continue
                        soapBody.child("Fault")?.let { fault ->
                            val code = fault.find("ResponseCode")?.text ?: fault.text("faultcode") ?: "SoapFault"
                            throw EwsException(code, fault.find("Message")?.text ?: fault.text("faultstring") ?: "Ошибка сервера")
                        }
                        onEnvelope(soapBody)
                    }
                }
            }
        }
    }

    /**
     * Для подписок: запросы должны попадать на сервер, где открыт ящик (несколько серверов Exchange за балансировщиком).
     */
    private fun Request.Builder.applyAffinity(): Request.Builder {
        val mailbox = session.mailbox
        if (affinity && !mailbox.isNullOrBlank()) {
            header("X-AnchorMailbox", mailbox)
            header("X-PreferServerAffinity", "true")
        }
        return this
    }

    /** Короткий запрос для аутентификации соединения; ошибки не важны — их покажет основной запрос. */
    private suspend fun warmUp(url: String) {
        val probe = Request.Builder()
            .url(url)
            .header("Content-Type", "text/xml; charset=utf-8")
            .post(envelope(WARM_UP_BODY, null).toRequestBody(XML))
            .build()
        try {
            http.newCall(probe).await().close()
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
        }
    }

    private fun isTimeZoneError(e: EwsException) =
        e.code.contains("TimeZone", true) || e.message.orEmpty().contains("time zone", true)

    /** Начало/конец события «на весь день» для отправки: местная полночь, а без пояса на сервере — полночь UTC той же даты. */
    internal fun allDayBoundary(i: Instant): Instant =
        if (activeTimeZone != null) i else i.atZone(zone()).toLocalDate().atStartOfDay(ZoneOffset.UTC).toInstant()

    /**
     * Событие «на весь день» хранится от полуночи до полуночи в поясе, где его создали. В другом поясе
     * (или при работе в UTC) границы сдвигаются на часы — показываем по ближайшей местной полуночи.
     */
    internal fun nearestMidnight(i: Instant): Instant {
        val t = i.atZone(zone())
        val day = if (t.hour >= 12) t.toLocalDate().plusDays(1) else t.toLocalDate()
        return day.atStartOfDay(zone()).toInstant()
    }

    private fun CalendarEvent.localAllDay(): CalendarEvent {
        if (!isAllDay) return this
        val s = nearestMidnight(start)
        val e = nearestMidnight(end).takeIf { it.isAfter(s) } ?: s.atZone(zone()).plusDays(1).toInstant()
        return copy(start = s, end = e)
    }

    private fun handle(response: Response): XmlNode {
        if (response.code == 401) throw EwsAuthException()
        val body = response.body ?: throw EwsHttpException(response.code, "Пустой ответ сервера")
        val contentType = response.header("Content-Type").orEmpty()
        if (!contentType.contains("xml", true)) {
            throw EwsHttpException(response.code, "Сервер вернул ошибку HTTP ${response.code}")
        }
        val root = try {
            Xml.parse(body.byteStream())
        } catch (e: Exception) {
            throw EwsHttpException(response.code, "Не удалось разобрать ответ сервера: ${e.message}")
        }
        val soapBody = root.child("Body") ?: throw EwsHttpException(response.code, "Некорректный SOAP-ответ")
        soapBody.child("Fault")?.let { fault ->
            val code = fault.find("ResponseCode")?.text ?: fault.text("faultcode") ?: "SoapFault"
            val msg = fault.find("Message")?.text ?: fault.text("faultstring") ?: "Ошибка сервера"
            throw EwsException(code, msg)
        }
        if (!response.isSuccessful) throw EwsHttpException(response.code, "Сервер вернул ошибку HTTP ${response.code}")
        return soapBody
    }

    /**
     * Autodiscover (POX): адрес EWS по e-mail. Пробует https://домен и https://autodiscover.домен
     * с текущими учётными данными сессии. Возвращает null, если найти не удалось.
     */
    suspend fun autodiscover(email: String): String? {
        val domain = email.substringAfter('@', "").trim().lowercase()
        if (domain.isEmpty()) return null
        val request = """<?xml version="1.0" encoding="utf-8"?>""" +
            """<Autodiscover xmlns="http://schemas.microsoft.com/exchange/autodiscover/outlook/requestschema/2006">""" +
            """<Request><EMailAddress>${esc(email.trim())}</EMailAddress>""" +
            """<AcceptableResponseSchema>http://schemas.microsoft.com/exchange/autodiscover/outlook/responseschema/2006a</AcceptableResponseSchema>""" +
            """</Request></Autodiscover>"""
        for (host in listOf(domain, "autodiscover.$domain")) {
            val url = "https://$host/autodiscover/autodiscover.xml"
            val found = try {
                mutex.withLock {
                    http.newCall(Request.Builder().url(url).post(request.toRequestBody(XML)).build()).await().use { r ->
                        if (!r.isSuccessful) return@use null
                        val root = withContext(Dispatchers.IO) { r.body?.byteStream()?.let(Xml::parse) } ?: return@use null
                        val protocols = root.findAll("Protocol")
                        // Внешний адрес (EXPR) подходит для телефона лучше внутреннего (EXCH)
                        listOf("EXPR", "EXCH").firstNotNullOfOrNull { type ->
                            protocols.firstOrNull { it.text("Type") == type }?.text("EwsUrl")
                        } ?: root.find("EwsUrl")?.text
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (!found.isNullOrBlank()) return found.trim()
        }
        return null
    }

    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                cont.resume(response) { _, value, _ -> value.close() }
            }
        })
        cont.invokeOnCancellation { runCatching { cancel() } }
    }

    // endregion

    // region Папки

    /** Возвращает id стандартных папок: inbox, sentitems, drafts, deleteditems, junkemail, outbox, calendar, contacts. */
    suspend fun getWellKnownFolders(): Map<String, EwsFolder> {
        val ids = WELL_KNOWN.joinToString("") { "<t:DistinguishedFolderId Id=\"$it\"/>" }
        val body = call(
            """<m:GetFolder><m:FolderShape><t:BaseShape>AllProperties</t:BaseShape></m:FolderShape>""" +
                """<m:FolderIds>$ids</m:FolderIds></m:GetFolder>""",
        )
        val result = LinkedHashMap<String, EwsFolder>()
        responseMessages(body).forEachIndexed { i, msg ->
            if (msg.attr("ResponseClass") == "Error") return@forEachIndexed
            val folderNode = msg.child("Folders")?.children?.firstOrNull() ?: return@forEachIndexed
            WELL_KNOWN.getOrNull(i)?.let { result[it] = parseFolder(folderNode) }
        }
        return result
    }

    /** Все почтовые папки ящика (дерево под msgfolderroot), без скрытых. */
    suspend fun findMailFolders(): List<EwsFolder> {
        val body = call(
            """<m:FindFolder Traversal="Deep"><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape>""" +
                """<t:AdditionalProperties>""" +
                field("folder:DisplayName") + field("folder:FolderClass") + field("folder:ParentFolderId") +
                field("folder:TotalCount") + field("folder:UnreadCount") + field("folder:ChildFolderCount") +
                """<t:ExtendedFieldURI PropertyTag="0x10F4" PropertyType="Boolean"/>""" +
                """</t:AdditionalProperties></m:FolderShape>""" +
                """<m:IndexedPageFolderView MaxEntriesReturned="1000" Offset="0" BasePoint="Beginning"/>""" +
                """<m:ParentFolderIds><t:DistinguishedFolderId Id="msgfolderroot"/></m:ParentFolderIds></m:FindFolder>""",
        )
        val msg = responseMessages(body).firstOrNull()?.ok() ?: return emptyList()
        return msg.at("RootFolder", "Folders")?.children.orEmpty()
            .map(::parseFolder)
            .filter { !it.hidden && (it.folderClass == null || it.folderClass.startsWith("IPF.Note", true)) }
    }

    // endregion

    // region Почта

    suspend fun findMessages(
        folderId: String,
        offset: Int = 0,
        limit: Int = 50,
        query: String? = null,
        /** Только письма, полученные не позже (страница «загрузить ещё» по дате). */
        before: Instant? = null,
        /** Только письма, полученные в [from, until) — поиск по дате. Вместе с [query] не задаётся. */
        from: Instant? = null,
        until: Instant? = null,
    ): MessagePage {
        val q = query?.takeIf { it.isNotBlank() }?.let { "<m:QueryString>${esc(it)}</m:QueryString>" } ?: ""
        fun received(op: String, at: Instant) =
            """<t:$op>${field("item:DateTimeReceived")}""" +
                """<t:FieldURIOrConstant><t:Constant Value="${Xml.formatInstant(at)}"/></t:FieldURIOrConstant></t:$op>"""
        val conditions = listOfNotNull(
            before?.let { received("IsLessThanOrEqualTo", it) },
            from?.let { received("IsGreaterThanOrEqualTo", it) },
            until?.let { received("IsLessThan", it) },
        )
        val restriction = when (conditions.size) {
            0 -> ""
            1 -> "<m:Restriction>${conditions[0]}</m:Restriction>"
            else -> "<m:Restriction><t:And>${conditions.joinToString("")}</t:And></m:Restriction>"
        }
        val body = call(
            """<m:FindItem Traversal="Shallow"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>""" +
                """<t:AdditionalProperties>$SUMMARY_FIELDS</t:AdditionalProperties></m:ItemShape>""" +
                """<m:IndexedPageItemView MaxEntriesReturned="$limit" Offset="$offset" BasePoint="Beginning"/>$restriction""" +
                """<m:SortOrder><t:FieldOrder Order="Descending">${field("item:DateTimeReceived")}</t:FieldOrder></m:SortOrder>""" +
                """<m:ParentFolderIds>${folderIdXml(folderId)}</m:ParentFolderIds>$q</m:FindItem>""",
        )
        val msg = responseMessages(body).first().ok()
        val root = msg.child("RootFolder")
        val items = root?.child("Items")?.children.orEmpty().map(::parseSummary)
        return MessagePage(
            items = items,
            total = root?.attr("TotalItemsInView")?.toIntOrNull() ?: items.size,
            isLast = root?.attr("IncludesLastItemInRange")?.equals("true", true) ?: true,
        )
    }

    suspend fun getMessage(id: String): MessageDetail {
        val body = call(
            """<m:GetItem><m:ItemShape><t:BaseShape>AllProperties</t:BaseShape><t:BodyType>HTML</t:BodyType>""" +
                """<t:AdditionalProperties>""" + field("item:Body") + field("message:ToRecipients") +
                field("message:CcRecipients") + field("message:BccRecipients") + field("item:Attachments") +
                """</t:AdditionalProperties></m:ItemShape>""" +
                """<m:ItemIds><t:ItemId Id="${esc(id)}"/></m:ItemIds></m:GetItem>""",
        )
        val node = responseMessages(body).first().ok().child("Items")?.children?.firstOrNull()
            ?: throw EwsException("ErrorItemNotFound", "Письмо не найдено")
        return parseDetail(node)
    }

    /** Содержимое вложений в порядке [ids]; null — вложение не получено (ошибка или вложенное письмо). */
    suspend fun getAttachments(ids: List<String>): List<FileAttachmentData?> {
        if (ids.isEmpty()) return emptyList()
        val body = call(
            """<m:GetAttachment><m:AttachmentIds>""" +
                ids.joinToString("") { """<t:AttachmentId Id="${esc(it)}"/>""" } +
                """</m:AttachmentIds></m:GetAttachment>""",
        )
        return responseMessages(body).map { msg ->
            if (msg.attr("ResponseClass") == "Error") return@map null
            val att = msg.child("Attachments")?.child("FileAttachment") ?: return@map null
            FileAttachmentData(
                name = att.text("Name").orEmpty(),
                contentType = att.text("ContentType") ?: "application/octet-stream",
                bytes = Base64.getMimeDecoder().decode(att.text("Content").orEmpty()),
            )
        }
    }

    suspend fun setRead(refs: List<ItemRef>, read: Boolean) =
        updateMessages(refs, "message:IsRead", "<t:IsRead>$read</t:IsRead>")

    suspend fun setFlag(refs: List<ItemRef>, flagged: Boolean) =
        updateMessages(refs, "item:Flag", "<t:Flag><t:FlagStatus>${if (flagged) "Flagged" else "NotFlagged"}</t:FlagStatus></t:Flag>")

    private suspend fun updateMessages(refs: List<ItemRef>, fieldUri: String, valueXml: String) {
        if (refs.isEmpty()) return
        val changes = refs.joinToString("") { ref ->
            """<t:ItemChange><t:ItemId Id="${esc(ref.id)}"/><t:Updates><t:SetItemField>${field(fieldUri)}""" +
                """<t:Message>$valueXml</t:Message></t:SetItemField></t:Updates></t:ItemChange>"""
        }
        val body = call(
            """<m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AlwaysOverwrite" SuppressReadReceipts="true">""" +
                """<m:ItemChanges>$changes</m:ItemChanges></m:UpdateItem>""",
        )
        responseMessages(body).forEach { it.ok() }
    }

    /** Удаление в «Удалённые» (для встреч — с отправкой отмены участникам, если [sendCancellations]). */
    suspend fun deleteItems(
        refs: List<ItemRef>,
        sendCancellations: Boolean = false,
        hardDelete: Boolean = false,
        wholeSeries: Boolean = false,
    ) {
        if (refs.isEmpty()) return
        val ids = if (wholeSeries) refs.joinToString("") { "<t:RecurringMasterItemId OccurrenceId=\"${esc(it.id)}\"/>" }
        else refs.joinToString("") { itemIdXml(it) }
        val type = if (hardDelete) "SoftDelete" else "MoveToDeletedItems"
        val cancel = if (sendCancellations) "SendToAllAndSaveCopy" else "SendToNone"
        val body = call(
            """<m:DeleteItem DeleteType="$type" SendMeetingCancellations="$cancel" AffectedTaskOccurrences="AllOccurrences">""" +
                """<m:ItemIds>$ids</m:ItemIds></m:DeleteItem>""",
        )
        responseMessages(body).forEach { it.ok(setOf("ErrorItemNotFound")) }
    }

    suspend fun moveItems(refs: List<ItemRef>, toFolderId: String) {
        if (refs.isEmpty()) return
        val body = call(
            """<m:MoveItem><m:ToFolderId>${folderIdXml(toFolderId)}</m:ToFolderId>""" +
                """<m:ItemIds>${refs.joinToString("") { itemIdXml(ItemRef(it.id)) }}</m:ItemIds></m:MoveItem>""",
        )
        responseMessages(body).forEach { it.ok() }
    }

    suspend fun sendMessage(message: OutgoingMessage) {
        val content = "<t:Subject>${esc(message.subject)}</t:Subject>" +
            "<t:Body BodyType=\"HTML\">${esc(message.bodyHtml)}</t:Body>" +
            (if (message.highImportance) "<t:Importance>High</t:Importance>" else "") +
            recipients("ToRecipients", message.to) + recipients("CcRecipients", message.cc) +
            recipients("BccRecipients", message.bcc)
        if (message.attachments.isEmpty()) {
            val body = call(
                """<m:CreateItem MessageDisposition="SendAndSaveCopy">""" +
                    """<m:SavedItemFolderId><t:DistinguishedFolderId Id="sentitems"/></m:SavedItemFolderId>""" +
                    """<m:Items><t:Message>$content</t:Message></m:Items></m:CreateItem>""",
            )
            responseMessages(body).forEach { it.ok() }
        } else {
            val draft = createDraft("<t:Message>$content</t:Message>")
            sendWithAttachments(draft, message.attachments)
        }
    }

    suspend fun reply(
        kind: ReplyKind,
        original: ItemRef,
        bodyHtml: String,
        to: List<String> = emptyList(),
        cc: List<String> = emptyList(),
        bcc: List<String> = emptyList(),
        subject: String? = null,
        attachments: List<NewAttachment> = emptyList(),
    ) {
        val content = "<t:${kind.element}>" +
            (subject?.let { "<t:Subject>${esc(it)}</t:Subject>" } ?: "") +
            recipients("ToRecipients", to) + recipients("CcRecipients", cc) + recipients("BccRecipients", bcc) +
            referenceItemId(withChangeKey(original)) +
            "<t:NewBodyContent BodyType=\"HTML\">${esc(bodyHtml)}</t:NewBodyContent>" +
            "</t:${kind.element}>"
        if (attachments.isEmpty()) {
            val body = call(
                """<m:CreateItem MessageDisposition="SendAndSaveCopy">""" +
                    """<m:SavedItemFolderId><t:DistinguishedFolderId Id="sentitems"/></m:SavedItemFolderId>""" +
                    """<m:Items>$content</m:Items></m:CreateItem>""",
            )
            responseMessages(body).forEach { it.ok() }
        } else {
            sendWithAttachments(createDraft(content), attachments)
        }
    }

    /**
     * Актуальный ChangeKey элемента. Exchange требует его в ReferenceItemId (ответ, пересылка, ответ на приглашение),
     * а меняется он при любом изменении письма — например, при отметке «прочитано» сразу после открытия.
     */
    internal suspend fun withChangeKey(ref: ItemRef): ItemRef {
        val body = call(
            """<m:GetItem><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>""" +
                """<m:ItemIds><t:ItemId Id="${esc(ref.id)}"/></m:ItemIds></m:GetItem>""",
        )
        val id = responseMessages(body).first().ok().child("Items")?.children?.firstOrNull()?.child("ItemId")
        return ItemRef(id?.attr("Id") ?: ref.id, id?.attr("ChangeKey") ?: ref.changeKey)
    }

    internal suspend fun createDraft(itemXml: String): ItemRef {
        val body = call(
            """<m:CreateItem MessageDisposition="SaveOnly">""" +
                """<m:SavedItemFolderId><t:DistinguishedFolderId Id="drafts"/></m:SavedItemFolderId>""" +
                """<m:Items>$itemXml</m:Items></m:CreateItem>""",
        )
        val item = responseMessages(body).first().ok().child("Items")?.children?.firstOrNull()?.child("ItemId")
            ?: throw EwsException("ErrorCreateItem", "Сервер не вернул черновик")
        return ItemRef(item.attr("Id")!!, item.attr("ChangeKey"))
    }

    internal suspend fun addAttachments(draft: ItemRef, attachments: List<NewAttachment>): ItemRef {
        var ref = draft
        for (a in attachments) {
            val body = call(
                """<m:CreateAttachment><m:ParentItemId Id="${esc(ref.id)}"${ref.changeKey?.let { " ChangeKey=\"${esc(it)}\"" }.orEmpty()}/>""" +
                    """<m:Attachments><t:FileAttachment><t:Name>${esc(a.name)}</t:Name>""" +
                    """<t:ContentType>${esc(a.contentType)}</t:ContentType>""" +
                    """<t:Content>${Base64.getEncoder().encodeToString(a.bytes)}</t:Content>""" +
                    """</t:FileAttachment></m:Attachments></m:CreateAttachment>""",
            )
            val id = responseMessages(body).first().ok().find("AttachmentId")
            ref = ItemRef(id?.attr("RootItemId") ?: ref.id, id?.attr("RootItemChangeKey") ?: ref.changeKey)
        }
        return ref
    }

    internal suspend fun sendItem(ref: ItemRef) {
        val body = call(
            """<m:SendItem SaveItemToFolder="true"><m:ItemIds>${itemIdXml(ref)}</m:ItemIds>""" +
                """<m:SavedItemFolderId><t:DistinguishedFolderId Id="sentitems"/></m:SavedItemFolderId></m:SendItem>""",
        )
        responseMessages(body).forEach { it.ok() }
    }

    private suspend fun sendWithAttachments(draft: ItemRef, attachments: List<NewAttachment>) = sendItem(addAttachments(draft, attachments))

    // endregion

    // region Календарь

    suspend fun findEvents(start: Instant, end: Instant): List<CalendarEvent> {
        val body = call(
            """<m:FindItem Traversal="Shallow"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>""" +
                """<t:AdditionalProperties>$EVENT_FIELDS</t:AdditionalProperties></m:ItemShape>""" +
                """<m:CalendarView MaxEntriesReturned="1000" StartDate="${Xml.formatInstant(start)}" EndDate="${Xml.formatInstant(end)}"/>""" +
                """<m:ParentFolderIds><t:DistinguishedFolderId Id="calendar"/></m:ParentFolderIds></m:FindItem>""",
        )
        val msg = responseMessages(body).first().ok()
        return msg.at("RootFolder", "Items")?.children.orEmpty().mapNotNull(::parseEvent).map { it.localAllDay() }
    }

    suspend fun getEvent(id: String): CalendarEvent {
        val body = call(
            """<m:GetItem><m:ItemShape><t:BaseShape>AllProperties</t:BaseShape><t:BodyType>HTML</t:BodyType>""" +
                """<t:AdditionalProperties>$EVENT_FIELDS""" + field("item:Body") +
                field("calendar:RequiredAttendees") + field("calendar:OptionalAttendees") +
                field("item:ReminderIsSet") + field("item:ReminderMinutesBeforeStart") + field("calendar:IsOrganizer") +
                field("calendar:Resources") + field("calendar:CalendarItemType") +
                """</t:AdditionalProperties></m:ItemShape>""" +
                """<m:ItemIds><t:ItemId Id="${esc(id)}"/></m:ItemIds></m:GetItem>""",
        )
        val node = responseMessages(body).first().ok().child("Items")?.children?.firstOrNull()
        return node?.let(::parseEvent)?.localAllDay() ?: throw EwsException("ErrorItemNotFound", "Событие не найдено")
    }

    private fun eventStart(d: EventDraft) = if (d.isAllDay) allDayBoundary(d.start) else d.start
    private fun eventEnd(d: EventDraft) = if (d.isAllDay) allDayBoundary(d.end) else d.end

    suspend fun createEvent(draft: EventDraft): ItemRef {
        val invite = if (draft.required.isEmpty() && draft.optional.isEmpty() && draft.resources.isEmpty()) "SendToNone" else "SendToAllAndSaveCopy"
        val body = call(
            """<m:CreateItem SendMeetingInvitations="$invite">""" +
                """<m:SavedItemFolderId><t:DistinguishedFolderId Id="calendar"/></m:SavedItemFolderId>""" +
                """<m:Items><t:CalendarItem>""" +
                "<t:Subject>${esc(draft.subject)}</t:Subject>" +
                "<t:Body BodyType=\"HTML\">${esc(draft.bodyHtml)}</t:Body>" +
                "<t:ReminderIsSet>${draft.reminderMinutes != null}</t:ReminderIsSet>" +
                (draft.reminderMinutes?.let { "<t:ReminderMinutesBeforeStart>$it</t:ReminderMinutesBeforeStart>" } ?: "") +
                "<t:Start>${Xml.formatInstant(eventStart(draft))}</t:Start>" +
                "<t:End>${Xml.formatInstant(eventEnd(draft))}</t:End>" +
                "<t:IsAllDayEvent>${draft.isAllDay}</t:IsAllDayEvent>" +
                "<t:LegacyFreeBusyStatus>${if (draft.isAllDay) "Free" else "Busy"}</t:LegacyFreeBusyStatus>" +
                (if (draft.location.isNotBlank()) "<t:Location>${esc(draft.location)}</t:Location>" else "") +
                attendees("RequiredAttendees", draft.required) + attendees("OptionalAttendees", draft.optional) +
                attendees("Resources", draft.resources) +
                (draft.recurrence?.let { recurrenceXml(it, draft.start) } ?: "") +
                """</t:CalendarItem></m:Items></m:CreateItem>""",
        )
        val id = responseMessages(body).first().ok().find("ItemId")
            ?: throw EwsException("ErrorCreateItem", "Сервер не вернул событие")
        return ItemRef(id.attr("Id")!!, id.attr("ChangeKey"))
    }

    suspend fun updateEvent(ref: ItemRef, draft: EventDraft, notifyAttendees: Boolean) {
        fun set(uri: String, value: String) =
            "<t:SetItemField>${field(uri)}<t:CalendarItem>$value</t:CalendarItem></t:SetItemField>"
        fun del(uri: String) = "<t:DeleteItemField>${field(uri)}</t:DeleteItemField>"

        val updates = buildString {
            append(set("item:Subject", "<t:Subject>${esc(draft.subject)}</t:Subject>"))
            append(set("item:Body", "<t:Body BodyType=\"HTML\">${esc(draft.bodyHtml)}</t:Body>"))
            append(set("item:ReminderIsSet", "<t:ReminderIsSet>${draft.reminderMinutes != null}</t:ReminderIsSet>"))
            draft.reminderMinutes?.let {
                append(set("item:ReminderMinutesBeforeStart", "<t:ReminderMinutesBeforeStart>$it</t:ReminderMinutesBeforeStart>"))
            }
            append(set("calendar:Start", "<t:Start>${Xml.formatInstant(eventStart(draft))}</t:Start>"))
            append(set("calendar:End", "<t:End>${Xml.formatInstant(eventEnd(draft))}</t:End>"))
            append(set("calendar:IsAllDayEvent", "<t:IsAllDayEvent>${draft.isAllDay}</t:IsAllDayEvent>"))
            append(
                if (draft.location.isBlank()) del("calendar:Location")
                else set("calendar:Location", "<t:Location>${esc(draft.location)}</t:Location>"),
            )
            append(
                if (draft.required.isEmpty()) del("calendar:RequiredAttendees")
                else set("calendar:RequiredAttendees", attendees("RequiredAttendees", draft.required)),
            )
            append(
                if (draft.optional.isEmpty()) del("calendar:OptionalAttendees")
                else set("calendar:OptionalAttendees", attendees("OptionalAttendees", draft.optional)),
            )
            append(
                if (draft.resources.isEmpty()) del("calendar:Resources")
                else set("calendar:Resources", attendees("Resources", draft.resources)),
            )
            draft.recurrence?.let { append(set("calendar:Recurrence", recurrenceXml(it, draft.start))) }
        }
        val send = if (notifyAttendees) "SendToAllAndSaveCopy" else "SendToNone"
        val body = call(
            """<m:UpdateItem ConflictResolution="AlwaysOverwrite" SendMeetingInvitationsOrCancellations="$send">""" +
                """<m:ItemChanges><t:ItemChange><t:ItemId Id="${esc(ref.id)}"/><t:Updates>$updates</t:Updates>""" +
                """</t:ItemChange></m:ItemChanges></m:UpdateItem>""",
        )
        responseMessages(body).forEach { it.ok() }
    }

    /** Ответ на приглашение; [ref] — письмо-приглашение или само событие календаря. */
    suspend fun respondToMeeting(ref: ItemRef, response: MeetingResponse, sendResponse: Boolean = true, comment: String? = null) {
        val disposition = if (sendResponse) "SendAndSaveCopy" else "SaveOnly"
        val bodyXml = comment?.takeIf { it.isNotBlank() }
            ?.let { "<t:Body BodyType=\"Text\">${esc(it)}</t:Body>" } ?: ""
        val body = call(
            """<m:CreateItem MessageDisposition="$disposition"><m:Items>""" +
                "<t:${response.element}>$bodyXml${referenceItemId(withChangeKey(ref))}</t:${response.element}>" +
                """</m:Items></m:CreateItem>""",
        )
        responseMessages(body).forEach { it.ok() }
    }

    /** Занятость участников на интервал [start, end) с шагом [intervalMinutes]. */
    suspend fun getAvailability(emails: List<String>, start: Instant, end: Instant, intervalMinutes: Int = 30): List<Availability> {
        if (emails.isEmpty()) return emptyList()
        val z = zone()
        val offsetMin = z.rules.getOffset(start).totalSeconds / 60
        val noDst = "<t:Bias>0</t:Bias><t:Time>00:00:00</t:Time><t:DayOrder>1</t:DayOrder><t:Month>1</t:Month><t:DayOfWeek>Sunday</t:DayOfWeek>"
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val body = call(
            """<m:GetUserAvailabilityRequest>""" +
                """<t:TimeZone><t:Bias>${-offsetMin}</t:Bias><t:StandardTime>$noDst</t:StandardTime><t:DaylightTime>$noDst</t:DaylightTime></t:TimeZone>""" +
                """<m:MailboxDataArray>""" + emails.joinToString("") {
                    """<t:MailboxData><t:Email><t:Address>${esc(it)}</t:Address></t:Email><t:AttendeeType>Required</t:AttendeeType><t:ExcludeConflicts>false</t:ExcludeConflicts></t:MailboxData>"""
                } + """</m:MailboxDataArray>""" +
                """<t:FreeBusyViewOptions><t:TimeWindow>""" +
                """<t:StartTime>${LocalDateTime.ofInstant(start, z).format(fmt)}</t:StartTime>""" +
                """<t:EndTime>${LocalDateTime.ofInstant(end, z).format(fmt)}</t:EndTime></t:TimeWindow>""" +
                """<t:MergedFreeBusyIntervalInMinutes>$intervalMinutes</t:MergedFreeBusyIntervalInMinutes>""" +
                """<t:RequestedView>MergedOnly</t:RequestedView></t:FreeBusyViewOptions>""" +
                """</m:GetUserAvailabilityRequest>""",
            withTimeZone = false,
        )
        val responses = body.find("FreeBusyResponseArray")?.children("FreeBusyResponse").orEmpty()
        return emails.mapIndexed { i, email ->
            val r = responses.getOrNull(i)
            val rm = r?.child("ResponseMessage")
            if (rm?.attr("ResponseClass") == "Error") {
                Availability(email, "", rm.text("MessageText") ?: "нет данных")
            } else {
                Availability(email, r?.find("MergedFreeBusy")?.text.orEmpty())
            }
        }
    }

    // endregion

    // region Контакты и адресная книга

    suspend fun findContacts(offset: Int = 0, limit: Int = 500): Pair<List<ContactInfo>, Boolean> {
        val fields = field("contacts:DisplayName") + field("contacts:CompanyName") + field("contacts:JobTitle") +
            field("contacts:Department") + field("contacts:OfficeLocation") +
            indexed("contacts:EmailAddress", "EmailAddress1") +
            indexed("contacts:PhoneNumber", "MobilePhone") + indexed("contacts:PhoneNumber", "BusinessPhone") +
            indexed("contacts:PhoneNumber", "HomePhone")
        val body = call(
            """<m:FindItem Traversal="Shallow"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape>""" +
                """<t:AdditionalProperties>$fields</t:AdditionalProperties></m:ItemShape>""" +
                """<m:IndexedPageItemView MaxEntriesReturned="$limit" Offset="$offset" BasePoint="Beginning"/>""" +
                """<m:SortOrder><t:FieldOrder Order="Ascending">${field("contacts:DisplayName")}</t:FieldOrder></m:SortOrder>""" +
                """<m:ParentFolderIds><t:DistinguishedFolderId Id="contacts"/></m:ParentFolderIds></m:FindItem>""",
        )
        val root = responseMessages(body).first().ok().child("RootFolder")
        val list = root?.child("Items")?.children("Contact").orEmpty().map { c ->
            ContactInfo(
                id = c.child("ItemId")?.attr("Id"),
                displayName = c.text("DisplayName").orEmpty(),
                email = c.child("EmailAddresses")?.children("Entry")?.firstOrNull()?.text?.removeSmtpPrefix().orEmpty(),
                phones = parsePhones(c),
                company = c.text("CompanyName").orEmpty(),
                department = c.text("Department").orEmpty(),
                jobTitle = c.text("JobTitle").orEmpty(),
                office = c.text("OfficeLocation").orEmpty(),
            )
        }
        val isLast = root?.attr("IncludesLastItemInRange")?.equals("true", true) ?: true
        return list to isLast
    }

    /**
     * Поиск по адресной книге (GAL) и/или личным контактам через ResolveNames (до 100 результатов).
     * @param includePersonal искать и в папке «Контакты»
     */
    suspend fun resolveNames(query: String, includePersonal: Boolean = false): List<ContactInfo> {
        if (query.isBlank()) return emptyList()
        val scope = if (includePersonal) "ActiveDirectoryContacts" else "ActiveDirectory"
        val body = call(
            """<m:ResolveNames ReturnFullContactData="true" SearchScope="$scope" ContactDataShape="AllProperties">""" +
                """<m:UnresolvedEntry>${esc(query.trim())}</m:UnresolvedEntry></m:ResolveNames>""",
        )
        val msg = responseMessages(body).firstOrNull() ?: return emptyList()
        if (msg.attr("ResponseClass") == "Error") {
            val code = msg.text("ResponseCode").orEmpty()
            if (code == "ErrorNameResolutionNoResults") return emptyList()
            throw EwsException(code, msg.text("MessageText") ?: code)
        }
        return msg.child("ResolutionSet")?.children("Resolution").orEmpty().mapNotNull { r ->
            val mb = r.child("Mailbox")
            val c = r.child("Contact")
            val email = mb?.text("EmailAddress")?.takeIf { mb.text("RoutingType") != "EX" }
                ?: c?.child("EmailAddresses")?.children("Entry")?.firstOrNull()?.text?.removeSmtpPrefix()
                ?: return@mapNotNull null
            val type = mb?.text("MailboxType").orEmpty()
            ContactInfo(
                id = mb?.child("ItemId")?.attr("Id"),
                displayName = c?.text("DisplayName")?.takeIf { it.isNotBlank() } ?: mb?.text("Name") ?: email,
                email = email,
                phones = c?.let(::parsePhones).orEmpty(),
                company = c?.text("CompanyName").orEmpty(),
                department = c?.text("Department").orEmpty(),
                jobTitle = c?.text("JobTitle").orEmpty(),
                office = c?.text("OfficeLocation").orEmpty(),
                isGroup = type == "PublicDL" || type == "PrivateDL",
            )
        }
    }

    /** Фото пользователя из Exchange/AD или null. */
    suspend fun getUserPhoto(email: String, size: String = "HR96x96"): ByteArray? {
        val body = call(
            """<m:GetUserPhoto><m:Email>${esc(email)}</m:Email><m:SizeRequested>$size</m:SizeRequested></m:GetUserPhoto>""",
        )
        val resp = body.child("GetUserPhotoResponse") ?: return null
        if (resp.attr("ResponseClass") == "Error") {
            val code = resp.text("ResponseCode").orEmpty()
            // Сервер занят или временная ошибка — это не «у сотрудника нет фото»
            if (TRANSIENT.any { code.contains(it, true) }) throw EwsException(code, resp.text("MessageText") ?: code)
            return null
        }
        val data = resp.text("PictureData")?.trim().orEmpty()
        return if (data.isEmpty()) null else Base64.getMimeDecoder().decode(data)
    }

    // endregion

    // region Разбор ответов

    internal fun responseMessages(body: XmlNode): List<XmlNode> =
        body.find("ResponseMessages")?.children.orEmpty()

    internal fun XmlNode.ok(allowed: Set<String> = emptySet()): XmlNode {
        if (attr("ResponseClass") == "Error") {
            val code = text("ResponseCode").orEmpty()
            if (code !in allowed) throw EwsException(code, text("MessageText")?.takeIf { it.isNotBlank() } ?: code)
        }
        return this
    }

    companion object {
        /** Коды временных ошибок сервера. */
        private val TRANSIENT = listOf("ServerBusy", "Timeout", "InternalServerError", "Transient", "TooManyObjects", "Throttl")

        /** Запрос больше этого (вложения) перед отправкой «прогревает» соединение. */
        private const val LARGE_BODY = 64 * 1024

        /** Соединение, простаивавшее дольше, могло быть закрыто сервером (IIS по умолчанию — 2 минуты). */
        private const val IDLE_NANOS = 20_000_000_000L

        private val ENVELOPE_END = "Envelope>".encodeUtf8()

        private const val WARM_UP_BODY = "<m:GetFolder><m:FolderShape><t:BaseShape>IdOnly</t:BaseShape></m:FolderShape>" +
            "<m:FolderIds><t:DistinguishedFolderId Id=\"inbox\"/></m:FolderIds></m:GetFolder>"

        private val XML = "text/xml; charset=utf-8".toMediaType()

        val WELL_KNOWN = listOf("inbox", "drafts", "sentitems", "deleteditems", "junkemail", "outbox", "calendar", "contacts")
        private val DISTINGUISHED = setOf(
            "inbox", "drafts", "sentitems", "deleteditems", "junkemail", "outbox", "calendar", "contacts",
            "msgfolderroot", "archive", "root",
        )

        internal fun field(uri: String) = "<t:FieldURI FieldURI=\"$uri\"/>"
        private fun indexed(uri: String, index: String) = "<t:IndexedFieldURI FieldURI=\"$uri\" FieldIndex=\"$index\"/>"

        private val SUMMARY_FIELDS = listOf(
            "item:Subject", "item:DateTimeReceived", "item:HasAttachments", "item:Importance", "item:ItemClass",
            "item:DisplayTo", "item:Preview", "item:Flag", "message:From", "message:IsRead",
        ).joinToString("") { field(it) }

        private val EVENT_FIELDS = listOf(
            "item:Subject", "calendar:Start", "calendar:End", "calendar:Location", "calendar:IsAllDayEvent",
            "calendar:Organizer", "calendar:MyResponseType", "calendar:IsCancelled", "calendar:LegacyFreeBusyStatus",
            "calendar:IsMeeting", "calendar:IsRecurring",
        ).joinToString("") { field(it) }

        fun envelope(body: String, windowsTimeZone: String?): String =
            """<?xml version="1.0" encoding="utf-8"?>""" +
                """<soap:Envelope xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/" """ +
                """xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types" """ +
                """xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages">""" +
                """<soap:Header><t:RequestServerVersion Version="Exchange2013_SP1"/>""" +
                (windowsTimeZone?.let {
                    """<t:TimeZoneContext><t:TimeZoneDefinition Id="${esc(it)}"/></t:TimeZoneContext>"""
                } ?: "") +
                """</soap:Header><soap:Body>$body</soap:Body></soap:Envelope>"""

        fun folderIdXml(id: String) =
            if (id in DISTINGUISHED) "<t:DistinguishedFolderId Id=\"$id\"/>" else "<t:FolderId Id=\"${esc(id)}\"/>"

        internal fun itemIdXml(ref: ItemRef) =
            "<t:ItemId Id=\"${esc(ref.id)}\"${ref.changeKey?.let { " ChangeKey=\"${esc(it)}\"" } ?: ""}/>"

        internal fun referenceItemId(ref: ItemRef) =
            "<t:ReferenceItemId Id=\"${esc(ref.id)}\"${ref.changeKey?.let { " ChangeKey=\"${esc(it)}\"" } ?: ""}/>"

        internal fun recipients(tag: String, emails: List<String>): String =
            if (emails.isEmpty()) "" else "<t:$tag>" + emails.joinToString("") {
                "<t:Mailbox><t:EmailAddress>${esc(it)}</t:EmailAddress></t:Mailbox>"
            } + "</t:$tag>"

        internal fun attendees(tag: String, emails: List<String>): String =
            if (emails.isEmpty()) "" else "<t:$tag>" + emails.joinToString("") {
                "<t:Attendee><t:Mailbox><t:EmailAddress>${esc(it)}</t:EmailAddress></t:Mailbox></t:Attendee>"
            } + "</t:$tag>"

        private fun String.removeSmtpPrefix() = removePrefix("SMTP:").removePrefix("smtp:")

        fun parseFolder(f: XmlNode) = EwsFolder(
            id = f.child("FolderId")?.attr("Id").orEmpty(),
            changeKey = f.child("FolderId")?.attr("ChangeKey"),
            parentId = f.child("ParentFolderId")?.attr("Id"),
            displayName = f.text("DisplayName").orEmpty(),
            folderClass = f.text("FolderClass"),
            totalCount = f.int("TotalCount") ?: 0,
            unreadCount = f.int("UnreadCount") ?: 0,
            childCount = f.int("ChildFolderCount") ?: 0,
            hidden = f.children("ExtendedProperty").any {
                it.child("ExtendedFieldURI")?.attr("PropertyTag")?.equals("0x10f4", true) == true &&
                    it.bool("Value")
            },
        )

        /** Mailbox из узла вида `<From><Mailbox>…</Mailbox></From>` или самого `<Mailbox>`. */
        fun parseMailbox(node: XmlNode?): Mailbox? {
            val mb = when {
                node == null -> return null
                node.name == "Mailbox" -> node
                else -> node.child("Mailbox") ?: return null
            }
            return Mailbox(mb.text("Name").orEmpty(), mb.text("EmailAddress").orEmpty())
        }

        private fun parseMailboxList(node: XmlNode?): List<Mailbox> =
            node?.children("Mailbox").orEmpty().mapNotNull { parseMailbox(it) }

        fun parseSummary(n: XmlNode): MessageSummary {
            val id = n.child("ItemId")
            return MessageSummary(
                ref = ItemRef(id?.attr("Id").orEmpty(), id?.attr("ChangeKey")),
                subject = n.text("Subject").orEmpty(),
                from = parseMailbox(n.child("From")) ?: parseMailbox(n.child("Sender")),
                displayTo = n.text("DisplayTo").orEmpty(),
                preview = n.text("Preview").orEmpty().trim(),
                received = n.instant("DateTimeReceived"),
                isRead = n.child("IsRead")?.let { it.text.equals("true", true) } ?: true,
                hasAttachments = n.bool("HasAttachments"),
                importance = n.text("Importance") ?: "Normal",
                flagged = n.text("Flag", "FlagStatus") == "Flagged",
                itemClass = n.text("ItemClass") ?: "IPM.Note",
            )
        }

        fun parseAttachments(n: XmlNode?): List<AttachmentInfo> =
            n?.children.orEmpty().mapNotNull { a ->
                val id = a.child("AttachmentId")?.attr("Id") ?: return@mapNotNull null
                AttachmentInfo(
                    id = id,
                    name = a.text("Name").orEmpty().ifBlank { "вложение" },
                    contentType = a.text("ContentType") ?: "application/octet-stream",
                    size = a.text("Size")?.toLongOrNull() ?: 0,
                    isInline = a.bool("IsInline"),
                    contentId = a.text("ContentId"),
                    isItem = a.name == "ItemAttachment",
                )
            }

        fun parseDetail(n: XmlNode): MessageDetail {
            val s = parseSummary(n)
            val itemClass = s.itemClass
            val isMeeting = itemClass.startsWith("IPM.Schedule.Meeting", true)
            val body = n.child("Body")
            val bodyHtml = when {
                body == null -> ""
                body.attr("BodyType") == "Text" -> "<pre style=\"white-space:pre-wrap\">${esc(body.text)}</pre>"
                else -> body.text
            }
            return MessageDetail(
                ref = s.ref,
                subject = s.subject,
                from = s.from,
                to = parseMailboxList(n.child("ToRecipients")),
                cc = parseMailboxList(n.child("CcRecipients")),
                bcc = parseMailboxList(n.child("BccRecipients")),
                received = s.received,
                bodyHtml = bodyHtml,
                isRead = s.isRead,
                flagged = s.flagged,
                importance = s.importance,
                itemClass = itemClass,
                attachments = parseAttachments(n.child("Attachments")),
                parentFolderId = n.child("ParentFolderId")?.attr("Id"),
                meeting = if (!isMeeting) null else MeetingInfo(
                    start = n.instant("Start"),
                    end = n.instant("End"),
                    location = n.text("Location").orEmpty(),
                    isAllDay = n.bool("IsAllDayEvent"),
                    isCancellation = itemClass.contains("Canceled", true),
                    isResponse = itemClass.contains("Resp", true),
                    responseType = n.text("ResponseType") ?: n.text("MyResponseType"),
                ),
            )
        }

        private fun parseAttendees(n: XmlNode?): List<Attendee> =
            n?.children("Attendee").orEmpty().mapNotNull { a ->
                parseMailbox(a.child("Mailbox"))?.let { Attendee(it, a.text("ResponseType")) }
            }

        fun parseEvent(n: XmlNode): CalendarEvent? {
            val id = n.child("ItemId") ?: return null
            val start = n.instant("Start") ?: return null
            val end = n.instant("End") ?: start
            return CalendarEvent(
                ref = ItemRef(id.attr("Id").orEmpty(), id.attr("ChangeKey")),
                subject = n.text("Subject").orEmpty(),
                start = start,
                end = end,
                location = n.text("Location").orEmpty(),
                isAllDay = n.bool("IsAllDayEvent"),
                organizer = parseMailbox(n.child("Organizer")),
                myResponse = n.text("MyResponseType"),
                isCancelled = n.bool("IsCancelled"),
                isMeeting = n.bool("IsMeeting"),
                isRecurring = n.bool("IsRecurring"),
                freeBusy = n.text("LegacyFreeBusyStatus"),
                isOrganizer = n.child("IsOrganizer")?.text?.equals("true", true),
                bodyHtml = n.child("Body")?.let { b ->
                    if (b.attr("BodyType") == "Text") "<pre style=\"white-space:pre-wrap\">${esc(b.text)}</pre>" else b.text
                },
                requiredAttendees = parseAttendees(n.child("RequiredAttendees")),
                optionalAttendees = parseAttendees(n.child("OptionalAttendees")),
                resources = parseAttendees(n.child("Resources")),
                itemType = n.text("CalendarItemType"),
                reminderSet = n.bool("ReminderIsSet"),
                reminderMinutes = n.int("ReminderMinutesBeforeStart"),
            )
        }

        private fun parsePhones(c: XmlNode): List<Pair<String, String>> =
            c.child("PhoneNumbers")?.children("Entry").orEmpty()
                .filter { it.text.isNotBlank() }
                .map { (it.attr("Key") ?: "") to it.text.trim() }

        /** OkHttp-клиент с NTLM: только HTTP/1.1, одно переиспользуемое соединение. */
        /** Клиент для проверки адреса сервера: без учётных данных и с короткими таймаутами. */
        private val probeHttp: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(6, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(10, TimeUnit.SECONDS)
                .followRedirects(false)
                .build()
        }

        /**
         * Отвечает ли по [url] сервер EWS. Запрос без учётных данных: Exchange отвечает 401 и предлагает NTLM,
         * Negotiate или Basic. Пароль на непроверенный адрес не отправляется.
         */
        suspend fun isEwsEndpoint(url: String): Boolean = withContext(Dispatchers.IO) {
            try {
                probeHttp.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                    r.code == 401 && r.headers("WWW-Authenticate").any { h ->
                        listOf("NTLM", "Negotiate", "Basic").any { h.trim().startsWith(it, ignoreCase = true) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
        }

        fun createHttpClient(
            session: EwsSession,
            builder: OkHttpClient.Builder = OkHttpClient.Builder(),
            readTimeoutSeconds: Long = 120,
        ): OkHttpClient =
            builder
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectionPool(ConnectionPool(2, 5, TimeUnit.MINUTES))
                .authenticator(NtlmAuthenticator({ session.config?.credentials }, basic = { session.config?.basic == true }))
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(readTimeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
    }

    // endregion
}
