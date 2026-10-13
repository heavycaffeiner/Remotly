package com.remotly.app.terminal

import android.graphics.Rect
import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup

internal fun isTouchpadScrollGesture(event: MotionEvent): Boolean {
    if (event.pointerCount == 0) return false
    val physical = event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
        || event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    if (!physical || event.actionMasked == MotionEvent.ACTION_SCROLL) return false
    val classification = if (Build.VERSION.SDK_INT >= 29) event.classification else 0
    val travel = event.getAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE)
    val horizontal = event.getAxisValue(AXIS_GESTURE_SCROLL_X_DISTANCE)
    return isTouchpadGestureClassification(classification) ||
        event.isFromSource(InputDevice.SOURCE_TOUCHPAD) && event.pointerCount >= 2 ||
        travel.isFinite() && travel != 0f || horizontal.isFinite() && horizontal != 0f
}

/** Compose 1.7 drops repeated mouse gestures before AndroidView receives them. */
internal class TerminalTouchpadDispatcher(private val root: View) {
    private var target: TerminalView? = null
    private var deviceId = -1
    private var anchorX = 0f
    private var anchorY = 0f

    fun dispatch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN || event.deviceId != deviceId) target = null
        if (target == null) {
            val padWheel = event.actionMasked == MotionEvent.ACTION_SCROLL && event.isFromSource(InputDevice.SOURCE_TOUCHPAD)
            if ((!isTouchpadScrollGesture(event) && !padWheel) || !root.hasWindowFocus() ||
                !event.rawX.isFinite() || !event.rawY.isFinite()
            ) return false
            val view = findTerminal(root, event.rawX.toInt(), event.rawY.toInt()) ?: return false
            target = view
            deviceId = event.deviceId
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            anchorX = event.rawX - location[0]
            anchorY = event.rawY - location[1]
        }
        val view = target ?: return false
        if (root.hasWindowFocus() && view.isAttachedToWindow && view.isShown && view.touchpadGesturesEnabled) {
            val local = MotionEvent.obtain(event)
            try {
                // Preserve raw two-pointer movement; classified axes keep the stationary cursor anchor.
                val classified = Build.VERSION.SDK_INT >= 29 && isTouchpadGestureClassification(event.classification)
                if (classified || event.actionMasked == MotionEvent.ACTION_SCROLL) local.setLocation(anchorX, anchorY)
                else {
                    val location = IntArray(2)
                    view.getLocationOnScreen(location)
                    local.offsetLocation(-location[0].toFloat(), -location[1].toFloat())
                }
                if (event.actionMasked == MotionEvent.ACTION_SCROLL) view.onGenericMotionEvent(local)
                else view.onTouchpadGesture(local)
            } finally {
                local.recycle()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL ||
            event.actionMasked == MotionEvent.ACTION_SCROLL
        ) target = null
        return true
    }

    private fun findTerminal(view: View, x: Int, y: Int): TerminalView? {
        if (!view.isShown) return null
        if (view is TerminalView && view.touchpadGesturesEnabled) {
            val bounds = Rect()
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            if (view.getLocalVisibleRect(bounds)) {
                bounds.offset(location[0], location[1])
                if (bounds.contains(x, y)) return view
            }
        }
        if (view is ViewGroup) {
            for (index in view.childCount - 1 downTo 0) {
                findTerminal(view.getChildAt(index), x, y)?.let { return it }
            }
        }
        return null
    }
}
