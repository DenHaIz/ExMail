package io.github.denhaiz.exmail.ews

import io.github.denhaiz.exmail.ews.EwsClient.Companion.field
import io.github.denhaiz.exmail.ews.EwsClient.Companion.folderIdXml
import io.github.denhaiz.exmail.ews.EwsClient.Companion.recipients
import io.github.denhaiz.exmail.ews.EwsClient.Companion.referenceItemId
import io.github.denhaiz.exmail.ews.Xml.esc
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.time.ZoneId

// Дополнительные операции EWS: папки, черновики, автоответ, переговорные, личные контакты.

/** XML правила повторения для CalendarItem (порядок элементов — по схеме EWS). */
internal fun recurrenceXml(r: Recurrence, start: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
    val date = start.atZone(zone).toLocalDate()
    val dayName = date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
    val month = date.month.name.lowercase().replaceFirstChar { it.uppercase() }
    val pattern = when (r.kind) {
        Recurrence.Kind.DAILY -> "<t:DailyRecurrence><t:Interval>1</t:Interval></t:DailyRecurrence>"
        Recurrence.Kind.WEEKDAYS ->
            "<t:WeeklyRecurrence><t:Interval>1</t:Interval><t:DaysOfWeek>Monday Tuesday Wednesday Thursday Friday</t:DaysOfWeek></t:WeeklyRecurrence>"
        Recurrence.Kind.WEEKLY ->
            "<t:WeeklyRecurrence><t:Interval>1</t:Interval><t:DaysOfWeek>$dayName</t:DaysOfWeek></t:WeeklyRecurrence>"
        Recurrence.Kind.MONTHLY ->
            "<t:AbsoluteMonthlyRecurrence><t:Interval>1</t:Interval><t:DayOfMonth>${date.dayOfMonth}</t:DayOfMonth></t:AbsoluteMonthlyRecurrence>"
        Recurrence.Kind.YEARLY ->
            "<t:AbsoluteYearlyRecurrence><t:DayOfMonth>${date.dayOfMonth}</t:DayOfMonth><t:Month>$month</t:Month></t:AbsoluteYearlyRecurrence>"
    }
    val range = r.until?.let {
        "<t:EndDateRecurrence><t:StartDate>$date</t:StartDate><t:EndDate>$it</t:EndDate></t:EndDateRecurrence>"
    } ?: "<t:NoEndRecurrence><t:StartDate>$date</t:StartDate></t:NoEndRecurrence>"
    return "<t:Recurrence>$pattern$range</t:Recurrence>"
}

// region Папки

/**
 * «Отметить все прочитанными». Exchange выполняет MarkAllItemsAsRead в фоне, и ещё какое-то время
 * письма отдаются непрочитанными, поэтому оставшиеся непрочитанные отмечаем явно через UpdateItem.
 */
suspend fun EwsClient.markAllRead(folderId: String) {
    val bulk = try {
        val body = call(
            """<m:MarkAllItemsAsRead><m:ReadFlag>true</m:ReadFlag><m:SuppressReadReceipts>true</m:SuppressReadReceipts>""" +
                """<m:FolderIds>${folderIdXml(folderId)}</m:FolderIds></m:MarkAllItemsAsRead>""",
        )
        responseMessages(body).forEach { it.ok() }
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        e
    }
    var rounds = 0
    while (rounds++ < MARK_READ_ROUNDS) {
        val (unread, more) = findUnreadIds(folderId)
        if (unread.isEmpty()) return
        unread.chunked(100).forEach { markReadTolerant(it) }
        if (!more) break
    }
    // Если сервер не принял ни массовую, ни поштучную отметку — сообщаем об ошибке
    bulk?.let { e -> if (findUnreadIds(folderId).first.isNotEmpty()) throw e }
}

private const val MARK_READ_ROUNDS = 20

/** Первая страница id непрочитанных писем папки и признак, что есть ещё. */
internal suspend fun EwsClient.findUnreadIds(folderId: String, limit: Int = 500): Pair<List<ItemRef>, Boolean> {
    val body = call(
        """<m:FindItem Traversal="Shallow"><m:ItemShape><t:BaseShape>IdOnly</t:BaseShape></m:ItemShape>""" +
            """<m:IndexedPageItemView MaxEntriesReturned="$limit" Offset="0" BasePoint="Beginning"/>""" +
            """<m:Restriction><t:IsEqualTo>${field("message:IsRead")}""" +
            """<t:FieldURIOrConstant><t:Constant Value="false"/></t:FieldURIOrConstant></t:IsEqualTo></m:Restriction>""" +
            """<m:ParentFolderIds>${folderIdXml(folderId)}</m:ParentFolderIds></m:FindItem>""",
    )
    val root = responseMessages(body).first().ok().child("RootFolder")
    val ids = root?.child("Items")?.children.orEmpty().mapNotNull { it.child("ItemId")?.attr("Id")?.let(::ItemRef) }
    val last = root?.attr("IncludesLastItemInRange")?.equals("true", true) ?: true
    return ids to !last
}

