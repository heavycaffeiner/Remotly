package com.remotly.app.transport

// Base64 without padding, URL-safe alphabet (RFC 4648 section 5). Matches the
// daemon's base64.RawURLEncoding byte for byte. Hand-rolled because
// java.util.Base64 needs API 26 while the app targets 24, and the codec must
// run on the JVM for tests.
object Base64Url {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
    private val INDEX = IntArray(128) { -1 }.also { idx ->
        for (i in ALPHABET.indices) idx[ALPHABET[i].code] = i
    }

    fun encode(src: ByteArray): String {
        val out = StringBuilder((src.size + 2) / 3 * 4)
        var i = 0
        while (i + 3 <= src.size) {
            val n = ((src[i].toInt() and 0xff) shl 16) or
                ((src[i + 1].toInt() and 0xff) shl 8) or
                (src[i + 2].toInt() and 0xff)
            out.append(ALPHABET[(n ushr 18) and 0x3f])
            out.append(ALPHABET[(n ushr 12) and 0x3f])
            out.append(ALPHABET[(n ushr 6) and 0x3f])
            out.append(ALPHABET[n and 0x3f])
            i += 3
        }
        when (src.size - i) {
            1 -> {
                val n = (src[i].toInt() and 0xff) shl 16
                out.append(ALPHABET[(n ushr 18) and 0x3f])
                out.append(ALPHABET[(n ushr 12) and 0x3f])
            }
            2 -> {
                val n = ((src[i].toInt() and 0xff) shl 16) or
                    ((src[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3f])
                out.append(ALPHABET[(n ushr 12) and 0x3f])
                out.append(ALPHABET[(n ushr 6) and 0x3f])
            }
        }
        return out.toString()
    }

    fun decode(s: String): ByteArray {
        if (s.length % 4 == 1) throw IllegalArgumentException("bad base64url length")
        val out = ByteArray(s.length / 4 * 3 + 2)
        var op = 0
        var buf = 0
        var bits = 0
        for (ch in s) {
            if (ch.code >= 128) throw IllegalArgumentException("bad base64url char")
            val v = INDEX[ch.code]
            if (v < 0) throw IllegalArgumentException("bad base64url char")
            buf = (buf shl 6) or v
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out[op++] = ((buf ushr bits) and 0xff).toByte()
            }
        }
        return out.copyOf(op)
    }
}
