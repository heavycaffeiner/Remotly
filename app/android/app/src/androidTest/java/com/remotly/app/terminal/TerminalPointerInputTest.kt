package com.remotly.app.terminal

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalPointerInputTest {
    private class Recorder : TerminalView.Host {
        val input = StringBuilder()
        var menus = 0
        var navigation = 0
        var resizes = 0
        var fontSize = 0
        override fun onReady(cols: Int, rows: Int) = Unit
        override fun onError(code: String) { error(code) }
        override fun onInput(data: ByteArray) { input.append(data.toString(Charsets.UTF_8)) }
        override fun onPtyWrite(data: ByteArray) { input.append(data.toString(Charsets.UTF_8)) }
        override fun onResize(cols: Int, rows: Int) { resizes++ }
        override fun onBell() = Unit
        override fun onTitle(title: String) = Unit
        override fun onFontSizeChange(fontSizeSp: Int) { fontSize = fontSizeSp }
        override fun onFocusChange(focused: Boolean) = Unit
        override fun onSelectionChange(active: Boolean) = Unit
        override fun onPasteRequest() = Unit
        override fun onNotify(title: String, body: String) = Unit
        override fun onLinkCopied(link: String) = Unit
        override fun onContextMenuRequested(x: Float, y: Float) { menus++ }
        override fun onDoubleTap(): Boolean { navigation++; return true }
    }

    private fun withTerminal(block: (TerminalView, Recorder) -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val view = TerminalView(InstrumentationRegistry.getInstrumentation().targetContext)
            val recorder = Recorder()
            val id = "pointer-test-${System.nanoTime()}"
            view.host = recorder
            view.sessionId = id
            view.layout(0, 0, 800, 480)
            try { block(view, recorder) } finally {
                view.release()
                TerminalStore.release(id)
            }
        }
    }

    private fun event(
        action: Int,
        time: Long,
        x: Float = 10f,
        y: Float = 10f,
        buttons: Int = MotionEvent.BUTTON_PRIMARY,
        modifiers: Int = 0,
        wheel: Float = 0f,
        touch: Boolean = false,
    ): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply {
            id = 0
            toolType = if (touch) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_MOUSE
        }
        val coords = MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            setAxisValue(MotionEvent.AXIS_VSCROLL, wheel)
        }
        return MotionEvent.obtain(time, time, action, 1, arrayOf(properties), arrayOf(coords),
            modifiers, buttons, 1f, 1f, 1, 0,
            if (touch) InputDevice.SOURCE_TOUCHSCREEN else InputDevice.SOURCE_MOUSE, 0)
    }

    private fun send(view: TerminalView, event: MotionEvent, generic: Boolean = false) {
        try {
            if (generic) view.onGenericMotionEvent(event) else view.onTouchEvent(event)
        } finally { event.recycle() }
    }

    @Test fun remoteButtonsModifiersMotionAndDuplicatePaths() = withTerminal { view, recorder ->
        view.feed("\u001b[?1003h\u001b[?1006h".toByteArray())
        val time = SystemClock.uptimeMillis() + 10
        val mods = KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON
        send(view, event(MotionEvent.ACTION_DOWN, time, modifiers = mods))
        val press = recorder.input.toString()
        assertTrue(press.contains("\u001b[<24;"))
        send(view, event(MotionEvent.ACTION_DOWN, time, modifiers = mods), generic = true)
        assertEquals(press, recorder.input.toString())
        send(view, event(MotionEvent.ACTION_MOVE, time + 1, x = 50f, modifiers = mods))
        val motion = recorder.input.toString()
        assertTrue(motion.contains("\u001b[<56;"))
        send(view, event(MotionEvent.ACTION_MOVE, time + 1, x = 50f, modifiers = mods), generic = true)
        assertEquals(motion, recorder.input.toString())
        send(view, event(MotionEvent.ACTION_UP, time + 2, x = 50f, modifiers = mods))
        assertTrue(recorder.input.endsWith("m"))
        assertEquals(0, recorder.menus)
    }

    @Test fun rightClickBelongsToRemoteUntilShiftBypassesIt() = withTerminal { view, recorder ->
        view.feed("\u001b[?1000h\u001b[?1006h".toByteArray())
        val time = SystemClock.uptimeMillis() + 10
        send(view, event(MotionEvent.ACTION_DOWN, time, buttons = MotionEvent.BUTTON_SECONDARY))
        send(view, event(MotionEvent.ACTION_UP, time + 1, buttons = MotionEvent.BUTTON_SECONDARY))
        assertTrue(recorder.input.startsWith("\u001b[<2;"))
        assertEquals(0, recorder.menus)
        recorder.input.setLength(0)
        val before = recorder.resizes
        val connection = view.onCreateInputConnection(EditorInfo()) as TerminalInputConnection
        connection.setComposingText("한", 1)
        send(view, event(MotionEvent.ACTION_DOWN, time + 2, buttons = MotionEvent.BUTTON_SECONDARY,
            modifiers = KeyEvent.META_SHIFT_ON))
        send(view, event(MotionEvent.ACTION_UP, time + 3, buttons = MotionEvent.BUTTON_SECONDARY,
            modifiers = KeyEvent.META_SHIFT_ON))
        assertEquals("", recorder.input.toString())
        assertEquals(1, recorder.menus)
        assertEquals(before, recorder.resizes)
        assertTrue(connection.isComposing)
    }

    @Test fun shiftDragAndMouseDoubleClickSelectWithoutNavigation() = withTerminal { view, recorder ->
        view.feed("hello world\r\n\u001b[?1003h\u001b[?1006h".toByteArray())
        view.legacyTouchNavigationEnabled = true
        val time = SystemClock.uptimeMillis() + 10
        send(view, event(MotionEvent.ACTION_DOWN, time, modifiers = KeyEvent.META_SHIFT_ON))
        send(view, event(MotionEvent.ACTION_MOVE, time + 1, x = 100f, modifiers = KeyEvent.META_SHIFT_ON))
        send(view, event(MotionEvent.ACTION_UP, time + 2, x = 100f, modifiers = KeyEvent.META_SHIFT_ON))
        assertTrue(view.hasSelection)
        assertEquals("", recorder.input.toString())
        send(view, event(MotionEvent.ACTION_DOWN, time + 1000))
        send(view, event(MotionEvent.ACTION_UP, time + 1001))
        val firstClick = recorder.input.toString()
        send(view, event(MotionEvent.ACTION_DOWN, time + 1100))
        send(view, event(MotionEvent.ACTION_UP, time + 1101))
        assertTrue(view.hasSelection)
        assertEquals(firstClick, recorder.input.toString())
        assertEquals(0, recorder.navigation)
    }

    @Test fun controlWheelAdjustsFontLocallyAndClampsAtMaximum() = withTerminal { view, recorder ->
        view.feed("\u001b[?1003h\u001b[?1006h".toByteArray())
        view.setFontSizeSp(14f)
        val time = SystemClock.uptimeMillis() + 10
        send(view, event(MotionEvent.ACTION_SCROLL, time, wheel = 1f,
            modifiers = KeyEvent.META_CTRL_ON), generic = true)
        assertEquals(15, recorder.fontSize)
        send(view, event(MotionEvent.ACTION_SCROLL, time + 1, wheel = 100f,
            modifiers = KeyEvent.META_CTRL_ON), generic = true)
        assertEquals(32, recorder.fontSize)
        assertEquals("", recorder.input.toString())
    }

    @Test fun pointerCoordinatesAreClampedToTheDisplayedGrid() = withTerminal { view, recorder ->
        view.feed("\u001b[?1000h\u001b[?1006h".toByteArray())
        val time = SystemClock.uptimeMillis() + 10
        send(view, event(MotionEvent.ACTION_DOWN, time, x = -100f, y = -100f))
        send(view, event(MotionEvent.ACTION_UP, time + 1, x = -100f, y = -100f))
        assertEquals("\u001b[<0;1;1M\u001b[<0;1;1m", recorder.input.toString())
        recorder.input.setLength(0)
        send(view, event(MotionEvent.ACTION_DOWN, time + 1000, x = 10000f, y = 10000f))
        send(view, event(MotionEvent.ACTION_UP, time + 1001, x = 10000f, y = 10000f))
        assertEquals("\u001b[<0;${view.gridCols};${view.gridRows}M\u001b[<0;${view.gridCols};${view.gridRows}m",
            recorder.input.toString())
    }

    @Test fun fractionalRemoteWheelAndStaleReconnectEvents() = withTerminal { view, recorder ->
        view.feed("\u001b[?1000h\u001b[?1006h".toByteArray())
        val time = SystemClock.uptimeMillis() + 10
        repeat(3) { send(view, event(MotionEvent.ACTION_SCROLL, time + it, wheel = 0.25f), generic = true) }
        assertEquals("", recorder.input.toString())
        send(view, event(MotionEvent.ACTION_SCROLL, time + 3, wheel = 0.25f), generic = true)
        assertTrue(recorder.input.startsWith("\u001b[<64;"))
        recorder.input.setLength(0)
        view.resetConnectionInput()
        send(view, event(MotionEvent.ACTION_DOWN, 1))
        send(view, event(MotionEvent.ACTION_UP, 2))
        assertEquals("", recorder.input.toString())
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun touchpadScrollReversesWithoutClickingOrSelecting() = withTerminal { view, recorder ->
        view.feed((1..300).joinToString("\r\n", postfix = "\r\n").toByteArray())
        val bitmap = android.graphics.Bitmap.createBitmap(800, 480, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        view.draw(canvas)
        val before = view.viewportText()
        val time = SystemClock.uptimeMillis() + 10
        repeat(2) { swipe ->
            val start = time + swipe * 10
            send(view, touchpadEvent(MotionEvent.ACTION_DOWN, start))
            send(view, touchpadEvent(MotionEvent.ACTION_MOVE, start + 1, -200f))
            send(view, touchpadEvent(MotionEvent.ACTION_UP, start + 2))
        }
        view.draw(canvas)
        val older = view.viewportText()
        assertTrue(older.lineSequence().first().toInt() < before.lineSequence().first().toInt())
        send(view, touchpadEvent(MotionEvent.ACTION_DOWN, time + 30))
        send(view, touchpadEvent(MotionEvent.ACTION_MOVE, time + 31, 200f))
        send(view, touchpadEvent(MotionEvent.ACTION_UP, time + 32))
        view.draw(canvas)
        assertTrue(view.viewportText().lineSequence().first().toInt() > older.lineSequence().first().toInt())
        bitmap.recycle()
        assertFalse(view.hasSelection)
        assertEquals("", recorder.input.toString())
    }

    @androidx.test.filters.SdkSuppress(minSdkVersion = 34)
    @Test fun batchedTouchpadTravelGoesOnlyToRemoteWheelOrModifierOverride() = withTerminal { view, recorder ->
        view.feed("\u001b[?1049h\u001b[?1003h\u001b[?1006h".toByteArray())
        val time = SystemClock.uptimeMillis() + 10
        send(view, touchpadEvent(MotionEvent.ACTION_DOWN, time))
        val move = touchpadEvent(MotionEvent.ACTION_MOVE, time + 1, 150f)
        val coords = MotionEvent.PointerCoords().apply {
            x = 10f; y = 10f
            setAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE, 150f)
        }
        move.addBatch(time + 2, arrayOf(coords), 0)
        send(view, move)
        send(view, touchpadEvent(MotionEvent.ACTION_UP, time + 3))
        val reports = recorder.input.toString()
        assertTrue(reports.startsWith("\u001b[<65;"))
        assertTrue(reports.split("\u001b[<").drop(1).all { it.startsWith("65;") && it.endsWith("M") })
        assertFalse(view.hasSelection)
        recorder.input.setLength(0)
        send(view, touchpadEvent(MotionEvent.ACTION_DOWN, time + 10))
        send(view, touchpadEvent(MotionEvent.ACTION_MOVE, time + 11, 300f, KeyEvent.META_SHIFT_ON))
        send(view, touchpadEvent(MotionEvent.ACTION_UP, time + 12))
        assertEquals("", recorder.input.toString())
        view.setFontSizeSp(14f)
        send(view, touchpadEvent(MotionEvent.ACTION_DOWN, time + 20))
        send(view, touchpadEvent(MotionEvent.ACTION_MOVE, time + 21, -300f, KeyEvent.META_CTRL_ON))
        send(view, touchpadEvent(MotionEvent.ACTION_UP, time + 22))
        assertTrue(recorder.fontSize > 14)
        assertEquals("", recorder.input.toString())
    }

    @androidx.annotation.RequiresApi(34)
    private fun touchpadEvent(action: Int, time: Long, travel: Float = 0f, modifiers: Int = 0): MotionEvent {
        val properties = MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_MOUSE }
        val coords = MotionEvent.PointerCoords().apply {
            x = 10f; y = 10f
            setAxisValue(AXIS_GESTURE_SCROLL_Y_DISTANCE, travel)
        }
        return requireNotNull(MotionEvent.obtain(time, time, action, 1, arrayOf(properties), arrayOf(coords),
            modifiers, 0, 1f, 1f, 1, 0, InputDevice.SOURCE_MOUSE, 0, 0, CLASSIFICATION_TWO_FINGER_SWIPE))
    }

    @Test fun touchDoubleTapSelectsUnlessLegacyNavigationIsEnabled() = withTerminal { view, recorder ->
        view.feed("hello world".toByteArray())
        val time = SystemClock.uptimeMillis() + 10
        send(view, event(MotionEvent.ACTION_DOWN, time, touch = true))
        send(view, event(MotionEvent.ACTION_UP, time + 1, touch = true))
        send(view, event(MotionEvent.ACTION_DOWN, time + 100, touch = true))
        send(view, event(MotionEvent.ACTION_UP, time + 101, touch = true))
        assertTrue(view.hasSelection)
        assertEquals(0, recorder.navigation)
        view.clearSelection()
        view.legacyTouchNavigationEnabled = true
        send(view, event(MotionEvent.ACTION_DOWN, time + 1000, touch = true))
        send(view, event(MotionEvent.ACTION_UP, time + 1001, touch = true))
        send(view, event(MotionEvent.ACTION_DOWN, time + 1100, touch = true))
        send(view, event(MotionEvent.ACTION_UP, time + 1101, touch = true))
        assertFalse(view.hasSelection)
        assertEquals(1, recorder.navigation)
    }
}
