package com.remotly.app.terminal

import android.util.Log
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection

/**
 * What an input connection is allowed to do to the terminal.
 *
 * A seam, so the composition rules are testable without a View, a native
 * handle, or an IME. Everything that can reach the PTY goes through here.
 */
interface TerminalSink {
    /** Commits text to the terminal as UTF-8. */
    fun sendText(text: String)

    /** Sends a key. [composing] marks it as arriving during active preedit. */
    fun sendKey(event: KeyEvent, composing: Boolean)

    /** The preedit changed. The renderer redraws and updates the IME anchor. */
    fun onCompositionChanged(state: CompositionState)
}

/**
 * The terminal's IME connection.
 *
 * Preedit is local: it is drawn at the terminal cursor and never sent to the
 * PTY. Only a commit crosses that boundary, and exactly once.
 *
 * The decisions live in [TerminalImeController]; this class is the Android
 * binding around them.
 */
class TerminalInputConnection(
    view: View,
    private val sink: TerminalSink,
    /** Debug builds only. Records event shapes, never text. */
    private val trace: Boolean = false,
) : BaseInputConnection(view, false) {

    private val controller = TerminalImeController(sink)

    val composition: CompositionState get() = controller.composition
    val isComposing: Boolean get() = controller.isComposing

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        controller.onComposingText(text, newCursorPosition)
        traceEvent("setComposingText", composition.text.length, composition.selectionEndUtf16)
        return true
    }

    /** Drops the preedit without sending it. */
    fun abandonComposition() {
        controller.abandonComposition()
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // The region refers to text already committed to the terminal, which
        // this connection cannot take back. The preedit buffer is left alone
        // rather than inventing content for the IME to edit.
        controller.onSelection(start, end)
        traceEvent("setComposingRegion", (end - start).coerceAtLeast(0), end)
        return true
    }

    override fun finishComposingText(): Boolean {
        controller.onFinishComposing()
        traceEvent("finishComposingText", 0, 0)
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        controller.onCommitText(text)
        traceEvent("commitText", text?.length ?: 0, 0)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        if (controller.deleteFromComposition(beforeLength, afterLength)) {
            traceEvent("deleteSurroundingText.local", beforeLength, afterLength)
            return true
        }
        val dels = controller.onDeleteSurrounding(beforeLength)
        repeat(dels) {
            sink.sendKey(
                KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL),
                composing = false,
            )
        }
        traceEvent("deleteSurroundingText", dels, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(
        beforeLength: Int,
        afterLength: Int,
    ): Boolean = deleteSurroundingText(beforeLength, afterLength)

    override fun setSelection(start: Int, end: Int): Boolean {
        controller.onSelection(start, end)
        traceEvent("setSelection", start, end)
        return true
    }

    /**
     * What sits behind the cursor.
     *
     * Only the open preedit, which this connection owns outright. Committed
     * text is deliberately not reported: once it has gone to the shell it is
     * no longer the app's to describe or to edit.
     *
     * Reporting it made backspace destructive in a way the user could see. The
     * IME treats anything returned here as text it may edit, so it answered a
     * backspace by deleting from that string and rewriting the result, walking
     * back through characters that had already been sent. This is not language
     * specific and never was.
     */
    override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
        val c = composition
        val caret = minOf(c.selectionStartUtf16, c.selectionEndUtf16)
        val start = (caret - length.coerceAtLeast(0)).coerceAtLeast(0)
        return styled(c.text.subSequence(start, caret), flags)
    }

    override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
        val c = composition
        val caret = maxOf(c.selectionStartUtf16, c.selectionEndUtf16)
        val end = (caret + length.coerceAtLeast(0)).coerceAtMost(c.text.length)
        return styled(c.text.subSequence(caret, end), flags)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val c = composition
        val start = minOf(c.selectionStartUtf16, c.selectionEndUtf16)
        val end = maxOf(c.selectionStartUtf16, c.selectionEndUtf16)
        if (start == end) return null
        return styled(c.text.subSequence(start, end), flags)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val c = composition
        return ExtractedText().apply {
            text = c.text
            startOffset = 0
            partialStartOffset = -1
            partialEndOffset = -1
            selectionStart = c.selectionStartUtf16
            selectionEnd = c.selectionEndUtf16
            this.flags = ExtractedText.FLAG_SINGLE_LINE
        }
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        // Reject unknown bits, but accept immediate/monitor requests. The View
        // already updates its anchor on every composition and terminal frame.
        val known = InputConnection.CURSOR_UPDATE_IMMEDIATE or
            InputConnection.CURSOR_UPDATE_MONITOR
        return cursorUpdateMode and known.inv() == 0
    }

    override fun performEditorAction(editorAction: Int): Boolean {
        if (editorAction == EditorInfo.IME_ACTION_NONE ||
            editorAction == EditorInfo.IME_ACTION_UNSPECIFIED ||
            editorAction == EditorInfo.IME_ACTION_DONE
        ) {
            return sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        }
        return false
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        // Consume the up event as well. Re-dispatching it reaches onKeyUp in
        // the view hierarchy, which would send the key a second time.
        if (event.action != KeyEvent.ACTION_DOWN) return true

        return when (controller.onKeyDown(event.keyCode)) {
            TerminalImeController.KeyDecision.COMMIT_THEN_FORWARD -> {
                val committed = controller.flushComposition()
                // Composition is closed before the key goes out, so the Enter
                // or Space is encoded as an ordinary key rather than being
                // dropped as part of a composition sequence.
                traceEvent("sendKeyEvent.commit", committed?.length ?: 0, 0)
                sink.sendKey(event, composing = false)
                true
            }
            TerminalImeController.KeyDecision.BACKSPACE_COMPOSITION -> {
                controller.backspaceComposition()
                traceEvent("sendKeyEvent.backspace", composition.text.length, 0)
                true
            }
            TerminalImeController.KeyDecision.FORWARD -> {
                traceEvent("sendKeyEvent", event.keyCode, if (isComposing) 1 else 0)
                sink.sendKey(event, composing = isComposing)
                true
            }
        }
    }

    override fun closeConnection() {
        controller.onClose()
        traceEvent("closeConnection", 0, 0)
        super.closeConnection()
    }

    private fun styled(text: CharSequence, flags: Int): CharSequence =
        if (flags and InputConnection.GET_TEXT_WITH_STYLES != 0) text
        else TextUtils.substring(text, 0, text.length)

    // Never logs text or bytes: only the event shape, so a debug trace cannot
    // leak what the user typed.
    private fun traceEvent(name: String, length: Int, selection: Int) {
        if (!trace) return
        Log.d(TAG, "$name length=$length selection=$selection")
    }

    private companion object {
        const val TAG = "TerminalIme"
    }
}
