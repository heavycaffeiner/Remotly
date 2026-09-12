package com.remotly.app.terminal

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.remotly.app.ui.terminal.ModifierKey
import com.remotly.app.ui.terminal.extraKeyEncoding
import com.remotly.app.ui.terminal.modifiedTextEncoding
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalReconnectInputTest {
    private class Recorder : RemotlyTerminal.Listener {
        val input = StringBuilder()
        override fun onInput(data: ByteArray) { input.append(data.toString(Charsets.UTF_8)) }
        override fun onPtyWrite(data: ByteArray) = Unit
        override fun onBell() = Unit
        override fun onTitle(titleUtf8: ByteArray) = Unit
        override fun onNotify(title: String, body: String) = Unit
        override fun onClipboardWrite(text: String) = Unit
    }

    @Test
    fun shiftKeepsTheUnshiftedKeyIdentityInKittyMode() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val recorder = Recorder()
            val handle = RemotlyTerminal.nativeCreate(80, 24, 8L * 1024 * 1024, recorder)
            assertTrue(handle != 0L)
            try {
                RemotlyTerminal.nativeWrite(handle, "\u001b[>9u".toByteArray())
                val shiftedLetter = modifiedTextEncoding("a", ModifierKey.SHIFT)!!
                RemotlyTerminal.nativeSendKey(handle, shiftedLetter.key, shiftedLetter.mods, shiftedLetter.utf8, false)
                val shiftedTab = extraKeyEncoding("shift-tab", null)!!
                RemotlyTerminal.nativeSendKey(handle, shiftedTab.key, shiftedTab.mods, shiftedTab.utf8, false)
                assertEquals("\u001b[97;2u\u001b[9;2u", recorder.input.toString())
            } finally {
                RemotlyTerminal.nativeDestroy(handle)
            }
        }
    }

    @Test
    fun reconnectClearsMouseModesWithoutDiscardingShellHistory() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val recorder = Recorder()
            val handle = RemotlyTerminal.nativeCreate(80, 24, 8L * 1024 * 1024, recorder)
            assertTrue(handle != 0L)
            val sessionId = "native-reconnect-${System.nanoTime()}"
            TerminalStore.retain(sessionId, handle)
            try {
                repeat(100) { RemotlyTerminal.nativeWrite(handle, "history marker $it\r\n".toByteArray()) }
                val before = RemotlyTerminal.nativeScrollbar(handle)!!
                RemotlyTerminal.nativeWrite(handle, "\u001b[?1049h\u001b[?1003h\u001b[?1006h\u001b[?10".toByteArray())
                assertTrue(RemotlyTerminal.nativeMouseReporting(handle))

                TerminalStore.resetForConnection(sessionId)

                assertFalse(RemotlyTerminal.nativeMouseReporting(handle))
                val restored = RemotlyTerminal.nativeScrollbar(handle)!!
                assertEquals(before[0], restored[0])
                RemotlyTerminal.nativeSelectAll(handle)
                val history = RemotlyTerminal.nativeGetSelectionText(handle)!!.toString(Charsets.UTF_8)
                assertTrue(history.contains("history marker 0"))
                assertTrue(history.contains("history marker 99"))
                RemotlyTerminal.nativeClearSelection(handle)
                RemotlyTerminal.nativeScrollViewport(handle, -10)
                assertTrue(RemotlyTerminal.nativeScrollbar(handle)!![1] < restored[1])
                assertFalse(RemotlyTerminal.nativeSendMouse(handle, 0, 4, 0, 5, 5, 10, 20))
                assertEquals("", recorder.input.toString())
            } finally {
                TerminalStore.release(sessionId)
            }
        }
    }
}
