package com.remotly.app.terminal

import android.content.Context
import android.graphics.Canvas
import android.view.View

/** Owns no session identity, input connection, or system clipboard. */
class TerminalPreviewView(context: Context) : View(context) {
    private val renderer = TerminalRenderer(context, resources.displayMetrics.density).apply {
        fontSizePx = 13f * resources.displayMetrics.scaledDensity
    }
    private val frame = TerminalFrame()
    private var handle = 0L
    private var theme = TerminalThemes.Classic

    init {
        isFocusable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        contentDescription = "Terminal preview. Prompt, ANSI colors, selection, cursor, and CJK text."
    }

    fun applyTheme(theme: TerminalTheme) {
        this.theme = theme
        renderer.applyTheme(theme)
        if (handle != 0L) RemotlyTerminal.applyTheme(handle, theme)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!RemotlyTerminal.isAvailable() || handle != 0L) return
        handle = RemotlyTerminal.create(38, 7, 65536, InertListener, theme)
        if (handle == 0L) return
        RemotlyTerminal.nativeWrite(handle, SAMPLE.toByteArray(Charsets.UTF_8))
        RemotlyTerminal.nativeSelectRange(handle, 0, 4, 12, 4, false)
    }

    override fun onDetachedFromWindow() {
        if (handle != 0L) RemotlyTerminal.nativeDestroy(handle)
        handle = 0L
        frame.reset()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // drawColor fills the whole clip. A parent that does not clip its children, such as a
        // Compose AndroidView, would let the fill cover everything around this view.
        val checkpoint = canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawColor(theme.background)
        if (handle != 0L) {
            var len = RemotlyTerminal.nativeGetFrame(handle, frame.buffer)
            if (len < 0) {
                frame.growBuffer(-len)
                len = RemotlyTerminal.nativeGetFrame(handle, frame.buffer)
            }
            if (len > 0 && frame.parseFromBuffer(len)) {
                renderer.drawFrame(canvas, frame, TerminalView.CursorStyle.BLOCK, false)
            }
        }
        canvas.restoreToCount(checkpoint)
    }

    private object InertListener : RemotlyTerminal.Listener {
        override fun onBell() = Unit
        override fun onTitle(titleUtf8: ByteArray) = Unit
        override fun onInput(data: ByteArray) = Unit
        override fun onPtyWrite(data: ByteArray) = Unit
        override fun onNotify(title: String, body: String) = Unit
        override fun onClipboardWrite(text: String) = Unit
    }

    private companion object {
        val SAMPLE = buildString {
            append("\u001b[32muser@host\u001b[0m ~ $ ls\r\n")
            append("Documents  notes.txt\r\n")
            for (i in 0..7) append("\u001b[${30 + i}m $i  ")
            append("\u001b[0m\r\n")
            for (i in 0..7) append("\u001b[${90 + i}m ${i + 8} ")
            append("\u001b[0m\r\nSelected text\r\n")
            append("한글 日本語 中文\r\n$ ")
        }
    }
}
