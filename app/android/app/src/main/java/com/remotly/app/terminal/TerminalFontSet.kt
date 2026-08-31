package com.remotly.app.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.remotly.app.R
import kotlin.math.ceil

// Font selection and cell metrics for the terminal renderer.
//
// JetBrains Mono is bundled and is the primary face: its fixed advance defines
// the cell grid, so the grid cannot shift between devices. CJK glyphs come from
// the platform, because the renderer fits every glyph into its cell box and
// alignment therefore does not depend on the device font's own advances.
//
// Faces are loaded once per process. Loading a Typeface inside a draw pass
// would allocate on every frame.
class TerminalFontSet private constructor(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
    /** True when the bundled faces loaded; false means platform monospace. */
    val bundled: Boolean,
) {

    /** The face for a cell's style. */
    fun typeface(bold: Boolean, italic: Boolean): Typeface = when {
        bold && italic -> this.boldItalic
        bold -> this.bold
        italic -> this.italic
        else -> regular
    }

    companion object {
        private const val TAG = "TerminalFontSet"

        @Volatile
        private var cached: TerminalFontSet? = null

        fun get(context: Context): TerminalFontSet {
            cached?.let { return it }
            synchronized(this) {
                cached?.let { return it }
                val set = load(context)
                cached = set
                return set
            }
        }

        private fun load(context: Context): TerminalFontSet {
            val regular = font(context, R.font.jetbrains_mono_regular)
            val bold = font(context, R.font.jetbrains_mono_bold)
            val italic = font(context, R.font.jetbrains_mono_italic)
            val boldItalic = font(context, R.font.jetbrains_mono_bold_italic)

            if (regular == null || bold == null || italic == null || boldItalic == null) {
                // A missing asset is a build problem, not a user problem. The
                // terminal still renders on platform monospace rather than
                // showing nothing, and the failure is reported once.
                Log.e(TAG, "bundled terminal fonts failed to load; using platform monospace")
                return TerminalFontSet(
                    regular = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL),
                    bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD),
                    italic = Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC),
                    boldItalic = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD_ITALIC),
                    bundled = false,
                )
            }
            return TerminalFontSet(regular, bold, italic, boldItalic, bundled = true)
        }

        private fun font(context: Context, id: Int): Typeface? =
            try {
                ResourcesCompat.getFont(context, id)
            } catch (e: Exception) {
                null
            }
    }
}

// The terminal grid geometry for one font size.
data class CellMetrics(
    val widthPx: Int,
    val heightPx: Int,
    /** Distance from the row top to the text baseline. */
    val baselinePx: Float,
    /** Distance from the row top to the underline. */
    val underlinePx: Float,
) {
    /** The pixel width a cell occupies: one column, or two for a wide cell. */
    fun spanWidth(cells: Int): Float = (cells * widthPx).toFloat()
}

object TerminalMetrics {

    // Characters that must all share one advance in a fixed-width face. If they
    // disagree the face is not actually monospaced at this size, and the grid
    // would drift across a box-drawing TUI.
    private const val ADVANCE_PROBE = "M0iW\u2500\u2502\u250C\u2510"

    /**
     * Derives the cell grid from the primary font.
     *
     * Measures a representative fixed-width set rather than a single "M", so a
     * face whose box-drawing glyphs have a different advance is caught instead
     * of silently misaligning the grid.
     */
    fun measure(paint: Paint): CellMetrics {
        val widths = FloatArray(ADVANCE_PROBE.length)
        paint.getTextWidths(ADVANCE_PROBE, widths)

        var max = 0f
        for (w in widths) {
            if (w > max) max = w
        }
        // A zero advance means the face has no glyph for the probe at all.
        val width = if (max <= 0f) 8 else ceil(max).toInt()

        val fm = paint.fontMetrics
        val height = ceil(fm.descent - fm.ascent + fm.leading).toInt()
        val baseline = -fm.ascent

        return CellMetrics(
            widthPx = width,
            heightPx = if (height <= 0) 16 else height,
            baselinePx = baseline,
            underlinePx = (baseline + paint.underlinePosition).coerceAtLeast(1f),
        )
    }

    /**
     * True when every probe character shares one advance.
     *
     * Only meaningful for the bundled face; a platform fallback is allowed to
     * disagree because its glyphs are fitted to the box anyway.
     */
    fun isFixedAdvance(paint: Paint, tolerancePx: Float = 0.5f): Boolean {
        val widths = FloatArray(ADVANCE_PROBE.length)
        paint.getTextWidths(ADVANCE_PROBE, widths)
        var min = Float.MAX_VALUE
        var max = 0f
        for (w in widths) {
            if (w <= 0f) return false
            if (w < min) min = w
            if (w > max) max = w
        }
        return max - min <= tolerancePx
    }

    /**
     * The horizontal scale that fits a glyph into its target box.
     *
     * Returns 1 when the glyph already fits, so ordinary text is never
     * distorted. A glyph wider than its box is compressed, because overflowing
     * would paint into the neighbouring cell. A narrower glyph is left alone
     * and centered by [centerOffset]; stretching CJK to fill two cells looks
     * worse than the small gap it removes.
     */
    fun fitScale(naturalWidth: Float, targetWidth: Float): Float {
        if (naturalWidth <= 0f || targetWidth <= 0f) return 1f
        if (naturalWidth <= targetWidth) return 1f
        return targetWidth / naturalWidth
    }

    /** The x offset that centers a drawn glyph inside its target box. */
    fun centerOffset(drawnWidth: Float, targetWidth: Float): Float {
        if (drawnWidth >= targetWidth) return 0f
        return (targetWidth - drawnWidth) / 2f
    }
}
