package com.remotly.app.transport

// Unsigned LEB128, capped at 5 bytes (the widest protocol field is a uint32
// channel id). Mirrors the daemon's protocol.AppendVarint / ReadVarint byte for
// byte, so the app and daemon agree on framing.
object Varint {
    const val MAX_BYTES = 5

    // v must be < 2^32.
    fun encode(v: Long): ByteArray {
        require(v in 0..0xFFFFFFFFL) { "varint out of range: $v" }
        var value = v
        val out = ByteArray(MAX_BYTES)
        var i = 0
        while (true) {
            var b = (value and 0x7f).toInt()
            value = value ushr 7
            if (value != 0L) b = b or 0x80
            out[i++] = b.toByte()
            if (value == 0L) break
        }
        return out.copyOf(i)
    }

    // Returns the decoded value and the number of bytes consumed. Throws on a
    // truncated or over-long prefix.
    fun decode(b: ByteArray, off: Int): Pair<Long, Int> {
        var v = 0L
        var i = off
        while (true) {
            val pos = i - off
            if (pos == MAX_BYTES) throw IllegalArgumentException("varint too long")
            if (i >= b.size) throw IllegalArgumentException("varint truncated")
            val c = b[i].toInt() and 0xff
            v = v or ((c and 0x7f).toLong() shl (7 * pos))
            i++
            if (c and 0x80 == 0) return v to (i - off)
        }
    }
}
