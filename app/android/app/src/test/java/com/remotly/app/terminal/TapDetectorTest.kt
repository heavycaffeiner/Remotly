package com.remotly.app.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Only a one-finger tap opens the keyboard. A pinch changes the font size and a
// drag scrolls or selects; raising the IME during either would be wrong.
class TapDetectorTest {

    private fun detector() = TapDetector(touchSlopPx = 10f)

    @Test
    fun aStationaryOneFingerTouchIsATap() {
        val d = detector()
        d.onDown(100f, 100f)
        assertTrue(d.onUp(pointerCount = 1))
    }

    @Test
    fun aSmallWobbleWithinSlopIsStillATap() {
        // A finger never lands perfectly still.
        val d = detector()
        d.onDown(100f, 100f)
        d.onMove(104f, 103f)
        assertTrue(d.onUp(pointerCount = 1))
    }

    @Test
    fun aDragBeyondSlopIsNotATap() {
        val d = detector()
        d.onDown(100f, 100f)
        d.onMove(100f, 140f)
        assertFalse(d.onUp(pointerCount = 1))
    }

    @Test
    fun aDragThatReturnsToTheStartIsStillNotATap() {
        // Once the gesture has moved it is a drag, even if it ends where it
        // began.
        val d = detector()
        d.onDown(100f, 100f)
        d.onMove(100f, 200f)
        d.onMove(100f, 100f)
        assertFalse(d.onUp(pointerCount = 1))
    }

    @Test
    fun aSecondFingerCancelsTheTap() {
        // This is the pinch case.
        val d = detector()
        d.onDown(100f, 100f)
        d.onPointerDown()
        assertFalse(d.onUp(pointerCount = 2))
    }

    @Test
    fun liftingWithTwoFingersDownIsNotATap() {
        val d = detector()
        d.onDown(100f, 100f)
        assertFalse(d.onUp(pointerCount = 2))
    }

    @Test
    fun cancellationClearsTheGesture() {
        val d = detector()
        d.onDown(100f, 100f)
        d.onCancel()
        assertFalse(d.isCandidate)
        assertFalse(d.onUp(pointerCount = 1))
    }

    @Test
    fun anUpWithoutADownIsNotATap() {
        assertFalse(detector().onUp(pointerCount = 1))
    }

    @Test
    fun consecutiveTapsBothRegister() {
        // Repeated taps are the recovery path after dismissing the keyboard, so
        // the second must work as well as the first.
        val d = detector()
        d.onDown(10f, 10f)
        assertTrue(d.onUp(pointerCount = 1))
        d.onDown(20f, 20f)
        assertTrue(d.onUp(pointerCount = 1))
    }

    @Test
    fun aFailedGestureDoesNotPoisonTheNextTap() {
        val d = detector()
        d.onDown(10f, 10f)
        d.onMove(200f, 200f)
        assertFalse(d.onUp(pointerCount = 1))

        d.onDown(20f, 20f)
        assertTrue(d.onUp(pointerCount = 1))
    }

    @Test
    fun moveWithoutDownIsIgnored() {
        val d = detector()
        d.onMove(50f, 50f)
        assertFalse(d.isCandidate)
    }

    @Test
    fun slopIsMeasuredRadially() {
        // Diagonal movement inside the radius stays a tap; the same distance
        // on one axis alone does not become a drag either.
        val d = detector()
        d.onDown(0f, 0f)
        d.onMove(6f, 6f) // hypotenuse ~8.5, inside 10
        assertTrue(d.onUp(pointerCount = 1))

        val e = detector()
        e.onDown(0f, 0f)
        e.onMove(8f, 8f) // hypotenuse ~11.3, outside 10
        assertFalse(e.onUp(pointerCount = 1))
    }
}
