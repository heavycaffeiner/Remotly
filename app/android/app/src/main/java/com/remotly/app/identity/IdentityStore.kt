package com.remotly.app.identity

import android.content.Context
import com.remotly.app.transport.X25519
import java.io.File
import java.security.SecureRandom

// The app's long-term identity: one X25519 keypair. The public key is what
// daemons pin in their paired-device records (hello device_pub); the seed
// authenticates the app in every Noise IK handshake and never leaves the
// device.
class Identity(val seed: ByteArray, val publicKey: ByteArray) {
    init {
        require(seed.size == 32) { "seed must be 32 bytes" }
        require(publicKey.size == 32) { "public key must be 32 bytes" }
        require(X25519.pub(seed).contentEquals(publicKey)) { "public key does not match seed" }
    }

    override fun equals(other: Any?): Boolean =
        other is Identity && seed.contentEquals(other.seed) && publicKey.contentEquals(other.publicKey)

    override fun hashCode(): Int = 31 * seed.contentHashCode() + publicKey.contentHashCode()
}

class IdentityStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

// Protects the private seed at rest. Production uses a non-exportable
// Android Keystore AES-GCM key; JVM tests use a software key with the same
// wire layout so the file format is exercised identically.
interface SeedCipher {
    fun wrap(plaintext: ByteArray): ByteArray
    fun unwrap(wrapped: ByteArray): ByteArray
    fun delete()
}

// On-disk identity file, version 1. Fixed 97 bytes:
//   0..4   magic "RMI1"
//   4      format version (1)
//   5..65  wrapped seed: 12-byte IV || AES-GCM(seed) || 16-byte tag
//   65..97 public key
//
// Deletion and backup semantics: the file and its Keystore key are deleted
// together by delete(). The file is excluded from auto-backup (see the
// manifest backup rules) because Keystore keys never survive backup or
// device migration; restoring the file without its key would only produce
// an unrecoverable error, so a restored install behaves like a fresh one
// and generates a new identity. Keystore invalidation (security update,
// device reset) makes an existing identity unrecoverable as well: the app
// reports it and the user re-pairs. Neither case regenerates a key
// silently, because a new identity would orphan every paired daemon.
class IdentityStore(private val file: File, private val cipher: SeedCipher) {
    fun loadOrCreate(): Identity {
        if (!file.exists()) return create()
        val (seed, publicKey) = parse(file.readBytes())
        return try {
            Identity(seed, publicKey)
        } catch (e: IllegalArgumentException) {
            throw IdentityStoreException("identity file is corrupted: public key does not match seed", e)
        }
    }

    fun delete() {
        if (file.exists() && !file.delete()) throw IdentityStoreException("cannot delete identity file")
        File(file.parentFile, file.name + TMP_SUFFIX).delete()
        cipher.delete()
    }

    private fun create(): Identity {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val publicKey = X25519.pub(seed)
        val wrapped = cipher.wrap(seed)
        val buf = ByteArray(FILE_SIZE)
        System.arraycopy(MAGIC, 0, buf, 0, MAGIC.size)
        buf[MAGIC.size] = VERSION.toByte()
        System.arraycopy(wrapped, 0, buf, WRAPPED_OFFSET, wrapped.size)
        System.arraycopy(publicKey, 0, buf, PUB_OFFSET, publicKey.size)
        writeAtomic(buf)
        return Identity(seed, publicKey)
    }

    private fun parse(bytes: ByteArray): Pair<ByteArray, ByteArray> {
        if (bytes.size != FILE_SIZE) throw IdentityStoreException("identity file has wrong size")
        if (!bytes.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) {
            throw IdentityStoreException("identity file has bad magic")
        }
        if (bytes[MAGIC.size].toInt() != VERSION) {
            throw IdentityStoreException("identity file has unsupported version")
        }
        val wrapped = bytes.copyOfRange(WRAPPED_OFFSET, WRAPPED_OFFSET + WRAPPED_LEN)
        val seed = try {
            cipher.unwrap(wrapped)
        } catch (e: Exception) {
            throw IdentityStoreException("cannot unwrap identity seed; keystore key may be invalid", e)
        }
        if (seed.size != 32) throw IdentityStoreException("identity file has bad seed length")
        return seed to bytes.copyOfRange(PUB_OFFSET, PUB_OFFSET + 32)
    }

    private fun writeAtomic(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                throw IdentityStoreException("cannot replace identity file")
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is IdentityStoreException) throw e
            throw IdentityStoreException("cannot write identity file", e)
        }
    }

    companion object {
        const val FILE_NAME = "remotly-identity.bin"

        private const val TMP_SUFFIX = ".tmp"
        private const val VERSION = 1
        private const val WRAPPED_OFFSET = 5
        private const val WRAPPED_LEN = 60
        private const val PUB_OFFSET = 65
        private const val FILE_SIZE = 97
        private val MAGIC = byteArrayOf('R'.code.toByte(), 'M'.code.toByte(), 'I'.code.toByte(), '1'.code.toByte())

        fun create(context: Context): IdentityStore =
            IdentityStore(File(context.filesDir, FILE_NAME), KeystoreSeedCipher())
    }
}
