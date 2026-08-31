package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * What the terminal receives when Enter ends a CJK composition.
 *
 * The controller decides; this checks the resulting byte order. A TUI such as
 * Claude Code or OpenCode reads keys as they arrive, so the committed syllable
 * has to land before the newline. A plain shell waits for a complete line and
 * hides an ordering mistake, which is why this was only visible in a TUI.
 */
class TerminalEnterOrderingTest {

    private lateinit var sink: RecordingSink
    private lateinit var ime: TerminalImeController

    // The unit test variant stubs android.jar, so KeyEvent.getKeyCode()
    // returns a default rather than the constructor argument. The keycode is
    // recorded separately instead of read back off the event.
    private class RecordingSink : TerminalSink {
        /** Text and keys interleaved, in delivery order. */
        val stream = mutableListOf<String>()
        var nextKeyCode = 0

        override fun sendText(text: String) {
            stream.add("text:$text")
        }

        override fun sendKey(event: KeyEvent, composing: Boolean) {
            stream.add("key:$nextKeyCode:$composing")
        }

        override fun onCompositionChanged(state: CompositionState) = Unit
    }

    @Before
    fun setUp() {
        sink = RecordingSink()
        ime = TerminalImeController(sink)
    }

    // Mirrors TerminalInputConnection.sendKeyEvent for one key-down.
    private fun keyDown(keyCode: Int) {
        val event = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
        sink.nextKeyCode = keyCode
        when (ime.onKeyDown(keyCode)) {
            TerminalImeController.KeyDecision.BACKSPACE_COMPOSITION ->
                ime.backspaceComposition()
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD -> {
                ime.flushComposition()
                sink.sendKey(event, composing = false)
            }
            TerminalImeController.KeyDecision.FORWARD ->
                sink.sendKey(event, composing = ime.isComposing)
        }
    }

    /**
     * Nothing follows the key. The connection is never restarted after a
     * commit, because doing that dropped whichever keystroke the IME was still
     * delivering.
     */
    @Test
    fun spaceAfterASyllableSendsTheTextThenTheKeyAndNothingElse() {
        ime.onComposingText("\uD55C", 1)
        keyDown(KeyEvent.KEYCODE_SPACE)

        assertEquals(
            listOf("text:\uD55C", "key:${KeyEvent.KEYCODE_SPACE}:false"),
            sink.stream,
        )
    }

    @Test
    fun enterAfterKoreanSendsTheSyllableThenTheNewline() {
        ime.onComposingText("ㅎ", 1)
        ime.onComposingText("하", 1)
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_ENTER)

