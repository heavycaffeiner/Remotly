package com.remotly.app.transport

// Standard base64 with `+/` and `=` padding (RFC 4648 section 4). This is the
// alphabet the JS base64 helper (app/src/lib/base64.ts) uses for raw
// terminal bytes crossing the bridge, so native and JS round-trip byte for
// byte. Hand-rolled because java.util.Base64 needs API 26 while the app
// targets 24, and the codec must run on the JVM for tests.
object Base64Std {
    private const val ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
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
                out.append("==")
            }
            2 -> {
                val n = ((src[i].toInt() and 0xff) shl 16) or
                    ((src[i + 1].toInt() and 0xff) shl 8)
                out.append(ALPHABET[(n ushr 18) and 0x3f])
                out.append(ALPHABET[(n ushr 12) and 0x3f])
                out.append(ALPHABET[(n ushr 6) and 0x3f])
                out.append('=')
            }
        }
        return out.toString()
    }

    // Tolerates optional `=` padding and surrounding whitespace, matching the
    // JS decoder's leniency so a value produced on either side decodes here.
    fun decode(s: String): ByteArray {
        val clean = s.replace(" ", "").replace("\t", "").replace("\n", "").replace("\r", "")
        if (clean.length % 4 == 1) throw IllegalArgumentException("bad base64 length")
        val out = ByteArray(clean.length / 4 * 3 + 2)
        var op = 0
        var buf = 0
        var bits = 0
        for (ch in clean) {
            if (ch == '=') continue
            if (ch.code >= 128) throw IllegalArgumentException("bad base64 char")
            val v = INDEX[ch.code]
            if (v < 0) throw IllegalArgumentException("bad base64 char")
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
