package com.remotly.app.terminal

import android.view.InputDevice
import android.view.MotionEvent
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.remotly.app.MainActivity
import com.remotly.app.ui.terminal.TerminalHandle
import com.remotly.app.ui.terminal.TerminalPane
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34)
class TerminalTouchpadDispatchTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun repeatedGesturesReachTheSameTerminalAndKeepTheCursorAnchor() {
        val handle = TerminalHandle()
        val input = StringBuilder()
        compose.activity.setContent {
            TerminalPane(sessionKey = "touchpad-dispatch-test", fontSizeSp = 14, cursorStyle = "block",
                onInput = { input.append(it.toString(Charsets.UTF_8)) },
                onPtyWrite = { input.append(it.toString(Charsets.UTF_8)) },
                onResize = { _, _ -> }, onLinkCopied = {}, onFontSizeChanged = {},
                handle = handle, modifier = Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = requireNotNull(handle.view)
            val native = TerminalStore.take("touchpad-dispatch-test")
            assertTrue("Terminal has no native handle", native != 0L)
            view.feed("\u001b[?1049h\u001b[?1003h\u001b[?1006h".toByteArray())
            assertTrue("Mouse reporting not enabled", RemotlyTerminal.nativeMouseReporting(native))
            assertTrue("Activity lacks window focus", compose.activity.window.decorView.hasWindowFocus())
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = location[0] + view.width / 2f
            val y = location[1] + view.height / 2f
            val time = SystemClock.uptimeMillis() + 10
            repeat(3) { gesture ->
                val start = time + gesture * 10
                dispatch(event(MotionEvent.ACTION_DOWN, start, x, y, 0f))
                dispatch(event(MotionEvent.ACTION_MOVE, start + 1, x, y + 300f, 300f))
                dispatch(event(MotionEvent.ACTION_UP, start + 2, x, y + 300f, 0f))
            }
            val reports = input.toString().split("\u001b[<").filter { it.isNotEmpty() }
            assertTrue("Missing wheel reports: $reports", reports.size >= 3)
            assertTrue("Unexpected pointer reports: $reports", reports.all { it.startsWith("65;") })
            assertEquals(1, reports.distinct().size)
            assertFalse(view.hasSelection)
        }
    }

