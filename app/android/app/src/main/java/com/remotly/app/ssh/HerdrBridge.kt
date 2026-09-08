package com.remotly.app.ssh

import com.remotly.app.util.Base64Std
import java.util.concurrent.Executors
import sshcore.Exec
import sshcore.ExecConfig
import sshcore.ExecListener
import sshcore.ExecResult

// Runs one herdr command over a fresh SSH connection to a stored host and
// returns its stdout, stderr, and exit code.
//
// This is the one-shot twin of the terminal and SFTP paths: a herdr control
// call (session list, workspace create, pane read) is a command that finishes
// and disconnects, so it cannot share a long-lived session. The host-key
// policy is fail-closed rather than prompt-and-wait, because a one-shot call
// has no UI loop to park in: the presented key is checked against the keys
// already accepted for the host, and anything that is not known is refused.
// The command is a fully quoted command line built by the caller, which is
// what carries any herdr --session flag.
object HerdrBridge {

    // The outcome of one remote command. Exit code, stdout, and stderr are
    // meaningful when code is empty; a non-empty code is a connect-level
    // failure (the same SshCode vocabulary the terminal reports).
    data class Outcome(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray,
        val code: String = "",
        val message: String = "",
    ) {
        val ok: Boolean get() = code.isEmpty()
    }

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "remotly-herdr-exec").apply { isDaemon = true }
    }

    // Connect timeout for a control call: long enough to absorb a slow dial,
    // short enough that a dead host does not pin a worker for a minute.
    private const val CONNECT_TIMEOUT_MS = 10_000L
    // Command bound: herdr control calls return in milliseconds; this exists
    // to release the worker if the remote hangs.
    private const val COMMAND_TIMEOUT_MS = 30_000L

    // Runs a blocking exec on the worker pool, delivering the outcome or an
    // exception on that pool thread. Bridge methods complete their promise
    // from the returned callback.
    fun <T> execute(onResult: (Result<T>) -> Unit, block: () -> T) {
        executor.execute {
            try {
                onResult(Result.success(block()))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    /**
     * Runs [command] on [hostId] over a fresh SSH connection and returns the
     * combined result. The command must already be a fully quoted command
     * line; nothing here re-quotes or interprets it.
     */
    fun exec(hostId: String, command: String): Outcome {
        val store = SshModule.store ?: throw SshHostStoreException("ssh store unavailable")
        val host = store.get(hostId) ?: throw SshHostStoreException("no such host: $hostId")
        val credential = store.credential(hostId)
        val known = host.knownKeys

        lateinit var exec: Exec
        val listener = object : ExecListener {
            override fun onHostKey(algorithm: String, fingerprint: String) {
                val verdict = HostKeyVerifier.verify(
                    known,
                    HostKeyInfo(host.host, host.port, algorithm, fingerprint),
                )
                // A one-shot call cannot surface a first-use or changed-key
                // prompt, so only a key already accepted for this host
                // proceeds. New and Changed both fail closed.
                when (verdict) {
                    is HostKeyVerdict.Known -> exec.decideHostKey(true)
                    else -> exec.decideHostKey(false)
                }
            }
        }
        exec = Exec(listener)

        val cfg = ExecConfig()
        cfg.host = host.host
        cfg.port = host.port.toLong()
        cfg.user = host.username
        when (val cred = credential) {
            is SshCredential.Password -> cfg.password = String(cred.value, Charsets.UTF_8)
            is SshCredential.Key -> {
                cfg.privateKey = cred.privateKey
                cfg.passphrase = cred.passphrase ?: ByteArray(0)
            }
        }
        cfg.command = command
        cfg.connectTimeout = CONNECT_TIMEOUT_MS
        cfg.commandTimeout = COMMAND_TIMEOUT_MS

        try {
            val r: ExecResult = exec.run(cfg)
            return Outcome(
                exitCode = r.exitCode.toInt(),
                stdout = r.stdout ?: ByteArray(0),
                stderr = r.stderr ?: ByteArray(0),
                code = r.code ?: "",
                message = r.message ?: "",
            )
        } finally {
            exec.close()
        }
    }

    // The base64 helper is used by the bridge module to encode the raw stdout
    // before it crosses the bridge; named here to keep the encode site obvious.
    internal fun encodeB64(bytes: ByteArray): String = Base64Std.encode(bytes)
}
