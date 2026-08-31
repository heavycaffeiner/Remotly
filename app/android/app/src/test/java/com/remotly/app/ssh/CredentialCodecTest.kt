package com.remotly.app.ssh

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class CredentialCodecTest {

    @Test
    fun passwordRoundTrip() {
        val c = SshCredential.Password("secret".toByteArray())
        assertEquals(c, CredentialCodec.decode(CredentialCodec.encode(c)))
    }

    @Test
    fun keyRoundTripWithPassphrase() {
        val c = SshCredential.Key("-----BEGIN-----\nkey".toByteArray(), "pass".toByteArray())
        assertEquals(c, CredentialCodec.decode(CredentialCodec.encode(c)))
    }

    @Test
    fun keyRoundTripWithoutPassphrase() {
        val c = SshCredential.Key("pem".toByteArray(), null)
        val decoded = CredentialCodec.decode(CredentialCodec.encode(c))
        assertEquals(c, decoded)
        assertEquals(null, (decoded as SshCredential.Key).passphrase)
    }

    @Test
    fun rejectsTruncatedPayload() {
        val bytes = CredentialCodec.encode(SshCredential.Password("secret".toByteArray()))
        try {
            CredentialCodec.decode(bytes.copyOfRange(0, 5))
            fail("truncated payload should be rejected")
        } catch (e: SecretStoreException) {
            // expected
        }
    }

    @Test
    fun rejectsUnknownTag() {
        try {
            CredentialCodec.decode(byteArrayOf(0x09, 0, 0, 0, 1, 0x41))
            fail("unknown tag should be rejected")
        } catch (e: SecretStoreException) {
            // expected
        }
    }

    @Test
    fun rejectsOversizedDeclaredLength() {
        // Tag 0x01 (password) followed by a declared length far beyond the buffer.
        val bytes = byteArrayOf(0x01, 0x7F, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x41)
        try {
            CredentialCodec.decode(bytes)
            fail("oversized declared length should be rejected")
        } catch (e: SecretStoreException) {
            // expected
        }
    }
}
