package com.remotly.app.ssh

import java.io.ByteArrayOutputStream

// Serializes an SshCredential into the opaque bytes kept in a SecretStore, and
// parses them back. Layout is a tag byte followed by length-prefixed (big
// endian, 4 bytes) fields. The tag selects the shape. All inputs are untrusted
// (they come back from disk), so decoding is strict and bounds-checked.
object CredentialCodec {

    fun encode(cred: SshCredential): ByteArray {
        val bos = ByteArrayOutputStream()
        when (cred) {
            is SshCredential.Password -> {
                bos.write(TAG_PASSWORD)
                putLen(bos, cred.value.size)
                bos.write(cred.value)
            }
            is SshCredential.Key -> {
                val pass = cred.passphrase ?: ByteArray(0)
                bos.write(TAG_KEY)
                putLen(bos, cred.privateKey.size)
                bos.write(cred.privateKey)
                putLen(bos, pass.size)
                bos.write(pass)
            }
        }
        return bos.toByteArray()
    }

    fun decode(bytes: ByteArray): SshCredential {
        val r = Reader(bytes)
        return when (r.u8()) {
            TAG_PASSWORD -> SshCredential.Password(r.bytes(r.u32()))
            TAG_KEY -> {
                val pem = r.bytes(r.u32())
                val passLen = r.u32()
                val pass = if (passLen == 0) null else r.bytes(passLen)
                SshCredential.Key(pem, pass)
            }
            else -> throw SecretStoreException("unknown credential tag")
        }
    }

    private fun putLen(bos: ByteArrayOutputStream, n: Int) {
        if (n < 0 || n > MAX_FIELD) throw SecretStoreException("credential field too large")
        bos.write((n ushr 24) and 0xFF)
        bos.write((n ushr 16) and 0xFF)
        bos.write((n ushr 8) and 0xFF)
        bos.write(n and 0xFF)
    }

    private class Reader(val bytes: ByteArray) {
        private var pos = 0

        fun u8(): Int {
            if (pos >= bytes.size) throw SecretStoreException("truncated credential")
            return bytes[pos++].toInt() and 0xFF
        }

        fun u32(): Int {
            var v = 0L
            repeat(4) {
                if (pos >= bytes.size) throw SecretStoreException("truncated credential")
                v = (v shl 8) or (bytes[pos++].toInt() and 0xFF).toLong()
            }
            if (v > MAX_FIELD.toLong()) throw SecretStoreException("credential field too large")
            return v.toInt()
        }

        fun bytes(n: Int): ByteArray {
            if (n < 0 || pos + n > bytes.size) throw SecretStoreException("truncated credential")
            val out = bytes.copyOfRange(pos, pos + n)
            pos += n
            return out
        }
    }

    private const val TAG_PASSWORD = 0x01
    private const val TAG_KEY = 0x02
    private const val MAX_FIELD = 16 * 1024 * 1024
}
