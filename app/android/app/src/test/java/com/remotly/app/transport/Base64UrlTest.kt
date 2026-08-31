package com.remotly.app.transport

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class Base64UrlTest {
    @Test
    fun `encode matches Go RawURLEncoding vectors`() {
        assertEquals("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", Base64Url.encode(ByteArray(32)))
        val ff = ByteArray(32) { 0xff.toByte() }
        assertEquals("__________________________________________8", Base64Url.encode(ff))
        val pub = byteArrayOf(
            0x48.toByte(), 0x9c.toByte(), 0xeb.toByte(), 0xba.toByte(),
            0x7a.toByte(), 0x16.toByte(), 0x6c.toByte(), 0x61.toByte(),
        )
        assertEquals("SJzrunoWbGE", Base64Url.encode(pub))
    }

    @Test
    fun `round trip for every length`() {
        for (len in 0..40) {
            val b = ByteArray(len) { (it * 7 + 3).toByte() }
            val enc = Base64Url.encode(b)
            assertArrayEquals(b, Base64Url.decode(enc))
        }
    }

    @Test
    fun `decode rejects padding bad chars and bad length`() {
        for (bad in listOf("abc=", "ab+c", "a", "ab/c", "a b")) {
            try {
                Base64Url.decode(bad)
                fail("expected rejection of '$bad'")
            } catch (e: IllegalArgumentException) {
                // expected
            }
        }
    }
}
