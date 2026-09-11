package com.remotly.app.herdr

import android.util.Log
import com.remotly.app.ssh.HerdrBridge
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

// What the app knows about a host's herdr session, kept current by events.
//
// One entry per (host, session). A screen collects its StateFlow and joins
// with subscribe(); the store holds the event stream, applies what arrives,
// and re-reads a snapshot when an event cannot be applied exactly. A host
// whose reader never acknowledged falls back to re-reading on a timer, which
// is the only mode the app had before event support existed.
//
// State is a value, replaced on change, so a screen renders from StateFlow
// without a manual diff.

/** How a host's entry is being kept current. */
enum class HerdrFeed { STARTING, LIVE, POLLING }

data class HerdrHostState(
    /** Ordered by herdr's own number, which is what a move follows. */
    val workspaces: List<HerdrWorkspace> = emptyList(),
    /** Tabs of every workspace, keyed by workspace id, in place order. */
    val tabs: Map<String, List<HerdrTab>> = emptyMap(),
    val focusedWorkspaceId: String? = null,
    val focusedTabId: String? = null,
    val feed: HerdrFeed = HerdrFeed.STARTING,
    /** The last failure, for a screen that has nothing else to show. */
    val error: String? = null,
    /** True once a snapshot has been installed. */
    val loaded: Boolean = false,
)

/** The streaming slice of the transport HerdrStore needs, so a test can fake it. */
interface HerdrEventTransport {
    fun subscribe(
        hostId: String,
        command: String,
        onLine: (String) -> Unit,
        onClosed: (code: String, message: String) -> Unit,
    )
    fun release(hostId: String)
}

private object DefaultHerdrEventTransport : HerdrEventTransport {
    override fun subscribe(
        hostId: String,
        command: String,
        onLine: (String) -> Unit,
        onClosed: (code: String, message: String) -> Unit,
    ) {
        HerdrBridge.subscribe(hostId, command, onLine, onClosed)
    }

    override fun release(hostId: String) {
        HerdrBridge.release(hostId)
    }
}

/**
 * Keeps every subscribed host's herdr state current.
 *
 * [foregrounded] reports whether the app is in front. There is no
 * `androidx.lifecycle:lifecycle-process` dependency on this module's
 * classpath (only `lifecycle-runtime-compose` and `lifecycle-viewmodel-compose`
 * are declared, neither of which carries `ProcessLifecycleOwner`), so the
 * caller supplies the flow instead of this class reaching for
 * `ProcessLifecycleOwner` itself.
 */
