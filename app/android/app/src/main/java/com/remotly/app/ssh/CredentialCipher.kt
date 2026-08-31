package com.remotly.app.ssh

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Encrypts credential bytes for storage. The key never leaves its provider; the
// store only ever sees ciphertext.
//
// Extracted as an interface so the store's transaction logic is testable on the
// JVM, where AndroidKeyStore does not exist. The production implementation is
// AndroidKeyStoreCredentialCipher.
interface CredentialCipher {
    /** Returns iv + ciphertext. */
    fun seal(plaintext: ByteArray): ByteArray

    /** Takes iv + ciphertext. Throws when authentication fails. */
    fun open(sealed: ByteArray): ByteArray

    companion object {
        const val IV_LEN = 12
        const val TAG_BITS = 128
    }
}

// AES-256-GCM with a caller-supplied IV, for a key held by the platform.
open class GcmCredentialCipher(private val keyProvider: () -> SecretKey) : CredentialCipher {

    private val random = SecureRandom()

    override fun seal(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(CredentialCipher.IV_LEN).also { random.nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            keyProvider(),
            GCMParameterSpec(CredentialCipher.TAG_BITS, iv),
        )
        return iv + cipher.doFinal(plaintext)
    }

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size <= CredentialCipher.IV_LEN) {
            throw SecretStoreException("sealed credential is truncated")
        }
        val iv = sealed.copyOfRange(0, CredentialCipher.IV_LEN)
        val ciphertext = sealed.copyOfRange(CredentialCipher.IV_LEN, sealed.size)
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(
            Cipher.DECRYPT_MODE,
            keyProvider(),
            GCMParameterSpec(CredentialCipher.TAG_BITS, iv),
        )
        return try {
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            // A GCM authentication failure means the key changed or the bytes
            // are corrupt. Either way the plaintext is not recoverable.
            throw SecretStoreException("cannot decrypt credential", e)
        }
    }

    private companion object {
        const val TRANSFORM = "AES/GCM/NoPadding"
    }
}

// A test cipher that keeps the bytes readable. Never used in the app: the
// production wiring in RemotlyCore always supplies the keystore-backed one.
class PlaintextCredentialCipher : CredentialCipher {
    override fun seal(plaintext: ByteArray): ByteArray =
        ByteArray(CredentialCipher.IV_LEN) + plaintext

    override fun open(sealed: ByteArray): ByteArray {
        if (sealed.size < CredentialCipher.IV_LEN) {
            throw SecretStoreException("sealed credential is truncated")
        }
        return sealed.copyOfRange(CredentialCipher.IV_LEN, sealed.size)
    }
}

// Generates an AES-256 key in memory. For tests only.
fun inMemoryCipher(): CredentialCipher {
    val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    return GcmCredentialCipher { key }
}
