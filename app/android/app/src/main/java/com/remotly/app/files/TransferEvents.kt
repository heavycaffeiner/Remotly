package com.remotly.app.files

import com.remotly.app.ssh.SftpTransfers
import java.util.concurrent.ConcurrentHashMap

/**
 * Fans [SftpTransfers]' single global sink out to per-id listeners.
 *
 * SftpTransfers takes exactly one sink at a time, so every feature that wants
 * transfer events routes through this dispatcher rather than replacing the
 * sink under one another. It claims the sink once, lazily, the first time a
 * transfer needs watching, and never gives it up. Unknown ids are ignored,
 * which is the same silence a transfer nobody is watching had before this
 * existed.
 */
internal object TransferEvents : SftpTransfers.Sink {
    private val listeners = ConcurrentHashMap<String, (transferred: Long, done: Boolean, error: String?) -> Unit>()
    @Volatile private var installed = false

    fun watch(id: String, onEvent: (transferred: Long, done: Boolean, error: String?) -> Unit) {
        listeners[id] = onEvent
        if (!installed) {
            synchronized(this) {
                if (!installed) {
                    SftpTransfers.setSink(this)
                    installed = true
                }
            }
        }
    }

    fun stopWatching(id: String) {
        listeners.remove(id)
    }

    override fun onEvent(id: String, offset: Long, data: ByteArray?, done: Long?, error: String?) {
        val listener = listeners[id] ?: return
        when {
            error != null -> {
                listeners.remove(id)
                listener(offset, false, error)
            }
            done != null -> {
                listeners.remove(id)
                listener(done, true, null)
            }
            else -> listener(offset, false, null)
        }
    }
}
