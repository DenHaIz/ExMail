package io.github.denhaiz.exmail.data

import android.net.Uri
import android.webkit.WebResourceResponse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Request
import io.github.denhaiz.exmail.data.repo.MailRepository
import io.github.denhaiz.exmail.ui.common.WebImageSource
import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/** Адреса встроенных (cid:) картинок письма: WebView запрашивает их, а [MailImages] отдаёт из кэша. */
object InlineImages {
    /** Несуществующий домен: запрос никогда не уходит в сеть, его перехватывает приложение. */
    const val HOST = "inline.exmail.invalid"
    private const val PREFIX = "https://$HOST/"

    fun url(attachmentId: String): String = PREFIX + URLEncoder.encode(attachmentId, "UTF-8")

    fun attachmentId(url: String): String? =
        url.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.let { URLDecoder.decode(it, "UTF-8") }
}

/**
 * Картинки для страницы письма в WebView:
 * - встроенные в письмо — из дискового кэша, иначе одним запросом к серверу на все картинки письма;
 * - из интернета (https) — через HTTP-кэш на [MailCache.MAX_AGE_DAYS] дней, если пользователь разрешил их показ.
 */
@Singleton
class MailImages @Inject constructor(
    private val repo: MailRepository,
    private val cache: MailCache,
) : WebImageSource {

    /** Вызывается из потока WebView (не главного) — блокирующие операции допустимы. */
    override fun intercept(url: Uri, headers: Map<String, String>, allowRemote: Boolean): WebResourceResponse? {
        val scheme = url.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (url.host == InlineImages.HOST) return inline(url.toString())
        if (!allowRemote) return blank() // картинки из интернета скрыты — ни одного запроса к чужим серверам
        if (scheme != "https") return null
        return try {
            val request = Request.Builder().url(url.toString()).apply {
                // Только безопасные заголовки: сжатие OkHttp обрабатывает сам, условные запросы отдал бы 304
                headers.forEach { (k, v) -> if (k.equals("User-Agent", true) || k.equals("Accept", true)) header(k, v) }
            }.build()
            val response = cache.http.newCall(request).execute()
            val body = response.body
            if (!response.isSuccessful || body == null) {
                response.close()
                return blank()
            }
            val type = body.contentType()
            WebResourceResponse(type?.let { "${it.type}/${it.subtype}" } ?: "image/*", type?.charset()?.name(), body.byteStream())
        } catch (_: Exception) {
            null
        }
    }

    private fun inline(url: String): WebResourceResponse {
        val id = InlineImages.attachmentId(url) ?: return blank()
        val found = runCatching { runBlocking { withTimeoutOrNull(INLINE_TIMEOUT_MS) { repo.inlineImage(id) } } }.getOrNull()
            ?: return blank()
        val (file, mime) = found
        // Файл мог исчезнуть (выход из учётной записи, очистка кэша) — пустая картинка вместо сбоя
        val stream = runCatching { file.inputStream() }.getOrNull() ?: return blank()
        return WebResourceResponse(mime, null, stream)
    }

    /** Прозрачная картинка 1×1: вместо скрытой картинки — пустое место без значка «картинка не загружена». */
    private fun blank() = WebResourceResponse("image/gif", null, ByteArrayInputStream(TRANSPARENT_GIF))

    private companion object {
        const val INLINE_TIMEOUT_MS = 60_000L
        val TRANSPARENT_GIF = byteArrayOf(
            0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x21, 0xF9.toByte(), 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2C, 0x00, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3B,
        )
    }
}
