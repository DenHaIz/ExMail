package io.github.denhaiz.exmail.ews

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.data.db.FolderEntity
import io.github.denhaiz.exmail.ui.mail.DrawerEntry
import io.github.denhaiz.exmail.ui.mail.MailListViewModel
import java.time.LocalDate
import java.time.ZoneOffset

class ExtrasTest {
    private val start = LocalDate.of(2026, 11, 4).atTime(10, 0).toInstant(ZoneOffset.UTC) // среда

    @Test
    fun recurrence_weekly_noEnd() {
        val xml = recurrenceXml(Recurrence(Recurrence.Kind.WEEKLY), start, ZoneOffset.UTC)
        assertEquals(
            "<t:Recurrence><t:WeeklyRecurrence><t:Interval>1</t:Interval><t:DaysOfWeek>Wednesday</t:DaysOfWeek></t:WeeklyRecurrence>" +
                "<t:NoEndRecurrence><t:StartDate>2026-11-04</t:StartDate></t:NoEndRecurrence></t:Recurrence>",
            xml,
        )
    }

    @Test
    fun recurrence_yearly_withEnd() {
        val xml = recurrenceXml(Recurrence(Recurrence.Kind.YEARLY, LocalDate.of(2030, 1, 1)), start, ZoneOffset.UTC)
        assertTrue(xml.contains("<t:AbsoluteYearlyRecurrence><t:DayOfMonth>4</t:DayOfMonth><t:Month>November</t:Month>"))
        assertTrue(xml.contains("<t:EndDateRecurrence><t:StartDate>2026-11-04</t:StartDate><t:EndDate>2030-01-01</t:EndDate>"))
    }

    @Test
    fun drawer_respectsSavedOrder() {
        val folders = listOf(
            FolderEntity("i", null, "Входящие", "inbox", 0, 0, 0, 0),
            FolderEntity("s", null, "Отправленные", "sentitems", 0, 0, 0, 1),
            FolderEntity("a", null, "А-папка", null, 0, 0, 0, 2),
            FolderEntity("b", "a", "Б-вложенная", null, 0, 0, 1, 3),
        )
        val settings = AppSettings(
            tabOrder = listOf("contacts", "mail", "calendar"),
            groupOrder = listOf("user", "main", "tabs"),
            folderOrder = listOf("s", "i", "b", "a"),
        )
        val groups = MailListViewModel.buildDrawer(folders, settings)
        assertEquals(listOf("user", "main", "tabs"), groups.map { it.key })
        assertEquals(listOf("b", "a"), groups[0].entries.map { (it as DrawerEntry.Folder).folder.id })
        assertEquals("А-папка › Б-вложенная", (groups[0].entries[0] as DrawerEntry.Folder).title)
        assertEquals(listOf("s", "i"), groups[1].entries.map { (it as DrawerEntry.Folder).folder.id })
        assertEquals(listOf("contacts", "mail", "calendar"), groups[2].entries.map { (it as DrawerEntry.Tab).route })
    }
}
