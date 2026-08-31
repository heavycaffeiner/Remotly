package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Backspace part-way through a Korean word.
 *
 * Every syllable is committed to the terminal as it settles, so this class
 * holds no history of the text: only the trailing syllable stays composing.
 * A backspace mid-word is handled inside the IME and never arrives as a key,
 * and deleting a vowel leaves the preceding consonant orphaned, which the IME
 * shows merged backwards. 안녕하세요 becomes 안녕하셍, one fewer settled
 * syllable, so the terminal takes a backspace of its own and the rest stays in
 * the preedit.
 *
 * Screen state is the terminal text plus the preedit, and it has to match what
 * the IME is showing at every step.
 */
class HangulBackspaceTest {

    private sealed interface Ev {
        data class Text(val s: String) : Ev
        object Del : Ev
    }

    private class RecordingSink : TerminalSink {
        val log = mutableListOf<Ev>()
        val dels: Int get() = log.count { it == Ev.Del }
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

    /**
     * What the terminal holds. The events are replayed in order: a retraction
     * removes the character in front of it, not one from the final string.
     */
    private fun terminalText(sink: RecordingSink): String =
        sink.log.fold(StringBuilder()) { sb, e ->
            when (e) {
                is Ev.Text -> sb.append(e.s)
                Ev.Del -> if (sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1) else sb
            }
        }.toString()

    /** Terminal plus preedit: what the user sees. */
    private fun screen(sink: RecordingSink, ime: TerminalImeController): String =
        terminalText(sink) + ime.composition.text

    private fun typeHello(ime: TerminalImeController) {
        listOf(
            "ㅇ", "아", "안", "안ㄴ", "안녀", "안녕",
            "안녕ㅎ", "안녕하", "안녕하ㅅ", "안녕하세",
            "안녕하세ㅇ", "안녕하세요",
        ).forEach { ime.onComposingText(it, 1) }
    }

    /** The reported bug: the word must never appear twice. */
    @Test
    fun backspacingAVowelDoesNotRepeatTheWord() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        assertEquals("안녕하세요", screen(sink, ime))

        // Backspace on 요: ㅛ goes and the orphan ㅇ merges into 세.
        ime.onComposingText("안녕하셍", 1)

        assertEquals("안녕하셍", screen(sink, ime))
    }

    /** The reported sequence continued: a second and third backspace. */
    @Test
    fun repeatedBackspacesTrackTheIme() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)

        ime.onComposingText("안녕하셍", 1)
        assertEquals("안녕하셍", screen(sink, ime))

        ime.onComposingText("안녕하세", 1)
        assertEquals("안녕하세", screen(sink, ime))

        // 세 loses ㅔ; the orphan ㅅ merges into 하, giving 핫.
        ime.onComposingText("안녕핫", 1)
        assertEquals("안녕핫", screen(sink, ime))
    }

    /** Retyping the erased vowel sends the finished syllable exactly once. */
    @Test
    fun retypingAfterABackspaceSendsTheSyllableOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.onComposingText("안녕하셍", 1)
        ime.onComposingText("안녕하세요", 1)
        ime.onCommitText("안녕하세요")

        assertEquals("안녕하세요", terminalText(sink))
    }

    /** Backspacing the whole word empties the screen. */
    @Test
    fun backspacingTheWholeWordLeavesNothing() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        listOf("안녕하셍", "안녕하세", "안녕핫", "안녕하", "안녕ㅎ", "안녕", "안녀", "안ㄴ", "안", "아", "ㅇ", "")
            .forEach { ime.onComposingText(it, 1) }

        assertEquals("", screen(sink, ime))
    }

    /** A word typed straight through still arrives once, unchanged. */
    @Test
    fun anUneditedWordIsUnaffected() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.onCommitText("안녕하세요")

        assertEquals("안녕하세요", terminalText(sink))
        assertEquals(0, sink.dels)
    }
}