        assertEquals(
            listOf("text:한", "key:${KeyEvent.KEYCODE_ENTER}:false"),
            sink.stream,
        )
    }

    // The Enter must not be flagged as composing. Ghostty's encoder drops
    // every non-modifier key marked that way, which would swallow the newline.
    @Test
    fun theForwardedEnterIsNotMarkedComposing() {
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_ENTER)

        assertEquals("key:${KeyEvent.KEYCODE_ENTER}:false", sink.stream.last())
    }

    @Test
    fun enterWithoutCompositionIsJustANewline() {
        keyDown(KeyEvent.KEYCODE_ENTER)

        assertEquals(listOf("key:${KeyEvent.KEYCODE_ENTER}:false"), sink.stream)
    }

    @Test
    fun asciiThenEnterSendsNoExtraText() {
        // Plain typing commits without ever opening a composition.
        ime.onCommitText("ls")
        keyDown(KeyEvent.KEYCODE_ENTER)

        assertEquals(
            listOf("text:ls", "key:${KeyEvent.KEYCODE_ENTER}:false"),
            sink.stream,
        )
    }

    @Test
    fun theImeEchoAfterEnterDoesNotDoubleTheText() {
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_ENTER)
        // Gboard and the Samsung keyboard both replay the syllable here.
        ime.onCommitText("한")

        assertEquals(
            listOf("text:한", "key:${KeyEvent.KEYCODE_ENTER}:false"),
            sink.stream,
        )
    }

    @Test
    fun asecondEnterAfterAnEchoStillSends() {
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_ENTER)
        ime.onCommitText("한")
        keyDown(KeyEvent.KEYCODE_ENTER)

        assertEquals(
            listOf(
                "text:한",
                "key:${KeyEvent.KEYCODE_ENTER}:false",
                "key:${KeyEvent.KEYCODE_ENTER}:false",
            ),
            sink.stream,
        )
    }

    // Some IMEs commit the Enter key as literal text rather than sending a key
    // event. A TUI reads a newline character as a line continuation, which is
    // what its own Ctrl+Enter produces, so it has to become a real Enter key.
    @Test
    fun aCommittedNewlineBecomesAnEnterKey() {
        sink.nextKeyCode = KeyEvent.KEYCODE_ENTER
        ime.onCommitText("\n")

        assertEquals(listOf("key:${KeyEvent.KEYCODE_ENTER}:false"), sink.stream)
    }

    @Test
    fun textAroundACommittedNewlineKeepsItsOrder() {
        sink.nextKeyCode = KeyEvent.KEYCODE_ENTER
        ime.onCommitText("ls\n")

        assertEquals(
            listOf("text:ls", "key:${KeyEvent.KEYCODE_ENTER}:false"),
            sink.stream,
        )
    }

    @Test
    fun aPastedBlockKeepsEveryLineAndBreak() {
        sink.nextKeyCode = KeyEvent.KEYCODE_ENTER
        ime.onCommitText("one\ntwo\nthree")

        assertEquals(
            listOf(
                "text:one",
                "key:${KeyEvent.KEYCODE_ENTER}:false",
                "text:two",
                "key:${KeyEvent.KEYCODE_ENTER}:false",
                "text:three",
            ),
            sink.stream,
        )
    }

    @Test
    fun aTrailingNewlineSendsNoEmptyText() {
        sink.nextKeyCode = KeyEvent.KEYCODE_ENTER
        ime.onCommitText("\nx\n")

        assertEquals(
            listOf(
                "key:${KeyEvent.KEYCODE_ENTER}:false",
                "text:x",
                "key:${KeyEvent.KEYCODE_ENTER}:false",
            ),
            sink.stream,
        )
    }

    @Test
    fun ordinaryTextStillCommitsAsOneRun() {
        ime.onCommitText("hello")

        assertEquals(listOf("text:hello"), sink.stream)
    }

    /**
     * A Space delivered to this connection is a word separator the user typed:
     * an IME that wanted it to pick a candidate handles it internally and
     * never passes it on. Swallowing it here is what dropped the space after a
     * Korean word.
     *
     * It behaves exactly as Enter does: the syllable, then the key.
     */
    @Test
    fun spaceAfterAKoreanSyllableSendsTheSyllableThenTheSpace() {
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_SPACE)

        assertEquals(
            listOf("text:한", "key:${KeyEvent.KEYCODE_SPACE}:false"),
            sink.stream,
        )
    }

    @Test
    fun spaceWithoutCompositionIsJustASpace() {
        keyDown(KeyEvent.KEYCODE_SPACE)

        assertEquals(listOf("key:${KeyEvent.KEYCODE_SPACE}:false"), sink.stream)
    }

    /** Backspace mid-syllable edits the preedit and reaches nothing. */
    @Test
    fun backspaceDuringCompositionReachesNothing() {
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_DEL)

        assertEquals(emptyList<String>(), sink.stream)
    }

    // Ctrl+C has to interrupt even mid-syllable, and it must not flush the
    // preedit: the user is cancelling, not submitting.
    @Test
    fun controlKeysDuringCompositionDoNotFlush() {
        ime.onComposingText("한", 1)
        keyDown(KeyEvent.KEYCODE_ESCAPE)

        assertEquals(
            listOf("key:${KeyEvent.KEYCODE_ESCAPE}:true"),
            sink.stream,
        )
    }
}
