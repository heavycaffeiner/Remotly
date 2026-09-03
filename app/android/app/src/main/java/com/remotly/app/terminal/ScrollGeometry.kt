package com.remotly.app.terminal

/**
 * What the scrollbar geometry says about the viewport.
 *
 * The terminal reports (total, offset, visible) rows and the view decides from
 * those three numbers whether a scroll has anywhere to go. The decisions are
 * here, free of Android types, because they gate work on the keystroke path:
 * a wrong answer either strands the viewport in the scrollback or makes every
 * key repaint the screen.
 *
 * A geometry that could not be read is absent rather than guessed at, and each
 * predicate names what it does with that case.
 */
object ScrollGeometry {

    /** The three rows the terminal reports, or null when it reported nothing. */
    fun of(bar: LongArray?): LongArray? {
        if (bar == null) return null
        return if (bar.size < 3) null else bar
    }

    /**
     * True when the viewport already shows the active area.
     *
     * An unreadable geometry answers false. Answering true is what refused to
     * scroll back to the bottom, so a terminal whose handle was briefly
     * unreadable came back unscrollable; a redundant pin costs nothing by
     * comparison.
     */
    fun atBottom(bar: LongArray?): Boolean {
        val b = of(bar) ?: return false
        val total = b[0]
        val visible = b[2]
        if (total <= visible) return true
        return b[1] + visible >= total
    }

    /** True when rows exist above the active area. */
    fun hasScrollback(bar: LongArray?): Boolean {
        val b = of(bar) ?: return false
        return b[0] > b[2]
    }

    /**
     * True when scrolling down by [rows] would land at or past the last row.
     *
     * An unreadable geometry answers false, so the scroll is attempted rather
     * than snapped.
     */
    fun withinSnapOfBottom(bar: LongArray?, rows: Int): Boolean {
        val b = of(bar) ?: return false
        val total = b[0]
        val visible = b[2]
        if (total <= visible) return false
        return b[1] + visible + rows >= total
    }

    /**
     * Whether pinning the viewport to the active area has anything to do.
     *
     * Every committed key asks for the pin, and the pin stamps the clock the
     * scrollbar fade is measured against. A fading bar posts its own frame
     * callback, so pinning an already-pinned viewport held a repaint loop open
     * for as long as the user kept typing. Answering false here is what keeps
     * a keystroke free.
     */
    fun pinNeeded(handle: Long, bar: LongArray?): Boolean {
        if (handle == 0L) return false
        return !atBottom(bar)
    }
}