class HerdrStore(
    private val client: HerdrClient = HerdrClient(),
    private val events: HerdrEventTransport = DefaultHerdrEventTransport,
    private val foregrounded: Flow<Boolean>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    /** How often a host without a working reader is re-read. */
    private val pollMs = 4_000L

    /** How long to wait for the subscription acknowledgement before polling. */
    private val ackTimeoutMs = 4_000L

    /** Polls between attempts at the stream, so a retry is bounded. */
    private val streamRetryPolls = 5

    /**
     * How often a live host is re-read anyway.
     *
     * herdr publishes nothing for a move made by its own key bindings, so a
     * tab or workspace switched by typing `prefix+n` inside the terminal
     * reaches no event. Following that is what this costs: one command on
     * the connection already held, while a screen is up and the app is in
     * front.
     */
    private val reconcileMs = 3_000L

    private class Entry(val hostId: String, val session: String?, val key: String) {
        val state = MutableStateFlow(HerdrHostState())

        /** Lines that arrived before the snapshot was installed. */
        val buffered = mutableListOf<HerdrEvent>()
        var refCount = 0
        var bootstrapped = false

        /** True while the fallback re-read loop is running. */
        var polling = false

        /** True from an attempt at the stream until it is known to have failed. */
        var streaming = false

        /** Set while a snapshot read is in flight, so events do not stack reads. */
        var reading = false

        /** Bumped on every (re)subscribe so a late stream callback is ignored. */
        var epoch = 0
    }

    private val lock = Any()
    private val entries = HashMap<String, Entry>()

    @Volatile private var isForeground = true
    private val foregroundWatchStarted = AtomicBoolean(false)

    private fun keyOf(hostId: String, session: String?): String = "$hostId\u0000${session ?: ""}"

    private fun setState(entry: Entry, transform: (HerdrHostState) -> HerdrHostState) {
        entry.state.value = transform(entry.state.value)
    }

    /** The state for a host, live once [subscribe] has joined it. */
    fun hostState(hostId: String, session: String? = null): StateFlow<HerdrHostState> {
        val entry = synchronized(lock) { entries[keyOf(hostId, session)] }
        return entry?.state?.asStateFlow() ?: EMPTY_STATE
    }

    /**
     * Starts keeping a host current and returns the handle to leave.
     *
     * The last leaver stops the stream and releases the connection: a held
     * connection with no screen behind it is what a phone must not keep. Two
     * sessions on the same host share the connection, so it is only released
     * once nothing on that host is watched anymore.
     */
    fun subscribe(hostId: String, session: String? = null): AutoCloseable {
        val key = keyOf(hostId, session)
        val entry: Entry
        var shouldStart = false
        synchronized(lock) {
            entry = entries.getOrPut(key) { Entry(hostId, session, key) }
            entry.refCount += 1
            if (entry.refCount == 1) shouldStart = true
        }
        if (shouldStart) start(entry)
        return AutoCloseable {
            var shouldStop = false
            synchronized(lock) {
                entry.refCount -= 1
                if (entry.refCount == 0) shouldStop = true
            }
            if (shouldStop) stop(entry)
        }
    }

    /** Re-reads a host now, for a screen that just changed something itself. */
    suspend fun refresh(hostId: String, session: String? = null) {
        val entry = synchronized(lock) { entries[keyOf(hostId, session)] } ?: return
        read(entry)
    }

    /**
     * Applies a change the app just made, before herdr has answered.
     *
     * The event that follows carries the same ids, so applying it again is a
     * no-op: the optimistic paint is a head start, not a second source of
     * truth.
     */
    fun applyLocal(hostId: String, session: String?, event: HerdrEvent) {
        val entry = synchronized(lock) { entries[keyOf(hostId, session)] } ?: return
        apply(entry, event)
    }

    /** Stops every entry and cancels the store's own coroutine scope. */
    fun close() {
        val all = synchronized(lock) {
            val copy = entries.values.toList()
            entries.clear()
            copy
        }
        all.forEach { it.epoch += 1 }
        scope.cancel()
    }

    private fun start(entry: Entry) {
        entry.epoch += 1
        entry.bootstrapped = false
        entry.buffered.clear()
        setState(entry) { it.copy(feed = HerdrFeed.STARTING) }

        // The stream opens first and its lines are buffered, so nothing that
        // happens during the snapshot read is lost.
        scope.launch { openStream(entry) }
        scope.launch { read(entry) }
        watchForeground()
        reconcile(entry)
    }

    /**
     * Re-reads a live host while the app is in front.
     *
     * Everything the app does is reported by an event, so this is only for
     * what was typed inside herdr, which herdr changes without publishing
     * anything. The fallback poll already re-reads, so this runs only while
     * the stream is the source, and it stops while the app is away.
     */
    private fun reconcile(entry: Entry) {
        val epoch = entry.epoch
        scope.launch {
            while (true) {
                delay(reconcileMs)
                if (entry.epoch != epoch) return@launch
                // Skipped only when the app says it is away: a platform that
                // reports nothing yet still has a screen in front of the user.
                if (entry.state.value.feed == HerdrFeed.LIVE && isForeground) {
                    scope.launch { read(entry) }
                }
            }
        }
    }

    /**
     * Re-reads every watched host when the app comes back to the foreground.
     *
     * [reconcile] stops while the app is away, so this is what catches up
     * everything that changed in the meantime, at the moment the user
     * returns. Started once, the first time anything subscribes.
     */
    private fun watchForeground() {
        if (!foregroundWatchStarted.compareAndSet(false, true)) return
        scope.launch {
            var previous: Boolean? = null
            foregrounded.collect { fg ->
                val cameToForeground = previous == false && fg
                previous = fg
                isForeground = fg
                if (cameToForeground) {
                    val watched = synchronized(lock) { entries.values.toList() }
                    watched.forEach { entry -> scope.launch { read(entry) } }
                }
            }
        }
    }

    private fun stop(entry: Entry) {
        // Every loop and callback checks the epoch, so bumping it is what
        // stops them: nothing here holds a handle to cancel.
        entry.epoch += 1
        entry.polling = false
        entry.streaming = false
        val stillWatched = synchronized(lock) {
            entries.remove(entry.key)
            entries.values.any { it.hostId == entry.hostId }
        }
        if (!stillWatched) events.release(entry.hostId)
    }

    /**
     * Falls back to re-reading, and keeps trying for the stream.
     *
     * A host reached before its key was accepted, or before its herdr was
     * up, cannot open a stream on the first try. Without a retry that host
     * stays on a timer for as long as the screen is open, which is the
     * behaviour this whole fallback exists to remove.
     */
    private fun startPolling(entry: Entry) {
        // The attempt is given back first: a host that fails twice reaches
        // here with the loop already running, and leaving the flag set would
        // stop every later retry, which is how a host stayed on the timer
        // after its key was accepted.
        entry.streaming = false
        if (entry.polling) return
        entry.polling = true
        setState(entry) { it.copy(feed = HerdrFeed.POLLING) }
        val epoch = entry.epoch
        scope.launch {
            var ticks = 0
            while (true) {
                delay(pollMs)
                if (entry.epoch != epoch || !entry.polling) return@launch
                scope.launch { read(entry) }
                ticks += 1
                if (!entry.streaming && ticks % streamRetryPolls == 0) {
                    scope.launch { openStream(entry) }
                }
            }
        }
    }

    private fun stopPolling(entry: Entry) {
        entry.polling = false
    }

    private suspend fun openStream(entry: Entry) {
        val epoch = entry.epoch
        if (entry.streaming) return
        entry.streaming = true
        var socketPath: String? = null
        var failure: Throwable? = null
        try {
            val sessions = client.listHerdrSessions(entry.hostId)
            val wanted = if (entry.session == null) {
                sessions.firstOrNull { it.default } ?: sessions.firstOrNull()
            } else {
                sessions.firstOrNull { it.name == entry.session }
            }
            socketPath = wanted?.socketPath
        } catch (e: Exception) {
            failure = e
        }
        // An entry that has since stopped or restarted has nobody to tell,
        // and its attempt has to be given back or no retry will ever run
        // again.
        if (entry.epoch != epoch) {
            entry.streaming = false
            return
        }
        if (failure != null) {
            // A host whose key is not accepted yet answers nothing. The poll
            // loop retries this, so it is a warning rather than a failure.
            Log.w("HerdrStore", "session list failed for ${entry.hostId}: $failure")
        }
        if (socketPath.isNullOrEmpty()) {
            startPolling(entry)
            return
        }

        val onLine: (String) -> Unit = onLine@{ line ->
            if (entry.epoch != epoch) return@onLine
            val parsed = parseHerdrEvent(line) ?: return@onLine
            if (parsed is HerdrEvent.Ack) {
                stopPolling(entry)
                setState(entry) { it.copy(feed = HerdrFeed.LIVE) }
                return@onLine
            }
            if (!entry.bootstrapped) {
                entry.buffered += parsed
                return@onLine
            }
            apply(entry, parsed)
        }
        val onClosed: (String, String) -> Unit = onClosed@{ _, _ ->
            if (entry.epoch != epoch) return@onClosed
            // The stream is what kept the state current; without it the
            // screen falls back to re-reading rather than showing a frozen
            // list.
            startPolling(entry)
        }

        // Called directly, with no dispatcher hop: listHerdrSessions above
        // already dialed and cached the host's connection, so the transport
        // finds it held and returns without a real network wait here.
        events.subscribe(entry.hostId, eventStreamCommand(socketPath), onLine, onClosed)

        // A reader that never acknowledges is a host with none of the
        // interpreters, or a herdr too old for the subscription. Either way,
        // polling.
        scope.launch {
            delay(ackTimeoutMs)
            if (entry.epoch != epoch) {
                entry.streaming = false
                return@launch
            }
            if (entry.state.value.feed != HerdrFeed.LIVE) startPolling(entry)
        }
    }

    private suspend fun read(entry: Entry) {
        if (entry.reading) return
        entry.reading = true
        val epoch = entry.epoch
        try {
            val snap = client.herdrSnapshot(entry.hostId, entry.session)
            if (entry.epoch != epoch) return
            install(entry, snap)
        } catch (e: Exception) {
            if (entry.epoch != epoch) return
            setState(entry) { it.copy(error = e.message ?: e.toString(), loaded = it.loaded) }
        } finally {
            entry.reading = false
        }
    }

    private fun install(entry: Entry, snap: HerdrSnapshot) {
        val tabs = snap.tabs.groupBy { it.workspaceId }
        setState(entry) {
            it.copy(
                workspaces = snap.workspaces.sortedBy { w -> w.number },
                tabs = tabs,
                focusedWorkspaceId = snap.focusedWorkspaceId,
                focusedTabId = snap.focusedTabId,
                error = null,
                loaded = true,
            )
        }

        // Anything that happened while the snapshot was being read is newer
        // than the snapshot, so it goes on top in the order it arrived.
        val pending = entry.buffered.toList()
        entry.buffered.clear()
        entry.bootstrapped = true
        pending.forEach { apply(entry, it) }
    }

    private fun apply(entry: Entry, event: HerdrEvent) {
        when (event) {
            is HerdrEvent.Ack -> Unit
            is HerdrEvent.Resync -> scope.launch { read(entry) }
            is HerdrEvent.WorkspaceCreated -> setState(entry) { s ->
                val without = s.workspaces.filter { it.workspaceId != event.workspace.workspaceId }
                s.copy(workspaces = (without + event.workspace).sortedBy { it.number })
            }
            is HerdrEvent.WorkspaceClosed -> setState(entry) { s ->
                s.copy(
                    workspaces = s.workspaces.filter { it.workspaceId != event.workspaceId },
                    tabs = s.tabs - event.workspaceId,
                )
            }
            is HerdrEvent.WorkspaceRenamed -> setState(entry) { s ->
                s.copy(
                    workspaces = s.workspaces.map {
                        if (it.workspaceId == event.workspaceId) it.copy(label = event.label) else it
                    },
                )
            }
            is HerdrEvent.WorkspaceFocused -> setState(entry) { s ->
                s.copy(
                    focusedWorkspaceId = event.workspaceId,
                    workspaces = s.workspaces.map { it.copy(focused = it.workspaceId == event.workspaceId) },
                )
            }
            is HerdrEvent.TabCreated -> setState(entry) { s ->
                val list = s.tabs[event.tab.workspaceId] ?: emptyList()
                val without = list.filter { it.tabId != event.tab.tabId }
                s.copy(tabs = s.tabs + (event.tab.workspaceId to (without + event.tab).sortedBy { it.number }))
            }
            is HerdrEvent.TabClosed -> setState(entry) { s ->
                val list = (s.tabs[event.workspaceId] ?: emptyList()).filter { it.tabId != event.tabId }
                s.copy(tabs = s.tabs + (event.workspaceId to list))
            }
            is HerdrEvent.TabRenamed -> setState(entry) { s ->
                val list = (s.tabs[event.workspaceId] ?: emptyList()).map {
                    if (it.tabId == event.tabId) it.copy(label = event.label) else it
                }
                s.copy(tabs = s.tabs + (event.workspaceId to list))
            }
            is HerdrEvent.TabFocused -> setState(entry) { s ->
                val list = (s.tabs[event.workspaceId] ?: emptyList()).map { it.copy(focused = it.tabId == event.tabId) }
                s.copy(focusedTabId = event.tabId, tabs = s.tabs + (event.workspaceId to list))
            }
        }
    }

    companion object {
        private val EMPTY_STATE: StateFlow<HerdrHostState> = MutableStateFlow(HerdrHostState()).asStateFlow()
    }
}
