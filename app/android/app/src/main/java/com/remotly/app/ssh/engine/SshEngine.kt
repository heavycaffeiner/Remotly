package com.remotly.app.ssh.engine

import com.remotly.app.ssh.CloseCode
import com.remotly.app.ssh.SshListener
import com.remotly.app.ssh.SshSpec

// One live SSH terminal connection: connect, host-key decision, PTY write and
// resize, close. Implementations own the transport details; the orchestration
// (SshSession) depends only on this surface. The shipped implementation is the
// Go sshcore .aar (GoSshEngine).
interface SshEngine {
    // Starts the connection. Returns immediately; the outcome is reported
    // through the listener.
    fun connect(spec: SshSpec)

    // Writes terminal input to the remote PTY.
    fun write(data: ByteArray)

    // Sends a PTY window-change.
    fun resize(cols: Int, rows: Int)

    // The app's answer to a host-key challenge. Safe to call from any thread.
    fun decideHostKey(accept: Boolean)

    // Closes the connection. Idempotent; reports onClosed exactly once.
    fun close(code: Int = CloseCode.NORMAL, reason: String = "closed")
}

// Creates the engine for a session. Set once by the app shell (see SshModule);
// the factory keeps SshSession free of any engine implementation type.
fun interface SshEngineFactory {
    fun create(listener: SshListener): SshEngine
}
