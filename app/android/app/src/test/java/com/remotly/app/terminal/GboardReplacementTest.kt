package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GboardReplacementTest {
    private class Recorder : TerminalSink {
        val terminal = StringBuilder()
        var deletes = 0
        override fun sendText(text: String) { terminal.append(text) }
        override fun sendKey(event: KeyEvent, composing: Boolean) {
            // These controller scenarios emit only DEL; Android KeyEvent is stubbed in JVM tests.
            deletes++
            if (terminal.isNotEmpty()) terminal.delete(terminal.offsetByCodePoints(terminal.length, -1), terminal.length)
        }
        override fun onCompositionChanged(state: CompositionState) = Unit
    }

    @Test fun betaJamoReplacementsAndCompoundFinalConsonantsReachTheTerminal() {
        val sink = Recorder()
        val ime = TerminalImeController(sink)
        ime.onCommitText("ㅇ")
        for (text in listOf("아", "안", "앉", "안자")) assertTrue(ime.replaceText(0, 1, text, 1))
        assertEquals("안자", sink.terminal.toString())
        assertEquals("안자", ime.editorComposition.text)
        assertFalse(ime.isComposing)
    }

    @Test fun betaBackspaceAndAutocorrectRetainAbsoluteOffsets() {
        val sink = Recorder()
        val ime = TerminalImeController(sink)
        "teh quick".forEach { ime.onCommitText(it.toString()) }
        repeat(2) {
            ime.terminalKeySent(KeyEvent.KEYCODE_DEL)
            sink.terminal.deleteCharAt(sink.terminal.length - 1)
        }
        ime.onCommitText("k")
        assertTrue(ime.replaceText(4, 8, "quick ", 1))
        "tset".forEach { ime.onCommitText(it.toString()) }
        assertTrue(ime.replaceText(10, 14, "test ", 1))
        assertEquals("teh quick test ", sink.terminal.toString())
        assertEquals(sink.terminal.toString(), ime.editorComposition.text)
    }

    @Test fun betaBackspaceCanDecomposeThePreviousSyllable() {
        val sink = Recorder()
        val ime = TerminalImeController(sink)
        ime.onCommitText("ㅎ")
        ime.replaceText(0, 1, "한", 1)
        ime.onCommitText("ㄱ")
        for (text in listOf("그", "글", "그", "ㄱ")) ime.replaceText(1, 2, text, 1)
        ime.terminalKeySent(KeyEvent.KEYCODE_DEL)
        sink.terminal.deleteCharAt(sink.terminal.length - 1)
        assertTrue(ime.replaceText(0, 1, "하", 1))
        assertEquals("하", sink.terminal.toString())
    }

    @Test fun invalidOrSubmittedRangesCannotRetractTerminalText() {
        val sink = Recorder()
        val ime = TerminalImeController(sink)
        ime.onCommitText("a😀b")
        for ((start, end) in listOf(-1 to 2, 0 to 10, 1 to 2, 2 to 3, Int.MAX_VALUE to Int.MAX_VALUE)) {
            assertFalse(ime.replaceText(start, end, "x", 1))
        }
        assertFalse(ime.replaceText(0, 1, "x", 1))
        assertFalse(ime.replaceText(0, 4, "\n", 1))
        assertEquals(0, sink.deletes)
        ime.terminalKeySent(KeyEvent.KEYCODE_ENTER)
        assertFalse(ime.replaceText(0, 4, "x", 1))
        ime.onCommitText("ㅇ")
        assertTrue(ime.replaceText(4, 5, "아", 1))
        assertEquals("a😀b아", sink.terminal.toString())
    }

    @Test fun contextIsBoundedWithoutDiscardingUserInput() {
        val sink = Recorder()
        val ime = TerminalImeController(sink)
        val text = "a".repeat(5000)
        ime.onCommitText(text)
        assertEquals(text, sink.terminal.toString())
        assertEquals(904, ime.documentOffset)
        assertFalse(ime.replaceText(0, 1, "b", 1))
        assertTrue(ime.replaceText(4999, 5000, "b", 1))
        assertEquals(text.dropLast(1) + "b", sink.terminal.toString())
    }
}
