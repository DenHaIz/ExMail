package io.github.denhaiz.exmail.data

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import io.github.denhaiz.exmail.data.db.MessageEntity
import io.github.denhaiz.exmail.data.repo.MailRepository
import io.github.denhaiz.exmail.ui.mail.DateRange
import io.github.denhaiz.exmail.ui.mail.MessageActions
import io.github.denhaiz.exmail.ui.mail.Swipes
import io.github.denhaiz.exmail.ui.mail.dateRangeLabel
import java.time.LocalDate

/** Поиск по дате и настраиваемые свайпы в списке писем. */
class SearchSwipeTest {
    private fun msg(id: String, at: Long) =
        MessageEntity(id, null, "inbox", "", "", "", "", "", at, false, false, false, false, "IPM.Note")

    @Test
    fun inPeriod_filtersPagesAndStopsAfterPeriod() = runTest {
        // Страницы по 3 письма от новых к старым: 100, 90, … 10
        val all = (10 downTo 1).map { msg("m$it", it * 10L) }
        val offsets = mutableListOf<Int>()
        val found = MailRepository.inPeriod(fromMs = 40, untilMs = 80) { offset ->
            offsets += offset
            val page = all.drop(offset).take(3)
            page to (offset + 3 >= all.size)
        }
        assertEquals(listOf("m7", "m6", "m5", "m4"), found.map { it.id }) // конец периода не включается
        // На третьей странице (40, 30, 20) ещё есть письмо из периода — запрошена и последняя
        assertEquals(listOf(0, 3, 6, 9), offsets)
    }

    @Test
    fun inPeriod_stopsWhenWholePageIsOlder() = runTest {
        val all = (10 downTo 1).map { msg("m$it", it * 10L) }
        val offsets = mutableListOf<Int>()
        val found = MailRepository.inPeriod(fromMs = 70, untilMs = Long.MAX_VALUE) { offset ->
            offsets += offset
            all.drop(offset).take(3) to (offset + 3 >= all.size)
        }
        assertEquals(listOf("m10", "m9", "m8", "m7"), found.map { it.id })
        // 100,90,80 → 70,60,50 (есть 70) → 40,30,20 — все старше: дальше не ищем
        assertEquals(listOf(0, 3, 6), offsets)
    }

    @Test
    fun dateRangeLabel_readable() {
        val today = LocalDate.of(2026, 11, 6)
        assertEquals("Сегодня", dateRangeLabel(DateRange(today, today), today))
        assertEquals("Вчера", dateRangeLabel(DateRange(today.minusDays(1), today.minusDays(1)), today))
        assertEquals("Последние 7 дней", dateRangeLabel(DateRange(today.minusDays(6), today), today))
        assertEquals("Последние 30 дней", dateRangeLabel(DateRange(today.minusDays(29), today), today))
        assertEquals("2 нояб. – сегодня", dateRangeLabel(DateRange(LocalDate.of(2026, 11, 2), today), today))
        assertEquals("1 окт. 2025 – 3 окт. 2025", dateRangeLabel(DateRange(LocalDate.of(2025, 10, 1), LocalDate.of(2025, 10, 3)), today))
    }

    @Test
    fun swipeAction_parsesStoredKeys() {
        assertEquals(SwipeAction.DELETE, SwipeAction.of("delete", SwipeAction.NONE))
        assertEquals(SwipeAction.READ, SwipeAction.of(null, SwipeAction.READ)) // по умолчанию — как раньше
        assertEquals(SwipeAction.NONE, SwipeAction.of("unknown", SwipeAction.NONE))
        // Значения по умолчанию: вправо — прочитано, влево — ничего
        assertEquals(SwipeAction.READ, AppSettings().swipeRight)
        assertEquals(SwipeAction.NONE, AppSettings().swipeLeft)
    }

    @Test
    fun messageActions_draftsHaveNoReplySwipe() {
        val calls = mutableListOf<String>()
        fun actions(canReply: Boolean) = MessageActions(
            onOpen = {}, onReply = { calls += "reply" }, onForward = { calls += "forward" },
            onToggleRead = { calls += "read" }, onToggleFlag = {}, onMove = {}, onDelete = { calls += "delete" },
            canReply = canReply, swipes = Swipes(SwipeAction.DELETE, SwipeAction.REPLY),
        )
        val drafts = actions(canReply = false)
        assertEquals(SwipeAction.DELETE, drafts.swipeRight)
        assertEquals(SwipeAction.NONE, drafts.swipeLeft)
        val inbox = actions(canReply = true)
        assertEquals(SwipeAction.REPLY, inbox.swipeLeft)
        inbox.perform(inbox.swipeLeft, msg("a", 0))
        inbox.perform(inbox.swipeRight, msg("a", 0))
        assertEquals(listOf("reply", "delete"), calls)
    }
}
