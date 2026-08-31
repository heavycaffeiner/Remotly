package com.remotly.app.ssh

import java.net.SocketException
import java.net.UnknownHostException

// Stable error codes for the SSH bridge. The UI maps these into RemotlyError.
object SshCode {
    const val CONNECT_FAILED = "ssh_connect_failed"
    const val AUTH_FAILED = "ssh_auth_failed"
    const val HOST_KEY_REJECTED = "ssh_host_key_rejected"
    const val HOST_KEY_CHANGED = "ssh_host_key_changed"
    const val PTY_FAILED = "ssh_pty_failed"
    const val REMOTE_CLOSED = "ssh_remote_closed"
    const val CANCELLED = "ssh_cancelled"
    const val NETWORK = "ssh_network"
    const val TIMEOUT = "ssh_timeout"
    const val PROTOCOL = "ssh_protocol"
}

// One SSH connection target. host may be a literal IP or a name; port and user
// are validated by the caller (M4-02). auth carries exactly one method.
data class SshSpec(
    val host: String,
    val port: Int,
    val user: String,
    val auth: SshAuth,
    val cols: Int,
    val rows: Int,
)

// A single authentication method. Kept as a value so the caller does not
// retain plaintext after handing it over.
sealed class SshAuth {
    data class Password(val password: String) : SshAuth()

    // privateKey is PEM (PKCS#8 or OpenSSH) and may be encrypted; passphrase
    // is the decrypt passphrase (null when the key is unencrypted). Neither is
    // ever logged.
    data class Key(val privateKey: ByteArray, val passphrase: ByteArray?) : SshAuth()
}

// Server host key material surfaced before first trust (M4-02). fingerprint is
// the algorithm-qualified digest shown to the user, e.g. "SHA256:...".
data class HostKeyInfo(
    val host: String,
    val port: Int,
    val algorithm: String,
    val fingerprint: String,
)

// One filesystem entry from an SFTP listing or stat. permissions is the POSIX
// mode (the high bits carry the type); isDirectory and isSymlink are decoded
// from it so callers need not. modifyTimeMillis is epoch milliseconds, or 0 if
// the server did not report it.
data class SftpEntry(
    val name: String,
    val isDirectory: Boolean,
    val isSymlink: Boolean,
    val size: Long,
    val modifyTimeMillis: Long,
    val permissions: Int,
)

// SSH connection close codes. Local to the SSH stack (not the Remotly
// WebSocket range); the hub maps them into its own event vocabulary.
object CloseCode {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
}

// Outcome of a blocking SFTP connect.
sealed class SftpConnectResult {
    object Ready : SftpConnectResult()
    data class Failure(val code: String, val message: String) : SftpConnectResult()
}

// Converts a stored credential into a one-shot auth value. The password value
// is decoded to a String here and not retained by the caller.
internal fun SshCredential.toSshAuth(): SshAuth = when (this) {
    is SshCredential.Password -> SshAuth.Password(String(value, Charsets.UTF_8))
    is SshCredential.Key -> SshAuth.Key(privateKey, passphrase)
}

// Classifies a failure during the connect/auth phase into an SshCode. A
// host-key rejection is decided by the caller before this. Network-level
// failures (refused, timeout, unknown host) are CONNECT_FAILED; the cause
// chain and the message are checked. classify maps an engine-specific
// exception type to a code when the message check does not decide; it is
// null in trees without that engine type.
fun mapSshError(e: Exception, classify: ((Exception) -> String?)? = null): String {
    var cur: Throwable? = e
    while (cur != null) {
        if (cur is SocketException || cur is UnknownHostException) {
            return SshCode.CONNECT_FAILED
        }
        cur = cur.cause
    }
    val msg = e.message?.lowercase() ?: ""
    return when {
        msg.contains("auth") -> SshCode.AUTH_FAILED
        msg.contains("refused") || msg.contains("timeout") ||
            msg.contains("unknown host") || msg.contains("connect") ||
            msg.contains("unreachable") || msg.contains("reset") ->
            SshCode.CONNECT_FAILED
        else -> classify?.invoke(e) ?: SshCode.CONNECT_FAILED
    }
}

// Bridge listener. onHostKeyChallenge may be delivered from the SSH handshake
// thread; onTerminalData from the reader thread. Implementers must be
// thread-safe and must not block the calling thread. The host-key decision is
// returned through the engine's decideHostKey, which may be called from any
// thread.
interface SshListener {
    fun onHostKeyChallenge(info: HostKeyInfo)
    fun onReady()
    fun onTerminalData(data: ByteArray)
    fun onClosed(code: Int, reason: String)

    /**
     * A terminal failure. [stage] names the operation that failed and may be
     * empty when the engine does not report one; [code] is the stable SshCode
     * the error mapping already uses.
     */
    fun onFailure(code: String, message: String, stage: String = "")
}

// The operation that failed. A dial refusal and a rejected key exchange both
// surface as ssh_connect_failed, which is not enough to diagnose a Windows
// interoperability problem; the stage is.
object SshStage {
    const val DIAL = "ssh_dial_failed"
    const val HANDSHAKE = "ssh_handshake_failed"
    const val AUTH = "ssh_auth_failed"
    const val HOST_KEY = "ssh_host_key_rejected"
    const val CHANNEL = "ssh_channel_failed"
    const val PTY = "ssh_pty_failed"
    const val SHELL = "ssh_shell_failed"
    const val REMOTE_CLOSED = "ssh_remote_closed"
    const val TIMEOUT = "ssh_timeout"
    const val CANCELLED = "ssh_cancelled"
}
