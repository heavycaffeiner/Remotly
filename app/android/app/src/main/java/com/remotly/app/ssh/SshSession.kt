package com.remotly.app.ssh

import com.remotly.app.ssh.engine.SshEngine
import com.remotly.app.ssh.engine.SshEngineFactory
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

// Session lifecycle states exposed to the UI. Connecting covers the transport
// and credential exchange. HostKey is entered only when the presented key is
// not already accepted (new or changed) and blocks until the user decides.
// Closed is a clean termination; Failed is a connection or authentication
// failure and carries the bridge's SshCode for error mapping.
sealed class SshSessionState {
    object Disconnected : SshSessionState()
    object Connecting : SshSessionState()
    data class HostKey(val info: HostKeyInfo, val verdict: HostKeyVerdict) : SshSessionState()
    object Active : SshSessionState()
    data class Closed(val code: Int, val reason: String, val userInitiated: Boolean) : SshSessionState()
    data class Failed(
        val code: String,
        val reason: String,
        /** The operation that failed. Empty when the engine reports none. */
        val stage: String = "",
    ) : SshSessionState()
}

// Orchestrates one live SSH terminal: connects with the M4-02 credential, runs
// the host-key verification flow (auto-accept known, await approval for new,
// reject changed by default), streams terminal bytes, and exposes close/retry.
// A single session is one remote session; a retry is a new session. All
// callbacks (onState, onTerminal) are delivered on one executor thread.
class SshSession(
    private val host: SshHost,
    private val credential: SshCredential,
    private val store: SshHostStore,
    private val engineFactory: SshEngineFactory,
    private val onState: (SshSessionState) -> Unit,
    private val onTerminal: (ByteArray) -> Unit,
) {

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "ssh-session").apply { isDaemon = true } }
    // Written in start() on the caller's thread and read from write()/resize()
    // on bridge threads. Without volatile there is no happens-before between
    // those, so a reader can see a stale null and silently drop input; the
    // weakly-ordered ARM cores in most phones make that observable where x86
    // hides it.
    @Volatile private var engine: SshEngine? = null
    @Volatile private var pendingInfo: HostKeyInfo? = null
    @Volatile private var started = false
    @Volatile private var terminal = false
    @Volatile private var released = false

    // Begins the connection. No-op if already started.
    fun start(cols: Int, rows: Int) {
        if (started) return
        started = true
        setState(SshSessionState.Connecting)
        val b = engineFactory.create(object : SshListener {
            override fun onHostKeyChallenge(info: HostKeyInfo) {
                val known = store.get(host.id)?.knownKeys ?: emptyList()
                val verdict = HostKeyVerifier.verify(known, info)
                if (verdict is HostKeyVerdict.Known) {
                    this@SshSession.engine?.decideHostKey(true)
                } else {
                    pendingInfo = info
                    dispatch { onState(SshSessionState.HostKey(info, verdict)) }
                }
            }

            override fun onReady() = setState(SshSessionState.Active)

            override fun onTerminalData(data: ByteArray) = dispatch { onTerminal(data) }

            override fun onClosed(code: Int, reason: String) {
                terminal = true
                setState(SshSessionState.Closed(code, reason, code == CloseCode.GOING_AWAY))
            }

            override fun onFailure(code: String, message: String, stage: String) {
                terminal = true
                setState(SshSessionState.Failed(code, message, stage))
            }
        })
        engine = b
        val spec = SshSpec(host.host, host.port, host.username, credential.toSshAuth(), cols, rows)
        b.connect(spec)
    }

    fun write(bytes: ByteArray) {
        engine?.write(bytes)
    }

    fun resize(cols: Int, rows: Int) {
        engine?.resize(cols, rows)
    }

    // Approves a first-use (NEW) key after explicit user approval, persists it,
    // and unblocks the connection.
    fun acceptNewHostKey() {
        val info = pendingInfo ?: return
        pendingInfo = null
        store.acceptHostKey(host.id, KnownHostKey(info.algorithm, info.fingerprint))
        engine?.decideHostKey(true)
    }

    // Intentionally replaces a changed key with the presented one.
    fun replaceChangedHostKey() {
        val info = pendingInfo ?: return
        pendingInfo = null
        store.replaceHostKeys(host.id, KnownHostKey(info.algorithm, info.fingerprint))
        engine?.decideHostKey(true)
    }

    // Declines the presented key (NEW not approved, or CHANGED).
    fun rejectHostKey() {
        pendingInfo = null
        engine?.decideHostKey(false)
    }

    // Closes the session. If a host-key decision is pending, it is rejected so
    // the connection proceeds to close instead of hanging.
    fun close() {
        val b = engine ?: return
        if (pendingInfo != null) {
            pendingInfo = null
            b.decideHostKey(false)
        }
        b.close(CloseCode.GOING_AWAY, "user close")
    }

    // Releases the executor. The session is single-use; do not start after
    // this. Engine callbacks can arrive after release wins the race; those
    // are dropped by design.
    fun release() {
        released = true
        exec.shutdown()
        // A close/failure callback is dispatched by this executor. Waiting
        // for the executor from its own worker would stall terminal teardown
        // for two seconds and can delay foreground-service shutdown.
        if (Thread.currentThread().name == "ssh-session") return
        try {
            exec.awaitTermination(2, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private fun setState(state: SshSessionState) {
        dispatch { onState(state) }
    }

    private fun dispatch(r: Runnable) {
        if (released) return
        try {
            exec.execute(r)
        } catch (_: RejectedExecutionException) {
            // release() won the race; late events are dropped by design.
        }
    }
}
