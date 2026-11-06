package io.github.denhaiz.exmail.ews

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import io.github.denhaiz.exmail.auth.NtlmCredentials

/** «Отметить все прочитанными»: письма, которые сервер ещё отдаёт непрочитанными, отмечаются явно. */
class MarkAllReadTest {
    private lateinit var server: MockWebServer
    private lateinit var client: EwsClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val session = EwsSession().apply {
            config = EwsConfig(server.url("/EWS/Exchange.asmx").toString(), NtlmCredentials("u", "", "p"))
        }
        client = EwsClient(EwsClient.createHttpClient(session, OkHttpClient.Builder()), session)
    }

    @After
    fun tearDown() = server.shutdown()

    private fun reply(inner: String) = server.enqueue(
        MockResponse().addHeader("Content-Type", "text/xml; charset=utf-8").setBody(
            """<?xml version="1.0" encoding="utf-8"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/">""" +
                """<s:Body xmlns:m="http://schemas.microsoft.com/exchange/services/2006/messages" """ +
                """xmlns:t="http://schemas.microsoft.com/exchange/services/2006/types">$inner</s:Body></s:Envelope>""",
        ),
    )

    private fun ok(op: String) =
        reply("<m:${op}Response><m:ResponseMessages><m:${op}ResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode></m:${op}ResponseMessage></m:ResponseMessages></m:${op}Response>")

    private fun unread(vararg ids: String) = reply(
        "<m:FindItemResponse><m:ResponseMessages><m:FindItemResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode>" +
            "<m:RootFolder TotalItemsInView=\"${ids.size}\" IncludesLastItemInRange=\"true\"><t:Items>" +
            ids.joinToString("") { "<t:Message><t:ItemId Id=\"$it\" ChangeKey=\"ck\"/></t:Message>" } +
            "</t:Items></m:RootFolder></m:FindItemResponseMessage></m:ResponseMessages></m:FindItemResponse>",
    )

    @Test
    fun leftoversAreMarkedExplicitly() = runTest {
        ok("MarkAllItemsAsRead")
        unread("a1", "a2") // сервер ещё не применил массовую отметку
        ok("UpdateItem")

        client.markAllRead("inbox")

        assertEquals(3, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("<m:MarkAllItemsAsRead><m:ReadFlag>true</m:ReadFlag>"))
        val find = server.takeRequest().body.readUtf8()
        assertTrue(find.contains("<t:FieldURI FieldURI=\"message:IsRead\"/>"))
        assertTrue(find.contains("<t:Constant Value=\"false\"/>"))
        val update = server.takeRequest().body.readUtf8()
        assertTrue(update.contains("<t:ItemId Id=\"a1\"/>") && update.contains("<t:ItemId Id=\"a2\"/>"))
        assertTrue(update.contains("<t:IsRead>true</t:IsRead>"))
    }

    @Test
    fun nothingLeft_noUpdate() = runTest {
        ok("MarkAllItemsAsRead")
        unread()

        client.markAllRead("inbox")

        assertEquals(2, server.requestCount)
    }
}
