package com.remotly.app.ssh

import com.remotly.app.util.Base64Std
import java.util.concurrent.Executors
import sshcore.Control
import sshcore.ControlLines
import sshcore.ExecConfig
import sshcore.ExecListener
import sshcore.ExecResult

// Runs herdr commands on a stored host over one held SSH connection, and
// carries the event subscription that keeps the app's view of a host current.
//
// A herdr control call is a command that prints one document and exits, but
// that does not mean it needs its own connection: the handshake was most of
// what a call from a phone cost. One authenticated connection per host is kept
// and every command runs as a channel on it. A connection that broke is
// dropped and redialled once, which is what a resumed app or a moved network
// looks like.
//
// The host-key policy is fail-closed rather than prompt-and-wait, because a
// control call has no UI loop to park in: the presented key is checked against
// the keys already accepted for the host, and anything else is refused.
//
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

    private val lock = Any()
    private val held = HashMap<String, Control>()

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
     * Runs [command] on [hostId] and returns the combined result. The command
     * must already be a fully quoted command line; nothing here re-quotes or
     * interprets it.
     *
     * A connect-level failure on a held connection is retried once on a fresh
     * one, since the likeliest cause is that the connection went away while
     * the app was not using it.
     */
    fun exec(hostId: String, command: String): Outcome {
        val first = connection(hostId, reuse = true)
        val outcome = first.control?.let { run(it, command) } ?: first.asOutcome()
        if (outcome.ok || !retryable(outcome.code)) return outcome
        val second = connection(hostId, reuse = false)
        return second.control?.let { run(it, command) } ?: second.asOutcome()
    }

    /**
     * Starts [command] on [hostId] and reports its stdout line by line.
     *
     * The command is expected to keep printing: this is what carries herdr's
     * event subscription. [onClosed] fires exactly once, with an empty code
     * when the command ended on its own.
     */
    fun subscribe(
        hostId: String,
        command: String,
        onLine: (String) -> Unit,
        onClosed: (String, String) -> Unit,
    ) {
        val sink = object : ControlLines {
            override fun onLine(line: String) = onLine(line)
            override fun onClosed(code: String, message: String) = onClosed(code, message)
        }
        val dialed = connection(hostId, reuse = true)
        val control = dialed.control
        if (control == null) {
            onClosed(dialed.code, dialed.message)
            return
        }
        val started = control.stream(command, sink)
        val code = started.code ?: ""
        if (code.isEmpty()) return
        // A held connection that will not open a channel is finished; the
        // caller resubscribes, which redials.
        drop(hostId)
        onClosed(code, started.message ?: "")
    }

    /** Closes the connection to [hostId] and everything running on it. */
    fun release(hostId: String) {
        drop(hostId)
    }

    /** Closes every held connection, for an app going to the background. */
    fun releaseAll() {
        val all = synchronized(lock) {
            val copy = held.values.toList()
            held.clear()
            copy
        }
        all.forEach { it.close() }
    }

    private fun run(control: Control, command: String): Outcome {
        val r: ExecResult = control.run(command, COMMAND_TIMEOUT_MS)
        return Outcome(
            exitCode = r.exitCode.toInt(),
            stdout = r.stdout ?: ByteArray(0),
            stderr = r.stderr ?: ByteArray(0),
            code = r.code ?: "",
            message = r.message ?: "",
        )
    }

    // A broken or missing connection is worth one redial; a refused key or a
    // bad credential is not, because redialling would only repeat it.
    private fun retryable(code: String): Boolean =
        code == SshCode.REMOTE_CLOSED || code == SshCode.NETWORK || code == SshCode.CONNECT_FAILED

    private fun drop(hostId: String) {
        val control = synchronized(lock) { held.remove(hostId) }
        control?.close()
    }

    // What a dial produced: a live connection, or the connect code that
    // stopped it. Reported as a value rather than thrown, because the JS layer
    // maps a connect code to a user-facing failure and a host that is simply
    // down is not an app fault.
    private class Dialed(
        val control: Control?,
        val code: String = "",
        val message: String = "",
    ) {
        fun asOutcome(): Outcome =
            Outcome(exitCode = 0, stdout = ByteArray(0), stderr = ByteArray(0), code = code, message = message)
    }

    /**
     * The connection for [hostId], dialling one when there is none or when
     * [reuse] is false.
     */
    private fun connection(hostId: String, reuse: Boolean): Dialed {
        if (reuse) {
            val existing = synchronized(lock) { held[hostId] }
            if (existing != null) return Dialed(existing)
        } else {
            drop(hostId)
        }

        val store = SshModule.store ?: throw SshHostStoreException("ssh store unavailable")
        val host = store.get(hostId) ?: throw SshHostStoreException("no such host: $hostId")
        val credential = store.credential(hostId)
        val known = host.knownKeys

        lateinit var control: Control
        control = Control(
            object : ExecListener {
                override fun onHostKey(algorithm: String, fingerprint: String) {
                    val verdict = HostKeyVerifier.verify(
                        known,
                        HostKeyInfo(host.host, host.port, algorithm, fingerprint),
                    )
                    // A control call cannot surface a first-use or changed-key
                    // prompt, so only a key already accepted for this host
                    // proceeds. New and Changed both fail closed.
                    control.decideHostKey(verdict is HostKeyVerdict.Known)
                }
            },
        )

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
        cfg.connectTimeout = CONNECT_TIMEOUT_MS

        val connected = control.connect(cfg)
        val code = connected.code ?: ""
        if (code.isNotEmpty()) {
            control.close()
            return Dialed(null, code, connected.message ?: "")
        }

        val kept = synchronized(lock) {
            val raced = held[hostId]
            if (raced != null) raced else { held[hostId] = control; control }
        }
        if (kept !== control) control.close()
        return Dialed(kept)
    }

    // The base64 helper is used by the bridge module to encode the raw stdout
    // before it crosses the bridge; named here to keep the encode site obvious.
    internal fun encodeB64(bytes: ByteArray): String = Base64Std.encode(bytes)
}
