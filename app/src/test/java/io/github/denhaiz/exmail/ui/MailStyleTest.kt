package io.github.denhaiz.exmail.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.denhaiz.exmail.ui.common.MailPageStyle
import io.github.denhaiz.exmail.ui.common.forDarken
import io.github.denhaiz.exmail.ui.common.invertAmount
import io.github.denhaiz.exmail.ui.common.pageColor
import io.github.denhaiz.exmail.ui.common.mailPage

/** Оформление письма под тему приложения. */
class MailStyleTest {
    /** То, что делает браузер с цветом под `filter: invert(p) hue-rotate(180deg)` (матрицы из спецификации CSS). */
    private fun cssFilter(c: Color, p: Float = 1f): Color {
        val r = p + (1 - 2 * p) * c.red
        val g = p + (1 - 2 * p) * c.green
        val b = p + (1 - 2 * p) * c.blue
        return Color(
            (-0.574f * r + 1.430f * g + 0.144f * b).coerceIn(0f, 1f),
            (0.426f * r + 0.430f * g + 0.144f * b).coerceIn(0f, 1f),
            (0.426f * r + 1.430f * g - 0.856f * b).coerceIn(0f, 1f),
        )
    }

    @Test
    fun darkenedTextEndsUpAsThemeColor() {
        for (target in listOf(Color(0xFFE2E2E6), Color(0xFF8CC2F5), Color(0xFFB8C8D8))) {
            val shown = cssFilter(target.forDarken())
            assertEquals(target.red, shown.red, 0.02f)
            assertEquals(target.green, shown.green, 0.02f)
            assertEquals(target.blue, shown.blue, 0.02f)
        }
    }

    @Test
    fun darkTheme_textMatchesThemeAndWhiteBecomesBackground() {
        val style = MailPageStyle(Color(0xFF111418), Color(0xFFE2E2E6), Color(0xFF8CC2F5), Color(0xFFB8C8D8), darken = true)
        val p = style.invertAmount()
        for (target in listOf(style.text, style.link, style.quote)) {
            val shown = cssFilter(target.forDarken(p), p)
            assertEquals(target.red, shown.red, 0.02f)
            assertEquals(target.green, shown.green, 0.02f)
            assertEquals(target.blue, shown.blue, 0.02f)
        }
        // Белые блоки письма — цвета фона страницы (раньше становились чёрными прямоугольниками)
        val white = cssFilter(Color.White, p)
        val page = style.pageColor()
        assertEquals(page.red, white.red, 0.01f)
        assertEquals(page.green, white.green, 0.01f)
        // и почти не отличаются от фона приложения
        assertEquals(style.background.green, white.green, 0.03f)
    }

    @Test
    fun darkPage_usesThemeBackgroundAndKeepsImages() {
        val style = MailPageStyle(Color(0xFF111418), Color(0xFFE2E2E6), Color(0xFF8CC2F5), Color(0xFFB8C8D8), darken = true)
        val page = mailPage("<p>Привет</p><img src=\"cid:x\">", style)
        assertTrue(page.contains("html{background:#131313}") || page.contains("html{background:#141414}"))
        assertTrue(page.contains("body{background:#FFFFFF!important;filter:invert(0.9"))
        // Фон письма на body не превращает всё письмо в чёрный прямоугольник; вложенные картинки не обращаются дважды
        assertTrue(page.contains("[background]:not(body)"))
        assertTrue(page.contains("picture img"))
        assertTrue(page.contains("[style*=\"url(\"]:not(body)"))
        assertTrue(page.contains("{filter:invert(1) contrast(1.1"))
        assertTrue(page.contains("img,video"))
        assertTrue(page.contains("<p>Привет</p>"))
    }

    @Test
    fun originalPage_isWhiteWithoutFilter() {
        val page = mailPage("<p>x</p>", MailPageStyle.Original)
        assertTrue(page.contains("html{background:#FFFFFF}"))
        assertFalse(page.contains("filter:"))
    }
}
