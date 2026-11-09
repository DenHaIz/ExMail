package io.github.denhaiz.exmail.ews

import io.github.denhaiz.exmail.auth.NtlmCredentials
import io.github.denhaiz.exmail.auth.NtlmEngine
import io.github.denhaiz.exmail.data.repo.AccountRepository
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Поиск сервера: ответ Autodiscover, переадресации, вход по NTLM, порядок адресов. */
class AutodiscoverTest {

    private fun response(body: String) = """<?xml version="1.0" encoding="utf-8"?>
        <Autodiscover xmlns="http://schemas.microsoft.com/exchange/autodiscover/responseschema/2006">
          <Response xmlns="http://schemas.microsoft.com/exchange/autodiscover/outlook/responseschema/2006a">
            <Account><AccountType>email</AccountType>$body</Account>
          </Response>
        </Autodiscover>"""

    private val settings = response(
        "<Action>settings</Action>" +
            "<Protocol><Type>EXCH</Type><Server>srv01.corp.local</Server><EwsUrl>https://srv01.corp.local/EWS/Exchange.asmx</EwsUrl></Protocol>" +
            "<Protocol><Type>EXPR</Type><Server>mail.example.com</Server><EwsUrl>https://mail.example.com/EWS/Exchange.asmx</EwsUrl></Protocol>",
    )

    @Test
    fun parse_prefersExternalUrl() {
        assertEquals(
            AutodiscoverResult.Found("https://mail.example.com/EWS/Exchange.asmx"),
            EwsClient.parseAutodiscover(Xml.parse(settings)),
        )
        val internalOnly = response(
            "<Action>settings</Action><Protocol><Type>EXCH</Type><EwsUrl>https://srv01.corp.local/EWS/Exchange.asmx</EwsUrl></Protocol>",
        )
        assertEquals(AutodiscoverResult.Found("https://srv01.corp.local/EWS/Exchange.asmx"), EwsClient.parseAutodiscover(Xml.parse(internalOnly)))
    }

    @Test
    fun parse_redirects() {
        assertEquals(
            AutodiscoverResult.RedirectAddress("ivanov@example.com"),
            EwsClient.parseAutodiscover(Xml.parse(response("<Action>redirectAddr</Action><RedirectAddr>ivanov@example.com</RedirectAddr>"))),
        )
        assertEquals(
            AutodiscoverResult.RedirectUrl("https://autodiscover.example.com/autodiscover/autodiscover.xml"),
            EwsClient.parseAutodiscover(
                Xml.parse(response("<Action>redirectUrl</Action><RedirectUrl>https://autodiscover.example.com/autodiscover/autodiscover.xml</RedirectUrl>")),
            ),
        )
        assertNull(EwsClient.parseAutodiscover(Xml.parse(response("<Action>settings</Action>"))))
    }

    @Test
    fun autodiscover_ntlmHandshakeThenSettings() = runTest {
        val server = MockWebServer()
        // Как у Exchange: без учётных данных — 401 с Basic, Negotiate и NTLM
        server.enqueue(
            MockResponse().setResponseCode(401)
                .addHeader("WWW-Authenticate", "Basic realm=\"autodiscover.example.com\"")
                .addHeader("WWW-Authenticate", "Negotiate")
                .addHeader("WWW-Authenticate", "NTLM"),
        )
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM " + Base64.getEncoder().encodeToString(type2())))
        server.enqueue(MockResponse().setResponseCode(200).addHeader("Content-Type", "text/xml; charset=utf-8").setBody(settings))
        server.start()
        try {
            val session = EwsSession().apply {
                config = EwsConfig("https://example.com/EWS/Exchange.asmx", NtlmCredentials.fromLogin("ivanov@example.com", "p"))
            }
            val client = EwsClient(EwsClient.createHttpClient(session, OkHttpClient.Builder()), session, { "Russian Standard Time" })
            val url = client.autodiscover("ivanov@example.com", listOf(server.url("/autodiscover/autodiscover.xml").toString()), 0)
            assertEquals("https://mail.example.com/EWS/Exchange.asmx", url)
            server.takeRequest()
            assertTrue(server.takeRequest().getHeader("Authorization")!!.startsWith("NTLM "))
            val last = server.takeRequest()
            assertTrue(last.getHeader("Authorization")!!.startsWith("NTLM "))
            assertTrue(last.body.readUtf8().contains("<EMailAddress>ivanov@example.com</EMailAddress>"))
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun orderServers_autodiscoverHostIsLastResort() {
        val ad = "https://autodiscover.example.com/EWS/Exchange.asmx"
        val direct = "https://exchange.example.com/EWS/Exchange.asmx"
        val found = "https://mail.example.net/EWS/Exchange.asmx"
        // Отвечает только узел Autodiscover: сначала адрес из ответа Autodiscover, затем сам узел
        assertEquals(listOf(found, ad), AccountRepository.orderServers(listOf(ad), found))
        assertEquals(listOf(ad), AccountRepository.orderServers(listOf(ad), null))
        assertEquals(listOf(direct, ad), AccountRepository.orderServers(listOf(ad, direct), null))
        assertEquals(listOf(found), AccountRepository.orderServers(emptyList(), found))
        assertTrue(AccountRepository.serverCandidates("ivanov@example.com").contains(ad))
    }

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Вызов сервера NTLM (Type2) с TARGET_INFO — как в NtlmTest. */
    private fun type2(): ByteArray {
        val info = hex("02000c0044006f006d00610069006e00") + hex("01000c00530065007200760065007200") +
            hex("07000800") + NtlmEngine.fileTime(1_700_000_000_000) + hex("00000000")
        val header = java.io.ByteArrayOutputStream()
        header.write("NTLMSSP".toByteArray()); header.write(0)
        header.write(hex("02000000"))
        header.write(hex("0c000c00")); header.write(hex("38000000"))
        header.write(hex("35828ae2"))
        header.write(hex("0123456789abcdef"))
        header.write(ByteArray(8))
        val infoOffset = 56 + 12
        header.write(byteArrayOf(info.size.toByte(), 0, info.size.toByte(), 0))
        header.write(byteArrayOf(infoOffset.toByte(), 0, 0, 0))
        header.write(hex("0a00614a0000000f"))
        header.write("Domain".toByteArray(Charsets.UTF_16LE))
        header.write(info)
        return header.toByteArray()
    }
}
