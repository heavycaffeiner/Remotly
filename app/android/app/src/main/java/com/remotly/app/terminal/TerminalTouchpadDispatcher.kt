package com.remotly.app.terminal

import android.view.InputDevice
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.graphics.Rect

/** Compose 1.7 drops repeated classified mouse gestures before AndroidView receives them. */
internal class TerminalTouchpadDispatcher(private val root: View) {
    private var target: TerminalView? = null
    private var deviceId = -1
    private var anchorX = 0f
    private var anchorY = 0f

    fun dispatch(event: MotionEvent): Boolean {
        val classified = Build.VERSION.SDK_INT >= 34 && event.pointerCount > 0 &&
            (event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_TOUCHPAD)) &&
            isTouchpadGestureClassification(event.classification)
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            target = null
            if (!classified || !root.hasWindowFocus() || !event.rawX.isFinite() || !event.rawY.isFinite()) return false
            val view = findTerminal(root, event.rawX.toInt(), event.rawY.toInt()) ?: return false
            target = view
            deviceId = event.deviceId
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            anchorX = event.rawX - location[0]
            anchorY = event.rawY - location[1]
        }
        val view = target ?: return false
        if (event.deviceId != deviceId) return false
        if (root.hasWindowFocus() && view.isAttachedToWindow && view.isShown && view.touchpadGesturesEnabled) {
            val local = MotionEvent.obtain(event)
            try {
                // Gesture coordinates travel with the fingers, not with the stationary cursor.
                local.setLocation(anchorX, anchorY)
                view.onTouchpadGesture(local)
            } finally {
                local.recycle()
            }
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            target = null
        }
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
