package io.github.denhaiz.exmail.ews

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.denhaiz.exmail.auth.NtlmCredentials
import java.time.Instant

class EwsClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EwsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val session = EwsSession().apply {
            config = EwsConfig(server.url("/EWS/Exchange.asmx").toString(), NtlmCredentials("u", "", "p"))
        }
        client = EwsClient(EwsClient.createHttpClient(session, OkHttpClient.Builder()), session, { "Russian Standard Time" })
    }

    @After
    fun tearDown() = server.shutdown()

    private fun fixture(name: String) = javaClass.classLoader!!.getResource("ews/$name")!!.readText()

    private fun enqueue(name: String, code: Int = 200) =
        server.enqueue(MockResponse().setResponseCode(code).addHeader("Content-Type", "text/xml; charset=utf-8").setBody(fixture(name)))

    @Test
    fun findMessages_parsesSummaries() = runTest {
        enqueue("FindItemMessages.xml")
        val page = client.findMessages("inbox", 0, 50)
        assertEquals(2, page.items.size)
        assertEquals(10, page.total)
        assertFalse(page.isLast)
        val first = page.items[0]
        assertEquals("Квартальный отчёт", first.subject)
        assertEquals("Иван Петров", first.from?.name)
        assertEquals("ivan.petrov@example.com", first.from?.email)
        assertFalse(first.isRead)
        assertTrue(first.hasAttachments)
        assertTrue(first.flagged)
        assertEquals(Instant.parse("2026-11-02T08:15:00Z"), first.received)
        assertTrue(page.items[1].itemClass.startsWith("IPM.Schedule.Meeting.Request"))

        val req = server.takeRequest().body.readUtf8()
        assertTrue(req.contains("<t:DistinguishedFolderId Id=\"inbox\"/>"))
        assertTrue(req.contains("TimeZoneDefinition Id=\"Russian Standard Time\""))
        assertTrue(req.contains("Exchange2013_SP1"))
    }

    @Test
    fun getMessage_parsesBodyRecipientsAttachments() = runTest {
        enqueue("GetItemMessage.xml")
        val m = client.getMessage("AAMk1")
        assertEquals(listOf("anna@example.com", "oleg@example.com"), m.to.map { it.email })
        assertEquals(1, m.cc.size)
        assertTrue(m.bodyHtml.contains("<b>Привет</b>"))
        assertEquals(2, m.attachments.size)
        assertTrue(m.attachments[1].isInline)
        assertEquals("image001.png@01DA", m.attachments[1].contentId)
    }

    @Test
    fun soapFault_and_errorResponse_areReported() = runTest {
        enqueue("ErrorResponse.xml")
        try {
            client.getMessage("missing")
            error("должно быть исключение")
        } catch (e: EwsException) {
            assertEquals("ErrorItemNotFound", e.code)
        }
        enqueue("SoapFault.xml", 500)
        try {
            client.findMessages("inbox")
            error("должно быть исключение")
        } catch (e: EwsException) {
            assertEquals("ErrorSchemaValidation", e.code)
        }
    }

    @Test
    fun calendarView_and_resolveNames() = runTest {
        enqueue("FindItemCalendar.xml")
        val events = client.findEvents(Instant.parse("2026-11-02T00:00:00Z"), Instant.parse("2026-11-09T00:00:00Z"))
        assertEquals(1, events.size)
        assertEquals("Планёрка", events[0].subject)
        assertEquals("Переговорная 3", events[0].location)
        assertTrue(events[0].isRecurring)
        assertEquals("Accept", events[0].myResponse)
        assertTrue(server.takeRequest().body.readUtf8().contains("StartDate=\"2026-11-02T00:00:00Z\""))

        enqueue("ResolveNames.xml")
        val people = client.resolveNames("ива")
        assertEquals(2, people.size)
        assertEquals("ivan.petrov@example.com", people[0].email)
        assertEquals("Бухгалтерия", people[0].department)
        assertEquals("+7 900 000-00-00", people[0].phones.first { it.first == "MobilePhone" }.second)
    }

    @Test
    fun sendMessage_buildsValidCreateItem() = runTest {
        // Отправку OkHttp после обрыва не повторяет — на новом соединении сначала короткий запрос («прогрев»)
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "text/xml; charset=utf-8").setBody(fixture("CreateItemOk.xml")))
        enqueue("CreateItemOk.xml")
        client.sendMessage(OutgoingMessage(to = listOf("a@example.com"), subject = "Тест <1>", bodyHtml = "<p>Hi & bye</p>"))
        assertTrue(server.takeRequest().body.readUtf8().contains("<m:GetFolder>"))
        val req = server.takeRequest().body.readUtf8()
        assertTrue(req.contains("MessageDisposition=\"SendAndSaveCopy\""))
        assertTrue(req.contains("<t:Subject>Тест &lt;1&gt;</t:Subject>"))
        assertTrue(req.contains("&lt;p&gt;Hi &amp; bye&lt;/p&gt;"))
        assertTrue(req.indexOf("<t:Body") < req.indexOf("<t:ToRecipients>"))
    }
}
