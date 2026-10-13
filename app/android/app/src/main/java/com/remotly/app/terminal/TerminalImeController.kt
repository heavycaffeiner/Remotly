package com.remotly.app.terminal

import android.view.KeyEvent

class TerminalImeController(private val sink: TerminalSink) {
    var composition: CompositionState = CompositionState.NONE
        private set
    val isComposing: Boolean get() = !composition.isEmpty

    // Only input sent at the current terminal cursor is editable, never terminal output.
    private var committedText = ""
    var documentOffset = 0
        private set
    private var releasedText = ""
    private var flushedText: String? = null
    private var flushedWord: String? = null
    private var batchDepth = 0
    private var compositionDirty = false

    val editorComposition: CompositionState
        get() {
            val offset = committedText.length + releasedText.length
            return CompositionState(committedText + releasedText + composition.text,
                offset + composition.selectionStartUtf16, offset + composition.selectionEndUtf16)
        }

    val composingStart: Int
        get() = if (isComposing || releasedText.isNotEmpty()) documentOffset + committedText.length else -1

    enum class KeyDecision { FORWARD, COMMIT_THEN_FORWARD, BACKSPACE_COMPOSITION }

    fun beginBatch() { batchDepth++ }

    fun endBatch() {
        if (batchDepth == 0) return
        batchDepth--
        if (batchDepth == 0 && compositionDirty) {
            compositionDirty = false
            sink.onCompositionChanged(composition)
        }
    }

    private fun publish(state: CompositionState) {
        composition = state
        if (batchDepth == 0) sink.onCompositionChanged(state) else compositionDirty = true
    }

