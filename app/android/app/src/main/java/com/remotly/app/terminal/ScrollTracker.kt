package com.remotly.app.terminal

import kotlin.math.abs

/**
 * Turns a one-finger vertical drag into whole-row viewport scrolling.
 *
 * The terminal scrolls in rows, not pixels, so leftover pixels are carried
 * between moves. Dropping them would make a slow drag scroll nothing at all
 * and a fast one drift short of the finger.
 *
 * Kept free of Android types so the arithmetic is unit testable.
 */
class ScrollTracker(private val touchSlopPx: Float) {

    private var lastY = 0f
    private var carryPx = 0f
    private var active = false
    private var armed = false

    /** True once the drag passed the slop and is scrolling. */
    val isScrolling: Boolean get() = active

    fun onDown(y: Float) {
        lastY = y
        carryPx = 0f
        active = false
        armed = true
    }

    /** A second finger hands the gesture to the pinch detector. */
    fun onPointerDown() {
        active = false
        armed = false
    }

    /**
     * Reports a move and returns how many rows to scroll.
     *
     * Positive scrolls toward the scrollback, matching the direction the
     * content moves under the finger: dragging down reveals older output.
     */
    fun onMove(y: Float, cellHeightPx: Int): Int {
        if (!armed || cellHeightPx <= 0) return 0
        val dy = y - lastY
        if (!active) {
            if (abs(dy) < touchSlopPx) return 0
            active = true
            // Start measuring from where the slop was crossed, so the first
            // step is not the whole slop distance at once.
            lastY = y
            return 0
        }
        lastY = y
        carryPx += dy
        val rows = (carryPx / cellHeightPx).let {
            if (it < 0) -((-it).toInt()) else it.toInt()
        }
        if (rows == 0) return 0
        carryPx -= rows * cellHeightPx
        return rows
    }

    fun onUp() {
        active = false
        armed = false
        carryPx = 0f
    }

    fun onCancel() = onUp()
}
