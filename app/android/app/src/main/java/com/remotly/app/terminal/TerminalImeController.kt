package com.remotly.app.terminal

import android.view.KeyEvent

/**
 * The composition rules, with no Android View or IME dependency.
 *
 * Kept separate from [TerminalInputConnection] so these decisions are unit
 * testable: they are the ones that were wrong, and they are the ones that
 * corrupt input when they are wrong. The connection is the thin binding that
 * calls into this.
 */
class TerminalImeController(private val sink: TerminalSink) {

    var composition: CompositionState = CompositionState.NONE
        private set

    val isComposing: Boolean get() = !composition.isEmpty

    /**
     * Text this controller committed on the IME's behalf when Enter closed a
     * composition, still awaiting the IME's own echo of it.
     *
     * Gboard and the Samsung keyboard both follow the Enter with a commitText
     * carrying the same syllable. Sending that again would double the text.
     */
    private var flushedText: String? = null

    /**
     * The leading Hangul this controller already released from the preedit.
     *
     * A Korean IME reports the whole word on every keystroke, so without this
     * a growing preedit would resend the syllables it already delivered.
     * Cleared whenever the composition ends or stops being Hangul.
     */
    private var sentPrefix: String = ""

    // Nothing about committed text is tracked here, and the IME is never asked
    // to restart. It cannot see committed text (getTextBeforeCursor reports the
    // preedit only), so it has no record to reconcile and nothing to reset.
    //
    // The restart that used to follow every commit is what dropped keys. It
    // tears down the connection, and an IME part-way through delivering a
    // keystroke loses it. Gboard defers a commit while a key could still become
    // something else, which is why o and u (accent popups) and the period and
    // space (autocorrect and smart punctuation) went missing most often.

    /** What should happen to a key event. */
    enum class KeyDecision {
        /** Forward it to the terminal. */
        FORWARD,

        /**
         * Commit the open preedit, then forward the key.
         *
         * Enter or Space while composing. The IME's own commit may arrive late
         * or not at all, so the text is flushed here and the key follows it in
         * the same batch.
         */
        COMMIT_THEN_FORWARD,

        /**
         * Backspace while composing: remove the last cluster of the preedit.
         *
         * Nothing reaches the terminal. The preedit is the app's own buffer,
         * and the shell must not lose a character the user never committed.
         */
        BACKSPACE_COMPOSITION,
    }

    /**
     * Takes a preedit update, releasing each Korean syllable once it is final.
     *
     * A Korean IME keeps the whole word in its preedit and commits it only at
     * a space or a punctuation mark, so a shell saw nothing until then and a
     * TUI reading keys as they arrive got one burst at the end. Releasing a
     * syllable as the user finishes it puts each character through when it is
     * typed, which is what a terminal expects.
     *
     * The last cluster is always held back. A finished-looking syllable can
     * still change: 하 becomes 한 on the next keystroke, so committing on
     * "looks complete" alone would send 하 and then 한 for one typed
     * character. A syllable is final only once the IME has moved past it, so
     * everything before the last cluster is released and the last stays in
     * the preedit.
     *
     * Only Hangul is released early. Latin preedit is autocorrect and
     * candidate selection, which must be able to rewrite what it showed.
     */
    fun onComposingText(text: CharSequence?, newCursorPosition: Int) {
        // A fresh composition means the IME moved on; any pending echo from
        // the previous one is no longer coming.
        flushedText = null
        val next = CompositionState.of(text, newCursorPosition)
        // What this preedit still owes the terminal. The IME reports the whole
        // word on every keystroke, so anything already released is dropped
        // here rather than reconsidered.
        val carried = if (sentPrefix.isNotEmpty() && next.text.startsWith(sentPrefix)) {
            sentPrefix.length
        } else {
            sentPrefix = ""
            0
        }
        val pending = next.text.substring(carried)
        val released = settledHangul(pending)
        if (released.isEmpty()) {
            val caret = (next.selectionEndUtf16 - carried).coerceIn(0, pending.length)
            composition = if (pending.isEmpty()) {
                CompositionState.NONE
            } else {
                CompositionState(pending, caret, caret)
            }
            sink.onCompositionChanged(composition)
            return
        }
        sentPrefix += released
        val rest = pending.substring(released.length)
        val caret = (next.selectionEndUtf16 - carried - released.length)
            .coerceIn(0, rest.length)
        composition = if (rest.isEmpty()) {
            CompositionState.NONE
        } else {
            CompositionState(rest, caret, caret)
        }
        sink.onCompositionChanged(composition)
        sink.sendText(released)
    }

