package com.remotly.app.terminal

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Whole typing sequences, as a Korean IME actually reports them.
 *
 * The per-update rules are covered in TerminalImeControllerTest. These run the
 * sequences end to end, because the failure this guards against only shows up
 * across updates: a syllable that looks finished can still grow, so releasing
 * on "looks complete" sent 하 and then 한 for one typed character.
 */
class HangulTypingScenarioTest {

    private class RecordingSink : TerminalSink {
        val sent = mutableListOf<String>()
        override fun sendText(text: String) {
            sent.add(text)
        }

        override fun sendKey(event: KeyEvent, composing: Boolean) = Unit
        override fun onCompositionChanged(state: CompositionState) = Unit
    }

    /** Types the preedit updates, then the commit the IME sends at the end. */
    private fun type(updates: List<String>, commit: String): List<String> {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        for (u in updates) ime.onComposingText(u, 1)
        ime.onCommitText(commit)
        return sink.sent
    }

    @Test
    fun aSyllableWithAFinalConsonantArrivesOnce() {
        // 하 is a complete syllable on its own, and becomes 한 on the next
        // keystroke. Releasing it early would send both.
        assertEquals(listOf("한"), type(listOf("ㅎ", "하", "한"), "한"))
        assertEquals(listOf("각"), type(listOf("ㄱ", "가", "각"), "각"))
    }

    @Test
    fun eachSyllableOfAWordArrivesOnceInOrder() {
        val updates = listOf("ㅇ", "아", "안", "안ㄴ", "안녀", "안녕")
        assertEquals(listOf("안", "녕"), type(updates, "안녕"))
    }

    @Test
    fun aLongWordArrivesOneSyllableAtATime() {
        // Every syllable after the first starts as a lone jamo, which makes
        // the preedit briefly not-all-syllables. Treating that as "start
        // over" resent the syllables already delivered: 안녕하 arrived as
        // 안, 안녕, 하.
        val updates = listOf(
            "ㅇ", "아", "안",
            "안ㄴ", "안녀", "안녕",
            "안녕ㅎ", "안녕하",
            "안녕하ㅅ", "안녕하세",
            "안녕하세ㅇ", "안녕하세요",
        )
        assertEquals(listOf("안", "녕", "하", "세", "요"), type(updates, "안녕하세요"))
    }

    @Test
    fun aTrailingConsonantMovingToTheNextSyllableIsNotDoubled() {
        // 하다: the ㄷ starts a new syllable rather than closing 하.
        val updates = listOf("ㅎ", "하", "하ㄷ", "하다")
        assertEquals(listOf("하", "다"), type(updates, "하다"))
    }

    @Test
    fun spaceMidWordDeliversTheWordExactlyOnce() {
        val sink = RecordingSink()
        val ime = TerminalImeController(sink)
        ime.onComposingText("ㅇ", 1)
        ime.onComposingText("안", 1)
        ime.onComposingText("안녕", 1)
        // Space commits the open preedit, then the IME echoes the whole word.
        ime.flushComposition()
        ime.onCommitText("안녕")

        assertEquals(listOf("안", "녕"), sink.sent)
    }

    @Test
    fun latinIsUnaffected() {
        // Latin preedit still waits for the commit, so autocorrect can rewrite
        // what it showed.
        assertEquals(listOf("hello"), type(listOf("h", "he", "hel"), "hello"))
    }
}