/** Отметка прочитанными без ошибки на отдельных письмах (например, отчётах о доставке). */
private suspend fun EwsClient.markReadTolerant(refs: List<ItemRef>) {
    val changes = refs.joinToString("") { ref ->
        """<t:ItemChange><t:ItemId Id="${esc(ref.id)}"/><t:Updates><t:SetItemField>${field("message:IsRead")}""" +
            """<t:Message><t:IsRead>true</t:IsRead></t:Message></t:SetItemField></t:Updates></t:ItemChange>"""
    }
    call(
        """<m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AlwaysOverwrite" SuppressReadReceipts="true">""" +
            """<m:ItemChanges>$changes</m:ItemChanges></m:UpdateItem>""",
    )
}

/** Очистка папки: из «Удалённых» — окончательно, из остальных — в «Удалённые». */
suspend fun EwsClient.emptyFolder(folderId: String, permanently: Boolean) {
    val type = if (permanently) "SoftDelete" else "MoveToDeletedItems"
    val body = call(
        """<m:EmptyFolder DeleteType="$type" DeleteSubFolders="false">""" +
            """<m:FolderIds>${folderIdXml(folderId)}</m:FolderIds></m:EmptyFolder>""",
    )
    responseMessages(body).forEach { it.ok() }
}

suspend fun EwsClient.createFolder(parentId: String, name: String): String {
    val body = call(
        """<m:CreateFolder><m:ParentFolderId>${folderIdXml(parentId)}</m:ParentFolderId>""" +
            """<m:Folders><t:Folder><t:FolderClass>IPF.Note</t:FolderClass><t:DisplayName>${esc(name)}</t:DisplayName>""" +
            """</t:Folder></m:Folders></m:CreateFolder>""",
    )
    return responseMessages(body).first().ok().find("FolderId")?.attr("Id")
        ?: throw EwsException("ErrorCreateFolder", "Сервер не вернул папку")
}

suspend fun EwsClient.renameFolder(folderId: String, name: String) {
    val body = call(
        """<m:UpdateFolder><m:FolderChanges><t:FolderChange><t:FolderId Id="${esc(folderId)}"/><t:Updates>""" +
            """<t:SetFolderField>${field("folder:DisplayName")}<t:Folder><t:DisplayName>${esc(name)}</t:DisplayName></t:Folder>""" +
            """</t:SetFolderField></t:Updates></t:FolderChange></m:FolderChanges></m:UpdateFolder>""",
    )
    responseMessages(body).forEach { it.ok() }
}

suspend fun EwsClient.deleteFolder(folderId: String) {
    val body = call(
        """<m:DeleteFolder DeleteType="MoveToDeletedItems"><m:FolderIds><t:FolderId Id="${esc(folderId)}"/></m:FolderIds></m:DeleteFolder>""",
    )
    responseMessages(body).forEach { it.ok() }
}

// endregion

// region Черновики

private fun messageContent(m: OutgoingMessage) =
    "<t:Subject>${esc(m.subject)}</t:Subject>" +
        "<t:Body BodyType=\"HTML\">${esc(m.bodyHtml)}</t:Body>" +
        (if (m.highImportance) "<t:Importance>High</t:Importance>" else "") +
        recipients("ToRecipients", m.to) + recipients("CcRecipients", m.cc) + recipients("BccRecipients", m.bcc)

/** Новый черновик в папке «Черновики». */
suspend fun EwsClient.saveDraft(message: OutgoingMessage): ItemRef =
    addAttachments(createDraft("<t:Message>${messageContent(message)}</t:Message>"), message.attachments)

/** Черновик ответа/пересылки (исходное письмо цитируется сервером). */
suspend fun EwsClient.saveReplyDraft(kind: ReplyKind, original: ItemRef, message: OutgoingMessage): ItemRef {
    val content = "<t:${kind.element}>" +
        "<t:Subject>${esc(message.subject)}</t:Subject>" +
        recipients("ToRecipients", message.to) + recipients("CcRecipients", message.cc) +
        recipients("BccRecipients", message.bcc) +
        referenceItemId(withChangeKey(original)) +
        "<t:NewBodyContent BodyType=\"HTML\">${esc(message.bodyHtml)}</t:NewBodyContent>" +
        "</t:${kind.element}>"
    return addAttachments(createDraft(content), message.attachments)
}

