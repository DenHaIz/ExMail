package io.github.denhaiz.exmail.data

import android.content.Context
import android.webkit.MimeTypeMap
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import io.github.denhaiz.exmail.ews.AttachmentInfo
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.Mailbox
import io.github.denhaiz.exmail.ews.MeetingInfo
import io.github.denhaiz.exmail.ews.MessageDetail
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Дисковый кэш писем и картинок из них (в личной папке приложения, удаляется при выходе).
 * Полученное письмо не меняется, поэтому повторное открытие — без запроса к серверу,
 * а картинки, встроенные в письма и загруженные из интернета, хранятся [retentionDays] дней (настройка «Хранить письма»).
 * 0 — не хранить: письма и картинки загружаются при каждом открытии, временные файлы удаляются при запуске.
 */
@Singleton
class MailCache @Inject constructor(@ApplicationContext context: Context) {
    private val messages = File(context.cacheDir, "messages")
    private val images = File(context.cacheDir, "mail-images")

    /** HTTP-кэш картинок из интернета. */
    private val web = File(context.cacheDir, "mail-web")

    /** Срок хранения, дней (из настроек); 0 — не хранить. */
    @Volatile
    var retentionDays: Int = MAX_AGE_DAYS.toInt()
        private set
    val enabled: Boolean get() = retentionDays > 0
    private val maxAgeMillis: Long get() = TimeUnit.DAYS.toMillis(retentionDays.toLong())

    /** Новый срок: сокращение — сразу удаляем то, что старше; «не хранить» — удаляем всё. */
    fun setRetention(days: Int) {
        val shorter = days < retentionDays
        retentionDays = days.coerceAtLeast(0)
        if (!enabled) clear() else if (shorter) trim()
    }

    /** Клиент для картинок из интернета: с кэшем, пока хранение включено. */
    val http: OkHttpClient get() = if (enabled) cachedHttp else plainHttp

    private val plainHttp: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val cachedHttpLazy = lazy {
        OkHttpClient.Builder()
            .cache(Cache(web, WEB_CACHE_BYTES))
            // Картинки в письмах не меняются: храним их, даже если сервер рассылки запрещает кэш
            .addNetworkInterceptor { chain ->
                val r = chain.proceed(chain.request())
                if (!r.isSuccessful) r
                else r.newBuilder()
                    .removeHeader("Pragma")
                    .removeHeader("Expires")
                    .header("Cache-Control", "public, max-age=${TimeUnit.DAYS.toSeconds(retentionDays.toLong().coerceAtLeast(1))}")
                    .build()
            }
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }
    private val cachedHttp: OkHttpClient by cachedHttpLazy

    /** Очистка HTTP-кэша картинок; если клиент ещё не создавался — просто удаляем папку. */
    private fun clearWeb() {
        if (cachedHttpLazy.isInitialized()) runCatching { cachedHttp.cache?.evictAll() } else web.deleteRecursively()
    }

    fun readMessage(id: String): MessageDetail? {
        if (!enabled) return null
        val file = File(messages, key(id))
        if (!file.isFile || expired(file)) return null
        return runCatching { MessageCodec.decode(file.readBytes()) }.getOrNull()?.takeIf { it.ref.id == id }
    }

    /** Меняется при очистке: запись, начатая до выхода из учётной записи, не попадёт в кэш новой. */
    @Volatile
    var generation = 0
        private set

    fun writeMessage(detail: MessageDetail, generation: Int) {
        if (generation != this.generation || !enabled) return
        runCatching {
            messages.mkdirs()
            atomicWrite(File(messages, key(detail.ref.id)), MessageCodec.encode(detail))
        }
    }

    fun removeMessages(ids: Collection<String>) {
        ids.forEach { File(messages, key(it)).delete() }
    }

    /** Встроенная картинка по id вложения: файл и MIME-тип или null. */
    fun image(attachmentId: String): Pair<File, String>? {
        if (!enabled) return null // файл мог остаться от текущего показа письма — повторно не используем
        val base = key(attachmentId)
        val file = File(images, "$base.img")
        if (!file.isFile || expired(file)) return null
        val mime = runCatching { File(images, "$base.mime").readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return file to mime
    }

    /**
     * Картинка записывается целиком под временным именем и затем переименовывается — недописанный файл не отдаётся.
     * Тип пишется после картинки: без него запись не считается готовой.
     */
    fun putImage(attachmentId: String, contentType: String, bytes: ByteArray, generation: Int): Pair<File, String> {
        val mime = contentType.substringBefore(';').trim().ifBlank { "image/png" }.lowercase()
        val base = key(attachmentId)
        val file = File(images, "$base.img")
        if (generation == this.generation) {
            images.mkdirs()
            atomicWrite(file, bytes)
            atomicWrite(File(images, "$base.mime"), mime.toByteArray())
        }
        return file to mime
    }

    /** Расширение файла для сохранения картинки по её MIME-типу. */
    fun extensionOf(mime: String): String = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime) ?: "png"

    /** Удаляет записи старше срока хранения (при запуске и при смене настройки, в фоне). */
    fun trim() {
        if (!enabled) {
            listOf(messages, images).forEach { it.deleteRecursively() }
            clearWeb()
            return
        }
        listOf(messages, images).forEach { dir -> dir.listFiles()?.filter(::expired)?.forEach { it.delete() } }
    }

