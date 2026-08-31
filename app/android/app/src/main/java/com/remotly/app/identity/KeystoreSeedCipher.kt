package com.remotly.app.identity

import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

// Wraps the identity seed with a non-exportable AES-256-GCM key held in the
// Android Keystore. The key cannot be exported, read, or backed up, so the
// seed is protected by the platform's tamper-resistant storage. A random
// 12-byte IV is generated per wrap and stored alongside the ciphertext.
class KeystoreSeedCipher : SeedCipher {
    private val keyStore: KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    override fun wrap(plaintext: ByteArray): ByteArray {
        val iv = ByteArray(IV_LEN).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return iv + cipher.doFinal(plaintext)
    }

    override fun unwrap(wrapped: ByteArray): ByteArray {
        if (wrapped.size < IV_LEN) throw IdentityStoreException("wrapped seed too short")
        val iv = wrapped.copyOfRange(0, IV_LEN)
        val ct = wrapped.copyOfRange(IV_LEN, wrapped.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ct)
    }

    override fun delete() {
        if (keyStore.containsAlias(ALIAS)) keyStore.deleteEntry(ALIAS)
    }

    private fun key(): SecretKey {
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val builder = KeyGenParameterSpec.Builder(
            ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(KEY_BITS)
            // wrap/unwrap supply their own IV; without this the keystore default
            // (true) forbids a caller-provided IV and cipher.init throws.
            .setRandomizedEncryptionRequired(false)
        val gen = KeyGenerator.getInstance(KEY_ALGORITHM, ANDROID_KEYSTORE)
        gen.init(builder.build(), SecureRandom())
        return gen.generateKey()
    }

    companion object {
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALGORITHM = "AES"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        // v2: the v1 key was generated without setRandomizedEncryptionRequired
        // (false) and rejects the caller-provided IV. A fresh alias lets the
        // corrected keygen run; no seed was ever wrapped under v1 (its first
        // wrap threw).
        private const val ALIAS = "remotly_identity_seed_v2"
        private const val KEY_BITS = 256
        private const val IV_LEN = 12
        private const val TAG_BITS = 128
    }
}
