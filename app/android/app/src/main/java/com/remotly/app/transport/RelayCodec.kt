package com.remotly.app.transport

// Byte-for-byte mirror of the relay protocol codec (relay/relayproto). The app
// side only ever sees a subset of the message types: the relay sends the app
// join_ack, frame, keepalive, and end. The stream_* types are daemon-connection
// only; receiving one on an app connection is a protocol violation.
//
// The codec is a trust boundary. Every read validates lengths and ranges and
// throws [RelayCodecException] on malformed input, which the wire turns into a
// failed connection.
object RelayCodec {
    const val VERSION = 1
    const val ROLE_APP = 1
    const val RELAY_ID_LEN = 16

    const val T_JOIN = 0x01
    const val T_JOIN_ACK = 0x02
    const val T_FRAME = 0x03
    const val T_KEEPALIVE = 0x04
    const val T_END = 0x05

    const val MIN_FRAME_LEN = 19
    const val MAX_FRAME_LEN = 1 shl 20 or 0x80
    const val MAX_REASON_LEN = 127
    const val MAX_RELAY_LEN = 16

    class RelayCodecException(message: String) : Exception(message)

    // --- encode ---

    fun join(relayId: ByteArray): ByteArray {
        require(relayId.size == RELAY_ID_LEN) { "relay id must be 16 bytes" }
        val out = ByteArray(1 + 1 + 1 + RELAY_ID_LEN)
        out[0] = T_JOIN.toByte()
        out[1] = VERSION.toByte()
        out[2] = ROLE_APP.toByte()
        System.arraycopy(relayId, 0, out, 3, RELAY_ID_LEN)
        return out
    }

    fun frame(data: ByteArray): ByteArray {
        require(data.size in MIN_FRAME_LEN..MAX_FRAME_LEN) { "frame length out of range" }
        val len = Varint.encode(data.size.toLong())
        val out = ByteArray(1 + len.size + data.size)
        out[0] = T_FRAME.toByte()
        System.arraycopy(len, 0, out, 1, len.size)
        System.arraycopy(data, 0, out, 1 + len.size, data.size)
        return out
    }

    fun keepalive(): ByteArray = byteArrayOf(T_KEEPALIVE.toByte())

    // --- decode ---

    // Reads one full message from [buf] in [off, limit). Returns the decoded
    // message and the number of bytes consumed, or null when fewer than the
    // message's bytes are present yet (the caller accumulates and retries).
    // Throws [RelayCodecException] on a malformed value.
    sealed class Msg
    object JoinAck : Msg()
    data class Frame(val data: ByteArray) : Msg()
    object Keepalive : Msg()
    data class End(val code: Int, val reason: String) : Msg()

    fun decode(buf: ByteArray, off: Int, limit: Int): Pair<Msg, Int>? {
        if (off >= limit) return null
        val t = buf[off].toInt() and 0xff
        return when (t) {
            T_JOIN_ACK -> {
                if (limit < off + 2) return null
                // status byte; 0 is the only value the relay sends.
                JoinAck to (off + 2)
            }
            T_FRAME -> {
                if (limit < off + 2) return null
                val (len, n) = try {
                    Varint.decode(buf.copyOfRange(off + 1, limit), 0)
                } catch (e: IllegalArgumentException) {
                    throw RelayCodecException("bad frame length")
                }
                val start = off + 1 + n
                if (len !in MIN_FRAME_LEN..MAX_FRAME_LEN.toLong()) {
                    throw RelayCodecException("frame length out of range")
                }
                if (limit < start + len) return null
                val data = buf.copyOfRange(start, start + len.toInt())
                Frame(data) to (start + len.toInt())
            }
            T_KEEPALIVE -> {
                if (limit < off + 1) return null
                Keepalive to (off + 1)
            }
            T_END -> {
                if (limit < off + 4) return null
                val code = ((buf[off + 1].toInt() and 0xff) shl 8) or (buf[off + 2].toInt() and 0xff)
                val rlen = buf[off + 3].toInt() and 0xff
                if (rlen > MAX_REASON_LEN) throw RelayCodecException("reason too long")
                if (limit < off + 4 + rlen) return null
                val reason = String(buf, off + 4, rlen, Charsets.UTF_8)
                End(code, reason) to (off + 4 + rlen)
            }
            else -> throw RelayCodecException("unexpected message type 0x${t.toString(16)}")
        }
    }
}
