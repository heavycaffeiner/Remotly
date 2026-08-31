package com.remotly.app.terminal

import kotlin.math.abs

/**
 * Turns a scrolled row count into wheel reports.
 *
 * A full-screen application keeps no scrollback and scrolls by reading wheel
 * reports, so a drag has to become buttons rather than viewport movement.
 * Kept free of Android types, like the rest of the gesture arithmetic, so the
 * direction and the pacing are testable without a View.
 */
object WheelReport {

    /** Wheel up. SGR reports this as button 64. */
    const val UP = 4

    /** Wheel down. SGR reports this as button 65. */
    const val DOWN = 5

    /**
     * Rows of finger travel per wheel report.
     *
     * A report is one notch of a physical wheel and an application decides for
     * itself how far that is, so the mapping is a compromise: one notch per
     * row overshot badly, and one per three rows made a drag crawl because
     * most applications move fewer lines than that.
     *
     * Two keeps a drag close to the finger without the content racing ahead
     * of it. Anything larger is felt as the terminal ignoring the gesture.
     */
    const val ROWS_PER_NOTCH = 2

    /**
     * Reports sent for one move, however far it travelled.
     *
     * A fling can produce a large row count in a single frame, and flooding
     * the pty is worse than scrolling slightly less than the gesture asked
     * for. High enough that a fast flick still carries: the earlier bound cut
     * a fling short and made momentum feel dead.
     */
    const val MAX_STEPS = 12

    /**
     * The button for a row count.
     *
     * Follows ScrollTracker's convention: positive means the finger moved
     * down, which reveals older output. That is wheel up, and it is the same
     * direction the local viewport travels for the same count, so an
     * application that tracks the mouse and one that does not behave alike.
     */
    fun button(rows: Int): Int = if (rows > 0) UP else DOWN
}

/**
 * Paces wheel reports against finger travel.
 *
 * Rows arrive a few at a time as a drag proceeds, and a notch is worth several
 * of them, so the remainder has to be carried between moves. Dropping it would
 * make a slow drag send nothing at all and a fast one arrive short.
 *
 * Mirrors what ScrollTracker does for the local viewport, at the coarser step
 * a wheel moves in.
 */
class WheelTicker {

    private var carry = 0

    /**
     * Reports how many notches to send for [rows] of further travel.
     *
     * The sign is carried too: reversing direction mid-drag discards the
     * leftover from the other way rather than counting it backwards.
     */
    fun advance(rows: Int): Int {
        if (rows == 0) return 0
        if (carry != 0 && (carry > 0) != (rows > 0)) carry = 0
        carry += rows
        val notches = carry / WheelReport.ROWS_PER_NOTCH
        if (notches == 0) return 0
        carry -= notches * WheelReport.ROWS_PER_NOTCH
        return if (abs(notches) > WheelReport.MAX_STEPS) {
            if (notches > 0) WheelReport.MAX_STEPS else -WheelReport.MAX_STEPS
        } else {
            notches
        }
    }

    /** Drops the leftover, so a new gesture starts from nothing. */
    fun reset() {
        carry = 0
    }
}
