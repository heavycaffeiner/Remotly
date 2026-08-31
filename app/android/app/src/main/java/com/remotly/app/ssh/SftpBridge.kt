package com.remotly.app.ssh

import com.remotly.app.ssh.engine.SftpConnection
import com.remotly.app.ssh.engine.SftpOps
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// Manages live SFTP connections for the browser, one per host id. The
// host-key decision reuses the shared host-key store and verifier: a previously
// approved key is accepted without a prompt, while a new or changed key is
// surfaced to the caller, which answers it through [decideHostKey]. Metadata
// and transfer operations delegate to the SftpOps primitives, which block on a
// network round trip and so run on this object's worker pool.
object SftpBridge {

    enum class State { CONNECTING, HOST_KEY, READY, FAILED }

    // A new or changed host key awaiting the caller's decision. changed is
    // true when a key was previously recorded and no longer matches.
    data class HostKeyPrompt(val info: HostKeyInfo, val changed: Boolean)

    class Active(val hostId: String, val conn: SftpConnection?) {
        @Volatile
        var state: State = State.CONNECTING
        @Volatile
        var prompt: HostKeyPrompt? = null
        @Volatile
        var code: String = ""
        @Volatile
        var message: String = ""
    }

    private val active = ConcurrentHashMap<String, Active>()
    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "remotly-sftp-op").apply { isDaemon = true }
    }

    // Runs a blocking SFTP operation on the worker pool, delivering the result
    // or exception on that pool thread. Bridge methods complete their
    // CompletionBlock from the returned callback.
    fun <T> execute(onResult: (Result<T>) -> Unit, block: () -> T) {
        executor.execute {
            try {
                onResult(Result.success(block()))
            } catch (e: Exception) {
                onResult(Result.failure(e))
            }
        }
    }

    // Starts the connection for a stored host. Returns immediately; poll
    // [status] for the outcome. A new or changed host key pauses the connect in
    // the HOST_KEY state until [decideHostKey] is called.
    fun connect(hostId: String) {
        val store = SshModule.store ?: throw SshHostStoreException("ssh store unavailable")
        val factory = SshModule.sftpConnectionFactory ?: throw SshHostStoreException("ssh engine unavailable")
        val host = store.get(hostId) ?: throw SshHostStoreException("no such host: $hostId")
        // A connection already serving a transfer is left alone. Reopening it
        // tears down the channel the transfer is reading, which is what made a
        // download die when the file browser was reopened for the same host.
        val existing = active[hostId]
        if (existing?.state == State.READY && SftpTransfers.hasActiveForHost(hostId)) {
            return
        }
        closeActive(hostId)

        var act: Active? = null
        val conn = factory.create { info -> onHostKey(act, info) }
        val created = Active(hostId, conn)
        act = created
        active[hostId] = created
        executor.execute {
            try {
                val credential = store.credential(hostId)
                when (val result = conn.connect(host, credential)) {
                    is SftpConnectResult.Ready -> created.state = State.READY
                    is SftpConnectResult.Failure -> {
                        created.code = result.code
                        created.message = result.message
                        created.state = State.FAILED
                    }
                }
            } catch (e: Exception) {
                created.code = mapSshError(e)
                created.message = e.message ?: "sftp connect failed"
                created.state = State.FAILED
            }
        }
    }

    private fun onHostKey(act: Active?, info: HostKeyInfo) {
        if (act == null) return
        val store = SshModule.store
        val known = store?.get(act.hostId)?.knownKeys ?: emptyList()
        when (val verdict = HostKeyVerifier.verify(known, info)) {
            is HostKeyVerdict.Known -> act.conn?.decideHostKey(true)
            is HostKeyVerdict.New -> {
                act.prompt = HostKeyPrompt(info, false)
                act.state = State.HOST_KEY
            }
            is HostKeyVerdict.Changed -> {
                act.prompt = HostKeyPrompt(info, true)
                act.state = State.HOST_KEY
            }
        }
    }

    // Answers a host-key prompt. Accepting records the presented key (a Changed
    // verdict replaces the whole accepted set); rejecting tears the connect down.
    fun decideHostKey(hostId: String, accept: Boolean) {
        val act = active[hostId] ?: return
        val prompt = act.prompt ?: return
        if (accept) {
            val store = SshModule.store ?: return
            val key = KnownHostKey(prompt.info.algorithm, prompt.info.fingerprint)
            if (prompt.changed) {
                store.replaceHostKeys(hostId, key)
            } else {
                store.acceptHostKey(hostId, key)
            }
        }
        act.conn?.decideHostKey(accept)
    }

    fun status(hostId: String): Active? = active[hostId]

    // Metadata operations. Each throws if there is no ready session for the
    // host; the exception message is the user-facing failure.
    fun list(hostId: String, path: String): List<SftpEntry> = op(hostId) { it.list(path) }

    fun stat(hostId: String, path: String): SftpEntry = op(hostId) { it.stat(path) }

    fun mkdir(hostId: String, path: String) {
        op(hostId) { it.mkdir(path) }
    }

    fun rename(hostId: String, from: String, to: String) {
        op(hostId) { it.rename(from, to) }
    }

    fun removeFile(hostId: String, path: String) {
        op(hostId) { it.removeFile(path) }
    }

    fun removeDir(hostId: String, path: String) {
        op(hostId) { it.removeDir(path) }
    }

    // Transfers. Both return an id immediately and run on the transfer pool;
    // downloads stream through the sink set by the bridge module.
    fun startUpload(hostId: String, path: String, replace: Boolean): String =
        op(hostId) { SftpTransfers.startUpload(it, hostId, path, replace) }

    /** Reopens a partial upload and continues after what already arrived. */
    fun startUploadResume(hostId: String, path: String): String =
        op(hostId) {
            SftpTransfers.startUpload(it, hostId, path, replace = false, resume = true)
        }

    fun startDownload(hostId: String, path: String): String =
        op(hostId) { SftpTransfers.startDownload(it, hostId, path) }

    /** Streams a download into a content URI, without routing bytes through JS. */
    fun startDownloadToUri(
        hostId: String,
        path: String,
        context: android.content.Context,
        uri: android.net.Uri,
        resumeFrom: Long,
    ): String = op(hostId) {
        SftpTransfers.startDownloadToUri(it, hostId, path, context, uri, resumeFrom)
    }

    fun close(hostId: String) {
        // Kept open while a transfer is still using it. A download runs in the
        // background and outlives the screen that started it, so closing here
        // on navigation away would cut it off partway.
        if (SftpTransfers.hasActiveForHost(hostId)) return
        closeActive(hostId)
    }

    private fun closeActive(hostId: String) {
        // Transfers hold this connection's channels; dropping it under them
        // would leave their threads blocked on a dead socket. Only this host's
        // transfers are cancelled: this runs on every connect, and cancelling
        // globally killed a download running against another host the moment a
        // second SFTP tab was opened.
        SftpTransfers.cancelForHost(hostId)
        active.remove(hostId)?.let {
            try {
                it.conn?.close()
            } catch (_: Exception) {
            }
        }
    }

    private inline fun <T> op(hostId: String, block: (SftpOps) -> T): T {
        val act = active[hostId] ?: throw SshHostStoreException("no sftp session: $hostId")
        if (act.state != State.READY) {
            throw SshHostStoreException("sftp not ready: $hostId")
        }
        val conn = act.conn ?: throw SshHostStoreException("no sftp connection: $hostId")
        return block(conn.sftp)
    }
}
