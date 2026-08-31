package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A committed Korean word reaches the terminal exactly once.
 *
 * A Korean IME reports the whole word on every keystroke and commits all of it
 * at the end, while this controller releases each syllable as it settles. The
 * record of what was already released has to survive the composition ending,
 * because the commit that repeats the word arrives just after it. Clearing it
 * there made the shell see the word twice over.
 */
class CommitOnceTest {

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

    private fun typeHello(ime: TerminalImeController) {
        listOf(
            "ㅇ", "아", "안", "안ㄴ", "안녀", "안녕",
            "안녕ㅎ", "안녕하", "안녕하ㅅ", "안녕하세",
            "안녕하세ㅇ", "안녕하세요",
        ).forEach { ime.onComposingText(it, 1) }
    }

    /** The IME ends composition styling before committing the word. */
    @Test
    fun finishComposingThenCommitSendsTheWordOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.onFinishComposing()
        ime.onCommitText("안녕하세요")

        assertEquals("안녕하세요", terminalText(sink))
    }

    /** An unrelated key drops the overlay, then the IME commits anyway. */
    @Test
    fun abandonThenCommitSendsTheWordOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.abandonComposition()
        ime.onCommitText("안녕하세요")

        assertEquals("안녕하세요", terminalText(sink))
    }

    /** Space flushes the preedit, then the IME echoes the whole word. */
    @Test
    fun spaceFlushThenEchoSendsTheWordOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.flushComposition()
        ime.onCommitText("안녕하세요")

        assertEquals("안녕하세요", terminalText(sink))
    }

    /** Two words in a row each arrive once, in order. */
    @Test
    fun consecutiveWordsEachArriveOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.onCommitText("안녕하세요")
        listOf("ㅂ", "바", "반", "반ㄱ", "반가", "반갑").forEach { ime.onComposingText(it, 1) }
        ime.onCommitText("반갑")

        assertEquals("안녕하세요반갑", terminalText(sink))
    }

    /** Latin after Korean is unaffected by the delivered-prefix record. */
    @Test
    fun latinAfterKoreanIsUnaffected() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        typeHello(ime)
        ime.onCommitText("안녕하세요")
        ime.onComposingText("l", 1)
        ime.onComposingText("ls", 1)
        ime.onCommitText("ls")

        assertEquals("안녕하세요ls", terminalText(sink))
    }
}
