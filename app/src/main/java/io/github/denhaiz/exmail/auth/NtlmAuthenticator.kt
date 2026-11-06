package io.github.denhaiz.exmail.auth

import okhttp3.Authenticator
import okhttp3.Credentials
import okhttp3.Handshake
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.Base64

/** Учётные данные для NTLM: `DOMAIN\user` → (user, DOMAIN), `user@domain` → (user@domain, ""). */
data class NtlmCredentials(val user: String, val domain: String, val password: String) {
    override fun toString() = "NtlmCredentials(user=$user, domain=$domain)"

    companion object {
        fun fromLogin(login: String, password: String): NtlmCredentials {
            val trimmed = login.trim()
            val slash = trimmed.indexOf('\\')
            return if (slash > 0) {
                NtlmCredentials(trimmed.substring(slash + 1), trimmed.substring(0, slash).uppercase(), password)
            } else {
                NtlmCredentials(trimmed, "", password)
            }
        }
    }
}

/**
 * NTLM-рукопожатие для OkHttp: 401 (NTLM) → Type1 → 401 (Type2) → Type3.
 * Требует HTTP/1.1 и повторного использования соединения (NTLM аутентифицирует TCP-соединение).
 */
class NtlmAuthenticator(
    private val credentials: () -> NtlmCredentials?,
    private val engine: NtlmEngine = NtlmEngine(),
    /** true — вместо NTLM использовать Basic (для серверов, где NTLM выключен). */
    private val basic: () -> Boolean = { false },
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        val creds = credentials() ?: return null
        if (basic()) return basicAuth(creds, response)
        val header = response.headers("WWW-Authenticate")
            .firstOrNull { it.equals("NTLM", true) || it.startsWith("NTLM ", true) } ?: return null
        val token = header.substring(4).trim()
        val previous = response.request.header("Authorization")
            ?.takeIf { it.startsWith("NTLM ", true) }
            ?.let { runCatching { decode(it.substring(5).trim()) }.getOrNull() }

        if (token.isEmpty()) {
            // Сервер требует аутентификацию. Если Type3 уже отправлялся — пароль неверный, не зацикливаемся.
            if (previous != null && previous.size > 8 && previous[8].toInt() == 3) return null
            if (priorCount(response) >= 4) return null
            return response.request.newBuilder()
                .header("Authorization", "NTLM " + encode(engine.type1()))
                .build()
        }

        if (previous == null || previous.size <= 8 || previous[8].toInt() != 1) return null
        val type3 = engine.type3(
            type1 = previous,
            type2 = decode(token),
            user = creds.user,
            domain = creds.domain,
            password = creds.password,
            channelBindingsHash = channelBindings(response.handshake),
            targetSpn = "HTTP/" + response.request.url.host,
        )
        return response.request.newBuilder()
            .header("Authorization", "NTLM " + encode(type3))
            .build()
    }

    private fun basicAuth(creds: NtlmCredentials, response: Response): Request? {
        // Уже отправляли Basic — пароль неверный; Basic без HTTPS не отправляем
        if (response.request.header("Authorization")?.startsWith("Basic ", true) == true) return null
        if (!response.request.isHttps) return null
        if (response.headers("WWW-Authenticate").none { it.startsWith("Basic", true) }) return null
        val user = if (creds.domain.isNotEmpty()) "${creds.domain}\\${creds.user}" else creds.user
        return response.request.newBuilder()
            .header("Authorization", Credentials.basic(user, creds.password, Charsets.UTF_8))
            .build()
    }

    private fun priorCount(response: Response): Int {
        var n = 0
        var r = response.priorResponse
        while (r != null) {
            n++
            r = r.priorResponse
        }
        return n
    }

    companion object {
        private fun encode(b: ByteArray) = Base64.getEncoder().encodeToString(b)
        private fun decode(s: String) = Base64.getDecoder().decode(s)

        /** RFC 5929 tls-server-end-point → MD5(gss_channel_bindings_struct). */
        fun channelBindings(handshake: Handshake?): ByteArray? {
            val cert = handshake?.peerCertificates?.firstOrNull() as? X509Certificate ?: return null
            val alg = cert.sigAlgName.uppercase()
            val hashAlg = when {
                "SHA512" in alg -> "SHA-512"
                "SHA384" in alg -> "SHA-384"
                else -> "SHA-256" // в т.ч. MD5/SHA1 по RFC 5929
            }
            val certHash = MessageDigest.getInstance(hashAlg).digest(cert.encoded)
            return channelBindingsFromAppData("tls-server-end-point:".toByteArray(Charsets.US_ASCII) + certHash)
        }

        fun channelBindingsFromAppData(appData: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            fun int32(v: Int) {
                for (i in 0 until 4) out.write((v ushr (8 * i)) and 0xff)
            }
            int32(0); int32(0) // initiator addrtype, address length
            int32(0); int32(0) // acceptor addrtype, address length
            int32(appData.size)
            out.write(appData)
            return MessageDigest.getInstance("MD5").digest(out.toByteArray())
        }
    }
}