    fun onComposingText(text: CharSequence?, newCursorPosition: Int) {
        flushedText = null
        flushedWord = null
        val next = CompositionState.of(text, newCursorPosition)
        if (!isComposing && !next.text.startsWith(releasedText)) releasedText = ""
        val clusters = PreeditLayout.clusters(next.text)
        val settled = (clusters.size - 1).coerceAtLeast(0)
        val releasable = clusters.take(settled).all { CompositionState.isCompleteHangul(it.text) }
        val before = PreeditLayout.clusters(releasedText)
        val ready = if (releasable) settled else minOf(clusters.size, before.size)
        val target = clusters.take(ready)
        var match = 0
        while (match < before.size && match < target.size && before[match].text == target[match].text) match++
        val stale = before.size - match
        val added = target.drop(match).joinToString("") { it.text }
        releasedText = target.joinToString("") { it.text }
        val rest = next.text.substring(releasedText.length)
        val caret = (next.selectionEndUtf16 - releasedText.length).coerceIn(0, rest.length)
        publish(if (rest.isEmpty()) CompositionState.NONE else CompositionState(rest, caret, caret))
        repeat(stale) { sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false) }
        if (added.isNotEmpty()) sink.sendText(added)
        if (next.text.isEmpty()) releasedText = ""
    }

    fun onCommitText(text: CharSequence?): Boolean {
        var raw = CompositionState.sanitizeUtf16(text)
        val pending = flushedText
        val whole = flushedWord
        flushedText = null
        flushedWord = null
        if (pending != null) {
            if (!whole.isNullOrEmpty() && raw.startsWith(whole)) raw = raw.substring(whole.length)
            else if (raw.startsWith(pending)) raw = raw.substring(pending.length)
        }
        val before = PreeditLayout.clusters(releasedText)
        val after = PreeditLayout.clusters(raw)
        var match = 0
        while (match < before.size && match < after.size && before[match].text == after[match].text) match++
        val stale = before.size - match
        val prefix = if (match == 0) 0 else after[match - 1].let { it.startUtf16 + it.text.length }
        releasedText = ""
        remember(raw.substring(0, prefix))
        publish(CompositionState.NONE)
        repeat(stale) { sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false) }
        val remaining = raw.substring(prefix)
        if (remaining.isEmpty()) return false
        sendCommitted(remaining)
        return true
    }

    fun replaceText(start: Int, end: Int, text: CharSequence?, newCursorPosition: Int): Boolean {
        if (start < 0 || end < 0) return false
        val editor = editorComposition.text
        val fromLong = minOf(start, end).toLong() - documentOffset
        val toLong = maxOf(start, end).toLong() - documentOffset
        if (fromLong < 0 || toLong > editor.length) return false
        val from = fromLong.toInt()
        val to = toLong.toInt()
        if (!validBoundary(editor, from) || !validBoundary(editor, to)) return false
        val replacement = CompositionState.sanitizeUtf16(text)
        // Rewrites across a submitted line are not reversible terminal edits.
        if ('\n' in replacement || '\r' in replacement) return false
        val target = editor.substring(0, from) + replacement + editor.substring(to)
        val caret = if (newCursorPosition > 0) from.toLong() + replacement.length + newCursorPosition - 1
            else from.toLong() + newCursorPosition
        if (caret.coerceIn(0, target.length.toLong()) != target.length.toLong()) return false
        val before = PreeditLayout.clusters(committedText + releasedText)
        val after = PreeditLayout.clusters(target)
        var match = 0
        while (match < before.size && match < after.size && before[match].text == after[match].text) match++
        val deletes = before.size - match
        if (deletes > MAX_DELETE) return false
        val prefix = if (match == 0) 0 else after[match - 1].let { it.startUtf16 + it.text.length }
        val added = target.substring(prefix)
        committedText = target
        releasedText = ""
        flushedText = null
        flushedWord = null
        trimContext()
        publish(CompositionState.NONE)
        repeat(deletes) { sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false) }
        if (added.isNotEmpty()) sink.sendText(added)
        return true
    }

    private fun remember(text: String) {
        committedText += text
        trimContext()
    }

    private fun trimContext() {
        if (committedText.length <= MAX_CONTEXT) return
        val limit = committedText.length - MAX_CONTEXT
        val cut = PreeditLayout.clusters(committedText).firstOrNull { it.startUtf16 >= limit }?.startUtf16
            ?: committedText.length
        documentOffset += cut
        committedText = committedText.substring(cut)
    }

    fun terminalKeySent(code: Int) {
        when {
            code == KeyEvent.KEYCODE_DEL && committedText.isNotEmpty() -> {
                committedText = committedText.substring(0, PreeditLayout.clusters(committedText).last().startUtf16)
                publish(composition)
            }
            isSpaceKey(code) -> {
                remember(" ")
                publish(composition)
            }
            code != KeyEvent.KEYCODE_DEL -> {
                documentOffset += committedText.length + releasedText.length + composition.text.length
                committedText = ""
                releasedText = ""
                publish(CompositionState.NONE)
                if (!isEnterKey(code)) {
                    flushedText = null
                    flushedWord = null
                }
            }
        }
    }

    private fun sendCommitted(text: String) {
        var start = 0
        for (i in text.indices) {
            if (text[i] != '\n' && text[i] != '\r') continue
            if (i > start) {
                val part = text.substring(start, i)
                remember(part)
                sink.sendText(part)
            }
            documentOffset += committedText.length
            committedText = ""
            sink.sendKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER), false)
            start = i + 1
        }
        if (start < text.length) {
            val part = text.substring(start)
            remember(part)
            sink.sendText(part)
        }
    }

    fun onSelection(start: Int, end: Int) {
        if (!isComposing) return
        publish(CompositionState(composition.text, boundary(composition.text, start), boundary(composition.text, end)))
    }

    fun editorSelection(start: Int, end: Int) {
        val offset = documentOffset + committedText.length + releasedText.length
        onSelection((start.toLong() - offset).coerceIn(0, Int.MAX_VALUE.toLong()).toInt(),
            (end.toLong() - offset).coerceIn(0, Int.MAX_VALUE.toLong()).toInt())
    }

    fun composingRegion(start: Int, end: Int): Boolean {
        val text = editorComposition.text
        val from = minOf(start, end) - documentOffset
        val to = maxOf(start, end) - documentOffset
        if (!validBoundary(text, from) || !validBoundary(text, to) || to != text.length || from > committedText.length) return false
        releasedText = committedText.substring(from) + releasedText
        committedText = committedText.substring(0, from)
        publish(composition)
        return true
    }

    fun deleteFromComposition(beforeLength: Int, afterLength: Int, codepoints: Boolean = false): Boolean {
        if (!isComposing) return false
        val text = composition.text
        val start = minOf(composition.selectionStartUtf16, composition.selectionEndUtf16)
        val end = maxOf(composition.selectionStartUtf16, composition.selectionEndUtf16)
        var from = if (codepoints) text.offsetByCodePoints(start, -minOf(beforeLength.coerceAtLeast(0), text.codePointCount(0, start)))
            else (start.toLong() - beforeLength.coerceAtLeast(0)).coerceAtLeast(0).toInt()
        var to = if (codepoints) text.offsetByCodePoints(end, minOf(afterLength.coerceAtLeast(0), text.codePointCount(end, text.length)))
            else (end.toLong() + afterLength.coerceAtLeast(0)).coerceAtMost(text.length.toLong()).toInt()
        if (from > 0 && from < text.length && Character.isLowSurrogate(text[from])) from--
        if (to > 0 && to < text.length && Character.isLowSurrogate(text[to])) to++
        val remaining = text.removeRange(from, to)
        publish(if (remaining.isEmpty()) CompositionState.NONE else CompositionState(remaining, from, from))
        return true
    }

    fun backspaceComposition(): Boolean {
        if (!isComposing) return false
        val text = composition.text
        val start = minOf(composition.selectionStartUtf16, composition.selectionEndUtf16)
        val end = maxOf(composition.selectionStartUtf16, composition.selectionEndUtf16)
        if (start != end) return deleteFromComposition(0, 0)
        if (start == 0) return true
        val clusters = PreeditLayout.clusters(text)
        val from = clusters.lastOrNull { it.startUtf16 < start }?.startUtf16 ?: 0
        val to = clusters.firstOrNull { it.startUtf16 < start && start < it.startUtf16 + it.text.length }
            ?.let { it.startUtf16 + it.text.length } ?: start
        return deleteFromComposition(start - from, to - start)
    }

    fun onKeyDown(keyCode: Int): KeyDecision = when {
        !isComposing -> KeyDecision.FORWARD
        isEnterKey(keyCode) || isSpaceKey(keyCode) -> KeyDecision.COMMIT_THEN_FORWARD
        keyCode == KeyEvent.KEYCODE_DEL -> KeyDecision.BACKSPACE_COMPOSITION
        else -> KeyDecision.FORWARD
    }

    fun flushComposition(): String? {
        if (!isComposing) return null
        val text = composition.text
        flushedText = text
        flushedWord = releasedText + text
        remember(releasedText + text)
        releasedText = ""
        publish(CompositionState.NONE)
        sink.sendText(text)
        return text
    }

    fun onFinishComposing() {
        if (isComposing) publish(CompositionState.NONE)
    }

    fun abandonComposition() {
        flushedText = null
        flushedWord = null
        if (isComposing) publish(CompositionState.NONE)
    }

    fun onDeleteSurrounding(beforeLength: Int): Int = if (isComposing) 0 else beforeLength.coerceIn(0, MAX_DELETE)

    fun onClose() {
        committedText = ""
        releasedText = ""
        documentOffset = 0
        batchDepth = 0
        compositionDirty = false
        abandonComposition()
    }

    companion object {
        const val MAX_DELETE = 64
        private const val MAX_CONTEXT = 4096
        fun isSpaceKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_SPACE
        fun isEnterKey(keyCode: Int): Boolean = keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        private fun validBoundary(text: String, offset: Int): Boolean = offset in 0..text.length &&
            (offset == 0 || offset == text.length || !Character.isHighSurrogate(text[offset - 1]) || !Character.isLowSurrogate(text[offset]))
        private fun boundary(text: String, offset: Int): Int {
            val value = offset.coerceIn(0, text.length)
            return if (validBoundary(text, value)) value else value - 1
        }
    }
}