    @Test fun gestureStartingWithClassifiedMoveBypassesCompose() {
        val handle = TerminalHandle()
        val input = StringBuilder()
        compose.activity.setContent {
            TerminalPane(sessionKey = "touchpad-late-dispatch", fontSizeSp = 14, cursorStyle = "block",
                onInput = { input.append(it.toString(Charsets.UTF_8)) },
                onPtyWrite = { input.append(it.toString(Charsets.UTF_8)) },
                onResize = { _, _ -> }, onLinkCopied = {}, onFontSizeChanged = {},
                handle = handle, modifier = Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = requireNotNull(handle.view)
            view.feed("\u001b[?1002h\u001b[?1006h".toByteArray())
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = location[0] + view.width / 2f
            val y = location[1] + view.height / 2f
            val time = SystemClock.uptimeMillis() + 10
            dispatch(event(MotionEvent.ACTION_MOVE, time, x, y, 200f))
            dispatch(event(MotionEvent.ACTION_UP, time + 1, x, y, 0f))
            assertTrue(input.toString().startsWith("\u001b[<65;"))
            assertFalse(input.toString().contains("\u001b[<0;"))
            assertFalse(view.hasSelection)
        }
    }

    @Test fun rawTwoPointerTouchpadGestureBypassesCompose() {
        val handle = TerminalHandle()
        val input = StringBuilder()
        compose.activity.setContent {
            TerminalPane(sessionKey = "touchpad-raw-dispatch", fontSizeSp = 14, cursorStyle = "block",
                onInput = { input.append(it.toString(Charsets.UTF_8)) },
                onPtyWrite = { input.append(it.toString(Charsets.UTF_8)) },
                onResize = { _, _ -> }, onLinkCopied = {}, onFontSizeChanged = {},
                handle = handle, modifier = Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = requireNotNull(handle.view)
            view.feed("\u001b[?1002h\u001b[?1006h".toByteArray())
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val x = location[0] + view.width / 2f
            val y = location[1] + view.height / 2f
            val time = SystemClock.uptimeMillis() + 10
            fun raw(action: Int, at: Long, currentY: Float): MotionEvent {
                val properties = Array(2) { index -> MotionEvent.PointerProperties().apply {
                    id = index; toolType = MotionEvent.TOOL_TYPE_MOUSE
                } }
                val coordinates = Array(2) { index -> MotionEvent.PointerCoords().apply {
                    this.x = x + index * 20f; this.y = currentY
                } }
                return requireNotNull(MotionEvent.obtain(at, at, action, 2, properties, coordinates,
                    0, 0, 1f, 1f, 1, 0, InputDevice.SOURCE_TOUCHPAD, 0))
            }
            dispatch(raw(MotionEvent.ACTION_DOWN, time, y))
            dispatch(raw(MotionEvent.ACTION_MOVE, time + 1, y + 200))
            dispatch(raw(MotionEvent.ACTION_UP, time + 2, y + 200))
            assertTrue(input.toString().startsWith("\u001b[<64;"))
            assertFalse(input.toString().contains("\u001b[<0;"))
            assertFalse(view.hasSelection)
        }
    }

    @Test fun touchpadWheelReachesTerminalThroughGenericActivityDispatch() {
        val handle = TerminalHandle()
        val input = StringBuilder()
        compose.activity.setContent {
            TerminalPane(sessionKey = "touchpad-wheel-dispatch", fontSizeSp = 14, cursorStyle = "block",
                onInput = { input.append(it.toString(Charsets.UTF_8)) },
                onPtyWrite = { input.append(it.toString(Charsets.UTF_8)) },
                onResize = { _, _ -> }, onLinkCopied = {}, onFontSizeChanged = {},
                handle = handle, modifier = Modifier.fillMaxSize())
        }
        compose.waitForIdle()
        compose.runOnIdle {
            val view = requireNotNull(handle.view)
            view.feed("\u001b[?1002h\u001b[?1006h".toByteArray())
            val location = IntArray(2)
            view.getLocationOnScreen(location)
            val point = MotionEvent.PointerCoords().apply {
                x = location[0] + view.width / 2f
                y = location[1] + view.height / 2f
                setAxisValue(MotionEvent.AXIS_VSCROLL, 0.25f)
            }
            val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
            val time = SystemClock.uptimeMillis() + 10
            repeat(4) { sample ->
                val wheel = requireNotNull(MotionEvent.obtain(time + sample, time + sample, MotionEvent.ACTION_SCROLL,
                    1, arrayOf(properties), arrayOf(point), 0, 0, 1f, 1f, 1, 0, InputDevice.SOURCE_TOUCHPAD, 0))
                try { assertTrue(compose.activity.dispatchGenericMotionEvent(wheel)) } finally { wheel.recycle() }
            }
            assertTrue(input.toString().startsWith("\u001b[<64;"))
            assertEquals(1, input.toString().split("\u001b[<").count { it.startsWith("64;") })
            assertFalse(view.hasSelection)
        }
    }

    private fun dispatch(event: MotionEvent) {
        try { assertTrue(compose.activity.dispatchTouchEvent(event)) } finally { event.recycle() }
    }

    private fun event(action: Int, time: Long, x: Float, y: Float, travel: Float): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coordinates = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE, travel)
        }
        return requireNotNull(MotionEvent.obtain(time, time, action, 1, arrayOf(properties), arrayOf(coordinates),
            0, 0, 1f, 1f, 1, 0, InputDevice.SOURCE_MOUSE, 0, 0, CLASSIFICATION_TWO_FINGER_SWIPE))
    }
}
