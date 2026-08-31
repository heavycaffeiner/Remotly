package com.remotly.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class RelayCodecTest {
    private val relayId = ByteArray(16) { it.toByte() }

    // decode that asserts a complete message was present.
    private fun decode(b: ByteArray, off: Int = 0, limit: Int = b.size): Pair<RelayCodec.Msg, Int> {
        val r = RelayCodec.decode(b, off, limit)
        if (r == null) fail("decode returned null")
        return r!!
    }

    @Test
    fun joinEncodesExactlyNineteenBytes() {
        val b = RelayCodec.join(relayId)
        assertEquals(19, b.size)
        assertEquals(0x01, b[0].toInt() and 0xff)
        assertEquals(RelayCodec.VERSION, b[1].toInt() and 0xff)
        assertEquals(RelayCodec.ROLE_APP, b[2].toInt() and 0xff)
        assertArrayEquals(relayId, b.copyOfRange(3, 19))
    }

    @Test
    fun frameRoundTrip() {
        for (size in intArrayOf(RelayCodec.MIN_FRAME_LEN, 100, 1024)) {
            val payload = ByteArray(size) { (it % 251).toByte() }
            val b = RelayCodec.frame(payload)
            val (msg, consumed) = decode(b)
            assertEquals(b.size, consumed)
            val frame = msg as? RelayCodec.Frame
            assertTrue("not a frame", frame != null)
            assertArrayEquals(payload, frame!!.data)
        }
    }

    @Test
    fun maxFrameRoundTrip() {
        val payload = ByteArray(RelayCodec.MAX_FRAME_LEN) { (it % 251).toByte() }
        val b = RelayCodec.frame(payload)
        val (msg, consumed) = decode(b)
        assertEquals(b.size, consumed)
        assertArrayEquals(payload, (msg as RelayCodec.Frame).data)
    }

    @Test
    fun joinAckDecodes() {
        val (msg, consumed) = decode(byteArrayOf(0x02, 0x00))
        assertEquals(2, consumed)
        assertTrue(msg is RelayCodec.JoinAck)
    }

    @Test
    fun keepaliveDecodesAndEncodes() {
        assertEquals(1, RelayCodec.keepalive().size)
        val (msg, consumed) = decode(byteArrayOf(0x04))
        assertEquals(1, consumed)
        assertTrue(msg is RelayCodec.Keepalive)
    }

    @Test
    fun endDecodes() {
        val reason = "no daemon".toByteArray()
        val b = byteArrayOf(
            0x05,
            (3001 shr 8).toByte(), (3001 and 0xff).toByte(),
            reason.size.toByte(),
        ) + reason
        val (msg, consumed) = decode(b)
        assertEquals(b.size, consumed)
        val end = msg as? RelayCodec.End
        assertTrue("not an end", end != null)
        assertEquals(3001, end!!.code)
        assertEquals("no daemon", end!!.reason)
    }

    @Test
    fun partialMessageReturnsNull() {
        // A join_ack split across two reads.
        assertNull(RelayCodec.decode(byteArrayOf(0x02), 0, 1))
        val (msg, _) = decode(byteArrayOf(0x02, 0x00))
        assertTrue(msg is RelayCodec.JoinAck)
    }

    @Test
    fun malformedInputsThrow() {
        // Unknown message type on an app connection.
        assertThrows(RelayCodec.RelayCodecException::class.java) {
            RelayCodec.decode(byteArrayOf(0x06, 0, 0, 0, 1), 0, 5)
        }
        // Frame length below the minimum.
        val short = byteArrayOf(0x03, 0x01) + ByteArray(1)
        assertThrows(RelayCodec.RelayCodecException::class.java) { RelayCodec.decode(short, 0, short.size) }
        // Frame length above the maximum.
        val long = byteArrayOf(0x03, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())
        assertThrows(RelayCodec.RelayCodecException::class.java) { RelayCodec.decode(long, 0, long.size) }
        // Empty input is incomplete, not an error.
        assertNull(RelayCodec.decode(ByteArray(0), 0, 0))
    }

    @Test
    fun decodeRespectsLimitNotBufferSize() {
        // A buffer larger than the valid prefix: decode must not read past the
        // limit, so the stale tail bytes are ignored.
        val frame = RelayCodec.frame(ByteArray(RelayCodec.MIN_FRAME_LEN))
        val b = ByteArray(64)
        System.arraycopy(frame, 0, b, 0, frame.size)
        for (i in frame.size until b.size) b[i] = 0xFF.toByte()
        val (msg, consumed) = decode(b, limit = frame.size)
        assertTrue(msg is RelayCodec.Frame)
        assertEquals(frame.size, consumed)
    }

    @Test
    fun frameBelowMinimumRejectedOnEncode() {
        assertThrows(IllegalArgumentException::class.java) { RelayCodec.frame(ByteArray(18)) }
    }

    private fun assertThrows(cls: Class<out Throwable>, block: () -> Unit) {
        try {
            block()
            fail("expected ${cls.name}")
        } catch (e: Throwable) {
            assertTrue("expected ${cls.name}, got ${e::class.java.name}", cls.isInstance(e))
        }
    }
}
