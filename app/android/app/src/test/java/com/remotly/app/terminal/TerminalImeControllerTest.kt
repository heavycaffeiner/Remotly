package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// The rules that decide what reaches the PTY during IME composition.
//
// These are the ones that corrupt input when they are wrong: preedit leaking
// through, a syllable submitted early by Space or Enter, or a commit delivered
// twice.
class TerminalImeControllerTest {

    private lateinit var sink: RecordingSink
    private lateinit var ime: TerminalImeController

    private class RecordingSink : TerminalSink {
        val sentText = mutableListOf<String>()
        val sentKeys = mutableListOf<Pair<Int, Boolean>>()
        val compositions = mutableListOf<CompositionState>()

        /**
         * Text and keys interleaved, in the order the terminal received them.
         *
         * A TUI reads keys as they arrive, so "committed text, then newline"
         * is a different input from "newline, then committed text".
         */
        val stream = mutableListOf<String>()

        override fun sendText(text: String) {
            sentText.add(text)
            stream.add("text:$text")
        }

        override fun sendKey(event: KeyEvent, composing: Boolean) {
            sentKeys.add(event.keyCode to composing)
            stream.add("key:${event.keyCode}:$composing")
        }

        override fun onCompositionChanged(state: CompositionState) {
            compositions.add(state)
        }

        /** Everything that reached the terminal, in order. */
        fun bytesSent(): Int = sentText.size + sentKeys.size
    }

    @Before
    fun setUp() {
        sink = RecordingSink()
        ime = TerminalImeController(sink)
    }

    // --- Korean releases one syllable at a time ------------------------------

    @Test
    fun oneSyllableIsHeldUntilTheNextOneStarts() {
        // Building 한 one jamo at a time. A syllable that still looks finished
        // can grow (하 becomes 한), so nothing is released while it is last.
        ime.onComposingText("ㅎ", 1)
        ime.onComposingText("하", 1)
        ime.onComposingText("한", 1)

        assertEquals("a syllable still being typed must not be sent", 0, sink.bytesSent())
        assertEquals("한", ime.composition.text)
    }

    @Test
    fun theCommitDeliversASingleSyllableExactlyOnce() {
        ime.onComposingText("ㅎ", 1)
        ime.onComposingText("하", 1)
        ime.onComposingText("한", 1)
        ime.onCommitText("한")

        assertEquals(listOf("한"), sink.sentText)
        assertFalse(ime.isComposing)
    }

    @Test
    fun aFinishedSyllableGoesThroughWhenTheNextBegins() {
        // 안녕: 안 is settled the moment ㄴ starts the second syllable.
        ime.onComposingText("ㅇ", 1)
        ime.onComposingText("아", 1)
        ime.onComposingText("안", 1)
        assertEquals(0, sink.bytesSent())

        ime.onComposingText("안녀", 1)
        assertEquals("the settled syllable is released", listOf("안"), sink.sentText)
        assertEquals("녀", ime.composition.text)

        ime.onComposingText("안녕", 1)
        assertEquals("the last syllable is still held", listOf("안"), sink.sentText)
    }

    @Test
    fun theWordCommitOnlySendsWhatWasNotReleased() {
        // The IME commits the whole word it was showing. The released prefix
        // must not be sent a second time.
        ime.onComposingText("안", 1)
        ime.onComposingText("안녕", 1)
        ime.onCommitText("안녕")

        assertEquals(listOf("안", "녕"), sink.sentText)
        assertFalse(ime.isComposing)
    }

    @Test
    fun aFlushedTailIsNotResentByTheFollowingCommit() {
        // Space flushes the tail, then the IME commits the full word. Only the
        // tail was outstanding, so the word must add nothing.
        ime.onComposingText("안", 1)
        ime.onComposingText("안녕", 1)
        ime.flushComposition()
        ime.onCommitText("안녕")

        assertEquals(listOf("안", "녕"), sink.sentText)
    }

    @Test
    fun aSecondWordStartsFromNothing() {
        ime.onComposingText("안", 1)
        ime.onComposingText("안녕", 1)
        ime.onCommitText("안녕")
        sink.sentText.clear()

        // The next word must not be stripped against the previous one.
        ime.onComposingText("한", 1)
        ime.onCommitText("한")

        assertEquals(listOf("한"), sink.sentText)
    }

