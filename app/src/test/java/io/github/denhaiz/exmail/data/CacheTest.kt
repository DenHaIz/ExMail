package io.github.denhaiz.exmail.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import io.github.denhaiz.exmail.ews.AttachmentInfo
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.Mailbox
import io.github.denhaiz.exmail.ews.MeetingInfo
import io.github.denhaiz.exmail.ews.MessageDetail
import io.github.denhaiz.exmail.ui.common.Recipient
import io.github.denhaiz.exmail.ui.common.withTyped
import java.time.Instant

/** Кэш писем, адреса встроенных картинок, получатели, набранные без «чипсы». */
class CacheTest {
    private val detail = MessageDetail(
        ref = ItemRef("AAMk+id/1=", "CQAAA"),
        subject = "Отчёт за ноябрь",
        from = Mailbox("Иванов Иван", "ivanov@example.com"),
        to = listOf(Mailbox("", "a@example.com"), Mailbox("Петров", "p@example.com")),
        cc = emptyList(),
        bcc = listOf(Mailbox("Скрытый", "s@example.com")),
        received = Instant.parse("2026-11-05T09:30:00Z"),
        // Длиннее 64 КБ — предел DataOutputStream.writeUTF
        bodyHtml = "<p>Привет</p>" + "x".repeat(100_000),
        isRead = true,
        flagged = true,
        importance = "High",
        itemClass = "IPM.Schedule.Meeting.Request",
        attachments = listOf(
            AttachmentInfo("att1", "logo.png", "image/png", 1234, isInline = true, contentId = "logo@1", isItem = false),
            AttachmentInfo("att2", "Письмо", "message/rfc822", 0, isInline = false, contentId = null, isItem = true),
        ),
        meeting = MeetingInfo(Instant.parse("2026-11-10T07:00:00Z"), null, "Переговорная 3", false, false, false, null),
        parentFolderId = null,
    )

    @Test
    fun messageCodec_roundTrip() {
        assertEquals(detail, MessageCodec.decode(MessageCodec.encode(detail)))
        val plain = detail.copy(from = null, meeting = null, received = null, ref = ItemRef("x"), parentFolderId = "inbox")
        assertEquals(plain, MessageCodec.decode(MessageCodec.encode(plain)))
    }

    @Test
    fun messageCodec_otherVersion_isIgnored() {
        val bytes = MessageCodec.encode(detail)
        bytes[3] = 99 // версия формата
        assertNull(MessageCodec.decode(bytes))
    }

    @Test
    fun inlineImageUrl_roundTrip() {
        val id = "AAMkAGI2+/xyz==\u0000?#"
        val url = InlineImages.url(id)
        assertEquals("https://${InlineImages.HOST}/", url.substring(0, url.indexOf(InlineImages.HOST) + InlineImages.HOST.length + 1))
        assertEquals(id, InlineImages.attachmentId(url))
        assertNull(InlineImages.attachmentId("https://example.com/a.png"))
    }

    @Test
    fun typedRecipients() {
        val list = listOf(Recipient("Анна", "anna@example.com"))
        assertEquals(list, withTyped(list, "  "))
        assertEquals(list + Recipient("", "b@example.com"), withTyped(list, "b@example.com"))
        // Повтор уже добавленного адреса и разделители
        assertEquals(list + Recipient("", "c@x.ru"), withTyped(list, "ANNA@example.com, c@x.ru;"))
        // Недописанный адрес или имя — ошибка, письмо не уходит без этого получателя молча
        assertNull(withTyped(list, "Иван"))
        assertNull(withTyped(list, "b@example"))
    }
}
