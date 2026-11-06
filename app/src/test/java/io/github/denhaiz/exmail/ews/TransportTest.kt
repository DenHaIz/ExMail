package io.github.denhaiz.exmail.ews

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.denhaiz.exmail.auth.NtlmCredentials
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/** Мгновенная доставка (streaming), часовые пояса и однократная отправка крупных вложений. */
class TransportTest {
    private lateinit var server: MockWebServer
    private val session = EwsSession()
    private val ekb = ZoneId.of("Asia/Yekaterinburg")

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        session.config = EwsConfig(server.url("/EWS/Exchange.asmx").toString(), NtlmCredentials("u", "", "p"))
        session.mailbox = "ivanov@example.com"
    }

    @After
    fun tearDown() = server.shutdown()

    private fun client(tz: String? = "Ekaterinburg Standard Time", affinity: Boolean = false) =
        EwsClient(EwsClient.createHttpClient(session, OkHttpClient.Builder()), session, { tz }, { ekb }, affinity)

    private fun soap(inner: String) =
        """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
            """<s:Body xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" """ +
            """xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">$inner</s:Body></s:Envelope>"""

    private fun xml(body: String, code: Int = 200) =
        MockResponse().setResponseCode(code).addHeader("Content-Type", "text/xml; charset=utf-8").setBody(body)

    private fun ok(op: String) =
        soap("<m:${op}Response><m:ResponseMessages><m:${op}ResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode></m:${op}ResponseMessage></m:ResponseMessages></m:${op}Response>")

    // region Мгновенная доставка

    @Test
    fun subscribeAndStream_reportsNewMail() = runTest {
        val c = client(affinity = true)
        server.enqueue(
            xml(
                soap(
                    "<m:SubscribeResponse><m:ResponseMessages><m:SubscribeResponseMessage ResponseClass=\"Success\">" +
                        "<m:ResponseCode>NoError</m:ResponseCode><m:SubscriptionId>SUB-1</m:SubscriptionId>" +
                        "</m:SubscribeResponseMessage></m:ResponseMessages></m:SubscribeResponse>",
                ),
            ),
        )
        fun events(inner: String) = soap(
            "<m:GetStreamingEventsResponse><m:ResponseMessages><m:GetStreamingEventsResponseMessage ResponseClass=\"Success\">" +
                "<m:ResponseCode>NoError</m:ResponseCode>$inner</m:GetStreamingEventsResponseMessage></m:ResponseMessages></m:GetStreamingEventsResponse>",
        )
        // Один HTTP-ответ, три конверта: «на связи», новое письмо, закрытие соединения
        val stream = events("<m:ConnectionStatus>OK</m:ConnectionStatus>") +
            events(
                "<m:Notifications><m:Notification><t:SubscriptionId>SUB-1</t:SubscriptionId>" +
                    "<t:NewMailEvent><t:TimeStamp>2026-11-03T10:00:00Z</t:TimeStamp><t:ItemId Id=\"n1\"/><t:ParentFolderId Id=\"inbox\"/></t:NewMailEvent>" +
                    "</m:Notification></m:Notifications>",
            ) +
            events("<m:ConnectionStatus>Closed</m:ConnectionStatus>")
        server.enqueue(MockResponse().addHeader("Content-Type", "text/xml; charset=utf-8").setChunkedBody(stream, 97))

        val id = c.subscribeInbox()
        var newMail = 0
        c.listen(id) { newMail++ }

        assertEquals("SUB-1", id)
        assertEquals(1, newMail)
        val sub = server.takeRequest()
        assertEquals("ivanov@example.com", sub.getHeader("X-AnchorMailbox"))
        assertTrue(sub.body.readUtf8().contains("<t:EventType>NewMailEvent</t:EventType>"))
        val get = server.takeRequest().body.readUtf8()
        assertTrue(get.contains("<t:SubscriptionId>SUB-1</t:SubscriptionId>"))
        assertTrue(get.contains("<m:ConnectionTimeout>29</m:ConnectionTimeout>"))
    }

    @Test(expected = EwsException::class)
    fun stream_expiredSubscription_throws() = runTest {
        server.enqueue(
            xml(
                soap(
                    "<m:GetStreamingEventsResponse><m:ResponseMessages><m:GetStreamingEventsResponseMessage ResponseClass=\"Error\">" +
                        "<m:MessageText>Subscription not found</m:MessageText><m:ResponseCode>ErrorSubscriptionNotFound</m:ResponseCode>" +
                        "</m:GetStreamingEventsResponseMessage></m:ResponseMessages></m:GetStreamingEventsResponse>",
                ),
            ),
        )
        client().listen("old") {}
    }

    // endregion

    // region Часовые пояса

    @Test
    fun unknownTimeZone_retriesInUtcAndRemembers() = runTest {
        val c = client()
        server.enqueue(
            xml(
                soap(
                    "<s:Fault><faultcode>s:Client</faultcode><faultstring>The specified time zone isn't valid.</faultstring>" +
                        "<detail><e:ResponseCode xmlns:e=\"http://schemas.microsoft.com/exchange/services/2006/errors\">ErrorTimeZone</e:ResponseCode></detail></s:Fault>",
                ),
                500,
            ),
        )
        server.enqueue(xml(ok("UpdateItem")))
        server.enqueue(xml(ok("UpdateItem")))

        c.setRead(listOf(ItemRef("a")), true)
        c.setRead(listOf(ItemRef("b")), true)

        assertTrue(server.takeRequest().body.readUtf8().contains("TimeZoneContext"))
        assertFalse(server.takeRequest().body.readUtf8().contains("TimeZoneContext"))
        assertFalse(server.takeRequest().body.readUtf8().contains("TimeZoneContext"))
        assertNull(c.activeTimeZone)
    }

    @Test
    fun allDay_boundariesAndDisplay() {
        val c = client()
        val localMidnight = LocalDate.of(2026, 11, 10).atStartOfDay(ekb).toInstant()
        // С поясом на сервере отправляем местную полночь как есть
        assertEquals(localMidnight, c.allDayBoundary(localMidnight))
        // Событие из Москвы (полночь МСК = 02:00 по Екатеринбургу) показывается с местной полуночи того же дня
        val moscowMidnight = LocalDate.of(2026, 11, 10).atStartOfDay(ZoneId.of("Europe/Moscow")).toInstant()
        assertEquals(localMidnight, c.nearestMidnight(moscowMidnight))
        // Из Калининграда (полночь = 03:00 по Екатеринбургу) — тоже
        assertEquals(localMidnight, c.nearestMidnight(LocalDate.of(2026, 11, 10).atStartOfDay(ZoneId.of("Europe/Kaliningrad")).toInstant()))

        // Без пояса на сервере (UTC) событие на весь день — полночь UTC той же даты
        val utc = client(tz = null)
        assertEquals(Instant.parse("2026-11-10T00:00:00Z"), utc.allDayBoundary(localMidnight))
        assertEquals(LocalDate.of(2026, 11, 10).atStartOfDay(ZoneOffset.UTC).toInstant(), utc.allDayBoundary(localMidnight))
    }

    @Test
    fun windowsZones_fallbacks() {
        val icu = mapOf("Asia/Yekaterinburg" to "Ekaterinburg Standard Time")
        assertEquals("Ekaterinburg Standard Time", WindowsZones.resolve("Asia/Yekaterinburg", { icu[it] }, { it }, 300, false))
        // Псевдоним, который ICU знает только в каноническом виде
        assertEquals(
            "Ekaterinburg Standard Time",
            WindowsZones.resolve("Asia/Yekaterinburg-alias", { icu[it] }, { "Asia/Yekaterinburg" }, 300, false),
        )
        // ICU не знает пояс вовсе — по смещению (без летнего времени)
        assertEquals("Russian Standard Time", WindowsZones.resolve("Europe/Kirov", { null }, { null }, 180, false))
        assertEquals("Omsk Standard Time", WindowsZones.resolve("Asia/Omsk", { throw IllegalStateException() }, { null }, 360, false))
        // С летним временем угадывать нельзя — работаем в UTC
        assertNull(WindowsZones.resolve("America/Nowhere", { null }, { null }, -300, true))
    }

    // endregion

    // region Подгрузка писем

    @Test
    fun loadMore_pagesByDate() = runTest {
        server.enqueue(
            xml(
                soap(
                    "<m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage ResponseClass=\"Success\">" +
                        "<m:ResponseCode>NoError</m:ResponseCode><m:RootFolder TotalItemsInView=\"0\" IncludesLastItemInRange=\"true\"><t:Items/></m:RootFolder>" +
                        "</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse>",
                ),
            ),
        )
        val page = client().findMessages("inbox", 0, 50, before = Instant.parse("2026-11-01T08:30:15.700Z"))
        assertTrue(page.isLast)
        val body = server.takeRequest().body.readUtf8()
        // Письма не позже самого старого в списке; ограничение — после IndexedPageItemView, до SortOrder (схема EWS)
        assertTrue(body.contains("<t:IsLessThanOrEqualTo><t:FieldURI FieldURI=\"item:DateTimeReceived\"/>"))
        assertTrue(body.contains("<t:Constant Value=\"2026-11-01T08:30:15Z\"/>"))
        assertTrue(body.indexOf("IndexedPageItemView") < body.indexOf("<m:Restriction>"))
        assertTrue(body.indexOf("<m:Restriction>") < body.indexOf("<m:SortOrder>"))
        assertTrue(body.contains("Offset=\"0\""))
    }

    @Test
    fun findMessages_dateRangeRestriction() = runTest {
        server.enqueue(
            xml(
                soap(
                    "<m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage ResponseClass=\"Success\">" +
                        "<m:ResponseCode>NoError</m:ResponseCode><m:RootFolder TotalItemsInView=\"0\" IncludesLastItemInRange=\"true\"><t:Items/></m:RootFolder>" +
                        "</m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse>",
                ),
            ),
        )
        client().findMessages("inbox", 0, 100, from = Instant.parse("2026-11-01T21:00:00Z"), until = Instant.parse("2026-11-02T21:00:00Z"))
        val body = server.takeRequest().body.readUtf8()
        // Поиск по дате: оба условия внутри And, без текстового запроса
        assertTrue(body.contains("<m:Restriction><t:And><t:IsGreaterThanOrEqualTo>"))
        assertTrue(body.contains("<t:Constant Value=\"2026-11-01T21:00:00Z\"/></t:FieldURIOrConstant></t:IsGreaterThanOrEqualTo>"))
        assertTrue(body.contains("<t:IsLessThan><t:FieldURI FieldURI=\"item:DateTimeReceived\"/>"))
        assertTrue(body.contains("<t:Constant Value=\"2026-11-02T21:00:00Z\"/></t:FieldURIOrConstant></t:IsLessThan></t:And></m:Restriction>"))
        assertTrue(!body.contains("QueryString"))
    }

    // endregion

    // region Крупные вложения

    @Test
    fun largeRequest_warmsUpConnectionFirst() = runTest {
        val c = client()
        server.enqueue(xml(ok("GetFolder")))
        server.enqueue(
            xml(
                soap(
                    "<m:CreateAttachmentResponse><m:ResponseMessages><m:CreateAttachmentResponseMessage ResponseClass=\"Success\">" +
                        "<m:ResponseCode>NoError</m:ResponseCode><m:Attachments><t:FileAttachment>" +
                        "<t:AttachmentId Id=\"at\" RootItemId=\"d\" RootItemChangeKey=\"ck2\"/></t:FileAttachment></m:Attachments>" +
                        "</m:CreateAttachmentResponseMessage></m:ResponseMessages></m:CreateAttachmentResponse>",
                ),
            ),
        )
        server.enqueue(xml(ok("UpdateItem")))

        c.addAttachments(ItemRef("d", "ck1"), listOf(NewAttachment("big.bin", "application/octet-stream", ByteArray(300_000))))
        c.setRead(listOf(ItemRef("x")), true) // мелкий запрос сразу после — без «прогрева»

        val warm = server.takeRequest()
        assertTrue(warm.body.readUtf8().contains("<m:GetFolder>"))
        val big = server.takeRequest()
        assertTrue(big.body.readUtf8().contains("<m:CreateAttachment>"))
        assertEquals("то же соединение, что и короткий запрос", 1, big.sequenceNumber)
        assertTrue(server.takeRequest().body.readUtf8().contains("<m:UpdateItem"))
        assertEquals(3, server.requestCount) // тело с вложением ушло один раз
    }

    // endregion
}
