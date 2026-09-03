package com.remotly.app.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The scrollbar geometry decisions that gate work on the keystroke path.
 *
 * Two of these have already regressed once each, in opposite directions, so
 * both directions are pinned here.
 */
class ScrollGeometryTest {

    /** (total, offset, visible), as the terminal reports it. */
    private fun bar(total: Long, offset: Long, visible: Long) =
        longArrayOf(total, offset, visible)

    // --- the guard that keeps a keystroke free --------------------------------

    /**
     * The regression this exists for. Every committed key calls
     * scrollToBottom, and the pin stamps the clock the scrollbar fade is
     * measured against. A fading bar posts its own frame callback, so pinning
     * an already-pinned viewport held a repaint loop open for as long as the
     * user kept typing.
     */
    @Test
    fun pinningIsSkippedWhenTheViewportIsAlreadyAtTheBottom() {
        // 500 rows, a 24-row window sitting on the last of them.
        assertFalse(ScrollGeometry.pinNeeded(1L, bar(500, 476, 24)))
    }

    @Test
    fun pinningIsNeededWhenTheViewportIsInTheScrollback() {
        assertTrue(ScrollGeometry.pinNeeded(1L, bar(500, 100, 24)))
    }

    /** No terminal, nothing to pin. */
    @Test
    fun pinningIsSkippedWithoutAHandle() {
        assertFalse(ScrollGeometry.pinNeeded(0L, bar(500, 100, 24)))
    }

    // --- what an unreadable geometry answers ----------------------------------

    /**
     * The other regression, in the other direction. atBottom used to answer
     * true here, which is the state that refuses to scroll back to the bottom:
     * a terminal whose handle was briefly unreadable came back unscrollable.
     * Answering false only costs a redundant pin.
     */
    @Test
    fun anUnreadableGeometryIsNotTreatedAsAtTheBottom() {
        assertFalse(ScrollGeometry.atBottom(null))
        assertFalse(ScrollGeometry.atBottom(longArrayOf(1, 2)))
    }

    /** So the pin runs rather than being skipped on an unreadable read. */
    @Test
    fun pinningIsAttemptedOnAnUnreadableGeometry() {
        assertTrue(ScrollGeometry.pinNeeded(1L, null))
    }

    @Test
    fun anUnreadableGeometryReportsNoScrollbackAndNoSnap() {
        assertFalse(ScrollGeometry.hasScrollback(null))
        assertFalse(ScrollGeometry.withinSnapOfBottom(null, 5))
    }

    // --- ordinary geometry ----------------------------------------------------

    /** Content shorter than the window is at the bottom by definition. */
    @Test
    fun contentShorterThanTheWindowIsAtTheBottom() {
        assertTrue(ScrollGeometry.atBottom(bar(10, 0, 24)))
        assertFalse(ScrollGeometry.hasScrollback(bar(10, 0, 24)))
    }

    @Test
    fun historyAboveTheActiveAreaCountsAsScrollback() {
        assertTrue(ScrollGeometry.hasScrollback(bar(500, 476, 24)))
    }

    /**
     * The snap: a downward scroll landing within a row of the end pins instead
     * of stopping where the delta happened to fall.
     */
    @Test
    fun aScrollThatReachesTheEndSnaps() {
        // 4 rows short of the end, travelling 5.
        assertTrue(ScrollGeometry.withinSnapOfBottom(bar(500, 472, 24), 5))
    }

    @Test
    fun aScrollThatFallsShortDoesNotSnap() {
        assertFalse(ScrollGeometry.withinSnapOfBottom(bar(500, 100, 24), 5))
    }
}