    /**
     * The leading syllables of a preedit the user has finished typing.
     *
     * Empty unless the text is composed Hangul holding more than one syllable.
     * The trailing syllable is never included: the next keystroke can still
     * change it, so 하 must not go out before it has had the chance to become
     * 한.
     */
    private fun settledHangul(text: String): String {
        if (text.isEmpty() || !CompositionState.isCompleteHangul(text)) return ""
        return text.substring(0, lastClusterStart(text))
    }

    /** UTF-16 offset where the final grapheme cluster of [text] begins. */
    private fun lastClusterStart(text: String): Int {
        val clusters = PreeditLayout.clusters(text)
        return if (clusters.isEmpty()) 0 else clusters.last().startUtf16
    }

    /**
     * Drops an open preedit without sending it.
     *
     * An extra key acts on the terminal, not on the text being composed, so
     * the overlay is taken down rather than left hanging over the result.
     */
    fun abandonComposition() {
        sentPrefix = ""
        if (!isComposing) return
        flushedText = null
        composition = CompositionState.NONE
        sink.onCompositionChanged(composition)
    }

    fun onSelection(start: Int, end: Int) {
        if (!isComposing) return
        val length = composition.text.length
        composition = composition.copy(
            selectionStartUtf16 = start.coerceIn(0, length),
            selectionEndUtf16 = end.coerceIn(0, length),
        )
        sink.onCompositionChanged(composition)
    }

    /** Delete only from the local preedit and return true when it handled it. */
    fun deleteFromComposition(beforeLength: Int, afterLength: Int): Boolean {
        if (!isComposing) return false
        val text = composition.text
        val selectionStart = minOf(
            composition.selectionStartUtf16,
            composition.selectionEndUtf16,
        )
        val selectionEnd = maxOf(
            composition.selectionStartUtf16,
            composition.selectionEndUtf16,
        )
        val from = (selectionStart - beforeLength.coerceAtLeast(0)).coerceAtLeast(0)
        val to = (selectionEnd + afterLength.coerceAtLeast(0)).coerceAtMost(text.length)
        val next = text.removeRange(from, to)
        composition = if (next.isEmpty()) {
            CompositionState.NONE
        } else {
            CompositionState(next, from, from)
        }
        sink.onCompositionChanged(composition)
        return true
    }

    /**
     * Ends composition styling.
     *
     * Sends nothing. An IME that calls this and then commits would otherwise
     * deliver the same text twice.
     */
    fun onFinishComposing() {
        sentPrefix = ""
        if (!isComposing) return
        composition = CompositionState.NONE
        sink.onCompositionChanged(composition)
    }

    /**
     * Commits text to the terminal, exactly once.
     *
     * Preedit is cleared before the send, so a redraw triggered by the send
     * cannot paint a composition that no longer exists.
     */
    fun onCommitText(text: CharSequence?): Boolean {
        val raw = CompositionState.sanitizeUtf16(text)
        // The IME echoing back what Enter already flushed. Swallow it once.
        val pending = flushedText
        flushedText = null
        if (pending != null && raw == pending) return false
        // A Korean IME commits the whole word it has been showing, including
        // the syllables already released from the preedit. Only the tail is
        // new; sending the rest again would repeat what the terminal has.
        val prefix = sentPrefix
        sentPrefix = ""
        val s = if (prefix.isNotEmpty() && raw.startsWith(prefix)) {
            raw.substring(prefix.length)
        } else {
            raw
        }
        val wasComposing = isComposing
        composition = CompositionState.NONE
        if (wasComposing) sink.onCompositionChanged(composition)
        if (s.isEmpty()) return false

        // Some IMEs commit the Enter key as literal text rather than sending a
        // key event. Writing that straight through gives a TUI a newline
        // character where it expected a key press, which it reads as a line
        // continuation instead of submit. Split the run and send the newlines
        // as real Enter keys.
        if (s.contains('\n')) {
            emitWithNewlinesAsEnter(s)
            return true
        }
        sink.sendText(s)
        return true
    }

