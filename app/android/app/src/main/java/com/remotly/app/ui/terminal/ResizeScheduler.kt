package com.remotly.app.ui.terminal

import android.os.Handler
import android.os.Looper

/** A terminal grid size in character cells. */
data class GridSize(val cols: Int, val rows: Int)

/**
 * Debounce window.
 *
 * Long enough to outlast a keyboard show or hide animation, which is what
 * produces the burst of intermediate heights. Each size that reaches the pty
 * makes a full-screen application repaint, so a mid-animation size is a
 * visible redraw for a grid the user never sees.
 */
const val DEFAULT_RESIZE_DELAY_MS = 400L

/**
 * Debounces and dedupes terminal resizes.
 *
 * The terminal reports a new grid on every layout pass, including the many
 * intermediate sizes during a keyboard transition. Forwarding each one
 * hammers the remote pty and can start a resize loop, so an identical size is
 * dropped and the rest are debounced.
 *
 * [send] runs once the debounce window elapses without a newer report
 * superseding it. A size is recorded as sent only once [send] returns without
 * throwing, so a send that fails to reach its target leaves the size
 * offerable again on the next [report].
 *
 * [schedule] and [cancelTimer] are seams for tests; the default posts to the
 * main-thread handler the way the real debounce runs.
 */
class ResizeScheduler(
    private val send: (GridSize) -> Unit,
    private val delayMs: Long = DEFAULT_RESIZE_DELAY_MS,
    private val schedule: (Long, () -> Unit) -> Any = { ms, action ->
        val runnable = Runnable(action)
        mainHandler.postDelayed(runnable, ms)
        runnable
    },
    private val cancelTimer: (Any) -> Unit = { token ->
        if (token is Runnable) mainHandler.removeCallbacks(token)
    },
) {
    private var timer: Any? = null
    private var pending: GridSize? = null
    private var sent: GridSize? = null

    private fun clearTimer() {
        timer?.let(cancelTimer)
        timer = null
    }

    private fun emit() {
        clearTimer()
        val next = pending ?: return
        pending = null
        if (next.cols <= 0 || next.rows <= 0) return
        if (sent == next) return
        // Recorded only once the send has run without throwing. A send that
        // fails has not reached the target, and recording it anyway would
        // drop every later report of the same size with nothing left to
        // correct it: the grid would stay at the old one forever. The next
        // measurement re-offers it instead.
        try {
            send(next)
        } catch (_: Throwable) {
            return
        }
        sent = next
    }

    /** Records a measured size. Sends it after the debounce window. */
    fun report(size: GridSize) {
        if (sent == size) {
            // Already the live size: drop it without arming a timer.
            pending = null
            clearTimer()
            return
        }
        pending = size
        clearTimer()
        timer = schedule(delayMs, ::emit)
    }

    /** Sends the latest pending size now, if it differs from the last sent. */
    fun flush() = emit()

    /** Drops any pending resize without sending it. */
    fun cancel() {
        clearTimer()
        pending = null
    }

    /**
     * Forgets the last-sent baseline as well as any pending size. Used on a
     * session switch: the new session has its own grid, and a size measured
     * for the previous one must never reach it.
     */
    fun reset() {
        clearTimer()
        pending = null
        sent = null
    }

    /**
     * Clears the baseline if it still records this size.
     *
     * The apply can run asynchronously, so a size can be recorded as sent and
     * then fail to actually reach its target. Clearing it lets the next
     * measurement offer the same size again instead of being deduped against
     * a send that never landed.
     */
    fun forget(size: GridSize) {
        if (sent == size) sent = null
    }

    /** The last size actually sent. */
    fun current(): GridSize? = sent

    private companion object {
        val mainHandler = Handler(Looper.getMainLooper())
    }
}
