package com.remotly.app.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rendering checks that need the real fonts, so they run on a device.
 *
 * The JVM tests cover the rules; these cover the thing the rules were written
 * for. A face that does not load, or an icon that ends up compressed anyway,
 * only shows up once Android has actually rasterized it.
 */
@RunWith(AndroidJUnit4::class)
class BundledFontRenderingTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val fonts = TerminalFontSet.get(context)

    private fun paintAt(size: Float, typeface: android.graphics.Typeface) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = size
            this.typeface = typeface
        }

    /** The painted width of [text], which is not its advance. */
    private fun inkWidth(paint: Paint, text: String): Int {
        val r = Rect()
        paint.getTextBounds(text, 0, text.length, r)
        return r.width()
    }

    @Test
    fun everyBundledFaceLoads() {
        // A face that failed to load silently falls back to the platform font,
        // which has no Nerd Font icons at all.
        assertTrue("bundled faces loaded", fonts.bundled)
        assertNotNull("symbol face", fonts.symbols)
        assertNotNull("CJK face", fonts.cjk)
    }

    @Test
    fun theTextFaceIsFixedAdvance() {
        // The cell grid comes from this advance. If the styles disagree the
        // grid drifts across a box-drawing TUI.
        for (typeface in listOf(fonts.regular, fonts.bold, fonts.italic, fonts.boldItalic)) {
            val paint = paintAt(48f, typeface)
            assertTrue(
                "face is fixed advance",
                TerminalMetrics.isFixedAdvance(paint),
            )
        }
    }

    @Test
    fun nerdIconsCarryMoreInkThanOneCell() {
        // This is the whole point of bundling the non-Mono build. The Mono
        // build pre-compresses every icon to exactly one cell, and if that is
        // what shipped, the renderer's two-cell path would never do anything.
        val symbols = fonts.symbols
        assertNotNull(symbols)
        val paint = paintAt(48f, symbols!!)
        val cell = paintAt(48f, fonts.regular).measureText("M")

        // One icon from each range a prompt actually draws.
        val icons = listOf("\uE0B0", "\uE5FA", "\uE702", "\uEA60", "\uF400")
        for (icon in icons) {
            val ink = inkWidth(paint, icon)
            assertTrue(
                "icon $icon has ink ($ink) wider than one cell ($cell)",
                ink > cell,
            )
        }
    }

    @Test
    fun nerdIconAdvanceStaysOneCell() {
        // The icons are wide in ink but must not be wide in advance, or they
        // would push the grid sideways.
        val symbols = fonts.symbols
        assertNotNull(symbols)
        val cell = paintAt(48f, fonts.regular).measureText("M")
        val paint = paintAt(48f, symbols!!)

        for (icon in listOf("\uE0B0", "\uE5FA", "\uF400")) {
            assertEquals(
                "icon $icon advance is one cell",
                cell,
                paint.measureText(icon),
                0.6f,
            )
        }
    }

    @Test
    fun theCjkFaceDrawsHangulAndHan() {
        // The text face has no CJK coverage at all, so a wrong route here is a
        // blank cell on screen.
        val cjk = fonts.cjk
        assertNotNull(cjk)
        val paint = paintAt(48f, cjk!!)

        for (text in listOf("한", "글", "漢", "字", "あ", "中")) {
            assertTrue("$text has coverage", paint.hasGlyph(text))
            assertTrue("$text paints ink", inkWidth(paint, text) > 0)
        }
    }

    @Test
    fun classifiedCellsResolveToAFaceThatCoversThem() {
        // The classifier picks the face; this proves the face it picks can
        // actually draw the character.
        val samples = mapOf(
            "A" to GlyphKind.TEXT,
            "\u2500" to GlyphKind.TEXT,
            "\uE0B0" to GlyphKind.SYMBOL,
            "\uF400" to GlyphKind.SYMBOL,
            "한" to GlyphKind.CJK,
            "漢" to GlyphKind.CJK,
            "あ" to GlyphKind.CJK,
        )
        for ((text, expected) in samples) {
            val kind = GlyphKind.of(text.codePointAt(0))
            assertEquals("kind of $text", expected, kind)
            val paint = paintAt(48f, fonts.typefaceFor(false, false, kind))
            assertTrue("$text is covered by its face", paint.hasGlyph(text))
        }
    }

    /**
     * An icon with a free column to its right is drawn at full size, and the
     * same icon with that column occupied is compressed to fit.
     *
     * Rendered and compared by the pixels actually painted, because that is
     * the behaviour being claimed: the icon stops being squeezed when there is
     * somewhere for its ink to go.
     */
    @Test
    fun anIconOverhangsIntoAFreeColumnAndIsCompressedWithoutOne() {
        val renderer = TerminalRenderer(context, density = 3f)
        renderer.fontSizePx = 48f
        val cellW = renderer.cellWidthPx
        val cellH = renderer.cellHeightPx

        // A character to the left in both cases, so the only difference is
        // the column to the right. That also pins the rule that what sits to
        // the left never matters.
        val roomy = renderFrame(renderer, listOf("W", "\uE5FA", ""), cellW, cellH)
        val crowded = renderFrame(renderer, listOf("W", "\uE5FA", "W"), cellW, cellH)

        // Measured from the icon's own column onward: in the roomy case its
        // ink is allowed to continue past that column.
        val roomyInk = paintedWidth(roomy, cellW, roomy.width)
        val crowdedInk = paintedWidth(crowded, cellW, cellW * 2)

        assertTrue(
            "icon with a free column ($roomyInk px) is wider than one without ($crowdedInk px)",
            roomyInk > crowdedInk,
        )
        // With room it must exceed a single cell: that is the fix.
        assertTrue(
            "icon with a free column ($roomyInk px) exceeds one cell ($cellW px)",
            roomyInk > cellW,
        )
        // Without room it must stay inside its own cell.
        assertTrue(
            "icon without room ($crowdedInk px) stays within one cell ($cellW px)",
            crowdedInk <= cellW,
        )
    }

    /**
     * An icon overhangs even when the cell it reaches into paints its own
     * background.
     *
     * This is the powerline prompt: every cell there carries a background, and
     * while backgrounds and glyphs were drawn in one pass the neighbour's rect
     * landed on top of the icon, so the overhang had to be refused for exactly
     * the prompts these icons are used in.
     */
    @Test
    fun anIconOverhangsIntoACellThatPaintsItsOwnBackground() {
        val renderer = TerminalRenderer(context, density = 3f)
        renderer.fontSizePx = 48f
        val cellW = renderer.cellWidthPx
        val cellH = renderer.cellHeightPx

        val plain = renderFrame(renderer, listOf("W", "\uE5FA", ""), cellW, cellH)
        val coloured = renderFrame(
            renderer,
            listOf("W", "\uE5FA", ""),
            cellW,
            cellH,
            bgOf = { x -> if (x == 2) 0x445566 else 0x101010 },
        )

        val plainInk = paintedWidth(plain, cellW, plain.width)
        // The coloured cell is a different colour from the frame background,
        // so "painted" there means anything that is not that colour: the
        // icon's own ink.
        val colouredInk = inkWidthOver(coloured, cellW, coloured.width, 0xFF445566.toInt())

        assertTrue(
            "icon overhangs past its own cell into a coloured cell ($colouredInk px > $cellW px)",
            colouredInk > cellW,
        )
        assertEquals(
            "a coloured neighbour does not change how far the icon draws",
            plainInk.toFloat(),
            colouredInk.toFloat(),
            2f,
        )
    }

    /**
     * Columns in [from, to) holding a pixel that is neither the frame
     * background nor [other].
     */
    private fun inkWidthOver(bitmap: Bitmap, from: Int, to: Int, other: Int): Int {
        val bg = bitmap.getPixel(0, bitmap.height - 1)
        var first = -1
        var last = -1
        for (x in from until to) {
            var painted = false
            for (y in 0 until bitmap.height) {
                val p = bitmap.getPixel(x, y)
                if (p != bg && p != other) {
                    painted = true
                    break
                }
            }
            if (painted) {
                if (first < 0) first = x
                last = x
            }
        }
        return if (first < 0) 0 else last - first + 1
    }

    /**
     * Inverse and selection swap a cell's colours, and both together swap back.
     *
     * Splitting the draw into a background pass and a glyph pass moved this
     * logic out of the single loop that used to hold it, so the result is
     * checked against the pixels rather than against the code.
     */
    @Test
    fun inverseAndSelectionSwapTheCellColours() {
        val renderer = TerminalRenderer(context, density = 3f)
        renderer.fontSizePx = 48f
        val cellW = renderer.cellWidthPx
        val cellH = renderer.cellHeightPx

        // A full block, so the cell is painted edge to edge and the sampled
        // pixel is the glyph's colour rather than a gap in it.
        val plain = cellPixels(renderer, "\u2588", 0, cellW, cellH)
        val inverse = cellPixels(renderer, "\u2588", CellFlags.INVERSE, cellW, cellH)
        val selected = cellPixels(renderer, "\u2588", CellFlags.SELECTED, cellW, cellH)
        val both = cellPixels(
            renderer, "\u2588", CellFlags.INVERSE or CellFlags.SELECTED, cellW, cellH,
        )

        assertTrue("inverse changes the cell", inverse != plain)
        assertTrue("selection changes the cell", selected != plain)
        // Applied in turn, so the pair swaps back and reads as ordinary text.
        assertEquals("inverse and selection together swap back", plain, both)
    }

    /** The colour at the centre of a single-cell frame holding [text]. */
    private fun cellPixels(
        renderer: TerminalRenderer,
        text: String,
        flags: Int,
        cellW: Int,
        cellH: Int,
    ): Int {
        val frame = TerminalFrame()
        val bytes = frameBytes(listOf(text), flagsOf = { flags })
        assertTrue("frame parses", frame.parse(bytes, bytes.size))
        val bitmap = Bitmap.createBitmap(cellW, cellH, Bitmap.Config.ARGB_8888)
        renderer.drawFrame(
            Canvas(bitmap), frame, TerminalView.CursorStyle.BAR, composing = true,
        )
        return bitmap.getPixel(cellW / 2, cellH / 2)
    }

    /** Draws a one-row frame of [texts] and returns the bitmap. */
    private fun renderFrame(
        renderer: TerminalRenderer,
        texts: List<String>,
        cellW: Int,
        cellH: Int,
        bgOf: (Int) -> Int = { 0x101010 },
    ): Bitmap {
        val frame = TerminalFrame()
        val bytes = frameBytes(texts, bgOf)
        assertTrue("frame parses", frame.parse(bytes, bytes.size))

        val bitmap = Bitmap.createBitmap(cellW * texts.size, cellH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        renderer.drawFrame(canvas, frame, TerminalView.CursorStyle.BAR, composing = true)
        return bitmap
    }

    /** Columns in [from, to) holding a pixel that is not the background. */
    private fun paintedWidth(bitmap: Bitmap, from: Int, to: Int): Int {
        val bg = bitmap.getPixel(0, 0)
        var first = -1
        var last = -1
        for (x in from until to) {
            var painted = false
            for (y in 0 until bitmap.height) {
                if (bitmap.getPixel(x, y) != bg) {
                    painted = true
                    break
                }
            }
            if (painted) {
                if (first < 0) first = x
                last = x
            }
        }
        return if (first < 0) 0 else last - first + 1
    }

    /** The little-endian frame layout the native serializer produces. */
    private fun frameBytes(
        texts: List<String>,
        bgOf: (Int) -> Int = { 0x101010 },
        flagsOf: (Int) -> Int = { 0 },
    ): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        // The frame default, which the canvas is cleared to. Kept distinct
        // from any per-cell colour so the two can be told apart in a bitmap.
        val defaultBg = 0x101010
        val fg = 0xE0E0E0
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        fun rgb(v: Int) {
            out.write((v shr 16) and 0xff)
            out.write((v shr 8) and 0xff)
            out.write(v and 0xff)
        }
        u16(texts.size)
        u16(1)
        u16(0)
        u16(0)
        // The cursor would paint a bar over the first cell.
        out.write(0)
        out.write(0)
        rgb(defaultBg)
        rgb(fg)
        for ((x, text) in texts.withIndex()) {
            out.write(CellFlags.WIDE_NARROW)
            out.write(flagsOf(x))
            rgb(fg)
            rgb(bgOf(x))
            val t = text.toByteArray(Charsets.UTF_8)
            out.write(t.size)
            out.write(t)
        }
        return out.toByteArray()
    }
}
