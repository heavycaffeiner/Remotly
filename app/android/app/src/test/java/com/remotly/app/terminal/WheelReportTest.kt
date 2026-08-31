package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which way a drag scrolls a tracking application, and how fast.
 *
 * The sign convention is ScrollTracker's: positive means the finger moved
 * down, which reveals older output. That is wheel up, and it is the same
 * direction the local viewport travels for the same count, so an application
 * that tracks the mouse and one that does not behave alike under one gesture.
 *
 * Reading the sign the other way scrolled every tracking application
 * backwards, which the encoder tests cannot catch: the bytes were valid, they
 * just said the opposite of what the finger did.
 */
class WheelReportTest {

    @Test
    fun draggingDownScrollsBackThroughHistory() {
        assertEquals(WheelReport.UP, WheelReport.button(1))
        assertEquals(WheelReport.UP, WheelReport.button(5))
    }

    @Test
    fun draggingUpScrollsForward() {
        assertEquals(WheelReport.DOWN, WheelReport.button(-1))
        assertEquals(WheelReport.DOWN, WheelReport.button(-5))
    }

    /** SGR encodes these as 64 and 65, which the native test_wheel pins. */
    @Test
    fun theButtonsAreTheOnesSgrExpects() {
        assertEquals(4, WheelReport.UP)
        assertEquals(5, WheelReport.DOWN)
    }
}

/**
 * How finger travel is paced into wheel notches.
 *
 * An application decides for itself how far a notch scrolls, and most take it
 * as about three lines. Sending one per row moved the content several times
 * faster than the finger, which is the wheel feeling coarse and overshooting.
 */
class WheelTickerTest {

    private val ticker = WheelTicker()

    @Test
    fun travelShorterThanANotchSendsNothingYet() {
        // One row short of a notch, whatever a notch is worth.
        repeat(WheelReport.ROWS_PER_NOTCH - 1) {
            assertEquals(0, ticker.advance(1))
        }
    }

    /**
     * The remainder is carried, so a slow drag still scrolls. Dropping it
     * would make small movements do nothing at all.
     */
    @Test
    fun carriedTravelEventuallySendsANotch() {
        repeat(WheelReport.ROWS_PER_NOTCH - 1) { ticker.advance(1) }
        assertEquals(1, ticker.advance(1))
    }

    @Test
    fun aNotchIsSeveralRows() {
        assertEquals(1, ticker.advance(WheelReport.ROWS_PER_NOTCH))
    }

    @Test
    fun travelIsProportional() {
        assertEquals(2, ticker.advance(WheelReport.ROWS_PER_NOTCH * 2))
    }

    @Test
    fun upwardTravelReportsNegativeNotches() {
        assertEquals(-1, ticker.advance(-WheelReport.ROWS_PER_NOTCH))
    }

    /** The leftover is not counted backwards when the finger reverses. */
    @Test
    fun reversingDirectionDropsTheLeftover() {
        ticker.advance(2)
        // Two rows down are pending. A single row up must not combine with
        // them into a downward notch.
        assertEquals(0, ticker.advance(-1))
    }

    /**
     * A fling can report a large jump in one frame. Sending that many presses
     * floods the pty, which is worse than scrolling a little short.
     */
    @Test
    fun aLargeJumpIsBounded() {
        assertEquals(WheelReport.MAX_STEPS, ticker.advance(1000))
        ticker.reset()
        assertEquals(-WheelReport.MAX_STEPS, ticker.advance(-1000))
    }

    @Test
    fun resettingDropsPendingTravel() {
        ticker.advance(WheelReport.ROWS_PER_NOTCH - 1)
        ticker.reset()
        assertEquals(0, ticker.advance(1))
    }

    /** A drag covers the same ground whichever way the scroll is delivered. */
    @Test
    fun pacingMatchesTheLocalViewport() {
        val rows = WheelReport.ROWS_PER_NOTCH * 3
        val notches = ticker.advance(rows)
        assertEquals(rows, notches * WheelReport.ROWS_PER_NOTCH)
    }
}
