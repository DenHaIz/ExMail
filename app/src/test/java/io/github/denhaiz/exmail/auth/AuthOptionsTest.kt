package io.github.denhaiz.exmail.auth

import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.denhaiz.exmail.data.repo.AccountRepository
import java.util.Base64

/** Дополнительные способы входа: Basic и домен для логина. */
class AuthOptionsTest {
    private val creds = NtlmCredentials("ivanov", "CORP", "пароль")

    private fun challenge(url: String, auth: String? = null): Response {
        val request = Request.Builder().url(url).apply { auth?.let { header("Authorization", it) } }.build()
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(401)
            .message("Unauthorized")
            .header("WWW-Authenticate", "Basic realm=\"exchange\"")
            .build()
    }

    @Test
    fun basic_sentOnlyOverHttpsAndOnce() {
        val auth = NtlmAuthenticator({ creds }, basic = { true })

        val first = auth.authenticate(null, challenge("https://mail.example.ru/EWS/Exchange.asmx"))
        val header = first?.header("Authorization").orEmpty()
        assertTrue(header.startsWith("Basic "))
        assertEquals("CORP\\ivanov:пароль", String(Base64.getDecoder().decode(header.removePrefix("Basic ")), Charsets.UTF_8))

        // Повторный 401 после Basic — неверный пароль, без зацикливания
        assertNull(auth.authenticate(null, challenge("https://mail.example.ru/EWS/Exchange.asmx", header)))
        // Без HTTPS пароль не отправляется
        assertNull(auth.authenticate(null, challenge("http://mail.example.ru/EWS/Exchange.asmx")))
    }

    @Test
    fun ntlmMode_ignoresBasicChallenge() {
        val auth = NtlmAuthenticator({ creds })
        assertNull(auth.authenticate(null, challenge("https://mail.example.ru/EWS/Exchange.asmx")))
    }

    @Test
    fun login_withDomain() {
        assertEquals("CORP\\ivanov", AccountRepository.normalizeLogin("ivanov", "CORP"))
        // Домен почты по умолчанию — только из company.properties; без него короткий логин не меняется
        assertEquals("ivanov@example.com", AccountRepository.normalizeLogin(" ivanov ", mailDomain = "example.com"))
        assertEquals("ivanov", AccountRepository.normalizeLogin(" ivanov ", mailDomain = ""))
        assertEquals("petrov@corp.ru", AccountRepository.normalizeLogin("petrov@corp.ru", "CORP"))
        assertEquals("DOM\\petrov", AccountRepository.normalizeLogin("DOM\\petrov", "CORP"))
        assertEquals(
            "https://mail.corp.ru/EWS/Exchange.asmx",
            AccountRepository.normalizeUrl("mail.corp.ru"),
        )
    }

    @Test
    fun normalizeUrl_fixesCommonInputs() {
        assertEquals("https://mail.corp.ru/EWS/Exchange.asmx", AccountRepository.normalizeUrl("https://mail.corp.ru/owa"))
        assertEquals("https://mail.corp.ru/EWS/Exchange.asmx", AccountRepository.normalizeUrl("mail.corp.ru/EWS"))
        assertEquals("https://mail.corp.ru/EWS/Exchange.asmx", AccountRepository.normalizeUrl("http://mail.corp.ru/EWS/Exchange.asmx"))
        assertEquals("https://mail.corp.ru/ews/exchange.asmx", AccountRepository.normalizeUrl("https://mail.corp.ru/ews/exchange.asmx"))
    }

    @Test
    fun serverCandidates_typicalExchangeNames() {
        val c = AccountRepository.serverCandidates("ivanov@Corp.RU")
        assertEquals("https://exchange.corp.ru/EWS/Exchange.asmx", c.first())
        assertEquals(true, "https://mail.corp.ru/EWS/Exchange.asmx" in c)
        assertEquals("https://corp.ru/EWS/Exchange.asmx", c.last())
        assertEquals(emptyList<String>(), AccountRepository.serverCandidates("ivanov"))
        assertEquals(emptyList<String>(), AccountRepository.serverCandidates("a@evil.com/x"))
    }

    @Test
    fun isEwsEndpoint_onlyAuthChallengeWithoutCredentials() = kotlinx.coroutines.runBlocking {
        val server = okhttp3.mockwebserver.MockWebServer()
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Negotiate").addHeader("WWW-Authenticate", "NTLM"))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(200).setBody("<html>Сайт</html>"))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Basic realm=\"site\""))
        server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Negotiate, NTLM"))
        server.start()
        try {
            val url = server.url("/EWS/Exchange.asmx").toString()
            assertEquals(true, io.github.denhaiz.exmail.ews.EwsClient.isEwsEndpoint(url))
            assertEquals(false, io.github.denhaiz.exmail.ews.EwsClient.isEwsEndpoint(url)) // обычный сайт
            assertEquals(false, io.github.denhaiz.exmail.ews.EwsClient.isEwsEndpoint(url)) // 401 без способа входа
            assertEquals(false, io.github.denhaiz.exmail.ews.EwsClient.isEwsEndpoint(url)) // сайт с паролем (только Basic)
            assertEquals(true, io.github.denhaiz.exmail.ews.EwsClient.isEwsEndpoint(url)) // способы в одной строке
            // Проверка не отправляет учётных данных
            repeat(5) { assertNull(server.takeRequest().getHeader("Authorization")) }
        } finally {
            server.shutdown()
        }
        assertEquals(false, io.github.denhaiz.exmail.ews.EwsClient.isEwsEndpoint("https://nonexistent.invalid/EWS/Exchange.asmx"))
    }
}
