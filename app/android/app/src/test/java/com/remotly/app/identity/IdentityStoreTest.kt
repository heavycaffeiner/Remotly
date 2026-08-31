package com.remotly.app.identity

import com.remotly.app.transport.X25519
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import kotlin.io.path.createTempDirectory
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class IdentityStoreTest {

    // Real AES-GCM with a fixed software key: exercises the exact wire
    // layout the Keystore cipher produces, on the JVM.
    private class StaticKeySeedCipher : SeedCipher {
        var keyExists = false
            private set
        private val key = run {
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256)
            gen.generateKey()
        }

        override fun wrap(plaintext: ByteArray): ByteArray {
            keyExists = true
            val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            return iv + c.doFinal(plaintext)
        }

        override fun unwrap(wrapped: ByteArray): ByteArray {
            val iv = wrapped.copyOfRange(0, 12)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            return c.doFinal(wrapped.copyOfRange(12, wrapped.size))
        }

        override fun delete() {
            keyExists = false
        }
    }

    private lateinit var dir: File
    private lateinit var cipher: StaticKeySeedCipher
    private lateinit var store: IdentityStore

    @Before
    fun setUp() {
        dir = createTempDirectory("identity-test").toFile()
        cipher = StaticKeySeedCipher()
        store = IdentityStore(File(dir, IdentityStore.FILE_NAME), cipher)
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun loadOrCreateGeneratesStableIdentity() {
        val first = store.loadOrCreate()
        val file = File(dir, IdentityStore.FILE_NAME)
        assertEquals(97, file.length())
        val second = store.loadOrCreate()
        assertEquals(first, second)
        assertArrayEquals(X25519.pub(first.seed), first.publicKey)
    }

    @Test
    fun fileLayoutMatchesVersion1Format() {
        val identity = store.loadOrCreate()
        val bytes = File(dir, IdentityStore.FILE_NAME).readBytes()
        assertArrayEquals(MAGIC_BYTES, bytes.copyOfRange(0, 4))
        assertEquals(1, bytes[4].toInt())
        assertArrayEquals(identity.seed, cipher.unwrap(bytes.copyOfRange(5, 65)))
        assertArrayEquals(identity.publicKey, bytes.copyOfRange(65, 97))
    }

    @Test
    fun corruptedWrappedSeedIsRejected() {
        store.loadOrCreate()
        corrupt { it[10] = (it[10] + 1).toByte() }
        assertStoreFails { store.loadOrCreate() }
    }

    @Test
    fun corruptedPublicKeyIsRejected() {
        store.loadOrCreate()
        corrupt { it[70] = (it[70] + 1).toByte() }
        assertStoreFails { store.loadOrCreate() }
    }

    @Test
    fun badMagicIsRejected() {
        store.loadOrCreate()
        corrupt { it[0] = 'X'.code.toByte() }
        assertStoreFails { store.loadOrCreate() }
    }

    @Test
    fun unsupportedVersionIsRejected() {
        store.loadOrCreate()
        corrupt { it[4] = 2 }
        assertStoreFails { store.loadOrCreate() }
    }

    @Test
    fun wrongSizeIsRejected() {
        store.loadOrCreate()
        val file = File(dir, IdentityStore.FILE_NAME)
        val bytes = file.readBytes()
        file.writeBytes(bytes.copyOfRange(0, 96))
        assertStoreFails { store.loadOrCreate() }
    }

    @Test
    fun deleteRemovesFileAndKey() {
        store.loadOrCreate()
        store.delete()
        assertFalse(File(dir, IdentityStore.FILE_NAME).exists())
        assertFalse(cipher.keyExists)
        store.delete()
    }

    @Test
    fun deleteWithoutIdentitySucceeds() {
        store.delete()
        assertFalse(cipher.keyExists)
    }

    @Test
    fun loadAfterDeleteCreatesNewIdentity() {
        val first = store.loadOrCreate()
        store.delete()
        val second = store.loadOrCreate()
        assertFalse(first.seed.contentEquals(second.seed))
    }

    private fun corrupt(mutate: (ByteArray) -> Unit) {
        val file = File(dir, IdentityStore.FILE_NAME)
        val bytes = file.readBytes()
        mutate(bytes)
        file.writeBytes(bytes)
    }

    private fun assertStoreFails(block: () -> Unit) {
        try {
            block()
            fail("expected IdentityStoreException")
        } catch (e: IdentityStoreException) {
        }
    }

    companion object {
        private val MAGIC_BYTES = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())
    }
}
