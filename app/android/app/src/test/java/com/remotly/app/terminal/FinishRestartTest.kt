package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A Korean IME that ends and restarts composition around a backspace.
 *
 * Some IMEs answer a mid-word backspace by finishing the composition and
 * starting a new one carrying the whole word. The record of what was already
 * released has to survive that cycle: clearing it made the restarted word
 * re-release the syllables the terminal already had.
 */
class FinishRestartTest {

    private sealed interface Ev {
        data class Text(val s: String) : Ev
        object Del : Ev
    }

    private class RecordingSink : TerminalSink {
        val log = mutableListOf<Ev>()
        override fun sendText(text: String) {
            log.add(Ev.Text(text))
        }

        // KeyEvent is stubbed in unit tests (returnDefaultValues), so keyCode
        // reads back as 0. Only DEL is sent through this path.
        override fun sendKey(event: KeyEvent, composing: Boolean) {
            log.add(Ev.Del)
        }

        override fun onCompositionChanged(state: CompositionState) = Unit
    }

    private fun terminalText(sink: RecordingSink): String =
        sink.log.fold(StringBuilder()) { sb, e ->
            when (e) {
                is Ev.Text -> sb.append(e.s)
                Ev.Del -> if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) else sb
            }
        }.toString()

    private fun backspace(ime: TerminalImeController, sink: RecordingSink) {
        when (ime.onKeyDown(KeyEvent.KEYCODE_DEL)) {
            TerminalImeController.KeyDecision.BACKSPACE_COMPOSITION -> ime.backspaceComposition()
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD -> ime.flushComposition()
            TerminalImeController.KeyDecision.FORWARD ->
                sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false)
        }
    }

    private fun typeHello(ime: TerminalImeController) {
        listOf("ㅇ", "아", "안", "안ㄴ", "안녀", "안녕", "안녕ㅎ", "안녕하")
            .forEach { ime.onComposingText(it, 1) }
    }

    /**
     * Backspace, then the IME finishes and restarts with the whole word. The
     * restarted word must not re-release 안녕, which the terminal already has.
     */
    @Test
    fun aRestartedWordDoesNotRepeatDeliveredSyllables() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        backspace(ime, sink)
        ime.onFinishComposing()
        ime.onComposingText("안녕하", 1)
        ime.onCommitText("안녕하")

        assertEquals("안녕하", terminalText(sink))
    }

    /** The same cycle driven through deleteSurroundingText rather than a key. */
    @Test
    fun aRestartAfterDeleteSurroundingDoesNotRepeat() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.deleteFromComposition(1, 0)
        ime.onFinishComposing()
        ime.onComposingText("안녕하", 1)
        ime.onCommitText("안녕하")

        assertEquals("안녕하", terminalText(sink))
    }

    /** Backspacing to a shorter word leaves only what the user still has. */
    @Test
    fun backspacingToAShorterWordDoesNotRepeat() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        backspace(ime, sink)
        ime.onFinishComposing()
        ime.onComposingText("안녕", 1)
        ime.onCommitText("안녕")

        assertEquals("안녕", terminalText(sink))
    }

    /**
     * The unedited preedit is still held back, so a backspace before any
     * commit leaves the terminal with the released syllables only.
     */
    @Test
    fun backspaceWithoutCommitLeavesTheReleasedSyllables() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        backspace(ime, sink)

        assertEquals("안녕", terminalText(sink))
        assertEquals("", ime.composition.text)
    }
}
