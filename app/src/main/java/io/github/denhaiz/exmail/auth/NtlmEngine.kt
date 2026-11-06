package io.github.denhaiz.exmail.auth

import java.io.ByteArrayOutputStream
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * NTLMv2 (MS-NLMP) для HTTP-аутентификации в EWS.
 *
 * Поддерживает MIC и Channel Binding (Extended Protection for Authentication),
 * чтобы работать и при включённой на Exchange расширенной защите.
 */
class NtlmEngine(
    private val random: SecureRandom = SecureRandom(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val workstation: String = "ANDROID",
) {

    fun type1(): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.writeInt32(1)
        out.writeInt32(TYPE1_FLAGS)
        out.writeSecBuf(0, 0) // domain
        out.writeSecBuf(0, 0) // workstation
        out.write(VERSION)
        return out.toByteArray()
    }

    class Challenge(val flags: Int, val serverChallenge: ByteArray, val targetInfo: ByteArray)

    fun parseType2(type2: ByteArray): Challenge {
        require(type2.size >= 32 && type2.copyOfRange(0, 8).contentEquals(SIGNATURE) && type2.readInt32(8) == 2) {
            "Некорректный NTLM challenge"
        }
        val flags = type2.readInt32(20)
        val challenge = type2.copyOfRange(24, 32)
        var targetInfo = ByteArray(0)
        if (type2.size >= 48 && flags and NEGOTIATE_TARGET_INFO != 0) {
            val len = type2.readInt16(40)
            val off = type2.readInt32(44)
            if (len > 0 && off + len <= type2.size) targetInfo = type2.copyOfRange(off, off + len)
        }
        return Challenge(flags, challenge, targetInfo)
    }

    /**
     * @param channelBindingsHash MD5 от gss_channel_bindings_struct (tls-server-end-point) или null
     * @param targetSpn например "HTTP/mail.example.com"
     */
    fun type3(
        type1: ByteArray,
        type2: ByteArray,
        user: String,
        domain: String,
        password: String,
        channelBindingsHash: ByteArray? = null,
        targetSpn: String? = null,
    ): ByteArray {
        val challenge = parseType2(type2)
        val pairs = parseAvPairs(challenge.targetInfo)
        val serverTimestamp = pairs.firstOrNull { it.first == AV_TIMESTAMP }?.second
        val withMic = serverTimestamp != null

        val timestamp = serverTimestamp ?: fileTime(clock())
        val clientChallenge = ByteArray(8).also { random.nextBytes(it) }
        val targetInfo = buildTargetInfo(pairs, withMic, channelBindingsHash, targetSpn)

        val ntowf = ntowfv2(password, user, domain)
        val ntResponse = ntlmv2Response(ntowf, challenge.serverChallenge, clientChallenge, timestamp, targetInfo)
        val ntProof = ntResponse.copyOfRange(0, 16)
        val lmResponse = if (withMic) ByteArray(24) else lmv2Response(ntowf, challenge.serverChallenge, clientChallenge)
        val sessionBaseKey = hmacMd5(ntowf, ntProof)

        val domainBytes = domain.toUtf16Le()
        val userBytes = user.toUtf16Le()
        val wsBytes = workstation.toUtf16Le()
        val flags = (TYPE1_FLAGS and challenge.flags) or NEGOTIATE_UNICODE or NEGOTIATE_NTLM or
            (challenge.flags and NEGOTIATE_TARGET_INFO)

        var offset = HEADER_LEN
        val out = ByteArrayOutputStream()
        out.write(SIGNATURE)
        out.writeInt32(3)
        out.writeSecBuf(lmResponse.size, offset); offset += lmResponse.size
        out.writeSecBuf(ntResponse.size, offset); offset += ntResponse.size
        out.writeSecBuf(domainBytes.size, offset); offset += domainBytes.size
        out.writeSecBuf(userBytes.size, offset); offset += userBytes.size
        out.writeSecBuf(wsBytes.size, offset); offset += wsBytes.size
        out.writeSecBuf(0, offset) // EncryptedRandomSessionKey
        out.writeInt32(flags)
        out.write(VERSION)
        out.write(ByteArray(16)) // MIC, заполняется ниже
        out.write(lmResponse)
        out.write(ntResponse)
        out.write(domainBytes)
        out.write(userBytes)
        out.write(wsBytes)
        val message = out.toByteArray()

        if (withMic) {
            val mic = hmacMd5(sessionBaseKey, type1 + type2 + message)
            System.arraycopy(mic, 0, message, MIC_OFFSET, 16)
        }
        return message
    }

    private fun buildTargetInfo(
        serverPairs: List<Pair<Int, ByteArray>>,
        withMic: Boolean,
        cbt: ByteArray?,
        spn: String?,
    ): ByteArray {
        val out = ByteArrayOutputStream()
        var flagsWritten = false
        for ((id, value) in serverPairs) {
            when (id) {
                AV_EOL, AV_CHANNEL_BINDINGS, AV_TARGET_NAME -> Unit
                AV_FLAGS -> {
                    val v = (if (value.size >= 4) value.readInt32(0) else 0) or (if (withMic) 0x2 else 0)
                    out.writeAvPair(AV_FLAGS, int32Le(v))
                    flagsWritten = true
                }
                else -> out.writeAvPair(id, value)
            }
        }
        if (withMic && !flagsWritten) out.writeAvPair(AV_FLAGS, int32Le(0x2))
        out.writeAvPair(AV_CHANNEL_BINDINGS, cbt ?: ByteArray(16))
        if (spn != null) out.writeAvPair(AV_TARGET_NAME, spn.toUtf16Le())
        out.writeAvPair(AV_EOL, ByteArray(0))
        return out.toByteArray()
    }

    companion object {
        private val SIGNATURE = byteArrayOf(0x4E, 0x54, 0x4C, 0x4D, 0x53, 0x53, 0x50, 0x00) // "NTLMSSP\0"
        // Windows 10.0 build 19041, NTLMSSP_REVISION_W2K3
        private val VERSION = byteArrayOf(10, 0, 0x61, 0x4A, 0, 0, 0, 0x0F)
        private const val HEADER_LEN = 88
        private const val MIC_OFFSET = 72

        const val NEGOTIATE_UNICODE = 0x00000001
        const val NEGOTIATE_OEM = 0x00000002
        const val REQUEST_TARGET = 0x00000004
        const val NEGOTIATE_NTLM = 0x00000200
        const val NEGOTIATE_ALWAYS_SIGN = 0x00008000
        const val NEGOTIATE_EXTENDED_SESSIONSECURITY = 0x00080000
        const val NEGOTIATE_TARGET_INFO = 0x00800000
        const val NEGOTIATE_VERSION = 0x02000000
        const val NEGOTIATE_128 = 0x20000000
        const val NEGOTIATE_56 = 0x80000000.toInt()

        private const val TYPE1_FLAGS = NEGOTIATE_UNICODE or NEGOTIATE_OEM or REQUEST_TARGET or NEGOTIATE_NTLM or
            NEGOTIATE_ALWAYS_SIGN or NEGOTIATE_EXTENDED_SESSIONSECURITY or NEGOTIATE_VERSION or
            NEGOTIATE_128 or NEGOTIATE_56

        const val AV_EOL = 0
        const val AV_FLAGS = 6
        const val AV_TIMESTAMP = 7
        const val AV_TARGET_NAME = 9
        const val AV_CHANNEL_BINDINGS = 10

        fun ntHash(password: String): ByteArray = Md4.digest(password.toUtf16Le())

        fun ntowfv2(password: String, user: String, domain: String): ByteArray =
            hmacMd5(ntHash(password), (user.uppercase() + domain).toUtf16Le())

        fun lmv2Response(ntowf: ByteArray, serverChallenge: ByteArray, clientChallenge: ByteArray): ByteArray =
            hmacMd5(ntowf, serverChallenge + clientChallenge) + clientChallenge

        /** NTProofStr + temp (MS-NLMP 3.3.2). */
        fun ntlmv2Response(
            ntowf: ByteArray,
            serverChallenge: ByteArray,
            clientChallenge: ByteArray,
            timestamp: ByteArray,
            targetInfo: ByteArray,
        ): ByteArray {
            val temp = byteArrayOf(1, 1, 0, 0, 0, 0, 0, 0) + timestamp + clientChallenge +
                ByteArray(4) + targetInfo + ByteArray(4)
            val proof = hmacMd5(ntowf, serverChallenge + temp)
            return proof + temp
        }

        fun parseAvPairs(info: ByteArray): List<Pair<Int, ByteArray>> {
            val result = mutableListOf<Pair<Int, ByteArray>>()
            var p = 0
            while (p + 4 <= info.size) {
                val id = info.readInt16(p)
                val len = info.readInt16(p + 2)
                if (id == AV_EOL || p + 4 + len > info.size) break
                result += id to info.copyOfRange(p + 4, p + 4 + len)
                p += 4 + len
            }
            return result
        }

        /** Windows FILETIME: сотни наносекунд с 1601-01-01, little-endian. */
        fun fileTime(epochMillis: Long): ByteArray {
            val v = (epochMillis + 11644473600000L) * 10000L
            return ByteArray(8) { i -> (v ushr (8 * i)).toByte() }
        }

        fun hmacMd5(key: ByteArray, data: ByteArray): ByteArray =
            Mac.getInstance("HmacMD5").run {
                init(SecretKeySpec(key, "HmacMD5"))
                doFinal(data)
            }

        private fun String.toUtf16Le() = toByteArray(Charsets.UTF_16LE)
        private fun int32Le(v: Int) = ByteArray(4) { i -> (v ushr (8 * i)).toByte() }
        private fun ByteArray.readInt16(o: Int) = (this[o].toInt() and 0xff) or ((this[o + 1].toInt() and 0xff) shl 8)
        private fun ByteArray.readInt32(o: Int) = readInt16(o) or (readInt16(o + 2) shl 16)
        private fun ByteArrayOutputStream.writeInt16(v: Int) { write(v and 0xff); write((v ushr 8) and 0xff) }
        private fun ByteArrayOutputStream.writeInt32(v: Int) { writeInt16(v and 0xffff); writeInt16(v ushr 16) }
        private fun ByteArrayOutputStream.writeSecBuf(len: Int, offset: Int) {
            writeInt16(len); writeInt16(len); writeInt32(offset)
        }
        private fun ByteArrayOutputStream.writeAvPair(id: Int, value: ByteArray) {
            writeInt16(id); writeInt16(value.size); write(value)
        }
    }
}
