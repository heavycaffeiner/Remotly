package com.remotly.app.files

import com.remotly.app.ssh.SftpTransfers

/**
 * Fans [SftpTransfers]' single global sink out to per-id listeners.
 *
 * SftpTransfers takes exactly one sink at a time, so every feature that wants
 * transfer events routes through this dispatcher rather than replacing the
 * sink under one another. It claims the sink once and never gives it up.
 *
 * A transfer starts on its own thread before the caller can watch it, and a
 * small file can finish in that gap. The terminal event is therefore kept for
 * an id nobody is watching yet and replayed when the watcher arrives, so no
 * transfer is left running forever in the registry.
 */
internal object TransferEvents : SftpTransfers.Sink {
    private class Terminal(val transferred: Long, val done: Boolean, val error: String?)

    private val listeners = HashMap<String, (transferred: Long, done: Boolean, error: String?) -> Unit>()
    private val unclaimed = HashMap<String, Terminal>()
    @Volatile private var installed = false

    /** Claims the sink. Called before a transfer starts, so no event predates it. */
    fun install() {
        if (installed) return
        synchronized(this) {
            if (!installed) {
                SftpTransfers.setSink(this)
                installed = true
            }
        }
    }

    fun watch(id: String, onEvent: (transferred: Long, done: Boolean, error: String?) -> Unit) {
        install()
        val pending = synchronized(this) {
            val p = unclaimed.remove(id)
            if (p == null) listeners[id] = onEvent
            p
        }
        if (pending != null) onEvent(pending.transferred, pending.done, pending.error)
    }

    fun stopWatching(id: String) {
        synchronized(this) {
            listeners.remove(id)
            unclaimed.remove(id)
        }
    }

    override fun onEvent(id: String, offset: Long, data: ByteArray?, done: Long?, error: String?) {
        val terminal = error != null || done != null
        val listener = synchronized(this) {
            val l = listeners[id]
            if (l == null) {
                if (terminal) unclaimed[id] = Terminal(done ?: offset, done != null, error)
                return
            }
            if (terminal) listeners.remove(id)
            l
        }
        when {
            error != null -> listener(offset, false, error)
            done != null -> listener(done, true, null)
            else -> listener(offset, false, null)
        }
    }
}