    /** Сколько занимают письма и картинки на телефоне, байт. */
    fun sizeBytes(): Long = listOf(messages, images, web).sumOf { dir ->
        dir.walkTopDown().filter { it.isFile && !it.name.startsWith("journal") }.sumOf { it.length() } // журнал HTTP-кэша — служебный
    }

    fun clear() {
        generation++
        listOf(messages, images).forEach { it.deleteRecursively() }
        clearWeb()
    }

    private fun expired(f: File) = System.currentTimeMillis() - f.lastModified() > maxAgeMillis

    /** Запись через уникальный временный файл: два одновременных писателя не испортят друг другу данные. */
    private fun atomicWrite(target: File, bytes: ByteArray) {
        val tmp = File.createTempFile(target.name, ".tmp", target.parentFile)
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } finally {
            tmp.delete()
        }
    }

    private fun key(id: String): String =
        MessageDigest.getInstance("SHA-256").digest(id.toByteArray()).take(16).joinToString("") { "%02x".format(it) }

    companion object {
        const val MAX_AGE_DAYS = 14L
        val MAX_AGE_MILLIS = TimeUnit.DAYS.toMillis(MAX_AGE_DAYS)
        const val WEB_CACHE_BYTES = 150L * 1024 * 1024
    }
}

/** Двоичная запись письма для кэша. Новая версия формата делает старые записи недействительными. */
internal object MessageCodec {
    private const val VERSION = 1

    fun encode(d: MessageDetail): ByteArray {
        val bytes = ByteArrayOutputStream(d.bodyHtml.length + 512)
        DataOutputStream(bytes).use { o ->
            o.writeInt(VERSION)
            o.str(d.ref.id)
            o.opt(d.ref.changeKey)
            o.str(d.subject)
            o.mailbox(d.from)
            listOf(d.to, d.cc, d.bcc).forEach { list ->
                o.writeInt(list.size)
                list.forEach { o.mailbox(it) }
            }
            o.instant(d.received)
            o.str(d.bodyHtml)
            o.writeBoolean(d.isRead)
            o.writeBoolean(d.flagged)
            o.str(d.importance)
            o.str(d.itemClass)
            o.writeInt(d.attachments.size)
            d.attachments.forEach { a ->
                o.str(a.id)
                o.str(a.name)
                o.str(a.contentType)
                o.writeLong(a.size)
                o.writeBoolean(a.isInline)
                o.opt(a.contentId)
                o.writeBoolean(a.isItem)
            }
            val m = d.meeting
            o.writeBoolean(m != null)
            if (m != null) {
                o.instant(m.start)
                o.instant(m.end)
                o.str(m.location)
                o.writeBoolean(m.isAllDay)
                o.writeBoolean(m.isCancellation)
                o.writeBoolean(m.isResponse)
                o.opt(m.responseType)
            }
            o.opt(d.parentFolderId)
        }
        return bytes.toByteArray()
    }

    fun decode(data: ByteArray): MessageDetail? = DataInputStream(ByteArrayInputStream(data)).use { i ->
        if (i.readInt() != VERSION) return null
        val ref = ItemRef(i.str(), i.opt())
        val subject = i.str()
        val from = i.mailbox()
        val (to, cc, bcc) = List(3) { List(i.readInt()) { i.mailbox()!! } }
        val received = i.instant()
        val body = i.str()
        val isRead = i.readBoolean()
        val flagged = i.readBoolean()
        val importance = i.str()
        val itemClass = i.str()
        val attachments = List(i.readInt()) {
            AttachmentInfo(i.str(), i.str(), i.str(), i.readLong(), i.readBoolean(), i.opt(), i.readBoolean())
        }
        val meeting = if (i.readBoolean()) {
            MeetingInfo(i.instant(), i.instant(), i.str(), i.readBoolean(), i.readBoolean(), i.readBoolean(), i.opt())
        } else null
        MessageDetail(ref, subject, from, to, cc, bcc, received, body, isRead, flagged, importance, itemClass, attachments, meeting, i.opt())
    }

    // Длина строки — Int: текст письма бывает длиннее 64 КБ (предел writeUTF)
    private fun DataOutputStream.str(s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        writeInt(b.size)
        write(b)
    }

    private fun DataInputStream.str(): String {
        val b = ByteArray(readInt())
        readFully(b)
        return String(b, Charsets.UTF_8)
    }

    private fun DataOutputStream.opt(s: String?) {
        writeBoolean(s != null)
        if (s != null) str(s)
    }

    private fun DataInputStream.opt(): String? = if (readBoolean()) str() else null

    private fun DataOutputStream.mailbox(m: Mailbox?) {
        writeBoolean(m != null)
        if (m != null) {
            str(m.name)
            str(m.email)
        }
    }

    private fun DataInputStream.mailbox(): Mailbox? = if (readBoolean()) Mailbox(str(), str()) else null

    private fun DataOutputStream.instant(t: Instant?) = writeLong(t?.toEpochMilli() ?: Long.MIN_VALUE)

    private fun DataInputStream.instant(): Instant? = readLong().takeIf { it != Long.MIN_VALUE }?.let(Instant::ofEpochMilli)
}