    @Test
    fun latinCompositionStillWaitsForCommit() {
        // Latin preedit is autocorrect and candidate selection, which must be
        // able to rewrite what it already showed.
        ime.onComposingText("h", 1)
        ime.onComposingText("he", 1)
        ime.onComposingText("hel", 1)

        assertTrue(ime.isComposing)
        assertEquals("preedit must never reach the terminal", 0, sink.bytesSent())
        assertEquals("hel", ime.composition.text)
    }

    @Test
    fun committingSendsExactlyOnce() {
        ime.onComposingText("hel", 1)
        ime.onCommitText("hello")

        assertEquals(listOf("hello"), sink.sentText)
        assertFalse(ime.isComposing)
    }

    @Test
    fun finishThenCommitSendsOnce() {
        // A real IME sequence: finishComposingText clears styling, then
        // commitText delivers the text. Sending from both would duplicate it.
        ime.onComposingText("hel", 1)
        ime.onFinishComposing()
        ime.onCommitText("hello")

        assertEquals(1, sink.sentText.size)
        assertEquals("hello", sink.sentText[0])
    }

    @Test
    fun cancellingSendsNothing() {
        ime.onComposingText("hel", 1)
        ime.onFinishComposing()

        assertEquals(0, sink.bytesSent())
        assertFalse(ime.isComposing)
    }

    @Test
    fun emptyComposingTextClearsWithoutSending() {
        ime.onComposingText("ㅎ", 1)
        ime.onComposingText("", 1)

        assertFalse(ime.isComposing)
        assertEquals(0, sink.bytesSent())
    }

    @Test
    fun anEmptyCommitSendsNothing() {
        ime.onComposingText("ㅎ", 1)
        assertFalse(ime.onCommitText(""))
        assertEquals(0, sink.sentText.size)
        assertFalse(ime.isComposing)
    }

    @Test
    fun commitWithoutCompositionStillSends() {
        // Plain ASCII typing never opens a composition.
        assertTrue(ime.onCommitText("a"))
        assertEquals(listOf("a"), sink.sentText)
    }

    // --- key handling during composition -------------------------------------

