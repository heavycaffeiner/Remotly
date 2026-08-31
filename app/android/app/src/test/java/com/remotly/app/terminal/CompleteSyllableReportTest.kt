package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A word whose IME report is entirely complete syllables.
 *
 * The trailing cluster always stays composing, so a word being typed never
 * leaves the preedit empty: 가나 releases 가 and composes 나. The record of
 * what was released is therefore still needed on the next update, and clearing
 * it whenever the preedit looks closed would re-release the whole word.
 */
class CompleteSyllableReportTest {

    private class RecordingSink : TerminalSink {
        val sent = mutableListOf<String>()
        var dels = 0
        override fun sendText(text: String) {
            sent.add(text)
        }

        // KeyEvent is stubbed in unit tests (returnDefaultValues), so keyCode
        // reads back as 0. Only DEL is sent through this path.
        override fun sendKey(event: KeyEvent, composing: Boolean) {
            dels++
        }

        override fun onCompositionChanged(state: CompositionState) = Unit
    }

    private fun terminalText(sink: RecordingSink): String = sink.sent.joinToString("")

    /** An IME reporting only finished syllables still sends each one once. */
    @Test
    fun aWordOfCompleteSyllablesArrivesOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        listOf("가", "가나", "가나다").forEach { ime.onComposingText(it, 1) }
        ime.onCommitText("가나다")

        assertEquals("가나다", terminalText(sink))
        assertEquals(0, sink.dels)
    }

    /** The trailing syllable is held back, so the preedit is never empty. */
    @Test
    fun theTrailingSyllableStaysComposing() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        ime.onComposingText("가나", 1)

        assertEquals("가", terminalText(sink))
        assertEquals("나", ime.composition.text)
    }

    /** Clearing the preedit sends nothing and retracts nothing. */
    @Test
    fun clearingThePreeditSendsNothing() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        ime.onComposingText("가", 1)
        ime.onComposingText("", 1)

        assertEquals("", terminalText(sink))
        assertEquals(0, sink.dels)
        assertEquals("", ime.composition.text)
    }

    /** The full greeting, typed as a Korean IME reports it. */
    @Test
    fun theGreetingArrivesOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        listOf(
            "ㅇ", "아", "안", "안ㄴ", "안녀", "안녕",
            "안녕ㅎ", "안녕하", "안녕하ㅅ", "안녕하세",
            "안녕하세ㅇ", "안녕하세요",
        ).forEach { ime.onComposingText(it, 1) }
        ime.onCommitText("안녕하세요")

        assertEquals("안녕하세요", terminalText(sink))
        assertEquals(0, sink.dels)
    }
}
