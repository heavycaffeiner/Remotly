package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The drag-to-scroll arithmetic.
 *
 * The terminal scrolls in whole rows, so the leftover pixels between moves
 * have to be carried. Dropping them makes a slow drag scroll nothing at all.
 */
class ScrollTrackerTest {

    private val slop = 8f
    private val cell = 20
    private lateinit var tracker: ScrollTracker

    @Before
    fun setUp() {
        tracker = ScrollTracker(slop)
    }

    /** Drags past the slop so the tracker is scrolling, and discards the step. */
    private fun beginDrag(from: Float = 0f) {
        tracker.onDown(from)
        tracker.onMove(from + slop + 1f, cell)
    }

    @Test
    fun aTouchBelowTheSlopScrollsNothing() {
        tracker.onDown(0f)
        assertEquals(0, tracker.onMove(3f, cell))
        assertFalse(tracker.isScrolling)
    }

    @Test
    fun crossingTheSlopStartsScrollingWithoutJumping() {
        tracker.onDown(0f)
        // The step that crosses the slop reports nothing: measuring from the
        // origin would scroll the whole slop distance at once.
        assertEquals(0, tracker.onMove(slop + 1f, cell))
        assertTrue(tracker.isScrolling)
    }

    @Test
    fun draggingDownScrollsTowardTheScrollback() {
        beginDrag()
        assertEquals(2, tracker.onMove(slop + 1f + 2 * cell, cell))
    }

    @Test
    fun draggingUpScrollsTowardTheActiveArea() {
        beginDrag(200f)
        assertEquals(-2, tracker.onMove(200f + slop + 1f - 2 * cell, cell))
    }

    // The reason the carry exists: three half-row moves are one and a half
    // rows, not zero.
    @Test
    fun leftoverPixelsAccumulateAcrossMoves() {
        beginDrag()
        var y = slop + 1f
        var total = 0
        repeat(3) {
            y += cell / 2f
            total += tracker.onMove(y, cell)
        }
        assertEquals(1, total)
    }

    @Test
    fun theCarryDoesNotSurviveANewGesture() {
        beginDrag()
        tracker.onMove(slop + 1f + cell / 2f, cell)
        tracker.onUp()

        beginDrag()
        // A fresh drag of just under one row must not borrow the old remainder.
        assertEquals(0, tracker.onMove(slop + 1f + cell / 2f, cell))
    }

    @Test
    fun aSecondFingerEndsScrolling() {
        beginDrag()
        tracker.onPointerDown()
        assertEquals(0, tracker.onMove(500f, cell))
        assertFalse(tracker.isScrolling)
    }

    @Test
    fun releasingStopsScrolling() {
        beginDrag()
        tracker.onUp()
        assertFalse(tracker.isScrolling)
        assertEquals(0, tracker.onMove(500f, cell))
    }

    @Test
    fun aCancelledGestureScrollsNothingFurther() {
        beginDrag()
        tracker.onCancel()
        assertEquals(0, tracker.onMove(500f, cell))
    }

    // A zero cell height happens before the first layout; dividing by it would
    // crash the touch handler.
    @Test
    fun anUnmeasuredCellScrollsNothing() {
        tracker.onDown(0f)
        assertEquals(0, tracker.onMove(500f, 0))
    }
}
