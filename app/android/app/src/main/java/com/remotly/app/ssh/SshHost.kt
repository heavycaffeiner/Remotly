package com.remotly.app.ssh

// An accepted host key for a specific host and port. The fingerprint is the
// bridge's reported value, in the form "SHA256:<base64>"; the algorithm is
// display metadata (the same key can appear under more than one signature
// algorithm). The canonical identity of a key is the hash part of the
// fingerprint, which is what verification matches on.
data class KnownHostKey(
    val algorithm: String,
    val fingerprint: String,
)

// A stored SSH credential, held only in the keystore-backed SecretStore. The
// host record keeps an opaque reference to it, never the bytes.
sealed class SshCredential {
    data class Password(val value: ByteArray) : SshCredential() {
        override fun equals(other: Any?): Boolean =
            other is Password && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    // privateKey is the PEM-encoded private key. passphrase is null when the
    // key is unencrypted.
    data class Key(val privateKey: ByteArray, val passphrase: ByteArray?) : SshCredential() {
        override fun equals(other: Any?): Boolean {
            if (other !is Key) return false
            if (!privateKey.contentEquals(other.privateKey)) return false
            return if (passphrase == null) {
                other.passphrase == null
            } else {
                passphrase != null && passphrase.contentEquals(other.passphrase)
            }
        }

        override fun hashCode(): Int =
            31 * privateKey.contentHashCode() + (passphrase?.contentHashCode() ?: 0)
    }
}

// A plain-SSH host, distinct from a daemon HostRecord. knownKeys holds the
// accepted keys for exactly this host and port. credentialRef is an opaque
// handle into the SecretStore; it is empty only while a credential is still
// being entered.
data class SshHost(
    val id: String,
    val displayName: String,
    val host: String,
    val port: Int,
    val username: String,
    val authKind: Int,
    val credentialRef: String,
    val knownKeys: List<KnownHostKey>,
    val createdAt: Long,
    val updatedAt: Long,
) {
    companion object {
        const val AUTH_PASSWORD = 0
        const val AUTH_KEY = 1
    }
}
