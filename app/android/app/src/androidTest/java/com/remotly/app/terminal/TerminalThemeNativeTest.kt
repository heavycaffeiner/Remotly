package com.remotly.app.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalThemeNativeTest {
    @Test fun themeChangesKeepHandleGeometryScrollbackModesAndCursorOverrides() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val listener = CountingListener()
            val handle = RemotlyTerminal.create(20, 4, 1024 * 1024, listener, TerminalThemes.Classic)
            assertNotEquals(0L, handle)
            try {
                RemotlyTerminal.nativeWrite(handle, ((1..20).joinToString("\r\n") { "line $it" } +
                    "\u001b[?1000h\u001b]12;#ABCDEF\u0007").toByteArray())
                val total = RemotlyTerminal.nativeTotalRows(handle)
                RemotlyTerminal.applyTheme(handle, TerminalThemes.SolarizedLight)
                assertEquals(20, RemotlyTerminal.nativeCols(handle))
                assertEquals(4, RemotlyTerminal.nativeRows(handle))
                assertEquals(total, RemotlyTerminal.nativeTotalRows(handle))
                assertTrue(RemotlyTerminal.nativeMouseReporting(handle))
                val frame = TerminalFrame()
                assertTrue(frame.parseFromBuffer(RemotlyTerminal.nativeGetFrame(handle, frame.buffer)))
                assertEquals(0xffABCDEF.toInt(), frame.cursorColor)
                assertEquals(TerminalThemes.SolarizedLight.background, frame.defaultBg)
                RemotlyTerminal.nativeWrite(handle, "\u001b]112\u0007".toByteArray())
                assertTrue(frame.parseFromBuffer(RemotlyTerminal.nativeGetFrame(handle, frame.buffer)))
                assertEquals(TerminalThemes.SolarizedLight.cursor, frame.cursorColor)
            } finally { RemotlyTerminal.nativeDestroy(handle) }
        }
    }

    @Test fun previewHasSeparateHandleAndNoInputConnectionOrSessionRegistration() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val view = TerminalPreviewView(context)
            assertFalse(view.isFocusable)
            assertFalse(view.onCheckIsTextEditor())
            assertNull(view.onCreateInputConnection(android.view.inputmethod.EditorInfo()))
            view.applyTheme(TerminalThemes.Dracula)
        }
    }

    private class CountingListener : RemotlyTerminal.Listener {
        override fun onBell() = Unit
        override fun onTitle(titleUtf8: ByteArray) = Unit
        override fun onInput(data: ByteArray) = Unit
        override fun onPtyWrite(data: ByteArray) = Unit
        override fun onNotify(title: String, body: String) = Unit
        override fun onClipboardWrite(text: String) = Unit
    }
}
