package com.remotly.app.session

import com.remotly.app.ssh.SshHub
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Process-wide SSH terminal sessions.
//
// Sessions outlive the screen that opened them. Navigating back leaves them
// running, and only closing a tab or closing the host ends one. That is why
// this is a singleton object rather than state owned by a screen or a
// ViewModel scoped to one: a ViewModel is cleared when its screen is popped,
// which is exactly the moment a live shell must not be killed.
//
// Output bytes are not routed here. SshHub writes every session's output
// straight into TerminalStore's own retained terminal, keyed by the bare
// session id, whether or not a screen is showing it; the terminal is the
// buffer, and a screen that attaches to render simply redraws from what it
// already holds. There is therefore no separate byte queue to keep in order
// in this file, only the connection phase each tab is in.

/** The transport this store drives. SshHubTransport below is the production wiring. */
interface SshTransport {
    fun connect(hostId: String, sessionId: String, cols: Int, rows: Int)
    fun write(hostId: String, sessionId: String, data: ByteArray)
    fun resize(hostId: String, sessionId: String, cols: Int, rows: Int)
    fun hostKey(hostId: String, sessionId: String, decision: String)
    fun close(hostId: String, sessionId: String)
    fun closeHost(hostId: String)
    fun setEventSink(hostId: String, sink: ((name: String, data: Map<String, Any?>) -> Unit)?)
}

/** Wires the store to the real SSH stack. A test substitutes its own [SshTransport] instead. */
object SshHubTransport : SshTransport {
    override fun connect(hostId: String, sessionId: String, cols: Int, rows: Int) =
        SshHub.connect(hostId, sessionId, cols, rows)

    override fun write(hostId: String, sessionId: String, data: ByteArray) =
        SshHub.write(hostId, sessionId, data)

    override fun resize(hostId: String, sessionId: String, cols: Int, rows: Int) =
        SshHub.resize(hostId, sessionId, cols, rows)

    override fun hostKey(hostId: String, sessionId: String, decision: String) =
        SshHub.hostKey(hostId, sessionId, decision)

    override fun close(hostId: String, sessionId: String) = SshHub.close(hostId, sessionId)

    override fun closeHost(hostId: String) = SshHub.closeHost(hostId)

    override fun setEventSink(hostId: String, sink: ((String, Map<String, Any?>) -> Unit)?) =
        SshHub.setEventSink(hostId, sink)
}

enum class HostKeyDecision { Accept, Replace, Reject }

data class SshHostKeyPrompt(
    val sessionId: String,
    val algorithm: String,
    val fingerprint: String,
    val changed: Boolean,
)

/** Everything one host's screen needs: its tabs, a pending host-key prompt, and its viewport state. */
data class SshHostSessionState(
    val tabs: SshTabsState,
    val hostKeyPrompt: SshHostKeyPrompt? = null,
    /** False until the viewport has reported a real grid, so a session started before then knows it ran at the placeholder. */
    val sized: Boolean = false,
)

object SshSessions {

    /** Swapped for a fake in tests; production wiring is [SshHubTransport]. */
    var transport: SshTransport = SshHubTransport

    private const val DEFAULT_COLS = 80
    private const val DEFAULT_ROWS = 24

    private class HostEntry(hostId: String) {
        val flow = MutableStateFlow(SshHostSessionState(createSshTabs(hostId)))

        /** The grid every tab of this host connects and resizes at. */
        var size: Pair<Int, Int> = DEFAULT_COLS to DEFAULT_ROWS
        var seq = 0
    }

    private val hosts = ConcurrentHashMap<String, HostEntry>()

    /** Commands waiting for their shell to come up, keyed by host and session. */
    private val pendingRuns = ConcurrentHashMap<Pair<String, String>, String>()

    private fun entry(hostId: String): HostEntry = hosts.computeIfAbsent(hostId) { id ->
        val e = HostEntry(id)
        // Registered before any connect, so a failure arriving immediately is
        // never missed. One sink per host: SshHub tags every event with the
        // session it belongs to, so a single callback demultiplexes here.
        transport.setEventSink(id) { name, data -> handleEvent(id, name, data) }
        e
    }

    /** The live state for one host. Read with `collectAsStateWithLifecycle()`; never completes. */
    fun state(hostId: String): StateFlow<SshHostSessionState> = entry(hostId).flow.asStateFlow()

    fun canAddTab(hostId: String): Boolean =
        surfaceCount(entry(hostId).flow.value.tabs, SshTabKind.Shell) < MAX_SSH_TABS

    /** True once at least one tab has been opened for this host. */
    fun hostStarted(hostId: String): Boolean = entry(hostId).flow.value.tabs.tabs.isNotEmpty()

    fun hostSized(hostId: String): Boolean = entry(hostId).flow.value.sized

