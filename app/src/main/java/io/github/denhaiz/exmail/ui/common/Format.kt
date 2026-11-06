package io.github.denhaiz.exmail.ui.common

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

object Format {
    val ru: Locale = Locale.forLanguageTag("ru-RU")
    private val time = DateTimeFormatter.ofPattern("HH:mm", ru)
    private val dayMonth = DateTimeFormatter.ofPattern("d MMM", ru)
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy", ru)
    private val full = DateTimeFormatter.ofPattern("d MMMM yyyy, HH:mm", ru)
    private val longDay = DateTimeFormatter.ofPattern("EEEE, d MMMM", ru)
    private val monthYear = DateTimeFormatter.ofPattern("LLLL yyyy", ru)

    fun zone(): ZoneId = ZoneId.systemDefault()

    /** Сегодняшняя дата — ключ для пересчёта относительных дат («вчера», «пн») при смене дня. */
    fun today(): LocalDate = LocalDate.now()

    private val initialsJunk = Regex("[<>\"()]")

    /** Короткое время для списка писем: «14:05», «вчера», «пн», «3 окт», «3 окт 2024». */
    fun listTime(millis: Long, now: ZonedDateTime = ZonedDateTime.now()): String {
        if (millis <= 0) return ""
        val t = Instant.ofEpochMilli(millis).atZone(zone())
        val days = java.time.temporal.ChronoUnit.DAYS.between(t.toLocalDate(), now.toLocalDate())
        return when {
            days == 0L -> t.format(time)
            days == 1L -> "вчера"
            days in 2..6 -> t.dayOfWeek.getDisplayName(TextStyle.SHORT, ru)
            t.year == now.year -> t.format(dayMonth)
            else -> t.format(dayMonthYear)
        }
    }

    fun full(instant: Instant?): String = instant?.atZone(zone())?.format(full).orEmpty()

    fun time(instant: Instant): String = instant.atZone(zone()).format(time)

    fun time(millis: Long): String = time(Instant.ofEpochMilli(millis))

    fun longDay(date: LocalDate): String = date.format(longDay).replaceFirstChar { it.titlecase(ru) }

    fun monthYear(date: LocalDate): String = date.format(monthYear).replaceFirstChar { it.titlecase(ru) }

    fun dayName(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, ru)

    /** «Сегодня», «Завтра» или «Пятница, 7 ноября». */
    fun relativeDay(date: LocalDate, today: LocalDate = LocalDate.now()): String = when (date) {
        today -> "Сегодня, " + date.format(DateTimeFormatter.ofPattern("d MMMM", ru))
        today.plusDays(1) -> "Завтра, " + date.format(DateTimeFormatter.ofPattern("d MMMM", ru))
        today.minusDays(1) -> "Вчера, " + date.format(DateTimeFormatter.ofPattern("d MMMM", ru))
        else -> longDay(date)
    }

    fun eventRange(start: Instant, end: Instant, allDay: Boolean): String {
        val z = zone()
        val s = start.atZone(z)
        val e = end.atZone(z)
        if (allDay) {
            val lastDay = e.minusSeconds(1).toLocalDate()
            return if (lastDay == s.toLocalDate()) relativeDay(s.toLocalDate()) + " · весь день"
            else "${s.format(dayMonth)} – ${lastDay.format(dayMonth)} · весь день"
        }
        return if (s.toLocalDate() == e.toLocalDate()) {
            relativeDay(s.toLocalDate()) + " · " + s.format(time) + "–" + e.format(time)
        } else {
            s.format(full) + " – " + e.format(full)
        }
    }

    fun size(bytes: Long): String = when {
        bytes <= 0 -> ""
        bytes < 1024 -> "$bytes Б"
        bytes < 1024 * 1024 -> "${bytes / 1024} КБ"
        else -> String.format(ru, "%.1f МБ", bytes / 1024.0 / 1024.0)
    }

    fun initials(name: String): String {
        val parts = name.replace(initialsJunk, " ").split(' ', '.', '_', '-').filter { it.isNotBlank() && it[0].isLetter() }
        return when {
            parts.isEmpty() -> name.firstOrNull { it.isLetterOrDigit() }?.uppercase() ?: "?"
            parts.size == 1 -> parts[0].take(1).uppercase()
            else -> (parts[0].take(1) + parts[1].take(1)).uppercase()
        }
    }

    fun plural(n: Int, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        val w = when {
            m10 == 1 && m100 != 11 -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
        return "$n $w"
    }

    val EMAIL = Regex("^[^\\s@<>,;]+@[^\\s@<>,;]+\\.[^\\s@<>,;]+$")
}
