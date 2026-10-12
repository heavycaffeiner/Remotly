package com.remotly.app.terminal

import kotlin.math.abs

internal enum class PointerOwner { Local, Remote, Font }

internal fun pointerOwner(reporting: Boolean, shift: Boolean, ctrlWheel: Boolean = false): PointerOwner =
    when {
        ctrlWheel -> PointerOwner.Font
        reporting && !shift -> PointerOwner.Remote
        else -> PointerOwner.Local
    }

// Fixed API 34 values keep class loading safe on older Android releases.
internal const val CLASSIFICATION_TWO_FINGER_SWIPE = 3
private const val CLASSIFICATION_MULTI_FINGER_SWIPE = 4
private const val CLASSIFICATION_PINCH = 5
internal const val AXIS_GESTURE_SCROLL_X_DISTANCE = 50
internal const val AXIS_GESTURE_SCROLL_Y_DISTANCE = 51

internal fun isTouchpadGestureClassification(classification: Int): Boolean =
    classification == CLASSIFICATION_TWO_FINGER_SWIPE ||
        classification == CLASSIFICATION_MULTI_FINGER_SWIPE ||
        classification == CLASSIFICATION_PINCH

// Local scrolling uses rows; remote scrolling and zoom use the touch wheel scale.
internal fun touchpadScrollDelta(distancePx: Float, cellPx: Int, owner: PointerOwner): Float {
    if (!distancePx.isFinite() || cellPx <= 0) return 0f
    val rows = distancePx / cellPx
    return if (owner == PointerOwner.Local) rows else rows / WheelReport.ROWS_PER_NOTCH
}

/** Carries fractions only while the event destination and device stay the same. */
internal class PointerScrollAccumulator {
    private var carry = 0f
    private var owner: PointerOwner? = null
    private var device = -1

    fun advance(delta: Float, destination: PointerOwner, deviceId: Int): Int {
        if (!delta.isFinite() || delta == 0f) return 0
        if (owner != destination || device != deviceId || (carry != 0f && (carry > 0f) != (delta > 0f))) {
            carry = 0f
        }
        owner = destination
        device = deviceId
        carry += delta.coerceIn(-120f, 120f)
        val steps = carry.toInt()
        carry -= steps
        return steps
    }

    fun reset() {
        carry = 0f
        owner = null
        device = -1
    }
}

/** Android can deliver a button through both touch and generic-motion paths. */
internal class PointerButtons {
    private val owners = mutableMapOf<Int, PointerOwner>()
    var acceptEventsSince = 0L
        private set

    fun press(button: Int, owner: PointerOwner, time: Long): Boolean {
        if (time < acceptEventsSince || owners.containsKey(button)) return false
        owners[button] = owner
        return true
    }

    fun owner(button: Int): PointerOwner? = owners[button]
    fun release(button: Int, time: Long): PointerOwner? =
        if (time < acceptEventsSince) null else owners.remove(button)
    fun pressedButton(): Int = owners.keys.firstOrNull() ?: -1
    fun remoteButtons(): List<Int> = owners.filterValues { it == PointerOwner.Remote }.keys.toList()
    fun reset(time: Long) {
        owners.clear()
        acceptEventsSince = time
    }
}

internal fun isPointerDoubleClick(
    previousAt: Long,
    previousX: Float,
    previousY: Float,
    at: Long,
    x: Float,
    y: Float,
    timeout: Long,
    slop: Float,
): Boolean = previousAt != 0L && at - previousAt in 0..timeout &&
    abs(x - previousX) <= slop && abs(y - previousY) <= slop &&
    (x - previousX) * (x - previousX) + (y - previousY) * (y - previousY) <= slop * slop
