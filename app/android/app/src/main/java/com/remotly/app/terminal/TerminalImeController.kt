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
    private var flushedEchoPrefix: String = ""
    /**
     * How many leading clusters of the preedit the terminal already has.
     *
     * A Korean IME reports the whole word on every keystroke, so this marks
     * where the new text starts. Reset whenever the preedit empties or the
     * composition ends.
     */
    private var committedClusters: Int = 0

    /**
     * The text those clusters were, so a commit can be checked against it.
     *
     * The count alone cannot tell the IME committing the word it was showing
     * from the user committing something else: after 한 was flushed, a commit
     * of 글 is new input and must go out whole. Only a commit that actually
     * begins with this text has its head skipped.
     */
    private var releasedText: String = ""

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
        flushedEchoPrefix = ""
        val next = CompositionState.of(text, newCursorPosition)
        val clusters = PreeditLayout.clusters(next.text)
        // A composition opening with no preedit behind it may be the IME
        // restarting the word it was already showing, or a new word after the
        // last one was flushed or committed. It continues the old word only if
        // it still carries the text that was released from it; otherwise the
        // record describes something the terminal has moved past. Carrying it
        // into a new word read the shorter preedit as a backspace and sent
        // retraction keys into the shell, deleting text the user had typed.
        if (!isComposing && !next.text.startsWith(releasedText)) {
            committedClusters = 0
            releasedText = ""
        }
        // Everything before the trailing cluster is finished and goes to the
        // terminal; the trailing one can still change, so it stays composing.
        // A half-typed syllable at the end (안녕ㅎ) is a cluster like any
        // other: the ones before it are still settled, and treating the whole
        // word as unreleasable there reset the count and resent them.
        val settled = (clusters.size - 1).coerceAtLeast(0)
        // Latin is held whole: autocorrect and candidate selection must be
        // able to rewrite what they showed, so nothing goes out until commit.
        val releasable = clusters.take(settled).all { CompositionState.isCompleteHangul(it.text) }
        val ready = if (releasable) settled else committedClusters
        val currentClusters = PreeditLayout.clusters(releasedText)
        val targetClusters = clusters.take(ready)
        var match = 0
        while (match < currentClusters.size && match < targetClusters.size && currentClusters[match].text == targetClusters[match].text) {
            match++
        }
        val stale = currentClusters.size - match
        val released = targetClusters.drop(match).joinToString("") { it.text }
        committedClusters = ready
        releasedText = targetClusters.joinToString("") { it.text }
        val rest = clusters.drop(committedClusters).joinToString("") { it.text }
        // The IME's offset counts from the whole word, which includes what the
        // terminal already took, so it is rebased onto the remaining preedit.
        // A caret the IME placed before the preedit (newCursorPosition 0) has
        // to survive that: clamping alone would drag it to the end.
        val consumed = next.text.length - rest.length
        val caret = (next.selectionEndUtf16 - consumed).coerceIn(0, rest.length)
        composition = if (rest.isEmpty()) {
            CompositionState.NONE
        } else {
            CompositionState(rest, caret, caret)
        }
        if (next.text.isEmpty()) {
            committedClusters = 0
            releasedText = ""
        }
        // Preedit is updated before the send so a redraw triggered by it never
        // paints text the terminal has already taken.
        sink.onCompositionChanged(composition)
        repeat(stale) {
            sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false)
        }
        if (released.isNotEmpty()) sink.sendText(released)
    }

    /**
     * Drops an open preedit without sending it.
     *
     * An extra key acts on the terminal, not on the text being composed, so
     * the overlay is taken down rather than left hanging over the result.
     */
    fun abandonComposition() {
        if (!isComposing) return
        flushedText = null
        flushedEchoPrefix = ""
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
     * Sends nothing. The cluster count goes with the preedit it described: a
     * commit that follows carries a word this class never released, so none of
     * it may be skipped.
     */
    fun onFinishComposing() {
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
        val pending = flushedText
        val echoPrefix = flushedEchoPrefix
        flushedText = null
        flushedEchoPrefix = ""

        // The IME echoing back what Enter/Space already flushed.
        if (pending != null && (raw == pending || (echoPrefix.isNotEmpty() && raw.startsWith(echoPrefix)))) {
            val wasOpen = isComposing
            composition = CompositionState.NONE
            if (wasOpen) sink.onCompositionChanged(composition)
            return false
        }

        val currentClusters = PreeditLayout.clusters(releasedText)
        val rawClusters = PreeditLayout.clusters(raw)
        var match = 0
        while (match < currentClusters.size && match < rawClusters.size && currentClusters[match].text == rawClusters[match].text) {
            match++
        }
        val stale = currentClusters.size - match
        val s = rawClusters.drop(match).joinToString("") { it.text }
        committedClusters = 0
        releasedText = ""
        val wasComposing = isComposing
        composition = CompositionState.NONE
        if (wasComposing) sink.onCompositionChanged(composition)
        repeat(stale) {
            sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false)
        }
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
        // Flushed text is user-committed to the shell and must never be retracted.
        flushedText = text
        flushedEchoPrefix = releasedText + text
        committedClusters = 0
        releasedText = ""
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
        committedClusters = 0
        releasedText = ""
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
