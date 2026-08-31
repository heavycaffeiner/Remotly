package com.remotly.app.transport

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.random.Random

// Property tests for the app-side untrusted parsers. Each feeds a large batch
// of random (and edge-shaped) inputs and asserts the parser either produces a
// well-typed result or a typed rejection, and never throws anything else or
// crashes. This is the JVM half of the RN-14 untrusted-parser requirement for
// the Kotlin ControlCodec and RelayCodec (the Go daemon side is covered by
// native fuzzing). A fixed seed keeps the batch reproducible.
class PropertyFuzzTest {

    @Test
    fun controlCodecDecodeSurvivesHostileInput() {
        val codec = ControlCodec()
        val rnd = Random(20260820)
        val jsonFragments = listOf(
            "{", "}", "}", "\"type\"", "\"id\":", "1.5", "null", "true",
            "\"session.attach\"", "0x1", "[", "]", "\\u0041",
        )
        for (i in 0 until 20000) {
            val s: String = when (i % 4) {
                0 -> randomBytes(rnd).toString(Charsets.ISO_8859_1)
                1 -> jsonFragments[rnd.nextInt(jsonFragments.size)] +
                    randomBytes(rnd).toString(Charsets.ISO_8859_1)
                2 -> randomJsonish(rnd)
                else -> ""
            }
            try {
                codec.decode(s)
            } catch (e: ControlException) {
                // expected rejection
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                fail("decode threw unexpected ${e::class.simpleName} on input len=${s.length}: ${e.message}")
            }
        }
    }

    @Test
    fun relayCodecDecodeSurvivesHostileBytes() {
        val rnd = Random(20260821)
        for (i in 0 until 20000) {
            val len = when {
                i % 5 == 0 -> 0
                i % 5 == 1 -> rnd.nextInt(1, 64)
                else -> rnd.nextInt(0, 4096)
            }
            val buf = ByteArray(len)
            rnd.nextBytes(buf)
            // Sometimes bias the first byte to each message type.
            if (len > 0 && i % 2 == 0) {
                buf[0] = (rnd.nextInt(0, 16)).toByte()
            }
            val off = 0
            try {
                RelayCodec.decode(buf, off, buf.size)
            } catch (e: RelayCodec.RelayCodecException) {
                // expected rejection
            } catch (e: AssertionError) {
                throw e
            } catch (e: Throwable) {
                fail("decode threw unexpected ${e::class.simpleName} on len=${buf.size}: ${e.message}")
            }
        }
        // A valid-length frame with too few bytes present means "need more
        // bytes", so it returns null (the socket reader supplies the rest).
        val truncated = byteArrayOf(0x03, 0x13) // frame, declared len 19 (min)
        assertTrue(RelayCodec.decode(truncated, 0, truncated.size) == null)
        // A declared length below the minimum is malformed, so the codec
        // rejects it with a typed error rather than waiting for bytes.
        val tooShort = runCatching { RelayCodec.decode(byteArrayOf(0x03, 0x10), 0, 2) }
        assertTrue(tooShort.exceptionOrNull() is RelayCodec.RelayCodecException)
    }

    private fun randomBytes(rnd: Random): ByteArray {
        val n = rnd.nextInt(0, 128)
        return ByteArray(n) { rnd.nextInt(256).toByte() }
    }

    private fun randomJsonish(rnd: Random): String {
        val chars = "{}[]\" :,truefnull0123456789.+-"
        val n = rnd.nextInt(0, 64)
        return (0 until n).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}
