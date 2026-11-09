package io.github.denhaiz.exmail.ui.common

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Источник картинок для страницы письма: встроенные в письмо — из кэша приложения, из интернета — через кэш
 * на диске. Вызывается из фонового потока WebView. null — запрос обрабатывает сам WebView.
 */
interface WebImageSource {
    fun intercept(url: Uri, headers: Map<String, String>, allowRemote: Boolean): WebResourceResponse?
}

val LocalWebImages = staticCompositionLocalOf<WebImageSource?> { null }

/**
 * Оформление страницы письма.
 * [darken] — письмо перекрашивается под тёмную тему: тексту и фону инвертируется яркость с сохранением оттенка,
 * картинки остаются как есть. Иначе письмо показывается в исходных (светлых) цветах.
 */
data class MailPageStyle(
    val background: Color,
    val text: Color,
    val link: Color,
    val quote: Color,
    val darken: Boolean,
) {
    companion object {
        /** Исходные цвета письма: белый фон, как в Outlook. */
        val Original = MailPageStyle(Color.White, Color(0xFF1B1F23), Color(0xFF0F6CBD), Color(0xFF5F6B76), darken = false)
    }
}

/** Стиль по текущей теме приложения. [darkMessages] — настройка «Тёмные письма». */
@Composable
fun mailPageStyle(darkMessages: Boolean = true): MailPageStyle {
    val c = MaterialTheme.colorScheme
    val dark = c.surface.luminance() < 0.5f
    return when {
        !dark -> MailPageStyle(c.surface, c.onSurface, c.primary, c.onSurfaceVariant, darken = false)
        darkMessages -> MailPageStyle(c.surface, c.onSurface, c.primary, c.onSurfaceVariant, darken = true)
        else -> MailPageStyle.Original
    }
}

private fun Color.css(): String = "#%06X".format(toArgb() and 0xFFFFFF)

private fun luminance(c: Color) = 0.213f * c.red + 0.715f * c.green + 0.072f * c.blue

/**
 * Сила инверсии `invert(p)` для тёмной темы: белый в письме становится тёмно-серым той же яркости, что фон темы
 * (без его лёгкого оттенка), а не чёрным. При `invert(1)` белые блоки и выделения письма (частый `background:white` в шаблонах Outlook и порталов)
 * выглядели чёрными прямоугольниками на сером фоне.
 */
internal fun MailPageStyle.invertAmount(): Float = (1f - luminance(background)).coerceIn(0.75f, 1f)

/**
 * Цвет, который после `filter: invert(p) hue-rotate(180deg)` станет этим цветом.
 * По спецификации CSS invert(p) — это `p + (1 − 2p)·x`, hue-rotate(180°) — `2·L − x` (L — яркость с весами
 * 0.213/0.715/0.072); hue-rotate обратен сам себе и перестановочен с invert, поэтому обращаем по очереди.
 */
internal fun Color.forDarken(p: Float = 1f): Color {
    val l = luminance(this)
    fun channel(y: Float) = (((2 * l - y) - p) / (1 - 2 * p)).coerceIn(0f, 1f)
    return Color(channel(red), channel(green), channel(blue))
}

/**
 * Элементы, которые в тёмной теме показываются в исходных цветах: картинки и блоки с фоновыми картинками
 * (атрибут background, background-image и сокращённая запись background:url(…)). Кроме самого body:
 * письмо может перенести на него свой фон, и тогда всё письмо стало бы чёрным прямоугольником.
 */
private val RESTORED = listOf(
    "img", "video", "picture", "svg", "canvas",
    "[background]:not(body)", "[style*=\"background-image\"]:not(body)", "[style*=\"url(\"]:not(body)",
)

/** Фон страницы письма. В тёмной теме — то, во что превращается белый: письмо сливается с фоном без «заплаток». */
fun MailPageStyle.pageColor(): Color = if (darken) invertAmount().let { Color(1f - it, 1f - it, 1f - it) } else background

private fun Float.css(): String = String.format(java.util.Locale.US, "%.4f", this)

