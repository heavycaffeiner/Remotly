package com.remotly.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SwipeNavTest {

    @Test
    fun `ignores a drag that has barely moved`() {
        assertFalse(shouldClaimSwipe(SWIPE_CLAIM_PX - 1, 0f))
    }

    @Test
    fun `claims a clearly horizontal drag`() {
        assertTrue(shouldClaimSwipe(SWIPE_CLAIM_PX + 10, 0f))
        assertTrue(shouldClaimSwipe(-(SWIPE_CLAIM_PX + 10), 0f))
    }

    // Vertical drags belong to whatever is being scrolled underneath.
    @Test
    fun `leaves a vertical drag alone`() {
        assertFalse(shouldClaimSwipe(20f, 200f))
    }

    @Test
    fun `needs more horizontal travel than vertical`() {
        val dy = 30f
        assertFalse(shouldClaimSwipe(dy * SWIPE_AXIS_RATIO - 1, dy))
        assertTrue(shouldClaimSwipe(dy * SWIPE_AXIS_RATIO + 5, dy))
    }

    @Test
    fun `stays put for a short slow drag`() {
        assertEquals(0, swipeDirection(10f, 0f))
    }

    @Test
    fun `moves forward when dragged left`() {
        assertEquals(1, swipeDirection(-(SWIPE_COMMIT_PX + 1), 0f))
    }

    @Test
    fun `moves back when dragged right`() {
        assertEquals(-1, swipeDirection(SWIPE_COMMIT_PX + 1, 0f))
    }

    // A quick flick is as deliberate as a long drag, so speed commits on its own.
    @Test
    fun `commits a short fast flick`() {
        assertEquals(1, swipeDirection(-(SWIPE_CLAIM_PX + 5), -1.2f))
    }

    @Test
    fun `ignores speed when the drag never really started`() {
        assertEquals(0, swipeDirection(2f, -3f))
    }

    // -- moving within the strip --------------------------------------------------------

    private data class Tab(val id: String)

    private val tabs = listOf(Tab("a"), Tab("b"), Tab("c"))

    @Test
    fun `moves to the next tab`() {
        assertEquals("b", neighborTab(tabs, "a", 1) { it.id })
    }

    @Test
    fun `moves to the previous tab`() {
        assertEquals("b", neighborTab(tabs, "c", -1) { it.id })
    }

    @Test
    fun `stops at the last tab instead of wrapping`() {
        assertNull(neighborTab(tabs, "c", 1) { it.id })
    }

    @Test
    fun `stops at the first tab instead of wrapping`() {
        assertNull(neighborTab(tabs, "a", -1) { it.id })
    }

    @Test
    fun `does nothing for a direction of zero`() {
        assertNull(neighborTab(tabs, "b", 0) { it.id })
    }

    @Test
    fun `does nothing when nothing is active`() {
        assertNull(neighborTab(tabs, null, 1) { it.id })
    }
}
