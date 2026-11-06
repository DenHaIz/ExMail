package io.github.denhaiz.exmail.ui

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.graphics.Canvas
import android.view.View
import org.robolectric.shadows.ShadowDialog
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.github.denhaiz.exmail.data.AppSettings
import io.github.denhaiz.exmail.data.db.EventEntity
import io.github.denhaiz.exmail.data.db.FolderEntity
import io.github.denhaiz.exmail.data.db.MessageEntity
import io.github.denhaiz.exmail.ews.Availability
import io.github.denhaiz.exmail.ews.AttachmentInfo
import io.github.denhaiz.exmail.ews.ContactInfo
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.Mailbox
import io.github.denhaiz.exmail.ews.MeetingInfo
import io.github.denhaiz.exmail.ews.MessageDetail
import io.github.denhaiz.exmail.ui.calendar.AvailabilityGrid
import io.github.denhaiz.exmail.ui.calendar.EventCard
import io.github.denhaiz.exmail.ui.calendar.MonthPager
import io.github.denhaiz.exmail.ui.calendar.WeekPager
import io.github.denhaiz.exmail.ui.calendar.startInstant
import io.github.denhaiz.exmail.ui.common.AddressBookDialog
import io.github.denhaiz.exmail.ui.common.CompactSearchField
import io.github.denhaiz.exmail.ui.common.ErrorBanner
import io.github.denhaiz.exmail.ui.common.Recipient
import io.github.denhaiz.exmail.ui.common.RecipientField
import io.github.denhaiz.exmail.ui.contacts.ContactRow
import io.github.denhaiz.exmail.ui.mail.FolderDrawer
import io.github.denhaiz.exmail.ui.mail.MailListViewModel
import io.github.denhaiz.exmail.ui.mail.MessageContent
import io.github.denhaiz.exmail.ui.mail.MessageRow
import io.github.denhaiz.exmail.ui.theme.ExMailTheme
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/**
 * Рендер ключевых элементов интерфейса в PNG на ширине телефона (360 dp) —
 * для проверки переносов и вёрстки без эмулятора. Картинки: app/build/screenshots/.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-xhdpi", application = Application::class)
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
class LayoutScreenshotTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val outDir = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        rule.setContent {
            ExMailTheme(dark = dark) {
                // Как в приложении: поверхность задаёт и фон, и цвет текста по умолчанию
                androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.width(360.dp)) { content() }
                }
            }
        }
        rule.waitForIdle()
        save(name, draw(rule.activity.window.decorView))
    }

    /** Рисует окно в Bitmap (без PixelCopy, который в Robolectric недоступен). */
    private fun draw(view: View): Bitmap {
        rule.runOnIdle { }
        val bmp = Bitmap.createBitmap(view.width.coerceAtLeast(1), view.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        rule.runOnUiThread { view.draw(Canvas(bmp)) }
        return bmp
    }

    private fun save(name: String, bmp: Bitmap) {
        File(outDir, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private val now = System.currentTimeMillis()

    private fun msg(i: Int, from: String, subject: String, preview: String, read: Boolean = true, att: Boolean = false, flag: Boolean = false, cls: String = "IPM.Note", high: Boolean = false) =
        MessageEntity("id$i", null, "inbox", subject, from, "user$i@example.com", "Я", preview, now - i * 3_600_000L * 7, read, att, high, flag, cls)

    @Test
    fun mailList() = shot("01_mail_list") {
        MessageRow(msg(1, "Иван Петров", "Квартальный отчёт по продажам в регионах за третий квартал 2026 года", "Коллеги, во вложении отчёт за третий квартал. Прошу ознакомиться до пятницы и прислать замечания", read = false, att = true, flag = true, high = true))
        HorizontalDivider()
        MessageRow(msg(2, "Анна Смирнова-Константинопольская", "Приглашение: Планёрка отдела", "", cls = "IPM.Schedule.Meeting.Request"))
        HorizontalDivider()
        MessageRow(msg(30, "noreply@very-long-domain-name.example.com", "", "Короткий текст"))
        ErrorBanner("Нет связи с почтовым сервером. Проверьте подключение к интернету.", onRetry = {}, onDismiss = {})
    }

    @Test
    fun drawer() = shot("02_drawer") {
        val folders = listOf(
            FolderEntity("inbox", null, "Входящие", "inbox", 12, 100, 0, 0),
            FolderEntity("drafts", null, "Черновики", "drafts", 0, 2, 0, 1),
            FolderEntity("sent", null, "Отправленные", "sentitems", 0, 50, 0, 2),
            FolderEntity("del", null, "Удалённые", "deleteditems", 1234, 2000, 0, 3),
            FolderEntity("junk", null, "Нежелательная почта", "junkemail", 3, 3, 0, 4),
            FolderEntity("p", null, "Проекты", null, 0, 0, 0, 5),
            FolderEntity("p1", "p", "Внедрение корпоративной почты на мобильных устройствах", null, 4, 10, 1, 6),
        )
        FolderDrawer(
            // Длинное ФИО и адрес — должны помещаться целиком (переносом строки)
            accountName = "Константинопольский-Иванов Александр Сергеевич", accountEmail = "alexander.konstantinopolsky-ivanov@example.com",
            groups = MailListViewModel.buildDrawer(folders, AppSettings()),
            current = folders.first(),
            onSelectFolder = {}, onSelectTab = {}, onReorderGroups = {}, onReorderEntries = { _, _ -> },
            onNewFolder = {}, onSettings = {},
        )
    }

    @Test
    fun messageWithMeeting() = shot("03_message_meeting") { meetingMessage(zoomBar = false) }

    @Test
    fun messageDarkWithZoomBar() = shot("10_message_dark", dark = true) { meetingMessage(zoomBar = true) }

    @Composable
    private fun meetingMessage(zoomBar: Boolean) {
        val start = LocalDate.now().plusDays(1).atTime(10, 0).atZone(java.time.ZoneId.systemDefault()).toInstant()
        MessageContent(
            d = MessageDetail(
                ref = ItemRef("m"), subject = "Приглашение: Обсуждение бюджета на следующий год с руководителями отделов",
                from = Mailbox("Олег Сидоров", "oleg@example.com"),
                to = listOf(Mailbox("Александр", "a.ivanov@example.com")), cc = emptyList(), bcc = emptyList(),
                received = Instant.now(), bodyHtml = "<p>Текст</p>", isRead = true, flagged = false, importance = "Normal",
                itemClass = "IPM.Schedule.Meeting.Request",
                attachments = listOf(
                    AttachmentInfo("a", "Бюджет_2027_финальная_версия_для_согласования.xlsx", "application/vnd.ms-excel", 234_000, false, null, false),
                    AttachmentInfo("b", "Презентация.pdf", "application/pdf", 1_830_000, false, null, false),
                ),
                meeting = MeetingInfo(start, start.plus(Duration.ofHours(1)), "Переговорная 3, 5 этаж", false, false, false, null),
                parentFolderId = null,
            ),
            html = "<p>Текст</p>", loadImages = true, onShowImages = {}, downloading = null, busy = false,
            onAttachment = {}, onRespond = {}, onMailto = {}, onContact = {}, zoomBar = zoomBar, textZoom = 120,
        )
    }

    @Test
    fun contacts() = shot("04_contacts") {
        CompactSearchField("", {}, "Имя, фамилия или e-mail", Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp))
        PrimaryTabRow(selectedTabIndex = 0) {
            Tab(true, {}, text = { Text("Сотрудники", maxLines = 1) })
            Tab(false, {}, text = { Text("Мои контакты", maxLines = 1) })
        }
        ContactRow("Константин Константинопольский", "k@example.com", "Главный специалист по информационной безопасности · Департамент ИТ") {}
        ContactRow("Анна", "anna@example.com", "") {}
    }

    @Test
    fun calendarMonth() = shot("05_calendar_month") {
        val today = LocalDate.now()
        val ev = { d: LocalDate, h: Int, s: String, allDay: Boolean, resp: String? ->
            val st = d.atTime(h, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            EventEntity("$d$h$s", null, s, if (allDay) d.startInstant().toEpochMilli() else st,
                if (allDay) d.plusDays(1).startInstant().toEpochMilli() else st + 3_600_000, "Переговорная 3",
                allDay, "Олег", "oleg@example.com", resp, false, true, s.startsWith("План"), "Busy")
        }
        val events = listOf(
            ev(today, 9, "Планёрка", false, "Accept"), ev(today, 11, "Обсуждение бюджета на следующий год с руководителями", false, "Tentative"),
            ev(today, 0, "Командировка в Санкт-Петербург", true, "NoResponseReceived"), ev(today.plusDays(2), 15, "Звонок", false, "Accept"),
            ev(today.plusDays(2), 16, "Ещё", false, "Accept"), ev(today.plusDays(2), 17, "И ещё", false, "Accept"), ev(today.plusDays(2), 18, "Много", false, "Accept"),
        )
        MonthPager(today, today, events) {}
        Column(Modifier.padding(16.dp)) {
            events.filter { it.start < today.plusDays(1).startInstant().toEpochMilli() }.forEach { EventCard(it) {}; androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp)) }
        }
    }

    @Test
    fun calendarWeek() = shot("06_calendar_week") {
        WeekPager(LocalDate.now(), LocalDate.now(), emptyList()) {}
    }

    @Test
    fun recipientsAndAvailability() = shot("07_compose_and_availability") {
        RecipientField(
            label = "Кому",
            recipients = listOf(Recipient("Иван Петров", "ivan@example.com"), Recipient("", "very.long.address@external-company.example.com")),
            onChange = {}, suggest = { emptyList() }, addressBook = { emptyList() },
        )
        RecipientField("Копия", emptyList(), {}, { emptyList() }, addressBook = { emptyList() })
        AvailabilityGrid(
            listOf(
                Availability("a.ivanov@example.com", "000000000000000000222200001111000000003333000000"),
                Availability("konstantin.konstantinopolsky@example.com", "000000000000000000000022222222000000000000000000"),
            ),
            loading = false, date = LocalDate.now(), start = LocalTime.of(10, 0), duration = Duration.ofMinutes(60), allDay = false,
        )
    }

    @Test
    fun darkMailList() = shot("08_mail_list_dark", dark = true) {
        MessageRow(msg(1, "Иван Петров", "Квартальный отчёт", "Коллеги, во вложении отчёт", read = false, att = true))
        ContactRow("Анна Смирнова", "anna@example.com", "Бухгалтерия") {}
    }

    @Test
    fun searchDateAndSwipes() = shot("11_search_date_swipes") {
        val today = java.time.LocalDate.now()
        io.github.denhaiz.exmail.ui.mail.SearchDateFilter(null) {}
        io.github.denhaiz.exmail.ui.mail.SearchDateFilter(io.github.denhaiz.exmail.ui.mail.DateRange(today.minusDays(4), today)) {}
        val m = msg(1, "Иван Петров", "Отчёт", "", read = false)
        listOf(
            Triple(io.github.denhaiz.exmail.data.SwipeAction.READ, false, true),
            Triple(io.github.denhaiz.exmail.data.SwipeAction.DELETE, true, true),
            Triple(io.github.denhaiz.exmail.data.SwipeAction.REPLY, true, false),
            Triple(io.github.denhaiz.exmail.data.SwipeAction.FORWARD, false, false),
        ).forEach { (a, fromEnd, armed) ->
            androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().padding(top = 4.dp).height(56.dp)) {
                io.github.denhaiz.exmail.ui.mail.SwipeBackground(a, fromEnd, m, armed)
            }
        }
    }

    @Test
    fun addressBookDialog() {
        rule.setContent {
            ExMailTheme {
                AddressBookDialog(
                    title = "Адресная книга · Кому",
                    selected = listOf(Recipient("Иван Петров", "ivan@example.com")),
                    search = {
                        listOf(
                            ContactInfo(null, "Иван Петров", "ivan@example.com", jobTitle = "Главный бухгалтер", department = "Бухгалтерия"),
                            ContactInfo(null, "Константин Константинопольский", "k@example.com", jobTitle = "Начальник отдела информационной безопасности"),
                        )
                    },
                    onDone = {}, onDismiss = {},
                )
            }
        }
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1000)
        rule.waitForIdle()
        save("09_address_book", draw(ShadowDialog.getLatestDialog().window!!.decorView))
    }
}
