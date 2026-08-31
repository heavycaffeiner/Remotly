package com.remotly.app.ssh.engine.go

import com.remotly.app.ssh.HostKeyInfo
import com.remotly.app.ssh.SshAuth
import com.remotly.app.ssh.SshCode
import com.remotly.app.ssh.SshListener
import com.remotly.app.ssh.SshSpec
import com.remotly.app.ssh.engine.SshEngine
import com.remotly.app.ssh.SshStage
import sshcore.Config
import sshcore.Listener
import sshcore.Session
import sshcore.StageListener

// Kotlin adapter over the Go sshcore Session. Implements the SshEngine seam
// (consumed by SshSession) and the Go Listener (called back from the Go worker
// goroutine). The engine holds no state of its own beyond the last spec, which
// supplies the host/port the Go host-key callback does not carry.
class GoSshEngine(private val listener: SshListener) : SshEngine, Listener, StageListener {

    private val session = Session(this)
    @Volatile
    private var spec: SshSpec? = null

    override fun connect(spec: SshSpec) {
        this.spec = spec
        val cfg = Config()
        cfg.host = spec.host
        cfg.port = spec.port.toLong()
        cfg.user = spec.user
        when (val auth = spec.auth) {
            is SshAuth.Password -> cfg.password = auth.password
            is SshAuth.Key -> {
                cfg.privateKey = auth.privateKey
                cfg.passphrase = auth.passphrase ?: ByteArray(0)
            }
        }
        cfg.cols = spec.cols.toLong()
        cfg.rows = spec.rows.toLong()
        try {
            // Returns immediately; the handshake runs on a Go goroutine and the
            // outcome arrives through this Listener.
            session.connect(cfg)
        } catch (e: Exception) {
            // Config rejected before any I/O, so the goroutine never started and
            // no Go callback will fire. Report it directly.
            listener.onFailure(SshCode.CONNECT_FAILED, e.message ?: "connect failed")
        }
    }

    override fun write(data: ByteArray) {
        session.write(data)
    }

    override fun resize(cols: Int, rows: Int) {
        session.windowChange(cols.toLong(), rows.toLong())
    }

    override fun decideHostKey(accept: Boolean) {
        session.decideHostKey(accept)
    }

    override fun close(code: Int, reason: String) {
        // Go closes idempotently and reports onClosed exactly once with its own
        // code and reason; the Kotlin-side code/reason are not forwarded.
        session.close()
    }

    // sshcore.Listener. Every callback arrives on the Go worker goroutine.

    override fun onHostKey(algorithm: String, fingerprint: String) {
        val s = spec
        val info = HostKeyInfo(
            host = s?.host.orEmpty(),
            port = s?.port ?: 0,
            algorithm = algorithm,
            fingerprint = fingerprint,
        )
        listener.onHostKeyChallenge(info)
    }

    override fun onReady() {
        listener.onReady()
    }

    override fun onData(data: ByteArray) {
        listener.onTerminalData(data)
    }

    override fun onClosed(code: Long, reason: String) {
        listener.onClosed(code.toInt(), reason)
    }

    override fun onFailure(code: String, message: String) {
        listener.onFailure(code, message)
    }

    // The Go core prefers this overload, so a failure carries the stage that
    // produced it as well as the stable code.
    override fun onFailureStage(code: String, stage: String, message: String) {
        listener.onFailure(code, message, stage)
    }
}
