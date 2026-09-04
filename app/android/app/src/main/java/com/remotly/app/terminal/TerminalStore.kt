package com.remotly.app.terminal

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Native terminals, kept alive across screens by session id.
 *
 * A terminal owns its scrollback. Navigating away pops the React screen and
 * drops the view, so a terminal tied to the view's lifetime lost every line it
 * had: coming back showed an empty screen for a session that was still
 * running. Keeping the handle here means the view is a renderer for a terminal
 * that outlives it.
 *
 * A handle is released only when its session is explicitly closed, or when the
 * store is trimmed.
 *
 * libghostty is main-thread only, so every native call here runs on the main
 * thread. The bridge calls in from its own thread, which is why nothing here
 * assumes it is already on the main one.
 *
 * [handles] is read from the bridge thread as well ([has]), so every access to
 * it is taken under [lock]. Mutating it is still main-thread only, because a
 * mutation is always paired with a native call.
 */
object TerminalStore {

    private val mainHandler = Handler(Looper.getMainLooper())

    private fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action()
        else mainHandler.post(action)
    }

    /** Guards [handles] against the bridge thread reading it during a change. */
    private val lock = Any()

    /** Live handles by session id. Mutated on the main thread only. */
    private val handles = LinkedHashMap<String, Long>()

    /**
     * How far into each session's output its terminal has been written.
     *
     * Paired with [handles] so the two cannot disagree: an entry is dropped
     * whenever the handle beside it is, because the offset describes one
     * terminal instance and means nothing for the next. Held here rather than
     * beside the transport for that reason. There it outlived the terminal it
     * described, and a replay skipped the history that terminal had lost.
     */
    private val consumed = HashMap<String, Long>()

    /**
     * Views currently rendering a session, so a write can repaint them.
     *
     * A write through this store does not go through the view, so nothing
     * would schedule a frame and the terminal would hold content the screen
     * never showed. A view can be attached and still receive output this way,
     * because output is routed per chunk and the view's own sink is briefly
     * absent while a tab is being switched.
     */
    private val renderers = HashMap<String, TerminalView>()

    /**
     * Registers the view rendering a session and repaints it.
     *
     * A view that adopts a terminal inherits whatever was written to it while
     * nothing was on screen, including writes still in flight when the view
     * mounted. Drawing once at mount catches only what had landed by then, so
     * the rest sits in the terminal unpainted until something else forces a
     * frame: the screen looks stuck partway through the previous output and
     * only catches up when the keyboard resizes it.
     */
    fun bindRenderer(sessionId: String, view: TerminalView) {
        if (sessionId.isEmpty()) return
        renderers[sessionId] = view
        // The caller draws for what the terminal already holds. This one runs
        // after the writes queued ahead of it on the looper, which that draw
        // cannot have seen.
        mainHandler.post {
            if (renderers[sessionId] === view) view.onExternalWrite()
        }
    }

    /** Unregisters a view, if it is still the one registered. */
    fun unbindRenderer(sessionId: String, view: TerminalView) {
        if (renderers[sessionId] === view) renderers.remove(sessionId)
    }

    /**
     * How many detached terminals to keep. Each holds up to its scrollback cap
     * of native memory, so this is bounded rather than left to grow with the
     * number of sessions a user has ever opened.
     *
     * Sized to the largest tab count one host can reach, which is the daemon
     * workspace's 16 (lib/workspace.ts MAX_TABS); SSH hosts cap at 8. At 8
     * this evicted a terminal belonging to a tab the user still had open, and
     * only one tab has a renderer at a time, so the other fifteen were all
     * eligible: opening a ninth tab silently destroyed the scrollback of a
     * session that was still running.
     */
    private const val MAX_RETAINED = 16

    /**
     * The handle for a session, or 0 when none is retained.
     *
     * The entry stays: a view adopting a terminal does not take ownership of
     * it, because output for the session can still arrive while no view is
     * attached and it has to reach the same terminal.
     */
    fun take(sessionId: String): Long {
        if (sessionId.isEmpty()) return 0L
        return synchronized(lock) { handles[sessionId] ?: 0L }
    }

    /**
     * Retains a handle for later reattachment.
     *
     * The oldest retained terminal is destroyed when the cap is reached, which
     * loses its scrollback. A terminal with a view on it is never the victim,
     * and the cap covers every tab a host can open, so in practice only a
     * session whose tab is already closed is evicted.
     */
    fun retain(sessionId: String, handle: Long) {
        if (sessionId.isEmpty() || handle == 0L) return
        synchronized(lock) {
            // A different handle is a different terminal, and nothing the old
            // one parsed is in it. Keeping the consumed offset would have a
            // replay skip bytes this terminal has never been shown.
            if (handles[sessionId] != handle) consumed.remove(sessionId)
            // Re-inserted synchronously so take() and has() immediately see it.
            handles.remove(sessionId)
            handles[sessionId] = handle
        }
        onMain {
            val evicted = ArrayList<Long>()
            synchronized(lock) {
                while (handles.size > MAX_RETAINED) {
                    // A session with a view on it is not a detached terminal,
                    // whatever its position in the LRU order. Destroying its
                    // handle leaves that view drawing from freed memory, and
                    // the tab renders as garbage or takes the process down.
                    val oldest = handles.keys.firstOrNull { !renderers.containsKey(it) }
                        ?: break
                    handles.remove(oldest)?.let { evicted.add(it) }
                    // Dropped with the handle. A stale offset left behind
                    // describes freed memory, and the empty terminal that
                    // replaces it then has its history skipped.
                    consumed.remove(oldest)
                }
            }
            for (h in evicted) {
                Log.d(TAG, "evicting retained terminal")
                RemotlyTerminal.nativeDestroy(h)
            }
        }
    }

    /**
     * Writes to a retained terminal without attaching a view.
     *
     * Output for a tab nobody is looking at goes straight into its own
     * terminal, which owns a bounded scrollback and a parser that understands
     * the byte stream. Holding it in a queue instead means deciding what to
     * drop when the queue is full, and a queue trimmed from the front cuts an
     * escape sequence in half, so the survivors render as garbage.
     *
     * [onDone] is what applies backpressure: the caller waits for it before
     * sending the next chunk. Reporting success at post time instead lets a
     * busy session queue main-thread jobs faster than they run, and the UI
     * then stalls behind the backlog. No thread is blocked here, because the
     * caller is the React JS thread and blocking it against the main thread
     * risks a deadlock rather than a stall.
     */
    fun feed(
        sessionId: String,
        data: ByteArray,
        cols: Int,
        rows: Int,
        onDone: (Boolean) -> Unit,
    ) = write(sessionId, data, null, cols, rows, onDone)

    /**
     * Writes the part of [data] this session's terminal has not parsed yet.
     *
     * [endOffset] is the session's output offset just past the last byte of
     * [data]. The overlap a replay carries is dropped here rather than by the
     * caller, because only this thread can read the consumed offset without
     * racing a write already queued ahead of it: a caller reading it from the
     * bridge thread sees a value from before those writes landed and hands
     * over bytes the terminal is about to be given twice.
     */
    fun feedAt(
        sessionId: String,
        data: ByteArray,
        endOffset: Long,
        cols: Int,
        rows: Int,
        onDone: (Boolean) -> Unit,
    ) = write(sessionId, data, endOffset, cols, rows, onDone)

    private fun write(
        sessionId: String,
        data: ByteArray,
        endOffset: Long?,
        cols: Int,
        rows: Int,
        onDone: (Boolean) -> Unit,
    ) {
        if (sessionId.isEmpty() || data.isEmpty()) {
            onDone(false)
            return
        }
        onMain {
            // The lookup and the create both run here: nativeCreate is a native
            // call like any other, and doing it on the caller's thread raced
            // with the main thread's own use of the same terminal.
            //
            // A session whose view has never mounted still produces output. It
            // is given a terminal here rather than left to a queue that grows
            // for as long as the tab stays unopened.
            val handle = synchronized(lock) { handles[sessionId] }
                ?: create(sessionId, cols, rows)
            if (handle == 0L) {
                onDone(false)
                return@onMain
            }
            val payload = if (endOffset == null) data else newTail(sessionId, data, endOffset)
            if (payload.isEmpty()) {
                onDone(true)
                return@onMain
            }
            RemotlyTerminal.nativeWrite(handle, payload)
            if (endOffset != null) {
                // Advanced only now the write has happened. Recorded up front
                // and then failing, the offset claims bytes the terminal never
                // parsed and the next replay skips them: a hole mid-stream
                // that renders as garbage.
                synchronized(lock) {
                    val had = consumed[sessionId]
                    if (had == null || endOffset > had) consumed[sessionId] = endOffset
                }
            }
            // Repaint whatever is showing this session. Writing without this
            // leaves the change in the terminal and off the screen until some
            // later event happens to draw, which is felt as output arriving
            // late or not at all.
            renderers[sessionId]?.onExternalWrite()
            onDone(true)
        }
    }

    /**
     * The slice of [data] past what this session's terminal already holds.
     *
     * A session with no consumed offset has parsed nothing the offset could
     * describe: its terminal was never created, or it was evicted or released
     * and the one standing in for it is empty. The whole write is new to it.
     */
    private fun newTail(sessionId: String, data: ByteArray, endOffset: Long): ByteArray {
        val have = synchronized(lock) { consumed[sessionId] } ?: return data
        val skip = ReplayOverlap.bytes(endOffset - data.size, have, data.size)
        return if (skip == 0) data else data.copyOfRange(skip, data.size)
    }

    /** True when a terminal is retained for the session. */
    fun has(sessionId: String): Boolean =
        synchronized(lock) { handles.containsKey(sessionId) }

    /**
     * Creates a terminal for a session that has no view.
     *
     * The listener discards everything: a terminal with nobody watching has no
     * bell to ring and no host to hand input to. What matters is that the
     * bytes are parsed and kept, so the scrollback is there when a view
     * finally attaches.
     */
    private fun create(sessionId: String, cols: Int, rows: Int): Long {
        val safeCols = if (cols > 0) cols else DEFAULT_COLS
        val safeRows = if (rows > 0) rows else DEFAULT_ROWS
        val handle = RemotlyTerminal.nativeCreate(
            safeCols,
            safeRows,
            SCROLLBACK_BYTES,
            DetachedListener,
        )
        if (handle != 0L) retain(sessionId, handle)
        return handle
    }

    /** Removes a session's handle and returns it, or 0 when none was held. */
    private fun detach(sessionId: String): Long =
        synchronized(lock) {
            consumed.remove(sessionId)
            handles.remove(sessionId) ?: 0L
        }

    /** Swallows every effect of a terminal nobody is rendering. */
    private object DetachedListener : RemotlyTerminal.Listener {
        override fun onBell() = Unit
        override fun onTitle(titleUtf8: ByteArray) = Unit
        override fun onInput(data: ByteArray) = Unit
        override fun onPtyWrite(data: ByteArray) = Unit
    }

    private const val DEFAULT_COLS = 80
    private const val DEFAULT_ROWS = 24
    private const val SCROLLBACK_BYTES = 8L * 1024 * 1024

    /** Destroys the handle for a session, if one is retained and has no active view. */
    fun release(sessionId: String) {
        onMain {
            // A session with a view on it is not a detached terminal. Destroying its
            // handle leaves that view drawing from freed memory and takes the process down.
            if (renderers.containsKey(sessionId)) return@onMain
            val handle = detach(sessionId)
            if (handle != 0L) RemotlyTerminal.nativeDestroy(handle)
        }
    }

    /** Destroys every retained handle. */
    fun releaseAll() {
        onMain {
            val all = synchronized(lock) {
                val copy = handles.values.toList()
                handles.clear()
                consumed.clear()
                copy
            }
            for (handle in all) RemotlyTerminal.nativeDestroy(handle)
        }
    }

    private const val TAG = "TerminalStore"
}
