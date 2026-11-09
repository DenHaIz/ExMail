package io.github.denhaiz.exmail.data.repo

import android.content.Context
import android.os.SystemClock
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import io.github.denhaiz.exmail.di.ApplicationScope
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import io.github.denhaiz.exmail.data.FileSaver
import io.github.denhaiz.exmail.data.InlineImages
import io.github.denhaiz.exmail.data.MailCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import java.io.IOException
import java.net.URLDecoder
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import io.github.denhaiz.exmail.data.db.FolderDao
import io.github.denhaiz.exmail.data.db.FolderEntity
import io.github.denhaiz.exmail.data.db.MessageDao
import io.github.denhaiz.exmail.data.db.MessageEntity
import io.github.denhaiz.exmail.ews.AttachmentInfo
import io.github.denhaiz.exmail.ews.EwsClient
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.MeetingResponse
import io.github.denhaiz.exmail.ews.MessageDetail
import io.github.denhaiz.exmail.ews.MessageSummary
import io.github.denhaiz.exmail.ews.NewAttachment
import io.github.denhaiz.exmail.ews.OutgoingMessage
import io.github.denhaiz.exmail.ews.ReplyKind
import io.github.denhaiz.exmail.ews.createFolder
import io.github.denhaiz.exmail.ews.deleteFolder
import io.github.denhaiz.exmail.ews.emptyFolder
import io.github.denhaiz.exmail.ews.markAllRead
import io.github.denhaiz.exmail.ews.renameFolder
import io.github.denhaiz.exmail.ews.saveDraft
import io.github.denhaiz.exmail.ews.saveReplyDraft
import io.github.denhaiz.exmail.ews.sendDraft
import io.github.denhaiz.exmail.ews.updateDraft
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MailRepository @Inject constructor(
    private val client: EwsClient,
    private val folderDao: FolderDao,
    private val messageDao: MessageDao,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val appScope: CoroutineScope,
    private val cache: MailCache,
) {
    private val detailCache = object : LinkedHashMap<String, MessageDetail>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MessageDetail>?) = size > 20
    }

    fun clearCache() {
        synchronized(detailCache) { detailCache.clear() }
        edits.clear()
        inlineJobs.values.forEach { it.cancel() }
        cache.clear()
    }

    /**
     * Отметки о локальных изменениях (прочитано, флажок, удаление, «прочитать всё») с моментом изменения.
     * Обновление списка, начатое раньше изменения, приносит с сервера устаревшее состояние —
     * для таких писем сохраняем локальное, чтобы письма не «возвращались» непрочитанными или удалёнными.
     */
    private val edits = ConcurrentHashMap<String, Long>()

    private fun touch(keys: Collection<String>) {
        val now = SystemClock.elapsedRealtimeNanos()
        keys.forEach { edits[it] = now }
        if (edits.size > 2000) edits.entries.removeIf { now - it.value > EDIT_TTL_NANOS }
    }

    private fun touchedSince(key: String, since: Long) = edits[key]?.let { it - since >= 0 } == true

    /** Свежие данные сервера с учётом локальных изменений, сделанных после начала запроса. */
    private suspend fun mergeLocal(folderId: String, since: Long, fresh: List<MessageEntity>): List<MessageEntity> {
        val folderTouched = touchedSince("all:$folderId", since)
        return fresh.mapNotNull { e ->
            when {
                touchedSince("del:${e.id}", since) -> null
                folderTouched || touchedSince(e.id, since) -> {
                    val local = messageDao.byId(e.id)
                    when {
                        local != null -> e.copy(isRead = local.isRead, flagged = local.flagged)
                        folderTouched -> e.copy(isRead = true)
                        else -> e
                    }
                }
                else -> e
            }
        }
    }

    fun folders(): Flow<List<FolderEntity>> = folderDao.observeAll()

    fun messages(folderId: String): Flow<List<MessageEntity>> = messageDao.observeFolder(folderId)

    suspend fun folder(id: String) = folderDao.byId(id)

    suspend fun wellKnownFolder(name: String): FolderEntity? {
        folderDao.byWellKnown(name)?.let { return it }
        refreshFolders()
        return folderDao.byWellKnown(name)
    }

    suspend fun refreshFolders() {
        val started = SystemClock.elapsedRealtimeNanos()
        val wellKnown = client.getWellKnownFolders()
        val all = client.findMailFolders()
        val nameById = wellKnown.entries.associate { it.value.id to it.key }
        val byId = all.associateBy { it.id }
        val rootId = all.firstOrNull { it.parentId != null && it.parentId !in byId }?.parentId

        fun path(f: io.github.denhaiz.exmail.ews.EwsFolder): List<String> {
            val result = mutableListOf<String>()
            var cur: io.github.denhaiz.exmail.ews.EwsFolder? = f
            var guard = 0
            while (cur != null && guard++ < 20) {
                result.add(0, cur.displayName)
                cur = cur.parentId?.takeIf { it != rootId }?.let(byId::get)
            }
            return result
        }

        val order = listOf("inbox", "drafts", "sentitems", "deleteditems", "junkemail", "outbox")
        val sorted = all.sortedWith(
            compareBy<io.github.denhaiz.exmail.ews.EwsFolder>(
                { nameById[it.id]?.let(order::indexOf)?.takeIf { i -> i >= 0 } ?: order.size },
                { path(it).joinToString("/").lowercase() },
            ),
        )
        val localUnread = folderDao.all().associate { it.id to it.unread }
        folderDao.replaceAll(
            sorted.mapIndexed { i, f ->
                val wk = nameById[f.id]
                // Счётчик, изменённый в приложении во время запроса, не перезаписываем устаревшим
                val unread = if (touchedSince("count:${f.id}", started)) localUnread[f.id] ?: f.unreadCount else f.unreadCount
                FolderEntity(
                    id = f.id,
                    parentId = f.parentId,
                    name = wk?.let(::russianName) ?: f.displayName,
                    wellKnown = wk,
                    unread = unread,
                    total = f.totalCount,
                    depth = if (wk != null) 0 else (path(f).size - 1).coerceAtLeast(0),
                    position = i,
                )
            },
        )
    }

    /** Обновление и подгрузка одной папки — по очереди: иначе ответ более раннего запроса удаляет только что пришедшее. */
    private val folderLocks = ConcurrentHashMap<String, kotlinx.coroutines.sync.Mutex>()
    private fun lockOf(folderId: String) = folderLocks.getOrPut(folderId) { kotlinx.coroutines.sync.Mutex() }

    /** Обновляет первую страницу папки. Возвращает число новых писем. */
    suspend fun refresh(folderId: String, pageSize: Int = PAGE): Int = lockOf(folderId).withLock { refreshLocked(folderId, pageSize) }

    private suspend fun refreshLocked(folderId: String, pageSize: Int): Int {
        val started = SystemClock.elapsedRealtimeNanos()
        val newestBefore = messageDao.newest(folderId) ?: 0L
        val page = client.findMessages(folderId, 0, pageSize)
        val entities = mergeLocal(folderId, started, page.items.map { it.toEntity(folderId) })
        messageDao.upsert(entities)
        if (page.isLast) {
            if (entities.isEmpty()) messageDao.clearFolder(folderId)
            else messageDao.deleteMissing(folderId, 0, entities.map { it.id })
        } else if (entities.isNotEmpty()) {
            val pageOldest = entities.minOf { it.receivedAt }
            messageDao.deleteMissing(folderId, pageOldest, entities.map { it.id })
            // Новых писем больше страницы: между ней и сохранёнными была бы «дыра», которую «загрузить ещё» не заполнит
            // (оно грузит старше самого старого). Сохранённые старше страницы убираем — они догрузятся при прокрутке
            if (newestBefore > 0 && pageOldest > newestBefore) messageDao.deleteOlderThan(folderId, pageOldest)
        }
        return entities.count { it.receivedAt > newestBefore && newestBefore > 0 }
    }

    /**
     * Догружает письма старше самого старого в списке. Возвращает true, если есть ещё письма.
     * По дате, а не по номеру: письма, удалённые или пришедшие на сервере (в Outlook, правилами), не сдвигают страницы —
     * ничего не пропускается и не повторяется.
     */
    suspend fun loadMore(folderId: String): Boolean {
        val started = SystemClock.elapsedRealtimeNanos()
        val oldest = lockOf(folderId).withLock { messageDao.oldest(folderId) } ?: return refresh(folderId).let { true }
        return lockOf(folderId).withLock {
            val page = client.findMessages(folderId, 0, PAGE, before = Instant.ofEpochMilli(oldest))
            val items = page.items.map { it.toEntity(folderId) }
            messageDao.upsert(mergeLocal(folderId, started, items))
            // Вся страница — письма с той же секундой, что и самое старое (массовая рассылка, импорт): следующая
            // была бы такой же — дальше не грузим, иначе «загрузка» повторялась бы бесконечно
            !page.isLast && items.any { it.receivedAt < oldest }
        }
    }

    /**
     * Поиск в папке: по тексту ([query], AQS на сервере), по дате получения ([from], [until]) или по обоим.
     * EWS не сочетает текстовый запрос с ограничением по дате, поэтому найденное по тексту отбирается по дате
     * здесь, страницами от новых писем к старым.
     */
    suspend fun search(folderId: String, query: String?, from: Instant? = null, until: Instant? = null): List<MessageEntity> {
        if (query.isNullOrBlank()) {
            if (from == null && until == null) return emptyList()
            return client.findMessages(folderId, 0, SEARCH_MAX, from = from, until = until).items.map { it.toEntity(folderId) }
        }
        if (from == null && until == null) {
            return client.findMessages(folderId, 0, SEARCH_MAX, query).items.map { it.toEntity(folderId) }
        }
        return inPeriod(from?.toEpochMilli() ?: Long.MIN_VALUE, until?.toEpochMilli() ?: Long.MAX_VALUE) { offset ->
            val page = client.findMessages(folderId, offset, SEARCH_PAGE, query)
            page.items.map { it.toEntity(folderId) } to page.isLast
        }
    }

    suspend fun cachedMessage(id: String): MessageEntity? = messageDao.byId(id)

    /** Соседи письма в его папке (как в списке): более новое и более старое; null — письма нет в списке. */
    suspend fun neighbors(id: String): Pair<String?, String?>? {
        val m = messageDao.byId(id) ?: return null
        return messageDao.newerThan(m.folderId, m.receivedAt, m.id) to messageDao.olderThan(m.folderId, m.receivedAt, m.id)
    }

    /**
     * Письмо целиком: из памяти, из дискового кэша (полученное письмо не меняется) или с сервера.
     * Отметка «прочитано» уходит на сервер в фоне — письмо показывается, не дожидаясь её.
     */
    suspend fun getMessage(id: String, forceRefresh: Boolean = false): MessageDetail {
        if (!forceRefresh) {
            synchronized(detailCache) { detailCache[id] }?.let { return opened(it) }
            // Прочитано и флажок могли измениться (в списке, на другом устройстве) — берём из списка писем;
            // письмо не из списка (из поиска) — с сервера, иначе его состояние неизвестно
            val entity = messageDao.byId(id)
            if (entity != null) withContext(Dispatchers.IO) { cache.readMessage(id) }?.let { stored ->
                val d = stored.copy(isRead = entity.isRead, flagged = entity.flagged)
                synchronized(detailCache) { detailCache[id] = d }
                return opened(d)
            }
        }
        val generation = cache.generation
        val detail = client.getMessage(id)
        synchronized(detailCache) { detailCache[id] = detail }
        if (detail.parentFolderId == null || detail.parentFolderId != folderDao.byWellKnown("drafts")?.id) {
            appScope.launch(Dispatchers.IO) { cache.writeMessage(detail.copy(isRead = true), generation) }
        }
        return opened(detail)
    }

    private fun opened(d: MessageDetail): MessageDetail {
        if (!d.isRead) {
            // Нет связи — письмо всё равно показываем; при ошибке отметка откатится в списке
            appScope.launch { runCatching { markRead(listOf(d.ref.id), true) } }
        }
        return d.copy(isRead = true)
    }

    /**
     * HTML письма: встроенные (cid:) картинки заменяются адресами, которые WebView запрашивает у приложения
     * ([io.github.denhaiz.exmail.data.MailImages]). Текст показывается сразу, картинки — по мере загрузки, из кэша — мгновенно.
     */
    suspend fun renderableHtml(detail: MessageDetail): String {
        var html = detail.bodyHtml
        val inline = detail.attachments.filter { it.contentId != null && !it.isItem && it.size < INLINE_MAX_BYTES }
            .filter { html.contains("cid:${it.contentId}", ignoreCase = true) }
        if (inline.isEmpty()) return html
        // Длинные идентификаторы первыми: иначе замена «cid:image1» испортила бы «cid:image10»
        inline.sortedByDescending { it.contentId?.length ?: 0 }
            .forEach { html = html.replace("cid:${it.contentId}", InlineImages.url(it.id), ignoreCase = true) }
        withContext(Dispatchers.IO) { prefetchInline(detail.ref.id, inline) }
        return html
    }

    /** Догрузка картинок по письму: письмо закрыли — догрузка останавливается и не задерживает другие запросы. */
    private val inlineJobs = ConcurrentHashMap<String, Job>()

    fun cancelInlinePrefetch(messageId: String) {
        inlineJobs.remove(messageId)?.cancel()
    }

    /** Встроенные картинки, которые скачиваются сейчас: id вложения → результат. */
    private val inlineLoads = ConcurrentHashMap<String, CompletableDeferred<Pair<File, String>?>>()

    /** Скачивает недостающие встроенные картинки письма в фоне — несколькими крупными запросами, а не по одной. */
    @Synchronized
    private fun prefetchInline(messageId: String, list: List<AttachmentInfo>) {
        val missing = list.filter { !inlineLoads.containsKey(it.id) && cache.image(it.id) == null }
        if (missing.isEmpty()) return
        val waiting = missing.associate { it.id to CompletableDeferred<Pair<File, String>?>() }
        waiting.forEach { (id, d) -> inlineLoads[id] = d }
        val generation = cache.generation
        val job = appScope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            // До ~2 МБ за запрос: первые картинки появляются быстрее, ответ сервера не слишком велик
            val chunks = mutableListOf(mutableListOf<AttachmentInfo>())
            var bytes = 0L
            missing.forEach { a ->
                if (chunks.last().isNotEmpty() && bytes + a.size > INLINE_CHUNK_BYTES) {
                    chunks += mutableListOf<AttachmentInfo>()
                    bytes = 0
                }
                chunks.last() += a
                bytes += a.size
            }
            try {
                for (chunk in chunks) {
                    val data = try {
                        client.getAttachments(chunk.map { it.id })
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    chunk.forEachIndexed { i, a ->
                        val result = data?.getOrNull(i)?.let { f -> runCatching { cache.putImage(a.id, f.contentType, f.bytes, generation) }.getOrNull() }
                        waiting[a.id]?.let { d ->
                            d.complete(result)
                            inlineLoads.remove(a.id, d)
                        }
                    }
                }
            } finally {
                // Отменено (письмо закрыли) или ошибка — ожидающие картинки получают «нет», следующий показ загрузит заново
                waiting.forEach { (id, d) ->
                    d.complete(null)
                    inlineLoads.remove(id, d)
                }
                inlineJobs.remove(messageId, coroutineContext[Job])
            }
        }
        inlineJobs[messageId] = job
        job.start()
    }

    /** Встроенная картинка по id вложения: из кэша, из уже идущей загрузки или отдельным запросом. */
    suspend fun inlineImage(attachmentId: String): Pair<File, String>? {
        withContext(Dispatchers.IO) { cache.image(attachmentId) }?.let { return it }
        inlineLoads[attachmentId]?.let { return it.await() ?: return null }
        val generation = cache.generation
        val f = client.getAttachments(listOf(attachmentId)).firstOrNull() ?: return null
        return withContext(Dispatchers.IO) { cache.putImage(attachmentId, f.contentType, f.bytes, generation) }
    }

    suspend fun markRead(ids: List<String>, read: Boolean) {
        val changed = ids.mapNotNull { messageDao.byId(it) }.filter { it.isRead != read }
        val keys = ids + changed.map { "count:${it.folderId}" }
        touch(keys)
        applyRead(changed, read)
        synchronized(detailCache) { ids.forEach { id -> detailCache[id]?.let { detailCache[id] = it.copy(isRead = read) } } }
        try {
            client.setRead(ids.map { ItemRef(it) }, read)
        } catch (e: Exception) {
            applyRead(changed, !read)
            synchronized(detailCache) { ids.forEach { id -> detailCache[id]?.let { detailCache[id] = it.copy(isRead = !read) } } }
            touch(keys)
            throw e
        }
    }

    private suspend fun applyRead(list: List<MessageEntity>, read: Boolean) {
        if (list.isEmpty()) return
        messageDao.setRead(list.map { it.id }, read)
        list.groupBy { it.folderId }.forEach { (folder, l) -> folderDao.adjustUnread(folder, if (read) -l.size else l.size) }
    }

    suspend fun setFlag(ids: List<String>, flagged: Boolean) {
        val changed = ids.mapNotNull { messageDao.byId(it) }.filter { it.flagged != flagged }.map { it.id }
        touch(ids)
        messageDao.setFlagged(changed, flagged)
        synchronized(detailCache) { ids.forEach { id -> detailCache[id]?.let { detailCache[id] = it.copy(flagged = flagged) } } }
        try {
            client.setFlag(ids.map { ItemRef(it) }, flagged)
        } catch (e: Exception) {
            messageDao.setFlagged(changed, !flagged)
            synchronized(detailCache) { ids.forEach { id -> detailCache[id]?.let { detailCache[id] = it.copy(flagged = !flagged) } } }
            touch(ids)
            throw e
        }
    }

    suspend fun delete(ids: List<String>) {
        val cached = ids.mapNotNull { messageDao.byId(it) }
        val inTrash = cached.isNotEmpty() && cached.all { folderDao.byId(it.folderId)?.wellKnown == "deleteditems" }
        client.deleteItems(ids.map { ItemRef(it) }, hardDelete = inTrash)
        removeLocal(ids, cached)
        // Письма, перемещённые в «Удалённые», увеличивают её счётчик непрочитанных
        if (!inTrash) folderDao.byWellKnown("deleteditems")?.let { trash -> addUnread(trash.id, cached) }
    }

    /** Удаление, которое должно завершиться даже после закрытия экрана. */
    fun deleteInBackground(ids: List<String>) {
        appScope.launch { runCatching { delete(ids) } }
    }

    suspend fun move(ids: List<String>, folderId: String) {
        val cached = ids.mapNotNull { messageDao.byId(it) }
        client.moveItems(ids.map { ItemRef(it) }, folderId)
        removeLocal(ids, cached)
        addUnread(folderId, cached)
    }

    private suspend fun addUnread(folderId: String, moved: List<MessageEntity>) {
        val n = moved.count { !it.isRead }
        if (n == 0) return
        touch(listOf("count:$folderId"))
        folderDao.adjustUnread(folderId, n)
    }

    /** Убирает письма из локального кэша (после удаления/перемещения на сервере). */
    private suspend fun removeLocal(ids: List<String>, cached: List<MessageEntity>) {
        touch(ids.map { "del:$it" } + cached.map { "count:${it.folderId}" })
        messageDao.delete(ids)
        cached.filter { !it.isRead }.groupBy { it.folderId }.forEach { (f, l) -> folderDao.adjustUnread(f, -l.size) }
        synchronized(detailCache) { ids.forEach(detailCache::remove) }
        withContext(Dispatchers.IO) { cache.removeMessages(ids) }
    }

    suspend fun send(message: OutgoingMessage) = client.sendMessage(message)

    suspend fun reply(
        kind: ReplyKind,
        originalId: String,
        bodyHtml: String,
        to: List<String>,
        cc: List<String>,
        bcc: List<String>,
        subject: String?,
        attachments: List<NewAttachment>,
    ) = client.reply(kind, ItemRef(originalId), bodyHtml, to, cc, bcc, subject, attachments)

    // region Черновики

    /** Сохраняет черновик; [existingDraftId] — если редактируется уже существующий черновик. */
    suspend fun saveDraft(message: OutgoingMessage, existingDraftId: String?, replyKind: ReplyKind?, originalId: String?) {
        when {
            existingDraftId != null -> client.updateDraft(ItemRef(existingDraftId), message)
            replyKind != null && originalId != null -> client.saveReplyDraft(replyKind, ItemRef(originalId), message)
            else -> client.saveDraft(message)
        }
        synchronized(detailCache) { existingDraftId?.let(detailCache::remove) }
        existingDraftId?.let { withContext(Dispatchers.IO) { cache.removeMessages(listOf(it)) } }
        folderDao.byWellKnown("drafts")?.let { runCatching { refresh(it.id) } }
    }

    suspend fun sendDraft(draftId: String, message: OutgoingMessage) {
        val cached = listOfNotNull(messageDao.byId(draftId))
        client.sendDraft(ItemRef(draftId), message)
        removeLocal(listOf(draftId), cached)
    }

    // endregion

    // region Операции с папками

    /**
     * Сразу отмечает всё локально; обновления списка, пришедшие во время операции на сервере,
     * не возвращают письма в непрочитанные (см. [mergeLocal]).
     */
    suspend fun markAllRead(folderId: String) {
        val keys = listOf("all:$folderId", "count:$folderId")
        touch(keys)
        messageDao.markFolderRead(folderId)
        folderDao.setUnread(folderId, 0)
        try {
            client.markAllRead(folderId)
        } catch (e: Exception) {
            // Возвращаем реальное состояние с сервера
            keys.forEach(edits::remove)
            runCatching { refresh(folderId) }
            runCatching { refreshFolders() }
            throw e
        }
        touch(keys)
    }

    suspend fun emptyFolder(folder: FolderEntity) {
        client.emptyFolder(folder.id, permanently = folder.wellKnown == "deleteditems")
        messageDao.clearFolder(folder.id)
        runCatching { refreshFolders() }
    }

    suspend fun createFolder(name: String, parentId: String? = null) {
        client.createFolder(parentId ?: "msgfolderroot", name.trim())
        refreshFolders()
    }

    suspend fun renameFolder(folder: FolderEntity, name: String) {
        client.renameFolder(folder.id, name.trim())
        refreshFolders()
    }

    suspend fun deleteFolder(folder: FolderEntity) {
        client.deleteFolder(folder.id)
        messageDao.clearFolder(folder.id)
        refreshFolders()
    }

    // endregion

    suspend fun respondToMeeting(messageId: String, response: MeetingResponse) {
        val cached = listOfNotNull(messageDao.byId(messageId))
        client.respondToMeeting(ItemRef(messageId), response)
        // Сервер перемещает приглашение в «Удалённые»
        removeLocal(listOf(messageId), cached)
    }

    /** Скачивает вложение во внутренний кэш и возвращает файл (для открытия через FileProvider). */
    suspend fun downloadAttachment(att: AttachmentInfo): File {
        val data = client.getAttachments(listOf(att.id)).firstOrNull()
            ?: throw IllegalStateException("Сервер не отдал вложение")
        return withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "attachments/${att.id.hashCode().toUInt()}").apply { mkdirs() }
            val safeName = FileSaver.safeName(att.name)
            File(dir, safeName).apply { writeBytes(data.bytes) }
        }
    }

    /**
     * Картинка из текста письма по адресу из WebView: встроенная (data:, после подстановки cid)
     * или из интернета (только https). Сохраняется во временный файл; возвращает файл и MIME-тип.
     */
    suspend fun downloadImage(src: String): Pair<File, String> = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "images").apply { mkdirs() }
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").format(LocalDateTime.now())
        val inlineId = InlineImages.attachmentId(src)
        when {
            inlineId != null -> {
                val (cached, mime) = inlineImage(inlineId) ?: throw IOException("Картинка ещё не загружена")
                File(dir, "image_$stamp.${cache.extensionOf(mime)}").also { cached.copyTo(it, overwrite = true) } to mime
            }
            src.startsWith("data:", true) -> {
                val header = src.substringBefore(',')
                val mime = header.removePrefix("data:").substringBefore(';').ifBlank { "image/png" }
                val payload = src.substringAfter(',')
                val bytes = if (header.contains(";base64", true)) Base64.getMimeDecoder().decode(payload)
                else URLDecoder.decode(payload, "UTF-8").toByteArray()
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"
                File(dir, "image_$stamp.$ext").apply { writeBytes(bytes) } to mime
            }
            src.startsWith("https://", true) -> cache.http.newCall(Request.Builder().url(src).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                val mime = r.header("Content-Type")?.substringBefore(';')?.trim()?.ifBlank { null } ?: "image/jpeg"
                val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "jpg"
                val name = src.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }
                    ?.let(FileSaver::safeName)?.takeIf { '.' in it } ?: "image_$stamp.$ext"
                val file = File(dir, name)
                r.body?.byteStream()?.use { input -> file.outputStream().use { input.copyTo(it) } } ?: throw IOException("Пустой ответ")
                file to mime
            }
            else -> throw IllegalArgumentException("Эту картинку сохранить нельзя")
        }
    }

    companion object {
        const val PAGE = 50
        /** Найденных писем показывается не больше. */
        const val SEARCH_MAX = 100
        /** Поиск по тексту и дате: страница и предел просмотренных писем. */
        private const val SEARCH_PAGE = 100
        private const val SEARCH_SCAN = 1000

        /**
         * Письма, полученные в [fromMs, untilMs), из страниц [page] (от новых к старым; страница — письма и признак
         * последней): не больше [SEARCH_MAX], просмотр — не дальше [SEARCH_SCAN] писем.
         */
        internal suspend fun inPeriod(
            fromMs: Long,
            untilMs: Long,
            page: suspend (offset: Int) -> Pair<List<MessageEntity>, Boolean>,
        ): List<MessageEntity> {
            val found = ArrayList<MessageEntity>()
            var offset = 0
            while (offset < SEARCH_SCAN && found.size < SEARCH_MAX) {
                val (items, last) = page(offset)
                items.filterTo(found) { it.receivedAt in fromMs until untilMs }
                // Вся страница старше начала периода — дальше письма ещё старше
                if (last || items.isEmpty() || items.all { it.receivedAt < fromMs }) break
                offset += items.size
            }
            // Между страницами письмо могло прийти или удалиться — одно и то же попало бы в список дважды
            return found.distinctBy { it.id }.take(SEARCH_MAX)
        }
        private const val INLINE_MAX_BYTES = 5_000_000L
        private const val INLINE_CHUNK_BYTES = 2_000_000L
        private const val EDIT_TTL_NANOS = 10 * 60 * 1_000_000_000L

        fun russianName(wellKnown: String) = when (wellKnown) {
            "inbox" -> "Входящие"
            "drafts" -> "Черновики"
            "sentitems" -> "Отправленные"
            "deleteditems" -> "Удалённые"
            "junkemail" -> "Нежелательная почта"
            "outbox" -> "Исходящие"
            else -> wellKnown
        }

        fun MessageSummary.toEntity(folderId: String) = MessageEntity(
            id = ref.id,
            changeKey = ref.changeKey,
            folderId = folderId,
            subject = subject,
            fromName = from?.name.orEmpty(),
            fromEmail = from?.email.orEmpty(),
            displayTo = displayTo,
            preview = preview,
            receivedAt = received?.toEpochMilli() ?: 0L,
            isRead = isRead,
            hasAttachments = hasAttachments,
            highImportance = importance == "High",
            flagged = flagged,
            itemClass = itemClass,
        )
    }
}
