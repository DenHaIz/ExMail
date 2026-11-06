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

/**
 * Ответ, пересылка и ответ на приглашение: Exchange требует актуальный ChangeKey в ReferenceItemId
 * («ChangeKey is required for this operation»), поэтому перед операцией он запрашивается заново.
 */
class ReplyTest {
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

    private fun getItemIdOnly(changeKey: String) = reply(
        "<m:GetItemResponse><m:ResponseMessages><m:GetItemResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode>" +
            "<m:Items><t:Message><t:ItemId Id=\"orig\" ChangeKey=\"$changeKey\"/></t:Message></m:Items>" +
            "</m:GetItemResponseMessage></m:ResponseMessages></m:GetItemResponse>",
    )

    private fun ok(op: String) =
        reply("<m:${op}Response><m:ResponseMessages><m:${op}ResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode></m:${op}ResponseMessage></m:ResponseMessages></m:${op}Response>")

    @Test
    fun reply_sendsFreshChangeKey() = runTest {
        getItemIdOnly("CK-new")
        ok("CreateItem")

        client.reply(ReplyKind.REPLY, ItemRef("orig", "CK-stale"), "<p>Ответ</p>", to = listOf("a@b.ru"), subject = "RE: Тест")

        assertEquals(2, server.requestCount)
        assertTrue(server.takeRequest().body.readUtf8().contains("<m:GetItem>"))
        val create = server.takeRequest().body.readUtf8()
        assertTrue(create.contains("<t:ReplyToItem>"))
        assertTrue(create.contains("<t:ReferenceItemId Id=\"orig\" ChangeKey=\"CK-new\"/>"))
    }

    @Test
    fun replyDraft_hasChangeKey() = runTest {
        getItemIdOnly("CK-2")
        reply(
            "<m:CreateItemResponse><m:ResponseMessages><m:CreateItemResponseMessage ResponseClass=\"Success\"><m:ResponseCode>NoError</m:ResponseCode>" +
                "<m:Items><t:Message><t:ItemId Id=\"draft\" ChangeKey=\"d1\"/></t:Message></m:Items>" +
                "</m:CreateItemResponseMessage></m:ResponseMessages></m:CreateItemResponse>",
        )

        val draft = client.saveReplyDraft(ReplyKind.FORWARD, ItemRef("orig"), OutgoingMessage(listOf("a@b.ru"), emptyList(), emptyList(), "FW: x", "<p/>", false, emptyList()))

        assertEquals(ItemRef("draft", "d1"), draft)
        server.takeRequest()
        assertTrue(server.takeRequest().body.readUtf8().contains("<t:ReferenceItemId Id=\"orig\" ChangeKey=\"CK-2\"/>"))
    }

    @Test
    fun meetingResponse_hasChangeKey() = runTest {
        getItemIdOnly("CK-3")
        ok("CreateItem")

        client.respondToMeeting(ItemRef("orig"), MeetingResponse.ACCEPT)

        server.takeRequest()
        val create = server.takeRequest().body.readUtf8()
        assertTrue(create.contains("<t:AcceptItem>"))
        assertTrue(create.contains("ChangeKey=\"CK-3\""))
    }
}