    /**
     * Removes the last grapheme cluster of the preedit.
     *
     * Returns false when there was nothing composing, so the caller forwards
     * the key to the terminal instead.
     */
    fun backspaceComposition(): Boolean {
        if (!isComposing) return false
        val text = composition.text
        val caret = minOf(composition.selectionStartUtf16, composition.selectionEndUtf16)
            .coerceIn(0, text.length)
        if (caret == 0) return true
        // Whole cluster, so a Hangul syllable or an emoji with a modifier is
        // removed as the one unit the user sees.
        var from = 0
        for (c in PreeditLayout.clusters(text)) {
            if (c.startUtf16 >= caret) break
            from = c.startUtf16
        }
        val next = text.removeRange(from, caret)
        composition = if (next.isEmpty()) {
            CompositionState.NONE
        } else {
            CompositionState(next, from, from)
        }
        sink.onCompositionChanged(composition)
        return true
    }

    // Sends the text around each newline, with an Enter key in place of the
    // newline itself. Order is preserved so a pasted multi-line block arrives
    // as it was written.
    private fun emitWithNewlinesAsEnter(text: String) {
        var start = 0
        while (start <= text.length) {
            val nl = text.indexOf('\n', start)
            val end = if (nl < 0) text.length else nl
            if (end > start) sink.sendText(text.substring(start, end))
            if (nl < 0) return
            sink.sendKey(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER),
                composing = false,
            )
            start = nl + 1
        }
    }

    /**
     * Decides a key event.
     *
     * A key that reaches this connection is one the IME chose to pass through.
     * An IME that wanted Space to pick a candidate, as Japanese and Chinese
     * ones do, handles it internally and it never arrives here at all.
     * Consuming Space on the way past therefore did not protect a syllable; it
     * deleted a word separator the user had typed, which is the space that
     * went missing after Korean input.
     *
     * So Space is treated exactly like Enter: the open preedit is committed
     * and the key follows it, in that order, in one batch. A TUI that reads
     * keys as they arrive needs both, and needs them that way round.
     *
     * Backspace is the one key that stays local. It edits the preedit buffer,
     * which the app owns, so it must not also take a character off the shell's
     * line.
     */
    fun onKeyDown(keyCode: Int): KeyDecision = when {
        !isComposing -> KeyDecision.FORWARD
        isEnterKey(keyCode) || isSpaceKey(keyCode) -> KeyDecision.COMMIT_THEN_FORWARD
        keyCode == KeyEvent.KEYCODE_DEL -> KeyDecision.BACKSPACE_COMPOSITION
        else -> KeyDecision.FORWARD
    }

    /**
     * Commits the open preedit as typed and clears composition.
     *
     * Used when Enter ends a composition the IME has not committed itself.
     * Returns the text that was sent, or null when there was nothing open.
     */
    fun flushComposition(): String? {
        if (!isComposing) return null
        val text = composition.text
        composition = CompositionState.NONE
        // The IME still owns the whole word, released syllables included, and
        // commits all of it after the key. The guard holds that full word so
        // the commit is recognised as an echo rather than resending the tail.
        flushedText = sentPrefix + text
        sentPrefix = ""
        sink.onCompositionChanged(composition)
        sink.sendText(text)
        return text
    }

    /**
     * How many DEL keys a delete request should produce.
     *
     * Zero while composing: the IME is editing its own preedit buffer and the
     * terminal must not also lose a character.
     */
    fun onDeleteSurrounding(beforeLength: Int): Int =
        if (isComposing) 0 else beforeLength.coerceIn(0, MAX_DELETE)

    /** The connection closed. Uncommitted preedit is dropped, not sent. */
    fun onClose() {
        flushedText = null
        sentPrefix = ""
        if (!isComposing) return
        composition = CompositionState.NONE
        sink.onCompositionChanged(composition)
    }

    companion object {
        const val MAX_DELETE = 64

        fun isSpaceKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_SPACE

        fun isEnterKey(keyCode: Int): Boolean = when (keyCode) {
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            -> true
            else -> false
        }
    }
}
