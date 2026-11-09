package io.github.denhaiz.exmail.data

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.denhaiz.exmail.ews.ItemRef
import io.github.denhaiz.exmail.ews.Mailbox
import io.github.denhaiz.exmail.ews.MessageDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** Настройка «Хранить письма на телефоне». */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], application = Application::class)
class CacheRetentionTest {
    private val detail = MessageDetail(
        ref = ItemRef("id-1", "ck"),
        subject = "Тема",
        from = Mailbox("Иван", "ivan@example.com"),
        to = emptyList(), cc = emptyList(), bcc = emptyList(),
        received = Instant.parse("2026-11-05T09:30:00Z"),
        bodyHtml = "<p>Текст</p>",
        isRead = true, flagged = false, importance = "Normal", itemClass = "IPM.Note",
        attachments = emptyList(), meeting = null, parentFolderId = null,
    )

    @Test
    fun noStorage_writesNothingAndClearsSaved() {
        val cache = MailCache(ApplicationProvider.getApplicationContext())
        cache.writeMessage(detail, cache.generation)
        cache.putImage("att-1", "image/png", byteArrayOf(1, 2, 3), cache.generation)
        assertNotNull(cache.readMessage("id-1"))
        assertNotNull(cache.image("att-1"))
        assertTrue(cache.sizeBytes() > 0)

        cache.setRetention(0)
        assertNull(cache.readMessage("id-1"))
        assertNull(cache.image("att-1"))
        assertEquals(0L, cache.sizeBytes())
        cache.writeMessage(detail, cache.generation)
        assertNull(cache.readMessage("id-1"))

        // Хранение снова включено — пишется и читается
        cache.setRetention(14)
        cache.writeMessage(detail, cache.generation)
        assertEquals("Тема", cache.readMessage("id-1")?.subject)
    }
}
