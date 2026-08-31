package com.remotly.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

object TestHex {
    fun decode(s: String): ByteArray {
        require(s.length % 2 == 0)
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            out[i] = s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    fun encode(b: ByteArray): String = b.joinToString("") { String.format("%02x", it) }
}

class NoiseHandshakeTest {

    @Test
    fun `IK handshake matches daemon reference`() {
        val ik = TransportVectors.ik
        val prologue = TransportVectors.prologue

        // Fixed key material (X25519 seeds) matching the generator.
        val initStatic = TestHex.decode(TransportVectors.ikInitStaticSeed)
        val respStatic = TestHex.decode(TransportVectors.ikRespStaticSeed)
        val initEph = TestHex.decode(TransportVectors.ephIkJ1)
        val respEph = TestHex.decode(TransportVectors.ephIkJ2)
        val respStaticPub = X25519.pub(respStatic)

        // The initiator knows the responder's static (pinned) as a pre-message.
        val hsI = HandshakeState(true, Patterns.IK, prologue, initStatic, respStaticPub, null, -1, initEph)
        val hsR = HandshakeState(false, Patterns.IK, prologue, respStatic, null, null, -1, respEph)

        val m1 = hsI.writeMessage(ByteArray(0))
        assertEquals(ik.msg1, TestHex.encode(m1.message))

        val r1 = hsR.readMessage(m1.message)
        assertEquals(ByteArray(0).size, r1.message.size)

        val m2 = hsR.writeMessage(ByteArray(0))
        assertEquals(ik.msg2, TestHex.encode(m2.message))

        val r2 = hsI.readMessage(m2.message)
        assertEquals(ByteArray(0).size, r2.message.size)

        // The initiator's split keys and the learned peer static.
        assertArrayEquals(TestHex.decode(ik.c1), r2.c1)
        assertArrayEquals(TestHex.decode(ik.c2), r2.c2)
        assertArrayEquals(respStaticPub, hsI.peerStatic())
    }

    @Test
    fun `XXpsk0 handshake matches daemon reference`() {
        val xx = TransportVectors.xx
        val prologue = TransportVectors.prologue

        val initStatic = TestHex.decode(TransportVectors.xxInitStaticSeed)
        val respStatic = TestHex.decode(TransportVectors.xxRespStaticSeed)
        val initEph = TestHex.decode(TransportVectors.ephXxJ1)
        val respEph = TestHex.decode(TransportVectors.ephXxJ2)
        val psk = TestHex.decode(TransportVectors.xxPsk)

        val hsI = HandshakeState(true, Patterns.XX, prologue, initStatic, null, psk, 0, initEph)
        val hsR = HandshakeState(false, Patterns.XX, prologue, respStatic, null, psk, 0, respEph)

        val m1 = hsI.writeMessage(ByteArray(0))
        assertEquals(xx.msg1, TestHex.encode(m1.message))

        hsR.readMessage(m1.message)

        val m2 = hsR.writeMessage(ByteArray(0))
        assertEquals(xx.msg2, TestHex.encode(m2.message))

        hsI.readMessage(m2.message)

        val m3 = hsI.writeMessage(ByteArray(0))
        assertEquals(xx.msg3, TestHex.encode(m3.message))

        hsR.readMessage(m3.message)

        assertArrayEquals(TestHex.decode(xx.c1), m3.c1)
        assertArrayEquals(TestHex.decode(xx.c2), m3.c2)
        // The initiator learns the responder's (token) static key.
        assertArrayEquals(X25519.pub(respStatic), hsI.peerStatic())
    }

    @Test
    fun `split keys are directional`() {
        // The responder's send key must equal the initiator's receive key, and
        // vice versa: the two sides derive the same two direction keys.
        val prologue = TransportVectors.prologue
        val initStatic = TestHex.decode(TransportVectors.ikInitStaticSeed)
        val respStatic = TestHex.decode(TransportVectors.ikRespStaticSeed)
        val initEph = TestHex.decode(TransportVectors.ephIkJ1)
        val respEph = TestHex.decode(TransportVectors.ephIkJ2)
        val respStaticPub = X25519.pub(respStatic)

        val hsI = HandshakeState(true, Patterns.IK, prologue, initStatic, respStaticPub, null, -1, initEph)
        val hsR = HandshakeState(false, Patterns.IK, prologue, respStatic, null, null, -1, respEph)
        val m1 = hsI.writeMessage(ByteArray(0))
        hsR.readMessage(m1.message)
        val m2 = hsR.writeMessage(ByteArray(0))
        val r2 = hsI.readMessage(m2.message)
        // Both sides derive the same two direction keys from the same
        // chaining key. The initiator sends with c1 / receives with c2; the
        // responder receives with c1 / sends with c2.
        assertArrayEquals(r2.c1, m2.c1)
        assertArrayEquals(r2.c2, m2.c2)
    }
}
