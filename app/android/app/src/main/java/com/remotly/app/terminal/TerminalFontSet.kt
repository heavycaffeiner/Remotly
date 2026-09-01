package com.remotly.app.terminal

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import androidx.core.content.res.ResourcesCompat
import com.remotly.app.R
import kotlin.math.ceil

// Font selection and cell metrics for the terminal renderer.
//
// JetBrains Mono Nerd Font is the primary face: its fixed advance defines the
// cell grid, so the grid cannot shift between devices. Three groups are
// bundled and the renderer picks between them per cell:
//
//   - Four text styles, carrying Latin, Greek, Cyrillic, box drawing, and
//     block elements.
//   - One symbol face for the Nerd Font ranges, shared by every style because
//     those outlines do not differ between styles. Its icons keep their
//     natural width, which for most of them is wider than one cell.
//   - Noto Sans Mono CJK for KR, JP, and SC. The three draw Han differently,
//     so the active one follows the user's language: the codepoint alone
//     cannot say which region's form is wanted.
//
// Faces are loaded once per process. Loading a Typeface inside a draw pass
// would allocate on every frame.
class TerminalFontSet private constructor(
    val regular: Typeface,
    val bold: Typeface,
    val italic: Typeface,
    val boldItalic: Typeface,
    /**
     * The Nerd Font symbol face, or null when it did not load.
     *
     * Null means symbol cells fall back to the text face, which draws them
     * from whatever the platform provides rather than showing nothing.
     */
    val symbols: Typeface?,
    /** The CJK face for the active region, or null when it did not load. */
    val cjk: Typeface?,
    /** [cjk] with a synthetic bold, so a bold CJK cell is not drawn as regular. */
    val cjkBold: Typeface?,
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

    /**
     * The face index for a cell, used to key cached advances.
     *
     * Every face a cell can be drawn with gets its own index, because two
     * faces disagree about the advance of the same character and a shared key
     * would hand one face the other's measurement.
     */
    fun faceIndex(bold: Boolean, italic: Boolean, kind: GlyphKind): Int = when (kind) {
        GlyphKind.SYMBOL -> if (symbols != null) FACE_SYMBOLS else styleIndex(bold, italic)
        GlyphKind.CJK ->
            if (cjk == null) styleIndex(bold, italic)
            else if (bold) FACE_CJK_BOLD
            else FACE_CJK
        GlyphKind.TEXT -> styleIndex(bold, italic)
    }

    /** The typeface for a cell, by style and by what the cell holds. */
    fun typefaceFor(bold: Boolean, italic: Boolean, kind: GlyphKind): Typeface = when (kind) {
        GlyphKind.SYMBOL -> symbols ?: typeface(bold, italic)
        GlyphKind.CJK -> (if (bold) cjkBold else cjk) ?: typeface(bold, italic)
        GlyphKind.TEXT -> typeface(bold, italic)
    }

    private fun styleIndex(bold: Boolean, italic: Boolean): Int =
        (if (bold) 1 else 0) or (if (italic) 2 else 0)

    companion object {
        private const val TAG = "TerminalFontSet"

        // Face indices. The four text styles occupy 0..3 so that the style bits
        // are their own index; the extra faces follow.
        const val FACE_SYMBOLS = 4
        const val FACE_CJK = 5
        const val FACE_CJK_BOLD = 6

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
            // Not required: a build without it draws symbols from the platform
            // rather than refusing to render.
            val symbols = font(context, R.font.nerd_symbols)
            val cjk = font(context, cjkFontId(context))

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
                    symbols = symbols,
                    cjk = cjk,
                    cjkBold = cjk?.let { syntheticBold(it) },
                    bundled = false,
                )
            }
            if (symbols == null) {
                Log.e(TAG, "Nerd Font symbol face failed to load; symbols use the text face")
            }
            if (cjk == null) {
                Log.e(TAG, "CJK face failed to load; CJK uses the platform font")
            }
            return TerminalFontSet(
                regular = regular,
                bold = bold,
                italic = italic,
                boldItalic = boldItalic,
                symbols = symbols,
                cjk = cjk,
                cjkBold = cjk?.let { syntheticBold(it) },
                bundled = true,
            )
        }

        /**
         * The bundled CJK face matching the user's language.
         *
         * Han is drawn differently in Korea, Japan, and China, and the
         * codepoint does not say which form is meant, so the only signal
         * available is what the user reads. Korean is the default: it is the
         * one region whose script (Hangul) the other two faces do not cover
         * well, so a wrong guess there is the most visible.
         */
        private fun cjkFontId(context: Context): Int {
            val locales = context.resources.configuration.let { config ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val list = config.locales
                    (0 until list.size()).map { list[it] }
                } else {
                    @Suppress("DEPRECATION")
                    listOf(config.locale)
                }
            }
            for (locale in locales) {
                when (locale.language) {
                    "ko" -> return R.font.noto_sans_mono_cjk_kr
                    "ja" -> return R.font.noto_sans_mono_cjk_jp
                    "zh" -> return R.font.noto_sans_mono_cjk_sc
                }
            }
            return R.font.noto_sans_mono_cjk_kr
        }

        /**
         * A bold derived from [base].
         *
         * Only the Regular CJK faces are bundled: a bundled Bold would double
         * a 12 MB payload to make bold CJK, which a terminal draws rarely,
         * marginally better than the synthetic weight.
         */
        private fun syntheticBold(base: Typeface): Typeface =
            Typeface.create(base, Typeface.BOLD)

        private fun font(context: Context, id: Int): Typeface? =
            try {
                ResourcesCompat.getFont(context, id)
            } catch (e: Exception) {
                null
            }
    }
}

