package com.remotly.app.session

import kotlin.math.abs

// Thresholds for the horizontal swipe that moves between tabs.
//
// The gesture competes with vertical scrolling everywhere it is used, and in
// the terminal with a view that owns vertical drags outright, so it is only
// claimed once it is clearly horizontal. The numbers live here rather than
// in each screen: a swipe that needs a different push depending on which tab
// strip is on screen feels broken.

/** Horizontal travel before the gesture is claimed away from the child view. */
const val SWIPE_CLAIM_PX = 28f

/** How much more horizontal than vertical the drag has to be to count. */
const val SWIPE_AXIS_RATIO = 2.0f

/** Travel that commits the switch on release. */
const val SWIPE_COMMIT_PX = 44f

/** A flick this fast commits regardless of distance, in px per millisecond. */
const val SWIPE_COMMIT_VELOCITY = 0.3f

/** True when a drag in progress should be claimed as a horizontal swipe. */
fun shouldClaimSwipe(dx: Float, dy: Float): Boolean =
    abs(dx) > SWIPE_CLAIM_PX && abs(dx) > abs(dy) * SWIPE_AXIS_RATIO

/**
 * Which way a released drag should move, or 0 to stay put.
 *
 * Distance or speed is enough on its own: a short flick is as deliberate as a
 * long slow drag, and requiring both makes the gesture feel unresponsive.
 * Negative dx (a leftward drag) moves forward, matching the direction a
 * paged view scrolls.
 */
fun swipeDirection(dx: Float, vx: Float): Int {
    val far = abs(dx) >= SWIPE_COMMIT_PX
    val fast = abs(vx) >= SWIPE_COMMIT_VELOCITY && abs(dx) > SWIPE_CLAIM_PX
    if (!far && !fast) return 0
    return if (dx < 0) 1 else -1
}

/**
 * Moves within a tab list, stopping at the ends rather than wrapping.
 *
 * Wrapping would let one swipe jump across the whole strip, which reads as a
 * bug rather than a shortcut. Returns null when there is nowhere to go: no
 * active tab, or already at the end the direction points toward.
 */
fun <T> neighborTab(tabs: List<T>, activeId: String?, direction: Int, idOf: (T) -> String): String? {
    if (direction == 0) return null
    val index = tabs.indexOfFirst { idOf(it) == activeId }
    if (index < 0) return null
    return tabs.getOrNull(index + direction)?.let(idOf)
}
