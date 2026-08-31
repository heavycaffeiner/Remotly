package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What happens after Space flushes an open preedit.
 *
 * The flush keeps its record so the IME's echo of the same word is recognised
 * and dropped. That record describes a composition that is over, so a new word
 * must not be measured against it: doing so read the shorter preedit as a
 * backspace and sent retraction keys into the shell, deleting committed text.
 */
class FlushCarryTest {

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

    /** A new word after Space must not delete anything the shell holds. */
    @Test
    fun aNewWordAfterAFlushSendsNoRetractions() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        ime.onComposingText("한", 1)
        ime.onComposingText("한글", 1)
        ime.flushComposition()

        ime.onComposingText("ㄱ", 1)
        ime.onComposingText("가", 1)
        ime.onComposingText("강", 1)
        ime.onCommitText("강")

        assertEquals(0, sink.dels)
        assertEquals("한글강", sink.sent.joinToString(""))
    }

    /** The echo the flush was guarding against is still swallowed. */
    @Test
    fun theImeEchoAfterAFlushIsStillDropped() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        ime.onComposingText("한", 1)
        ime.onComposingText("한글", 1)
        ime.flushComposition()
        ime.onCommitText("한글")

        assertEquals("한글", sink.sent.joinToString(""))
        assertEquals(0, sink.dels)
    }

    /** A word typed after a normal commit is likewise unaffected. */
    @Test
    fun aNewWordAfterACommitSendsNoRetractions() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        ime.onComposingText("한", 1)
        ime.onComposingText("한글", 1)
        ime.onCommitText("한글")

        ime.onComposingText("ㄱ", 1)
        ime.onComposingText("가", 1)
        ime.onCommitText("가")

        assertEquals(0, sink.dels)
        assertEquals("한글가", sink.sent.joinToString(""))
    }
}
