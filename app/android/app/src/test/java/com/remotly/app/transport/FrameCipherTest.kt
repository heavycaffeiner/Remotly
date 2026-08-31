package com.remotly.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCipherTest {

    // The generator filled each payload with byte(i) for i in 0 until len, so
    // the plaintext is recoverable from the ciphertext length in the header.
    private fun payloadFor(wireHex: String): Triple<Int, Long, ByteArray> {
        val wire = TestHex.decode(wireHex)
        val chType = wire[0].toInt() and 0xff
        var off = 1
        val (idv, n) = Varint.decode(wire, off)
        off += n
        val (ctLen, m) = Varint.decode(wire, off)
        off += m
        val plainLen = (ctLen - FrameCipher.OVERHEAD).toInt()
        assertEquals(wire.size - off, plainLen + FrameCipher.OVERHEAD)
        return Triple(chType, idv, ByteArray(plainLen) { it.toByte() })
    }

    @Test
    fun `seal matches daemon direction A`() {
        val c1 = TestHex.decode(TransportVectors.ik.c1)
        val c2 = TestHex.decode(TransportVectors.ik.c2)
        val cipher = FrameCipher(c1, c2)
        for (f in TransportVectors.framesA) {
            val (chType, chId, payload) = payloadFor(f.wire)
            assertEquals(f.chType, chType)
            assertEquals(f.chId, chId)
            val sealed = cipher.sealFrame(chType, chId, payload)
            assertEquals(f.wire, TestHex.encode(sealed))
        }
    }

    @Test
    fun `open matches daemon direction B`() {
        val c1 = TestHex.decode(TransportVectors.ik.c1)
        val c2 = TestHex.decode(TransportVectors.ik.c2)
        val cipher = FrameCipher(c1, c2)
        for (f in TransportVectors.framesB) {
            val (chType, chId, payload) = payloadFor(f.wire)
            val frame = cipher.openFrame(TestHex.decode(f.wire))
            assertEquals(chType, frame.chType)
            assertEquals(chId, frame.chId)
            assertArrayEquals(payload, frame.payload)
        }
    }

    @Test
    fun `round trip seal then open`() {
        val c1 = TestHex.decode(TransportVectors.xx.c1)
        val c2 = TestHex.decode(TransportVectors.xx.c2)
        val sender = FrameCipher(c1, c2)
        val receiver = FrameCipher(c2, c1)
        val payload = "remotly frame payload".encodeToByteArray()
        val wire = sender.sealFrame(1, 7, payload)
        val frame = receiver.openFrame(wire)
        assertEquals(1, frame.chType)
        assertEquals(7L, frame.chId)
        assertArrayEquals(payload, frame.payload)
    }

    @Test(expected = FrameException::class)
    fun `open rejects tampered ciphertext`() {
        val c1 = TestHex.decode(TransportVectors.ik.c1)
        val c2 = TestHex.decode(TransportVectors.ik.c2)
        val cipher = FrameCipher(c1, c2)
        val wire = TestHex.decode(TransportVectors.framesB[0].wire)
        wire[wire.size - 1] = (wire[wire.size - 1] + 1).toByte()
        cipher.openFrame(wire)
    }

    @Test(expected = FrameException::class)
    fun `open rejects truncated frame`() {
        val c1 = TestHex.decode(TransportVectors.ik.c1)
        val c2 = TestHex.decode(TransportVectors.ik.c2)
        val cipher = FrameCipher(c1, c2)
        val wire = TestHex.decode(TransportVectors.framesB[1].wire)
        cipher.openFrame(wire.copyOfRange(0, wire.size - 1))
    }

    @Test(expected = FrameException::class)
    fun `open rejects bad channel type`() {
        val c1 = TestHex.decode(TransportVectors.ik.c1)
        val c2 = TestHex.decode(TransportVectors.ik.c2)
        val cipher = FrameCipher(c1, c2)
        // Channel type 3 is reserved; the header is not authenticated yet, so
        // the bad-channel check fires before decryption.
        val wire = TestHex.decode(TransportVectors.framesA[0].wire)
        wire[0] = 3
        cipher.openFrame(wire)
    }
}