    /** Open tabs per host, for the hosts list. */
    fun sessionCounts(): Map<String, Int> {
        val counts = LinkedHashMap<String, Int>()
        for ((hostId, e) in hosts) {
            val n = e.flow.value.tabs.tabs.size
            if (n > 0) counts[hostId] = n
        }
        return counts
    }

    /** Opens a tab and returns its session id, or null at the cap or when the id could not be minted. */
    fun openTab(
        hostId: String,
        title: String? = null,
        runs: String? = null,
        mux: String? = null,
        muxSession: String? = null,
        kind: SshTabKind = SshTabKind.Shell,
        workspaceId: String? = null,
    ): String? {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            if (surfaceCount(current.tabs, kind) >= MAX_SSH_TABS) return null
            e.seq += 1
            val sessionId = mintSessionId(e.seq)
            val resolvedTitle = title ?: "Shell ${nextShellNumber(current.tabs.tabs)}"
            val (afterAdd, tab) = addSshTab(current.tabs, sessionId, resolvedTitle, kind)
            if (tab == null) return null
            // A given title is pinned: it names the workspace this tab attaches
            // to, and a program on the other end repaints the terminal's own
            // title on every redraw, which would otherwise replace it.
            var next = if (title != null) setSshTabTitle(afterAdd, sessionId, title, pin = true) else afterAdd
            if (mux != null || workspaceId != null) {
                next = next.copy(
                    tabs = next.tabs.map { t ->
                        if (t.sessionId != sessionId) {
                            t
                        } else {
                            t.copy(
                                mux = mux ?: t.mux,
                                muxSession = if (mux != null) muxSession ?: "" else t.muxSession,
                                workspaceId = workspaceId ?: t.workspaceId,
                            )
                        }
                    },
                )
            }
            e.flow.value = current.copy(tabs = next)
            if (!runs.isNullOrEmpty()) pendingRuns[hostId to sessionId] = runs
            startSession(hostId, sessionId, e)
            return sessionId
        }
    }

    /**
     * Opens, or reveals, the terminal attached to a herdr workspace, and
     * returns its session id.
     *
     * One terminal per herdr session, not per workspace: which workspace has
     * focus is state on the remote session rather than per client, so a
     * second terminal on the same session could only mirror the first.
     * Entering another workspace retags this one instead, which is also what
     * the user sees happen.
     */
    fun openWorkspaceTab(
        hostId: String,
        workspaceId: String,
        label: String,
        runs: String,
        session: String? = null,
    ): String? {
        val muxSession = session ?: ""
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            val live = current.tabs.tabs.firstOrNull {
                it.kind == SshTabKind.Workspace &&
                    (it.muxSession ?: "") == muxSession &&
                    (it.phase == SshTabPhase.Active || it.phase == SshTabPhase.Connecting)
            }
            if (live != null) {
                val retagged = current.tabs.copy(
                    tabs = current.tabs.tabs.map { t ->
                        if (t.sessionId == live.sessionId) t.copy(workspaceId = workspaceId, title = label) else t
                    },
                )
                e.flow.value = current.copy(tabs = retagged)
                selectTab(hostId, live.sessionId)
                return live.sessionId
            }
        }
        return openTab(
            hostId,
            title = label,
            runs = runs,
            mux = MUX_HERDR,
            muxSession = muxSession,
            kind = SshTabKind.Workspace,
            workspaceId = workspaceId,
        )
    }

    /** Renames a tab by hand. Ignores a blank name and pins the result. */
    fun renameTab(hostId: String, sessionId: String, title: String) {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            val next = setSshTabTitle(current.tabs, sessionId, title, pin = true)
            if (next === current.tabs) return
            e.flow.value = current.copy(tabs = next)
        }
    }

    /**
     * Records the title the running program set with an escape sequence.
     * Adopted only while the tab still carries its generated or last-reported
     * name; a hand-picked one is not taken back.
     */
    fun reportTerminalTitle(hostId: String, title: String) {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            val sessionId = current.tabs.activeSessionId ?: return
            val next = setSshTabTitle(current.tabs, sessionId, title)
            if (next === current.tabs) return
            e.flow.value = current.copy(tabs = next)
        }
    }

    fun selectTab(hostId: String, sessionId: String) {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            if (current.tabs.activeSessionId == sessionId) return
            if (findSshTab(current.tabs, sessionId) == null) return
            e.flow.value = current.copy(tabs = setActiveSshTab(current.tabs, sessionId))
        }
    }

    /** Closes one tab. This is the only thing that ends a session. */
    fun closeTab(hostId: String, sessionId: String) {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            if (findSshTab(current.tabs, sessionId) == null) return
            transport.close(hostId, sessionId)
            val nextPrompt = if (current.hostKeyPrompt?.sessionId == sessionId) null else current.hostKeyPrompt
            e.flow.value = current.copy(tabs = removeSshTab(current.tabs, sessionId), hostKeyPrompt = nextPrompt)
        }
    }

    /** Closes every tab for a host and forgets it. */
    fun closeHost(hostId: String) {
        val e = hosts.remove(hostId) ?: return
        synchronized(e) {
            for (tab in e.flow.value.tabs.tabs) transport.close(hostId, tab.sessionId)
            transport.setEventSink(hostId, null)
            e.flow.value = SshHostSessionState(createSshTabs(hostId))
        }
    }

    fun reconnectTab(hostId: String, sessionId: String) {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            if (findSshTab(current.tabs, sessionId) == null) return
            e.flow.value = current.copy(tabs = setSshTabPhase(current.tabs, sessionId, SshTabPhase.Connecting))
            startSession(hostId, sessionId, e)
        }
    }

    fun sendInput(hostId: String, bytes: ByteArray) {
        val e = entry(hostId)
        val sessionId = e.flow.value.tabs.activeSessionId
        if (hostId.isEmpty() || sessionId == null || bytes.isEmpty()) return
        transport.write(hostId, sessionId, bytes)
    }

    /**
     * Every open tab shares one viewport, so a resize applies to all of them.
     *
     * The viewport only mounts once a tab exists, so the first real grid
     * always arrives after that tab has already connected at the placeholder.
     * Skipping the resize on that first measurement would leave the remote
     * pty at the placeholder size for the life of the session.
     */
    fun resizeHost(hostId: String, cols: Int, rows: Int) {
        val e = entry(hostId)
        if (cols <= 0 || rows <= 0) return
        synchronized(e) {
            val current = e.flow.value
            val first = !current.sized
            e.size = cols to rows
            if (first) e.flow.value = current.copy(sized = true)
            if (hostId.isEmpty()) return
            for (tab in e.flow.value.tabs.tabs) transport.resize(hostId, tab.sessionId, cols, rows)
        }
    }

    fun answerHostKey(hostId: String, decision: HostKeyDecision) {
        val e = entry(hostId)
        synchronized(e) {
            val current = e.flow.value
            val prompt = current.hostKeyPrompt ?: return
            if (hostId.isEmpty()) return
            e.flow.value = current.copy(hostKeyPrompt = null)
            if (decision == HostKeyDecision.Reject) {
                transport.close(hostId, prompt.sessionId)
                val closed = e.flow.value
                e.flow.value = closed.copy(
                    tabs = setSshTabPhase(closed.tabs, prompt.sessionId, SshTabPhase.Closed, "The host key was rejected."),
                )
                return
            }
            transport.hostKey(hostId, prompt.sessionId, decision.name.lowercase())
        }
    }

    private fun startSession(hostId: String, sessionId: String, e: HostEntry) {
        if (hostId.isEmpty()) return
        transport.connect(hostId, sessionId, e.size.first, e.size.second)
    }

    /** Types a queued command once, when its shell reports ready. Never types it twice. */
    private fun flushPendingRun(hostId: String, sessionId: String) {
        val command = pendingRuns.remove(hostId to sessionId) ?: return
        transport.write(hostId, sessionId, "$command\n".toByteArray(Charsets.UTF_8))
    }

    /** Applies a "state" event from the transport. Any other event name is ignored: bytes are not this store's concern. */
    private fun handleEvent(hostId: String, name: String, data: Map<String, Any?>) {
        if (name != "state") return
        val sessionId = data["sessionId"] as? String ?: return
        val e = hosts[hostId] ?: return
        synchronized(e) {
            val current = e.flow.value
            val cleared = if (current.hostKeyPrompt?.sessionId == sessionId) {
                current.copy(hostKeyPrompt = null)
            } else {
                current
            }
            val next = when (data["state"] as? String) {
                "hostKey" -> cleared.copy(
                    tabs = setSshTabPhase(cleared.tabs, sessionId, SshTabPhase.HostKey),
                    hostKeyPrompt = SshHostKeyPrompt(
                        sessionId = sessionId,
                        algorithm = data["algorithm"] as? String ?: "",
                        fingerprint = data["fingerprint"] as? String ?: "",
                        changed = data["changed"] == true,
                    ),
                )
                "active" -> {
                    flushPendingRun(hostId, sessionId)
                    cleared.copy(tabs = setSshTabPhase(cleared.tabs, sessionId, SshTabPhase.Active))
                }
                "closed" -> {
                    val detail = if (data["userInitiated"] == true) {
                        "The session was closed."
                    } else {
                        "The remote host closed the session."
                    }
                    cleared.copy(tabs = setSshTabPhase(cleared.tabs, sessionId, SshTabPhase.Closed, detail))
                }
                "failed" -> {
                    val detail = (data["reason"] as? String)?.takeIf { it.isNotEmpty() } ?: "The connection failed."
                    cleared.copy(tabs = setSshTabPhase(cleared.tabs, sessionId, SshTabPhase.Failed, detail))
                }
                else -> return
            }
            e.flow.value = next
        }
    }
}