    /**
     * A Space that reaches this controller is one the IME passed through, so
     * it is a word separator the user typed, not a candidate selection. An IME
     * that wanted it for a candidate never delivers it here.
     *
     * Consuming it dropped the space, which is the reported input loss.
     */
    @Test
    fun spaceDuringCompositionCommitsThenForwards() {
        ime.onComposingText("ㅎ", 1)
        assertEquals(
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_SPACE),
        )
    }

    /**
     * The syllable has to land before the space, or the shell receives the
     * separator first and the word runs into the next one.
     */
    @Test
    fun spaceAfterAKoreanSyllableSendsTheSyllableThenTheSpace() {
        ime.onComposingText("ㅎ", 1)
        // Mirrors what the connection does for COMMIT_THEN_FORWARD.
        ime.flushComposition()
        sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE), false)

        assertEquals(listOf("text:ㅎ", "key:0:false"), sink.stream)
    }

    @Test
    fun enterDuringCompositionCommitsThenForwards() {
        // Enter ends composition and submits. The committed syllable and the
        // newline have to reach the terminal in that order.
        ime.onComposingText("ㅎ", 1)
        assertEquals(
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_ENTER),
        )
        ime.onComposingText("ㅎ", 1)
        assertEquals(
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_NUMPAD_ENTER),
        )
    }

    @Test
    fun flushingSendsThePreeditOnce() {
        ime.onComposingText("ㅎ", 1)
        assertEquals("ㅎ", ime.flushComposition())
        assertEquals(listOf("ㅎ"), sink.sentText)
        assertFalse(ime.isComposing)
    }

    @Test
    fun flushingWithoutCompositionSendsNothing() {
        assertEquals(null, ime.flushComposition())
        assertEquals(0, sink.bytesSent())
    }

    // Gboard and the Samsung keyboard follow the Enter with a commitText
    // carrying the same syllable. Sending it again would double the text.
    @Test
    fun theImeEchoAfterAFlushIsSwallowed() {
        ime.onComposingText("ㅎ", 1)
        ime.flushComposition()
        assertFalse(ime.onCommitText("ㅎ"))

        assertEquals(listOf("ㅎ"), sink.sentText)
    }

    @Test
    fun onlyTheMatchingEchoIsSwallowed() {
        // A commit carrying different text is real input, not an echo.
        ime.onComposingText("한", 1)
        ime.flushComposition()
        assertTrue(ime.onCommitText("글"))

        assertEquals(listOf("한", "글"), sink.sentText)
    }

    @Test
    fun theEchoGuardIsConsumedOnlyOnce() {
        // The same syllable typed again right after must still send.
        ime.onComposingText("한", 1)
        ime.flushComposition()
        assertFalse(ime.onCommitText("한"))
        assertTrue(ime.onCommitText("한"))

        assertEquals(listOf("한", "한"), sink.sentText)
    }

    @Test
    fun aNewCompositionClearsTheEchoGuard() {
        ime.onComposingText("ㅎ", 1)
        ime.flushComposition()
        // The user starts a new syllable instead of the IME echoing.
        ime.onComposingText("ㅎ", 1)
        assertTrue(ime.onCommitText("ㅎ"))

        assertEquals(listOf("ㅎ", "ㅎ"), sink.sentText)
    }

    @Test
    fun backspaceDuringCompositionEditsThePreedit() {
        ime.onComposingText("ㅎ", 1)
        assertEquals(
            TerminalImeController.KeyDecision.BACKSPACE_COMPOSITION,
            ime.onKeyDown(KeyEvent.KEYCODE_DEL),
        )
    }

    /**
     * A backspace mid-composition removes one whole syllable from the preedit
     * and never reaches the terminal, which owns a line the user has not
     * submitted yet.
     */
    @Test
    fun backspaceRemovesOneClusterFromThePreedit() {
        ime.onComposingText("ab", 1)
        assertTrue(ime.backspaceComposition())

        assertEquals("a", ime.composition.text)
        assertEquals(1, ime.composition.selectionEndUtf16)
        assertEquals(0, sink.bytesSent())
    }

    @Test
    fun backspacingTheLastClusterEndsTheComposition() {
        ime.onComposingText("ㅎ", 1)
        assertTrue(ime.backspaceComposition())

        assertFalse(ime.isComposing)
        assertEquals(0, sink.bytesSent())
    }

    @Test
    fun backspaceWithoutCompositionIsNotHandledLocally() {
        assertFalse(ime.backspaceComposition())
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_DEL),
        )
    }

    /** An astral cluster is one unit to the user, so it goes in one press. */
    @Test
    fun backspaceRemovesASurrogatePairAsOneUnit() {
        ime.onComposingText("a\uD83D\uDE00", 1)
        assertTrue(ime.backspaceComposition())

        assertEquals("a", ime.composition.text)
    }

    // Plain ASCII typing never opens a composition, so Enter is an ordinary
    // newline and nothing is flushed ahead of it.
    @Test
    fun spaceAndEnterForwardWithoutComposition() {
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_SPACE),
        )
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_ENTER),
        )
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_NUMPAD_ENTER),
        )
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_DEL),
        )
        assertEquals(0, sink.bytesSent())
    }

    @Test
    fun controlKeysStillReachTheTerminalDuringComposition() {
        // Ctrl+C has to interrupt even mid-syllable, and an arrow key is not
        // something the IME owns.
        ime.onComposingText("한", 1)
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_DPAD_LEFT),
        )
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_ESCAPE),
        )
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_TAB),
        )
    }

    @Test
    fun keysForwardAgainAfterCommit() {
        ime.onComposingText("ㅎ", 1)
        ime.onCommitText("한")
        assertEquals(
            TerminalImeController.KeyDecision.FORWARD,
            ime.onKeyDown(KeyEvent.KEYCODE_ENTER),
        )
    }

    // --- deletion -------------------------------------------------------------

    @Test
    fun deleteDuringCompositionSendsNoDel() {
        ime.onComposingText("ㅎ", 1)
        assertEquals(0, ime.onDeleteSurrounding(1))
    }

    @Test
    fun deleteWithoutCompositionSendsOneDelPerCharacter() {
        assertEquals(1, ime.onDeleteSurrounding(1))
        assertEquals(3, ime.onDeleteSurrounding(3))
    }

    @Test
    fun deleteCountIsBounded() {
        // A malformed request must not flood the PTY.
        assertEquals(TerminalImeController.MAX_DELETE, ime.onDeleteSurrounding(10_000))
        assertEquals(0, ime.onDeleteSurrounding(-5))
    }

    // --- lifecycle -----------------------------------------------------------

    @Test
    fun closingClearsPreeditWithoutSending() {
        ime.onComposingText("ㅎ", 1)
        ime.onClose()

        assertFalse(ime.isComposing)
        assertEquals(0, sink.bytesSent())
    }

    @Test
    fun compositionChangesAreReportedForRedraw() {
        ime.onComposingText("ㅎ", 1)
        ime.onComposingText("한", 1)
        ime.onCommitText("한")

        // Two updates while composing, then one clearing it.
        assertEquals(3, sink.compositions.size)
        assertTrue(sink.compositions.last().isEmpty)
    }

    @Test
    fun aRedundantFinishDoesNotReportAChange() {
        ime.onFinishComposing()
        assertEquals(0, sink.compositions.size)
    }

    // --- caret tracking -------------------------------------------------------

    @Test
    fun theCaretFollowsTheImeSelection() {
        ime.onComposingText("ab", 1)
        assertEquals(2, ime.composition.selectionEndUtf16)

        ime.onComposingText("ab", 0)
        assertEquals(0, ime.composition.selectionEndUtf16)
    }

    @Test
    fun selectionFromImeIsClamped() {
        ime.onComposingText("ab", 1)
        ime.onSelection(-50, 50)

        assertEquals(0, ime.composition.selectionStartUtf16)
        assertEquals(2, ime.composition.selectionEndUtf16)
    }

    @Test
    fun surroundingDeleteEditsPreeditWithoutReachingTerminal() {
        ime.onComposingText("ab", 1)
        assertTrue(ime.deleteFromComposition(1, 0))

        assertEquals("a", ime.composition.text)
        assertEquals(0, sink.bytesSent())
    }

    @Test
    fun deletingSelectedPreeditClearsItSafely() {
        ime.onComposingText("日本", 1)
        ime.onSelection(0, 2)
        assertTrue(ime.deleteFromComposition(0, 0))

        assertFalse(ime.isComposing)
        assertEquals(0, sink.bytesSent())
    }

    // --- what the IME can see -----------------------------------------------

    /**
     * The IME is told nothing about committed text and is never restarted.
     *
     * Both were attempts to stop it reconciling against a terminal that cannot
     * report its own line, and both made things worse. Reporting the text let
     * backspace delete characters that had already been sent; restarting to
     * clear that record tore down the connection mid-keystroke and dropped
     * keys, most visibly the ones Gboard defers (o and u for accents, the
     * period and space for autocorrect and smart punctuation).
     *
     * The connection reports the preedit and nothing else, which is the only
     * thing this app genuinely owns, so there is no record to clear.
     */
    @Test
    fun aCommitLeavesNoRecordBehind() {
        ime.onComposingText("\uD55C", 1)
        ime.onCommitText("\uD55C")

        assertFalse(ime.isComposing)
        assertEquals(CompositionState.NONE, ime.composition)
    }

    @Test
    fun anAsciiCommitLeavesNoRecordBehind() {
        ime.onCommitText("ls")

        assertFalse(ime.isComposing)
        assertEquals(CompositionState.NONE, ime.composition)
    }

    /**
     * A period straight after a commit reaches the terminal. This is the key
     * the restart used to swallow.
     */
    @Test
    fun aPeriodAfterACommitStillSends() {
        ime.onComposingText("\uD55C", 1)
        ime.onCommitText("\uD55C")
        ime.onCommitText(".")

        assertEquals(listOf("\uD55C", "."), sink.sentText)
    }

    /** The same for the letters whose accent popup makes Gboard defer. */
    @Test
    fun lettersWithAccentPopupsStillSend() {
        for (letter in listOf("o", "u")) ime.onCommitText(letter)

        assertEquals(listOf("o", "u"), sink.sentText)
    }

    /** A long run of plain typing loses nothing along the way. */
    @Test
    fun aRunOfPlainTypingSendsEveryCharacter() {
        val typed = "echo out. you too."
        for (ch in typed) ime.onCommitText(ch.toString())

        assertEquals(typed, sink.sentText.joinToString(""))
    }

}
