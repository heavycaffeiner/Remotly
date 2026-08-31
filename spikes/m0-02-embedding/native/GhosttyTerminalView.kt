// GhosttyTerminalView.kt
//
// Native boundary for M0-02: hosts libghostty (MIT, C ABI) behind a custom
// Android View and maps the platform IME contract onto the libghostty text and
// preedit APIs. Spike code, not the production module.
//
// The interface matches ADR 0001:
//   feedOutput(bytes)  app -> native, raw terminal bytes
//   onCommittedInput(bytes)  native -> app, committed key input only
//   onResize(cols, rows)     native -> app, viewport changed
//   onCompositionChanged(active)  native -> app, for the M0-03 IME proof
//   onBell(), onTitleChanged(title)
//   resize(cols, rows), setFontSize(px), getSelectedText()
//
// libghostty C API used (names per the pinned commit, see README):
//   ghostty_surface_new / ghostty_surface_feed
//   ghostty_surface_text(surface, str, len)         committed text
//   ghostty_surface_preedit(surface, str, len)      IME composition
//   ghostty_surface_key(surface, key)               physical/translated keys
//   ghostty_surface_size / ghostty_surface_draw
//
// The frame callback delivers cell content; this spike draws it with a Canvas.
// libghostty owns parsing, the screen model, and selection; we own rendering
// and font fallback.

package com.remotly.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection

class GhosttyTerminalView(context: Context) : View(context) {

    // Surface handle from ghostty_surface_new. The app event loop and frame
    // callbacks live in the JNI layer (libghostty owns its reader/render
    // threads); this view only draws and forwards input.
    private var surface: Long = 0

    private var cols = 80
    private var rows = 24
    private var cellW = 9f
    private var cellH = 18f

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = cellH
        typeface = Typeface.MONOSPACE
    }

    private var composing = false

    companion object {
        init {
            System.loadLibrary("ghostty")
        }

        // JNI bindings to the pinned libghostty commit. Exact signatures are
        // aligned to the pinned header during M0-02.
        external fun surfaceNew(cols: Int, rows: Int, fontSize: Float, scale: Float): Long
        external fun surfaceFeed(surface: Long, bytes: ByteArray, len: Int)
        external fun surfaceText(surface: Long, text: String)
        external fun surfacePreedit(surface: Long, text: String, offset: Int)
        external fun surfaceKey(
            surface: Long,
            action: Int,
            mods: Int,
            keycode: Int,
            text: String?,
            unshiftedCodepoint: Int,
            composing: Boolean,
        ): Boolean
        external fun surfaceSetSize(surface: Long, cols: Int, rows: Int)
        external fun surfaceDraw(surface: Long)
        external fun surfaceDestroy(surface: Long)
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun attach(cols: Int, rows: Int, fontSize: Float, scale: Float) {
        this.cols = cols
        this.rows = rows
        this.cellH = fontSize
        surface = surfaceNew(cols, rows, fontSize, scale)
    }

    // App -> native: raw terminal bytes, possibly split mid-UTF-8.
    fun feedOutput(bytes: ByteArray) = surfaceFeed(surface, bytes, bytes.size)

    // InputConnection contract: composition stays local until commit.
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return object : BaseInputConnection(this, true) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                surfaceText(surface, text?.toString() ?: "")
                onCommittedInput((text?.toString() ?: "").toByteArray(Charsets.UTF_8))
                return true
            }

            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val t = text?.toString() ?: ""
                if (!composing) {
                    composing = true
                    onCompositionChanged(true)
                }
                surfacePreedit(surface, t, t.length)
                return true
            }

            override fun finishComposingText(): Boolean {
                surfacePreedit(surface, "", 0)
                if (composing) {
                    composing = false
                    onCompositionChanged(false)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                return surfaceKey(surface, event.action, event.metaState, event.keyCode,
                    event.unicodeChar.takeIf { it != '\u0000' }?.toString(), 0, composing)
            }
        }
    }

    // Frame callback (JNI) hands a cell grid to this view via the renderer;
    // this skeleton redraws a placeholder grid. Replace with cell drawing from
    // the surface frame data during M0-02.
    override fun onDraw(canvas: Canvas) {
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                canvas.drawText(" ", c * cellW, (r + 1) * cellH, textPaint)
            }
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = resolveSize((cols * cellW).toInt(), widthMeasureSpec)
        val h = resolveSize((rows * cellH).toInt(), heightMeasureSpec)
        setMeasuredDimension(w, h)
        if (surface != 0L) {
            val newCols = (w / cellW).toInt().coerceAtLeast(1)
            val newRows = (h / cellH).toInt().coerceAtLeast(1)
            if (newCols != cols || newRows != rows) {
                cols = newCols
                rows = newRows
                surfaceSetSize(surface, cols, rows)
            }
        }
    }

    override fun onDetachedFromWindow() {
        if (surface != 0L) {
            surfaceDestroy(surface)
            surface = 0
        }
        super.onDetachedFromWindow()
    }

    // Native -> app callbacks; wired to Lynx custom events per the target Lynx
    // release during M0-02.
    private fun onCommittedInput(bytes: ByteArray) = Unit
    private fun onTitleChanged(title: String) = Unit
    private fun onBell() = Unit
    private fun onCompositionChanged(active: Boolean) = Unit
}
