package com.remotly.app.terminal

/**
 * Decides whether a touch gesture was a one-finger tap.
 *
 * Only a tap opens the keyboard. A pinch is how the font size is changed, and
 * raising the IME in the middle of one would be wrong; a drag is a scroll or a
 * selection. Kept free of Android types so the rules are unit testable.
 */
class TapDetector(private val touchSlopPx: Float) {

    private var startX = 0f
    private var startY = 0f
    private var candidate = false

    val isCandidate: Boolean get() = candidate

    fun onDown(x: Float, y: Float) {
        startX = x
        startY = y
        candidate = true
    }

    /** A second finger means a gesture, not a tap. */
    fun onPointerDown() {
        candidate = false
    }

    fun onMove(x: Float, y: Float) {
        if (!candidate) return
        val dx = x - startX
        val dy = y - startY
        if (dx * dx + dy * dy > touchSlopPx * touchSlopPx) candidate = false
    }

    /** True when this gesture ended as a tap. Consumes the candidate state. */
    fun onUp(pointerCount: Int): Boolean {
        val tap = candidate && pointerCount == 1
        candidate = false
        return tap
    }

    fun onCancel() {
        candidate = false
    }
}
