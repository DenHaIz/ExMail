package io.github.denhaiz.exmail.auth

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class NtlmTest {

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun md4_rfc1320() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", Md4.digest(ByteArray(0)).hex())
        assertEquals("bde52cb31de33e46245e05fbdbd6fb24", Md4.digest("a".toByteArray()).hex())
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", Md4.digest("abc".toByteArray()).hex())
        assertEquals("d9130a8164549fe818874806e1c7014b", Md4.digest("message digest".toByteArray()).hex())
        assertEquals(
            "e33b4ddc9c38f2199c3e7b164fcc0536",
            Md4.digest("12345678901234567890123456789012345678901234567890123456789012345678901234567890".toByteArray()).hex(),
        )
    }

    // MS-NLMP 4.2.4 NTLMv2 Authentication
    private val serverChallenge = hex("0123456789abcdef")
    private val clientChallenge = hex("aaaaaaaaaaaaaaaa")
    private val avPairs = hex(
        "02000c0044006f006d00610069006e00" + // MsvAvNbDomainName "Domain"
            "01000c005300650072007600650072000000" + "0000", // MsvAvNbComputerName "Server", EOL
    )

    @Test
    fun ntHash_and_ntowfv2_msnlmp() {
        assertEquals("a4f49c406510bdcab6824ee7c30fd852", NtlmEngine.ntHash("Password").hex())
        assertEquals("0c868a403bfd7a93a3001ef22ef02e3f", NtlmEngine.ntowfv2("Password", "User", "Domain").hex())
    }

    @Test
    fun lmv2_msnlmp() {
        val ntowf = NtlmEngine.ntowfv2("Password", "User", "Domain")
        assertEquals(
            "86c35097ac9cec102554764a57cccc19aaaaaaaaaaaaaaaa",
            NtlmEngine.lmv2Response(ntowf, serverChallenge, clientChallenge).hex(),
        )
    }

    @Test
    fun ntProofStr_and_sessionKey_msnlmp() {
        val ntowf = NtlmEngine.ntowfv2("Password", "User", "Domain")
        val resp = NtlmEngine.ntlmv2Response(ntowf, serverChallenge, clientChallenge, ByteArray(8), avPairs)
        val proof = resp.copyOfRange(0, 16)
        assertEquals("68cd0ab851e51c96aabc927bebef6a1c", proof.hex())
        assertEquals("8de40ccadbc14a82f15cb0ad0de95ca3", NtlmEngine.hmacMd5(ntowf, proof).hex())
    }

    @Test
    fun loginParsing() {
        assertEquals(NtlmCredentials("ivanov", "CORP", "p"), NtlmCredentials.fromLogin("corp\\ivanov", "p"))
        assertEquals(NtlmCredentials("ivanov@example.com", "", "p"), NtlmCredentials.fromLogin(" ivanov@example.com ", "p"))
    }

    /** Type2 с targetInfo (домен, компьютер, timestamp) — как у реального Exchange. */
    private fun type2(): ByteArray {
        val info = hex("02000c0044006f006d00610069006e00") + hex("01000c00530065007200760065007200") +
            hex("07000800") + NtlmEngine.fileTime(1_700_000_000_000) + hex("00000000")
        val header = java.io.ByteArrayOutputStream()
        header.write("NTLMSSP".toByteArray()); header.write(0)
        header.write(hex("02000000"))
        header.write(hex("0c000c00")); header.write(hex("38000000")) // target name at 56
        header.write(hex("35828ae2")) // flags incl. TARGET_INFO
        header.write(serverChallenge)
        header.write(ByteArray(8))
        val infoOffset = 56 + 12
        header.write(byteArrayOf(info.size.toByte(), 0, info.size.toByte(), 0))
        header.write(byteArrayOf(infoOffset.toByte(), 0, 0, 0))
        header.write(hex("0a0061 4a0000000f".replace(" ", "")))
        header.write("Domain".toByteArray(Charsets.UTF_16LE))
        header.write(info)
        return header.toByteArray()
    }

    @Test
    fun type3_structure_with_mic() {
        val engine = NtlmEngine()
        val t1 = engine.type1()
        val t3 = engine.type3(t1, type2(), "user@example.com", "", "secret", ByteArray(16) { 1 }, "HTTP/mail.example.com")
        assertEquals("NTLMSSP", String(t3, 0, 7))
        assertEquals(3, t3[8].toInt())
        // MIC заполнен (сервер прислал timestamp)
        assertTrue(t3.copyOfRange(72, 88).any { it.toInt() != 0 })
        // LM-ответ — нули при наличии timestamp
        val lmLen = (t3[12].toInt() and 0xff)
        val lmOff = (t3[16].toInt() and 0xff) or ((t3[17].toInt() and 0xff) shl 8)
        assertEquals(24, lmLen)
        assertTrue(t3.copyOfRange(lmOff, lmOff + 24).all { it.toInt() == 0 })
        // Пользователь в UTF-16LE
        assertTrue(String(t3, Charsets.UTF_16LE).contains("user@example.com") ||
            String(t3.copyOfRange(1, t3.size), Charsets.UTF_16LE).contains("user@example.com"))
    }

    @Test
    fun authenticator_handshake_against_mock_server() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "Negotiate").addHeader("WWW-Authenticate", "NTLM"))
        server.enqueue(
            MockResponse().setResponseCode(401)
                .addHeader("WWW-Authenticate", "NTLM " + Base64.getEncoder().encodeToString(type2())),
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        server.start()

        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .authenticator(NtlmAuthenticator({ NtlmCredentials("user", "CORP", "secret") }))
            .build()
        client.newCall(Request.Builder().url(server.url("/EWS/Exchange.asmx")).post("x".toRequestBody(null)).build())
            .execute().use { assertEquals(200, it.code) }

        server.takeRequest()
        val r2 = server.takeRequest().getHeader("Authorization")
        val r3 = server.takeRequest().getHeader("Authorization")
        assertNotNull(r2); assertNotNull(r3)
        assertEquals(1, Base64.getDecoder().decode(r2!!.removePrefix("NTLM "))[8].toInt())
        assertEquals(3, Base64.getDecoder().decode(r3!!.removePrefix("NTLM "))[8].toInt())
        server.shutdown()
    }

    @Test
    fun authenticator_stops_on_wrong_password() {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM"))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM " + Base64.getEncoder().encodeToString(type2())))
        server.enqueue(MockResponse().setResponseCode(401).addHeader("WWW-Authenticate", "NTLM"))
        server.enqueue(MockResponse().setResponseCode(200)) // не должен быть запрошен
        server.start()
        val client = OkHttpClient.Builder()
            .protocols(listOf(Protocol.HTTP_1_1))
            .authenticator(NtlmAuthenticator({ NtlmCredentials("user", "CORP", "bad") }))
            .build()
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().use { assertEquals(401, it.code) }
        assertEquals(3, server.requestCount)
        server.shutdown()
    }
}