/** Обновляет существующий черновик (тема, текст, получатели) и добавляет новые вложения. */
suspend fun EwsClient.updateDraft(ref: ItemRef, m: OutgoingMessage): ItemRef {
    fun set(uri: String, value: String) = "<t:SetItemField>${field(uri)}<t:Message>$value</t:Message></t:SetItemField>"
    fun del(uri: String) = "<t:DeleteItemField>${field(uri)}</t:DeleteItemField>"
    fun rcpt(uri: String, tag: String, list: List<String>) = if (list.isEmpty()) del(uri) else set(uri, recipients(tag, list))
    val updates = set("item:Subject", "<t:Subject>${esc(m.subject)}</t:Subject>") +
        set("item:Body", "<t:Body BodyType=\"HTML\">${esc(m.bodyHtml)}</t:Body>") +
        set("item:Importance", "<t:Importance>${if (m.highImportance) "High" else "Normal"}</t:Importance>") +
        rcpt("message:ToRecipients", "ToRecipients", m.to) +
        rcpt("message:CcRecipients", "CcRecipients", m.cc) +
        rcpt("message:BccRecipients", "BccRecipients", m.bcc)
    val body = call(
        """<m:UpdateItem MessageDisposition="SaveOnly" ConflictResolution="AlwaysOverwrite">""" +
            """<m:ItemChanges><t:ItemChange><t:ItemId Id="${esc(ref.id)}"/><t:Updates>$updates</t:Updates>""" +
            """</t:ItemChange></m:ItemChanges></m:UpdateItem>""",
    )
    val id = responseMessages(body).first().ok().find("ItemId")
    val updated = ItemRef(id?.attr("Id") ?: ref.id, id?.attr("ChangeKey"))
    return addAttachments(updated, m.attachments)
}

/** Отправляет черновик с актуальным содержимым (сохраняя его прежние вложения). */
suspend fun EwsClient.sendDraft(ref: ItemRef, m: OutgoingMessage) = sendItem(updateDraft(ref, m))

// endregion

// region Автоответ (Out of Office)

suspend fun EwsClient.getOof(email: String): OofSettings {
    val body = call(
        """<m:GetUserOofSettingsRequest><t:Mailbox><t:Address>${esc(email)}</t:Address></t:Mailbox></m:GetUserOofSettingsRequest>""",
        withTimeZone = false,
    )
    body.find("ResponseMessage")?.ok()
    val s = body.find("OofSettings") ?: throw EwsException("ErrorOof", "Сервер не вернул настройки автоответа")
    return OofSettings(
        state = s.text("OofState") ?: "Disabled",
        externalAudience = s.text("ExternalAudience") ?: "None",
        start = s.instant("Duration", "StartTime"),
        end = s.instant("Duration", "EndTime"),
        internalReply = s.text("InternalReply", "Message").orEmpty(),
        externalReply = s.text("ExternalReply", "Message").orEmpty(),
    )
}

suspend fun EwsClient.setOof(email: String, o: OofSettings) {
    val duration = if (o.start != null && o.end != null) {
        "<t:Duration><t:StartTime>${Xml.formatInstant(o.start)}</t:StartTime><t:EndTime>${Xml.formatInstant(o.end)}</t:EndTime></t:Duration>"
    } else ""
    val body = call(
        """<m:SetUserOofSettingsRequest><t:Mailbox><t:Address>${esc(email)}</t:Address></t:Mailbox>""" +
            """<t:UserOofSettings><t:OofState>${o.state}</t:OofState><t:ExternalAudience>${o.externalAudience}</t:ExternalAudience>""" +
            duration +
            """<t:InternalReply><t:Message>${esc(o.internalReply)}</t:Message></t:InternalReply>""" +
            """<t:ExternalReply><t:Message>${esc(o.externalReply)}</t:Message></t:ExternalReply>""" +
            """</t:UserOofSettings></m:SetUserOofSettingsRequest>""",
        withTimeZone = false,
    )
    body.find("ResponseMessage")?.ok()
}

// endregion

// region Переговорные

