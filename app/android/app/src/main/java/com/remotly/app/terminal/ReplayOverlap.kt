package com.remotly.app.terminal

/**
 * How much of a write a terminal has already parsed.
 *
 * A reattach replays output the retained terminal was already shown. Clearing
 * the terminal first hides the overlap but throws away the scrollback a
 * full-screen program keeps on the primary screen, so the overlap is measured
 * instead and only the new tail is written.
 *
 * The arithmetic is here, free of Android types, because getting it wrong is
 * silent: too small a skip duplicates output, and too large a one cuts a hole
 * mid-stream that the parser renders as garbage.
 */
object ReplayOverlap {

    /**
     * Leading bytes of a write the terminal already holds.
     *
     * [start] is the write's own offset in the session's output and [consumed]
     * the offset just past the last byte already parsed, so their difference
     * is the overlap. Clamped to the write: a consumed offset behind [start]
     * means a gap the daemon already reported and nothing is dropped, and one
     * past its end means every byte on offer is old.
     */
    fun bytes(start: Long, consumed: Long, length: Int): Int =
        (consumed - start).coerceIn(0L, length.toLong()).toInt()
}
