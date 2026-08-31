package com.remotly.app.ssh

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey

class SecretStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

// The production credential cipher. The AES-256-GCM key lives in
// AndroidKeyStore and is non-exportable, so only ciphertext ever reaches the
// store file. A missing or invalidated key surfaces as an exception rather
// than falling back to plaintext.
//
// This compiles against the Android platform and is not exercised by the JVM
// unit tests; the store's transaction logic is tested against an in-memory
// cipher through the same CredentialCipher interface.
class AndroidKeyStoreCredentialCipher : GcmCredentialCipher({ loadOrCreateKey() }) {

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"

        // v2: the v1 key was generated without setRandomizedEncryptionRequired
        // (false), so it rejects the caller-provided IV. A fresh alias lets the
        // corrected keygen run; no credentials were ever stored under v1 (its
        // first write threw). The alias is unchanged by the schema 2 move,
        // because existing ciphertext must still decrypt after the upgrade.
        const val ALIAS = "remotly.ssh.creds.v2"

        fun loadOrCreateKey(): SecretKey {
            val ks = KeyStore.getInstance(ANDROID_KEYSTORE)
            ks.load(null)
            (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
            val generator = KeyGenerator.getInstance("AES", ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    // The store supplies its own IV (stored with the blob);
                    // without this the keystore default (true) forbids a
                    // caller-provided IV and every cipher.init throws.
                    .setRandomizedEncryptionRequired(false)
                    .build(),
            )
            return generator.generateKey()
        }
    }
}
