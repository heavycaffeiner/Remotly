package com.remotly.app.terminal

import android.annotation.TargetApi
import android.os.Build
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.SurroundingText
import android.view.inputmethod.TextAttribute
import android.view.inputmethod.TextSnapshot

interface TerminalSink {
    fun sendText(text: String)
    fun sendKey(event: KeyEvent, composing: Boolean)
    fun onCompositionChanged(state: CompositionState)
}

class TerminalInputConnection private constructor(
    view: View,
    private val session: Session,
    private val trace: Boolean,
) : BaseInputConnection(view, true) {
    // Android may create a connection and discard it without replacing the served one.
    private class Session(val sink: TerminalSink) {
        var connections = 0
        var batches = 0
        var forwarding = false
        var invalidated = false
        val controller = TerminalImeController(object : TerminalSink {
            override fun sendText(text: String) = sink.sendText(text)
            override fun sendKey(event: KeyEvent, composing: Boolean) = forward(event, composing, false)
            override fun onCompositionChanged(state: CompositionState) = sink.onCompositionChanged(state)
        })

        fun forward(event: KeyEvent, composing: Boolean, track: Boolean) {
            controller.beginBatch()
            val previous = forwarding
            forwarding = true
            try {
                if (track && event.action == KeyEvent.ACTION_DOWN && !composing) {
                    controller.terminalKeySent(if (event.isCtrlPressed || event.isAltPressed) KeyEvent.KEYCODE_UNKNOWN else event.keyCode)
                }
                sink.sendKey(event, composing)
            } finally {
                forwarding = previous
                controller.endBatch()
            }
        }
    }

    constructor(view: View, sink: TerminalSink, trace: Boolean = false) : this(view, Session(sink), trace)
    constructor(view: View, previous: TerminalInputConnection) : this(view, previous.session, previous.trace)

    private val controller get() = session.controller
    private val localKeys = mutableSetOf<Int>()
    private var batchDepth = 0
    private var closed = false
    val isClosed: Boolean get() = closed || session.invalidated
    val hasLiveSession: Boolean get() = !session.invalidated && session.connections > 0

    init { session.connections++ }

    val composition: CompositionState get() = controller.composition
    val editorComposition: CompositionState get() = controller.editorComposition
    val documentOffset: Int get() = controller.documentOffset
    val composingStart: Int get() = controller.composingStart
    val isComposing: Boolean get() = controller.isComposing
    val isBatchEditing: Boolean get() = session.batches > 0

    override fun beginBatchEdit(): Boolean {
        if (isClosed || batchDepth == Int.MAX_VALUE) return false
        batchDepth++
        session.batches++
        controller.beginBatch()
        return true
    }

    override fun endBatchEdit(): Boolean {
        if (closed || batchDepth == 0) return false
        batchDepth--
        session.batches--
        controller.endBatch()
        return batchDepth > 0
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (isClosed) return false
        controller.onComposingText(text, newCursorPosition)
        traceEvent("setComposingText", composition.text.length, composition.selectionEndUtf16)
        return true
    }

    fun abandonComposition() { controller.abandonComposition() }

    fun closeSession() {
        closeConnection()
        session.invalidated = true
        controller.onClose()
    }

    // Called by hardware and toolbar keys that bypass InputConnection.sendKeyEvent.
    fun terminalKeySent(code: Int) {
        if (hasLiveSession && !session.forwarding && !KeyEvent.isModifierKey(code)) controller.terminalKeySent(code)
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean =
        !isClosed && start >= 0 && end >= 0 && controller.composingRegion(start, end)

    override fun finishComposingText(): Boolean {
        if (isClosed) return false
        controller.onFinishComposing()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        if (isClosed) return false
        controller.beginBatch()
        try {
            controller.onCommitText(text)
        } finally {
            controller.endBatch()
        }
        traceEvent("commitText", text?.length ?: 0, 0)
        return true
    }

    private fun delete(before: Int, after: Int, codepoints: Boolean): Boolean {
        if (isClosed || before < 0 || after < 0) return false
        if (controller.deleteFromComposition(before, after, codepoints)) return true
        repeat(controller.onDeleteSurrounding(before)) {
            session.forward(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL), false, true)
        }
        repeat(controller.onDeleteSurrounding(after)) {
            session.forward(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL), false, true)
        }
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, false)
    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, true)

    override fun setSelection(start: Int, end: Int): Boolean {
        if (isClosed || start < 0 || end < 0) return false
        controller.editorSelection(start, end)
        return true
    }

    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
        if (isClosed) return ""
        val c = editorComposition
        val caret = minOf(c.selectionStartUtf16, c.selectionEndUtf16)
        var start = (caret.toLong() - length.coerceAtLeast(0)).coerceAtLeast(0).toInt()
        if (start > 0 && start < c.text.length && Character.isLowSurrogate(c.text[start])) start++
        return c.text.substring(start, caret)
    }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
        if (isClosed) return ""
        val c = editorComposition
        val caret = maxOf(c.selectionStartUtf16, c.selectionEndUtf16)
        var end = (caret.toLong() + length.coerceAtLeast(0)).coerceAtMost(c.text.length.toLong()).toInt()
        if (end > 0 && end < c.text.length && Character.isLowSurrogate(c.text[end])) end--
        return c.text.substring(caret, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        if (isClosed) return null
        val c = editorComposition
        val start = minOf(c.selectionStartUtf16, c.selectionEndUtf16)
        val end = maxOf(c.selectionStartUtf16, c.selectionEndUtf16)
        return if (start == end) null else c.text.substring(start, end)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val c = editorComposition
        return ExtractedText().apply {
            text = if (isClosed) "" else c.text
            startOffset = documentOffset
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = if (isClosed) 0 else c.selectionStartUtf16
            selectionEnd = if (isClosed) 0 else c.selectionEndUtf16
            this.flags = ExtractedText.FLAG_SINGLE_LINE
        }
    }

    @TargetApi(31)
    override fun getSurroundingText(beforeLength: Int, afterLength: Int, flags: Int): SurroundingText? {
        if (isClosed || beforeLength < 0 || afterLength < 0) return null
        val c = editorComposition
        val left = minOf(c.selectionStartUtf16, c.selectionEndUtf16)
        val right = maxOf(c.selectionStartUtf16, c.selectionEndUtf16)
        var from = (left.toLong() - beforeLength).coerceAtLeast(0).toInt()
        var to = (right.toLong() + afterLength).coerceAtMost(c.text.length.toLong()).toInt()
        if (from > 0 && from < c.text.length && Character.isLowSurrogate(c.text[from])) from++
        if (to > 0 && to < c.text.length && Character.isLowSurrogate(c.text[to])) to--
        return SurroundingText(c.text.substring(from, to), c.selectionStartUtf16 - from,
            c.selectionEndUtf16 - from, documentOffset + from)
    }

    override fun getCursorCapsMode(reqModes: Int): Int = 0

    @TargetApi(33)
    override fun takeSnapshot(): TextSnapshot? {
        if (isClosed) return null
        val start = composingStart
        val end = if (start < 0) -1 else documentOffset + editorComposition.text.length
        return TextSnapshot(getSurroundingText(Int.MAX_VALUE, Int.MAX_VALUE, InputConnection.GET_TEXT_WITH_STYLES)!!, start, end, 0)
    }

    @TargetApi(34)
    override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int, textAttribute: TextAttribute?): Boolean =
        !isClosed && controller.replaceText(start, end, text, newCursorPosition)

    @TargetApi(33)
    override fun requestCursorUpdates(cursorUpdateMode: Int, cursorUpdateFilter: Int): Boolean =
        cursorUpdateFilter == 0 && requestCursorUpdates(cursorUpdateMode)

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        val known = InputConnection.CURSOR_UPDATE_IMMEDIATE or InputConnection.CURSOR_UPDATE_MONITOR
        return !isClosed && cursorUpdateMode and known.inv() == 0
    }

    override fun performEditorAction(editorAction: Int): Boolean =
        if (editorAction == EditorInfo.IME_ACTION_NONE || editorAction == EditorInfo.IME_ACTION_UNSPECIFIED || editorAction == EditorInfo.IME_ACTION_DONE) {
            sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        } else false

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (isClosed) return event.action == KeyEvent.ACTION_UP
        if (KeyEvent.isModifierKey(event.keyCode)) return true
        if (event.action == KeyEvent.ACTION_UP && localKeys.remove(event.keyCode)) return true
        if (event.action != KeyEvent.ACTION_DOWN) return true
        if (KeyMap.handlesBeforeIme(event.keyCode, event.metaState)) {
            session.forward(event, false, false)
            if (!isClosed) {
                controller.abandonComposition()
                controller.terminalKeySent(KeyEvent.KEYCODE_UNKNOWN)
            }
            return true
        }
        when (controller.onKeyDown(event.keyCode)) {
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD -> {
                controller.flushComposition()
                session.forward(event, false, true)
            }
            TerminalImeController.KeyDecision.BACKSPACE_COMPOSITION -> {
                localKeys.add(event.keyCode)
                controller.backspaceComposition()
            }
            TerminalImeController.KeyDecision.FORWARD -> session.forward(event, isComposing, true)
        }
        return true
    }

    override fun closeConnection() {
        if (closed) return
        while (batchDepth > 0) endBatchEdit()
        closed = true
        localKeys.clear()
        if (--session.connections == 0) controller.onClose()
        if (Build.VERSION.SDK_INT >= 24) super.closeConnection()
    }

    private fun traceEvent(name: String, length: Int, selection: Int) {
        if (trace) Log.d("TerminalIme", "$name length=$length selection=$selection")
    }
}