/**
 * What a cell holds, which decides the face it is drawn with.
 *
 * Derived from the cell's first codepoint. The terminal has already decided
 * how many columns the cell occupies; this only picks the font.
 */
enum class GlyphKind {
    TEXT,
    SYMBOL,
    CJK;

    companion object {
        /**
         * Classifies a cell by its first codepoint.
         *
         * Only the ranges the bundled faces actually cover are named. Anything
         * else is TEXT, which falls through to the platform font the way it
         * did before any of these faces were bundled.
         */
        fun of(codePoint: Int): GlyphKind = when {
            codePoint < 0x1100 -> TEXT
            // Nerd Font ranges: the BMP private use area and the supplementary
            // plane block holding Material Design icons.
            codePoint in 0xE000..0xF8FF -> SYMBOL
            codePoint in 0xF0000..0xF1AF0 -> SYMBOL
            // Hangul jamo, kana, bopomofo, CJK punctuation and Han, plus the
            // fullwidth forms.
            codePoint in 0x1100..0x11FF -> CJK
            codePoint in 0x2E80..0x2EFF -> CJK
            codePoint in 0x3000..0x33FF -> CJK
            codePoint in 0x3400..0x4DBF -> CJK
            codePoint in 0x4E00..0x9FFF -> CJK
            codePoint in 0xA960..0xA97F -> CJK
            codePoint in 0xAC00..0xD7A3 -> CJK
            codePoint in 0xD7B0..0xD7FF -> CJK
            codePoint in 0xF900..0xFAFF -> CJK
            codePoint in 0xFE30..0xFE4F -> CJK
            codePoint in 0xFF00..0xFFEF -> CJK
            else -> TEXT
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

/**
 * How far a glyph may paint outside the column the terminal gave its cell.
 *
 * Nerd Font icons carry up to about 1.5 cells of ink on a one-cell advance.
 * Squeezing that into one column is what makes them look pinched, so a glyph
 * may overhang into the next column when that column holds nothing.
 *
 * This never changes how many columns a cell occupies. Ghostty decided that
 * from the remote PTY's own width accounting, and the cursor, the selection,
 * and mouse reporting are all addressed in those columns. Only the ink is
 * allowed past the edge.
 *
 * Separate from the renderer because it is a decision about the frame, not
 * about the Canvas, and it is the part worth testing.
 */
object CellSpill {

    /**
     * Extra columns of drawing room for the cell at (x, y), as a count.
     *
     * Rightward only: these glyphs sit on the baseline origin and grow to the
     * right, so the overhang lands in the following column. A cell the
     * terminal already made wide is left alone, and so is one whose next
     * column holds something, because painting over it would lose what it
     * holds.
     */
    fun columns(f: TerminalFrame, x: Int, y: Int, i: Int): Float {
        if (f.spanCells(i) > 1) return 0f
        if (!isFree(f, x + 1, y)) return 0f
        return 1f
    }

    /**
     * True when (x, y) is on the grid and holds nothing a spill would cover.
     *
     * A cell with its own background or a selection is not free even with no
     * text: painting over it would show ink where the terminal asked for a
     * flat colour.
     */
    fun isFree(f: TerminalFrame, x: Int, y: Int): Boolean {
        if (x < 0 || x >= f.cols) return false
        if (y < 0 || y >= f.rows) return false
        val j = f.indexOf(x, y)
        if (f.textLengthAt(j) > 0) return false
        if (f.isSpacer(j)) return false
        if (f.bgAt(j) != f.defaultBg) return false
        if (f.hasFlag(j, CellFlags.SELECTED)) return false
        if (f.hasFlag(j, CellFlags.INVERSE)) return false
        return true
    }
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
