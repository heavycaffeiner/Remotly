package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalPointerTest {
    @Test fun fractionalTrackpadTravelAccumulates() {
        val scroll = PointerScrollAccumulator()
        repeat(3) { assertEquals(0, scroll.advance(0.25f, PointerOwner.Remote, 1)) }
        assertEquals(1, scroll.advance(0.25f, PointerOwner.Remote, 1))
        assertEquals(-2, scroll.advance(-2.75f, PointerOwner.Remote, 1))
        assertEquals(-1, scroll.advance(-0.25f, PointerOwner.Remote, 1))
    }

    @Test fun fractionsDoNotCrossOwnerDeviceDirectionOrReset() {
        val scroll = PointerScrollAccumulator()
        assertEquals(0, scroll.advance(0.9f, PointerOwner.Remote, 1))
        assertEquals(0, scroll.advance(0.2f, PointerOwner.Local, 1))
        assertEquals(0, scroll.advance(0.9f, PointerOwner.Local, 2))
        assertEquals(0, scroll.advance(-0.2f, PointerOwner.Local, 2))
        scroll.reset()
        assertEquals(0, scroll.advance(-0.9f, PointerOwner.Local, 2))
        assertEquals(0, scroll.advance(Float.NaN, PointerOwner.Local, 2))
        assertEquals(0, scroll.advance(Float.POSITIVE_INFINITY, PointerOwner.Local, 2))
    }

    @Test fun remoteTrackingShiftAndControlHaveOneOwner() {
        assertEquals(PointerOwner.Local, pointerOwner(false, false))
        assertEquals(PointerOwner.Remote, pointerOwner(true, false))
        assertEquals(PointerOwner.Local, pointerOwner(true, true))
        assertEquals(PointerOwner.Font, pointerOwner(true, false, true))
        assertEquals(PointerOwner.Font, pointerOwner(false, true, true))
    }

    @Test fun overlappingButtonPathsAreDeduplicatedAndReleaseKeepsPressOwner() {
        val buttons = PointerButtons()
        assertTrue(buttons.press(2, PointerOwner.Remote, 100))
        assertFalse(buttons.press(2, PointerOwner.Local, 100))
        assertEquals(PointerOwner.Remote, buttons.release(2, 120))
        assertNull(buttons.release(2, 120))
        assertEquals(-1, buttons.pressedButton())
    }

    @Test fun touchpadTravelConvertsToRowsOrNotches() {
        assertEquals(4f, touchpadScrollDelta(80f, 20, PointerOwner.Local), 0f)
        assertEquals(-1f, touchpadScrollDelta(-40f, 20, PointerOwner.Remote), 0f)
        assertEquals(1f, touchpadScrollDelta(40f, 20, PointerOwner.Font), 0f)
        assertEquals(0f, touchpadScrollDelta(Float.NaN, 20, PointerOwner.Local), 0f)
        assertEquals(0f, touchpadScrollDelta(60f, 0, PointerOwner.Local), 0f)
        assertTrue(isTouchpadGestureClassification(3))
        assertTrue(isTouchpadGestureClassification(4))
        assertTrue(isTouchpadGestureClassification(5))
        assertFalse(isTouchpadGestureClassification(0))
        assertFalse(isTouchpadGestureClassification(1))
    }

    @Test fun staleSessionEventsAreRejected() {
        val buttons = PointerButtons()
        assertTrue(buttons.press(1, PointerOwner.Remote, 100))
        buttons.reset(200)
        assertFalse(buttons.press(1, PointerOwner.Remote, 100))
        assertNull(buttons.release(1, 150))
        assertTrue(buttons.press(1, PointerOwner.Local, 201))
        assertEquals(PointerOwner.Local, buttons.owner(1))
    }

    @Test fun doubleClickRequiresTimeAndDistanceAndAFirstClick() {
        assertTrue(isPointerDoubleClick(100, 10f, 20f, 200, 11f, 21f, 300, 10f))
        assertFalse(isPointerDoubleClick(0, 10f, 20f, 200, 11f, 21f, 300, 10f))
        assertFalse(isPointerDoubleClick(100, 10f, 20f, 500, 11f, 21f, 300, 10f))
        assertFalse(isPointerDoubleClick(100, 10f, 20f, 200, 30f, 20f, 300, 10f))
        assertFalse(isPointerDoubleClick(100, 10f, 20f, 200, 18f, 28f, 300, 10f))
    }
}
