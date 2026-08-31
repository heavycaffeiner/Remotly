package com.remotly.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class VarintTest {
    @Test
    fun `encode matches daemon vectors`() {
        for (i in TransportVectors.varintValues.indices) {
            val v = TransportVectors.varintValues[i]
            val expected = TransportVectors.varintHex[i]
            val got = Varint.encode(v).joinToString("") { String.format("%02x", it) }
            assertEquals("value $v", expected, got)
        }
    }

    @Test
    fun `decode round trips`() {
        for (v in TransportVectors.varintValues) {
            val enc = Varint.encode(v)
            val (decoded, n) = Varint.decode(enc, 0)
            assertEquals(v, decoded)
            assertEquals(enc.size, n)
        }
    }

    @Test
    fun `decode reads from offset and reports consumed`() {
        val prefix = byteArrayOf(0x01, 0x02, 0x03)
        val enc = Varint.encode(300) // ac 02
        val combined = prefix + enc
        val (decoded, n) = Varint.decode(combined, 3)
        assertEquals(300L, decoded)
        assertEquals(2, n)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `encode rejects out of range`() {
        Varint.encode(0x100000000L)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `decode rejects truncated`() {
        // 80 01 is a complete 128, but 80 alone is truncated.
        Varint.decode(byteArrayOf(0x80.toByte()), 0)
    }

    @Test
    fun `encode zero is single zero byte`() {
        assertArrayEquals(byteArrayOf(0), Varint.encode(0))
    }
}