/** Все переговорные из списков помещений организации (пусто, если списки не настроены). */
suspend fun EwsClient.getRooms(): List<Room> {
    val lists = responseMessagesOrSelf(call("<m:GetRoomLists/>"))
        .flatMap { it.findAll("Address") }
        .mapNotNull { it.text("EmailAddress") }
    val rooms = mutableListOf<Room>()
    for (list in lists) {
        val body = call("""<m:GetRooms><m:RoomList><t:EmailAddress>${esc(list)}</t:EmailAddress></m:RoomList></m:GetRooms>""")
        body.findAll("Room").forEach { r ->
            val id = r.child("Id") ?: return@forEach
            val email = id.text("EmailAddress") ?: return@forEach
            rooms += Room(id.text("Name") ?: email, email)
        }
    }
    return rooms.distinctBy { it.email.lowercase() }.sortedBy { it.name.lowercase() }
}

private fun responseMessagesOrSelf(body: XmlNode): List<XmlNode> {
    val resp = body.children.firstOrNull() ?: return emptyList()
    if (resp.attr("ResponseClass") == "Error") {
        throw EwsException(resp.text("ResponseCode").orEmpty(), resp.text("MessageText") ?: "Ошибка сервера")
    }
    return listOf(resp)
}

// endregion

// region Личные контакты

suspend fun EwsClient.createContact(c: ContactInfo): ItemRef {
    val phones = c.phones.filter { it.second.isNotBlank() }
    val body = call(
        """<m:CreateItem><m:SavedItemFolderId><t:DistinguishedFolderId Id="contacts"/></m:SavedItemFolderId><m:Items><t:Contact>""" +
            "<t:FileAs>${esc(c.displayName)}</t:FileAs>" +
            "<t:DisplayName>${esc(c.displayName)}</t:DisplayName>" +
            (if (c.company.isNotBlank()) "<t:CompanyName>${esc(c.company)}</t:CompanyName>" else "") +
            (if (c.email.isNotBlank()) "<t:EmailAddresses><t:Entry Key=\"EmailAddress1\">${esc(c.email)}</t:Entry></t:EmailAddresses>" else "") +
            (if (phones.isNotEmpty()) "<t:PhoneNumbers>" + phones.joinToString("") { (k, v) -> "<t:Entry Key=\"$k\">${esc(v)}</t:Entry>" } + "</t:PhoneNumbers>" else "") +
            (if (c.department.isNotBlank()) "<t:Department>${esc(c.department)}</t:Department>" else "") +
            (if (c.jobTitle.isNotBlank()) "<t:JobTitle>${esc(c.jobTitle)}</t:JobTitle>" else "") +
            (if (c.office.isNotBlank()) "<t:OfficeLocation>${esc(c.office)}</t:OfficeLocation>" else "") +
            """</t:Contact></m:Items></m:CreateItem>""",
    )
    val id = responseMessages(body).first().ok().find("ItemId") ?: throw EwsException("ErrorCreateItem", "Контакт не создан")
    return ItemRef(id.attr("Id")!!, id.attr("ChangeKey"))
}

// endregion

// region Мгновенные уведомления (streaming-подписка)

/** Подписка на новые письма во «Входящих». Возвращает идентификатор подписки. */
suspend fun EwsClient.subscribeInbox(): String {
    val body = call(
        """<m:Subscribe><m:StreamingSubscriptionRequest><t:FolderIds><t:DistinguishedFolderId Id="inbox"/></t:FolderIds>""" +
            """<t:EventTypes><t:EventType>NewMailEvent</t:EventType><t:EventType>CreatedEvent</t:EventType></t:EventTypes>""" +
            """</m:StreamingSubscriptionRequest></m:Subscribe>""",
        withTimeZone = false,
    )
    return responseMessages(body).first().ok().text("SubscriptionId")
        ?: throw EwsException("ErrorSubscribe", "Сервер не создал подписку")
}

/**
 * Держит соединение до [minutes] минут и вызывает [onNewMail] при каждом новом письме.
 * Возвращается, когда сервер штатно закрыл соединение; ошибка подписки — исключение (нужна новая подписка).
 */
suspend fun EwsClient.listen(subscriptionId: String, minutes: Int = 29, onNewMail: suspend () -> Unit) {
    stream(
        """<m:GetStreamingEvents><m:SubscriptionIds><t:SubscriptionId>${esc(subscriptionId)}</t:SubscriptionId></m:SubscriptionIds>""" +
            """<m:ConnectionTimeout>$minutes</m:ConnectionTimeout></m:GetStreamingEvents>""",
    ) { body ->
        val msg = responseMessages(body).firstOrNull() ?: return@stream
        msg.ok()
        val newMail = msg.findAll("Notification").any { n ->
            n.children.any { it.name == "NewMailEvent" || it.name == "CreatedEvent" }
        }
        if (newMail) onNewMail()
    }
}

// endregion
