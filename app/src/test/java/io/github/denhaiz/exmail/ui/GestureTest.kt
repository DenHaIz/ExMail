package io.github.denhaiz.exmail.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import io.github.denhaiz.exmail.ui.common.HOLD_MILLIS
import io.github.denhaiz.exmail.ui.common.HoldableBox
import io.github.denhaiz.exmail.ui.common.ReorderableColumn

/** Жесты: удержание письма и перетаскивание пунктов боковой панели. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class GestureTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun hold_opensMenuAfterThreeQuartersOfSecond() {
        var taps = 0
        var holds = 0
        rule.setContent {
            HoldableBox(onTap = { taps++ }, onHold = { holds++ }, modifier = Modifier.testTag("row")) {
                Box(Modifier.fillMaxWidth().height(72.dp))
            }
        }
        assertEquals(500L, HOLD_MILLIS)

        rule.onNodeWithTag("row").performTouchInput { down(center); advanceEventTime(300); up() }
        rule.waitForIdle()
        assertEquals(1 to 0, taps to holds)

        rule.onNodeWithTag("row").performTouchInput { down(center); advanceEventTime(HOLD_MILLIS + 100); up() }
        rule.waitForIdle()
        assertEquals(1 to 1, taps to holds)
    }

    @Test
    fun hold_cancelledWhenFingerMoves() {
        var events = 0
        rule.setContent {
            HoldableBox(onTap = { events++ }, onHold = { events++ }, modifier = Modifier.testTag("row")) {
                Box(Modifier.fillMaxWidth().height(72.dp))
            }
        }
        rule.onNodeWithTag("row").performTouchInput {
            down(center)
            moveBy(Offset(0f, 200f))
            advanceEventTime(HOLD_MILLIS + 100)
            up()
        }
        rule.waitForIdle()
        assertEquals(0, events)
    }

    @Test
    fun reorder_keepsNewOrderAfterDrop() {
        var reported: List<String>? = null
        rule.setContent {
            // Источник обновляется не сразу (как запись в DataStore) — список не должен прыгать назад
            val items = remember { listOf("a", "b", "c") }
            ReorderableColumn(items = items, key = { it }, onReorder = { reported = it }) { item, _, handle ->
                Text(item, handle.fillMaxWidth().height(48.dp).testTag(item))
            }
        }
        val step = with(rule.density) { 48.dp.toPx() }
        rule.onNodeWithTag("a").performTouchInput {
            down(center)
            advanceEventTime(1000) // долгое нажатие «поднимает» пункт
            moveBy(Offset(0f, step * 0.3f))
            moveBy(Offset(0f, step * 0.6f)) // ниже середины «b»
            moveBy(Offset(0f, step * 0.6f))
            moveBy(Offset(0f, step * 0.6f)) // ниже середины «c»
            up()
        }
        rule.waitForIdle()
        assertEquals(listOf("b", "c", "a"), reported)
        // Источник ещё старый, но на экране — новый порядок
        val top = { tag: String -> rule.onNodeWithTag(tag).fetchSemanticsNode().positionInRoot.y }
        assert(top("b") < top("c") && top("c") < top("a")) { "порядок на экране вернулся назад" }
    }
}
