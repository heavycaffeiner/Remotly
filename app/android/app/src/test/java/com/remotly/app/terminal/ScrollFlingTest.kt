package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The fling curve.
 *
 * The terminal moves in whole rows, so the curve is sampled per frame and the
 * leftover pixels carried. Jumping to a final row instead reads as a snap, and
 * dropping the remainder makes a slow fling travel nothing.
 */
class ScrollFlingTest {

    private val cell = 20
    private val frame = 1f / 60f
    private lateinit var fling: ScrollFling

    @Before
    fun setUp() {
        fling = ScrollFling()
    }

    /** Runs frames until the fling settles, returning the rows travelled. */
    private fun runOut(limit: Int = 600): Int {
        var total = 0
        var frames = 0
        while (fling.isRunning && frames < limit) {
            total += fling.advance(frame, cell)
            frames += 1
        }
        return total
    }

    /** Frames the fling stays alive for. */
    private fun framesToSettle(limit: Int = 600): Int {
        var frames = 0
        while (fling.isRunning && frames < limit) {
            fling.advance(frame, cell)
            frames += 1
        }
        return frames
    }

    @Test
    fun aFlickTravelsInItsOwnDirection() {
        fling.start(3000f)
        assertTrue(runOut() > 0)

        fling.start(-3000f)
        assertTrue(runOut() < 0)
    }

    @Test
    fun aFasterFlickTravelsFurther() {
        fling.start(1500f)
        val slow = runOut()
        fling.start(6000f)
        val fast = runOut()
        assertTrue(fast > slow)
    }

    /**
     * Distance has to grow faster than velocity, or the range between a gentle
     * flick and a hard one collapses and every fling feels the same. Four times
     * the velocity covers well over four times the ground: the platform curve
     * puts it near eleven.
     *
     * A curve merely proportional to velocity gives exactly four and fails.
     */
    @Test
    fun distanceGrowsFasterThanVelocity() {
        fling.start(1000f)
        val slow = runOut()
        fling.start(4000f)
        val fast = runOut()
        assertTrue("slow=$slow fast=$fast", fast > slow * 8)
    }

    /**
     * A gentle flick has to stop quickly. Constant-rate decay drags every
     * fling out to roughly the same length, which is what made slow scrolling
     * feel like it was coasting on its own.
     */
    @Test
    fun aGentleFlickSettlesQuickly() {
        fling.start(600f)
        val frames = framesToSettle()
        assertTrue("frames=$frames", frames < 20)
    }

    /** A hard flick keeps going for noticeably longer than a gentle one. */
    @Test
    fun aHarderFlickRunsLonger() {
        fling.start(600f)
        val gentle = framesToSettle()
        fling.start(9000f)
        val hard = framesToSettle()
        assertTrue("gentle=$gentle hard=$hard", hard > gentle * 2)
    }

    /**
     * The same physical flick covers the same physical distance on any screen.
     *
     * A finger crossing six inches per second launches proportionally more
     * pixels per second on a denser screen, and has to travel proportionally
     * more pixels to cover the same ground. Rows are the same physical size
     * here because the cell is scaled with the density too.
     */
    @Test
    fun theSamePhysicalFlickTravelsTheSameDistance() {
        fun rowsFor(ppi: Float): Int {
            val cellForDensity = (cell * (ppi / 160f)).toInt()
            fling.start(6f * ppi, pixelsPerInch = ppi)
            var total = 0
            var frames = 0
            while (fling.isRunning && frames < 600) {
                total += fling.advance(frame, cellForDensity)
                frames += 1
            }
            return total
        }
        val coarse = rowsFor(160f)
        val dense = rowsFor(560f)
        assertTrue("coarse=$coarse dense=$dense", kotlin.math.abs(dense - coarse) <= 1)
    }

    // The point of the curve: it slows down rather than moving at one rate.
    @Test
    fun itDecelerates() {
        fling.start(6000f)
        var first = 0
        repeat(6) { first += fling.advance(frame, cell) }
        var later = 0
        repeat(30) { fling.advance(frame, cell) }
        repeat(6) { later += fling.advance(frame, cell) }
        assertTrue(later < first)
    }

    @Test
    fun itSettles() {
        fling.start(4000f)
        runOut()
        assertFalse(fling.isRunning)
        assertEquals(0, fling.advance(frame, cell))
    }

    @Test
    fun aSlowReleaseIsNotAFling() {
        fling.start(10f)
        assertFalse(fling.isRunning)
        assertEquals(0, fling.advance(frame, cell))
    }

    @Test
    fun stoppingEndsItImmediately() {
        fling.start(6000f)
        fling.stop()
        assertFalse(fling.isRunning)
        assertEquals(0, fling.advance(frame, cell))
    }

    /**
     * Travel below one row per frame accumulates instead of truncating away.
     *
     * 2000 px/s over a 20 px cell moves about half a row in a 60Hz frame at
     * the start, and less as it decays, so a curve sampled as per-frame deltas
     * rounded to rows would emit nothing at all.
     */
    @Test
    fun subRowFramesAccumulate() {
        fling.start(2000f)
        var frames = 0
        var total = 0
        while (fling.isRunning && frames < 600) {
            val step = fling.advance(frame, cell)
            assertTrue("single frame jumped $step rows", step <= 2)
            total += step
            frames += 1
        }
        assertTrue("total=$total", total > 5)
    }

    @Test
    fun anUnmeasuredCellScrollsNothing() {
        fling.start(4000f)
        assertEquals(0, fling.advance(frame, 0))
    }

    @Test
    fun aNonPositiveFrameScrollsNothing() {
        fling.start(4000f)
        assertEquals(0, fling.advance(0f, cell))
        assertEquals(0, fling.advance(-frame, cell))
    }

    // A very fast flick must not skip the whole scrollback in one frame.
    @Test
    fun velocityIsClamped() {
        fling.start(Float.MAX_VALUE)
        val rows = fling.advance(frame, cell)
        assertTrue(rows in 1..40)
    }

    @Test
    fun restartingReplacesTheCurrentFling() {
        fling.start(6000f)
        repeat(3) { fling.advance(frame, cell) }
        fling.start(-6000f)
        assertTrue(runOut() < 0)
    }
}
