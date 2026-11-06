package io.github.denhaiz.exmail.auth

/** MD4 (RFC 1320). Нужен для NT-хэша пароля; в Android нет штатного провайдера MD4. */
object Md4 {
    fun digest(input: ByteArray): ByteArray {
        val padLen = ((55 - input.size) % 64 + 64) % 64
        val total = input.size + 1 + padLen + 8
        val buf = ByteArray(total)
        System.arraycopy(input, 0, buf, 0, input.size)
        buf[input.size] = 0x80.toByte()
        val bitLen = input.size.toLong() * 8
        for (i in 0 until 8) buf[total - 8 + i] = (bitLen ushr (8 * i)).toByte()

        var a = 0x67452301
        var b = 0xefcdab89.toInt()
        var c = 0x98badcfe.toInt()
        var d = 0x10325476
        val x = IntArray(16)

        fun r1(p: Int, q: Int, r: Int, s: Int, k: Int, sh: Int) =
            Integer.rotateLeft(p + ((q and r) or (q.inv() and s)) + x[k], sh)
        fun r2(p: Int, q: Int, r: Int, s: Int, k: Int, sh: Int) =
            Integer.rotateLeft(p + ((q and r) or (q and s) or (r and s)) + x[k] + 0x5A827999, sh)
        fun r3(p: Int, q: Int, r: Int, s: Int, k: Int, sh: Int) =
            Integer.rotateLeft(p + (q xor r xor s) + x[k] + 0x6ED9EBA1, sh)

        for (block in 0 until total step 64) {
            for (i in 0 until 16) {
                val o = block + 4 * i
                x[i] = (buf[o].toInt() and 0xff) or
                    ((buf[o + 1].toInt() and 0xff) shl 8) or
                    ((buf[o + 2].toInt() and 0xff) shl 16) or
                    ((buf[o + 3].toInt() and 0xff) shl 24)
            }
            val aa = a; val bb = b; val cc = c; val dd = d

            for (i in 0 until 4) {
                a = r1(a, b, c, d, 4 * i, 3)
                d = r1(d, a, b, c, 4 * i + 1, 7)
                c = r1(c, d, a, b, 4 * i + 2, 11)
                b = r1(b, c, d, a, 4 * i + 3, 19)
            }
            for (i in 0 until 4) {
                a = r2(a, b, c, d, i, 3)
                d = r2(d, a, b, c, i + 4, 5)
                c = r2(c, d, a, b, i + 8, 9)
                b = r2(b, c, d, a, i + 12, 13)
            }
            for (i in intArrayOf(0, 2, 1, 3)) {
                a = r3(a, b, c, d, i, 3)
                d = r3(d, a, b, c, i + 8, 9)
                c = r3(c, d, a, b, i + 4, 11)
                b = r3(b, c, d, a, i + 12, 15)
            }
            a += aa; b += bb; c += cc; d += dd
        }

        val out = ByteArray(16)
        intArrayOf(a, b, c, d).forEachIndexed { i, v ->
            for (j in 0 until 4) out[4 * i + j] = (v ushr (8 * j)).toByte()
        }
        return out
    }
}