/** HTML-страница письма с оформлением под тему. */
fun mailPage(html: String, style: MailPageStyle): String {
    val p = style.invertAmount()
    val text = if (style.darken) style.text.forDarken(p) else style.text
    val link = if (style.darken) style.link.forDarken(p) else style.link
    val quote = if (style.darken) style.quote.forDarken(p) else style.quote
    val page = style.pageColor()
    val darkenCss = if (style.darken) {
        // Содержимое письма (вместе с белым фоном) перекрашивается; картинки — обратно в исходные цвета:
        // invert(1) contrast(1/(2p−1)) — точное обращение invert(p)
        "body{background:#FFFFFF!important;filter:invert(${p.css()}) hue-rotate(180deg)!important}" +
            RESTORED.joinToString(",") + "{filter:invert(1) contrast(${(1f / (2 * p - 1)).css()}) hue-rotate(180deg)}" +
            // Внутри уже возвращённого элемента (картинка в <picture>, в ячейке с фоновой картинкой) — второй раз не обращаем
            RESTORED.flatMap { a -> RESTORED.map { b -> "$a $b" } }.joinToString(",") + "{filter:none}"
    } else ""
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <style>
          html{background:${page.css()}}
          body{margin:0;padding:12px 16px 20px;font-family:sans-serif;font-size:15px;line-height:1.45;color:${text.css()};background:transparent;word-wrap:break-word;overflow-wrap:anywhere}
          img{max-width:100%!important;height:auto!important}
          table{max-width:100%!important}
          pre{white-space:pre-wrap}
          a{color:${link.css()}}
          blockquote{margin:8px 0;padding-left:10px;border-left:3px solid ${quote.css()};color:${quote.css()}}
          $darkenCss
        </style></head><body>$html</body></html>"""
}

/**
 * Безопасный показ HTML письма: без JavaScript, ссылки открываются во внешнем браузере,
 * внешние картинки — только по разрешению пользователя.
 *
 * @param textZoom размер текста в процентах.
 * @param zoomable приближение жестом (для полноэкранного просмотра; во встроенном виде страница растёт по высоте).
 * @param onImageLongPress долгое нажатие на картинку: адрес картинки (http(s) или data:).
 * @param onWebView доступ к WebView (кнопки приближения в полноэкранном просмотре).
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlView(
    html: String,
    loadRemoteImages: Boolean,
    onMailto: (String) -> Unit,
    modifier: Modifier = Modifier,
    style: MailPageStyle = mailPageStyle(),
    textZoom: Int = 100,
    zoomable: Boolean = false,
    fillHeight: Boolean = false,
    onImageLongPress: ((String) -> Unit)? = null,
    onWebView: (WebView) -> Unit = {},
) {
    val page = remember(html, style) { mailPage(html, style) }
    val mailto by rememberUpdatedState(onMailto)
    val longPress by rememberUpdatedState(onImageLongPress)
    val images = LocalWebImages.current
    // Читается из потока WebView
    val allowRemote = remember { AtomicBoolean(loadRemoteImages) }
    // Страница проявляется, когда WebView отрисовал её, — без белой вспышки и «прыжка» пустой области
    // (по событию WebView «страница видна»; если его нет — через 600 мс)
    var committed by remember { mutableStateOf<Any?>(null) }
    var timedOut by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page) {
        delay(600)
        timedOut = true
    }
    val alpha by animateFloatAsState(if (committed === page || timedOut) 1f else 0f, tween(Motion.SHORT), label = "page")
    AndroidView(
        modifier = modifier.graphicsLayer { this.alpha = alpha },
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    if (fillHeight) ViewGroup.LayoutParams.MATCH_PARENT else ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                settings.javaScriptEnabled = false
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.setSupportZoom(zoomable)
                settings.builtInZoomControls = zoomable
                settings.displayZoomControls = false
                isVerticalScrollBarEnabled = fillHeight
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                        images?.intercept(request.url, request.requestHeaders.orEmpty(), allowRemote.get())

                    override fun onPageCommitVisible(view: WebView, url: String?) {
                        committed = view.tag
                    }

                    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                        // Переход без касания пользователя (meta refresh в письме) — не открываем ничего:
                        // иначе письмо само откроет сайт, например фишинговый
                        if (!request.hasGesture() || request.isRedirect) return true
                        val url = request.url
                        when (url.scheme?.lowercase()) {
                            "mailto" -> mailto(url.schemeSpecificPart.substringBefore('?'))
                            // Только веб-ссылки и телефон: intent:, content:, file: и схемы других приложений из письма не открываются
                            "http", "https", "tel" ->
                                runCatching { view.context.startActivity(Intent(Intent.ACTION_VIEW, url).addCategory(Intent.CATEGORY_BROWSABLE)) }
                        }
                        return true
                    }
                }
                setOnLongClickListener { v ->
                    val r = (v as WebView).hitTestResult
                    val src = r.extra
                    val isImage = r.type == WebView.HitTestResult.IMAGE_TYPE || r.type == WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE
                    val handler = longPress
                    if (isImage && src != null && handler != null) {
                        handler(src)
                        true
                    } else {
                        false
                    }
                }
                onWebView(this)
            }
        },
        update = { wv ->
            wv.setBackgroundColor(style.pageColor().toArgb())
            wv.settings.loadsImagesAutomatically = true
            if (wv.settings.textZoom != textZoom) wv.settings.textZoom = textZoom
            val remoteChanged = allowRemote.getAndSet(loadRemoteImages) != loadRemoteImages
            if (images == null) {
                wv.settings.blockNetworkImage = !loadRemoteImages
            }
            if (wv.tag !== page) {
                wv.tag = page
                wv.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            } else if (remoteChanged && images != null) {
                // Разрешили картинки из интернета — страница перезагружается уже с ними
                wv.loadDataWithBaseURL(null, page, "text/html", "utf-8", null)
            }
        },
        // Экран закрыт — освобождаем память и процессы WebView сразу, а не при сборке мусора
        onRelease = { wv ->
            wv.stopLoading()
            wv.destroy()
        },
    )
}
