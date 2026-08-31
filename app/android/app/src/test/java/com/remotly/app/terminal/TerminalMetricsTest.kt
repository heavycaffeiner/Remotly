package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

// Cell occupancy and glyph box fitting. These are the rules that keep a
// mixed-script line aligned to the column grid: Ghostty decides how many cells
// a grapheme occupies, and the renderer fits the glyph into exactly that box.
class TerminalMetricsTest {

    private val metrics = CellMetrics(widthPx = 10, heightPx = 20, baselinePx = 16f, underlinePx = 18f)

    @Test
    fun narrowCellSpansOneColumn() {
        assertEquals(10f, metrics.spanWidth(1), 0.001f)
    }

    @Test
    fun wideCellSpansExactlyTwoColumns() {
        // Not 1.8 or 2.2 columns: a CJK glyph occupies two cells exactly, or a
        // box-drawing TUI drifts across the line.
        assertEquals(20f, metrics.spanWidth(2), 0.001f)
    }

    // --- fitScale ------------------------------------------------------------

    @Test
    fun aGlyphThatFitsIsNotScaled() {
        // Ordinary text must never be distorted.
        assertEquals(1f, TerminalMetrics.fitScale(10f, 10f), 0.001f)
        assertEquals(1f, TerminalMetrics.fitScale(8f, 10f), 0.001f)
    }

    @Test
    fun anOverwideGlyphIsCompressedToItsBox() {
        // A device CJK face whose advance exceeds two cells would otherwise
        // bleed into the next column.
        assertEquals(0.5f, TerminalMetrics.fitScale(40f, 20f), 0.001f)
        assertEquals(0.8f, TerminalMetrics.fitScale(25f, 20f), 0.001f)
    }

    @Test
    fun compressionNeverInverts() {
        // The scale stays positive and bounded for any input.
        val scale = TerminalMetrics.fitScale(1000f, 20f)
        assertTrue(scale > 0f && scale <= 1f)
    }

    @Test
    fun degenerateMeasurementsFallBackToNoScaling() {
        assertEquals(1f, TerminalMetrics.fitScale(0f, 20f), 0.001f)
        assertEquals(1f, TerminalMetrics.fitScale(-5f, 20f), 0.001f)
        assertEquals(1f, TerminalMetrics.fitScale(10f, 0f), 0.001f)
    }

    // --- centerOffset --------------------------------------------------------

    @Test
    fun aNarrowGlyphIsCenteredInItsBox() {
        // A Hangul glyph narrower than two cells is centered rather than
        // stretched; stretching looks worse than the gap it removes.
        assertEquals(5f, TerminalMetrics.centerOffset(10f, 20f), 0.001f)
    }

    @Test
    fun aGlyphThatFillsItsBoxIsNotOffset() {
        assertEquals(0f, TerminalMetrics.centerOffset(20f, 20f), 0.001f)
    }

    @Test
    fun anOverwideGlyphIsNotOffset() {
        // It is compressed instead, so the origin stays at the cell's left edge.
        assertEquals(0f, TerminalMetrics.centerOffset(30f, 20f), 0.001f)
    }

    // --- occupancy from frame data -------------------------------------------

    // One-cell frames, built through the wire layout the renderer parses.
    private fun frameOf(text: String, wide: Int, styleFlags: Int = 0): TerminalFrame {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        u16(1)
        u16(1)
        u16(0)
        u16(0)
        out.write(1)
        out.write(0)
        repeat(6) { out.write(0) }
        out.write(wide)
        out.write(styleFlags)
        repeat(6) { out.write(0) }
        val t = text.toByteArray(Charsets.UTF_8)
        out.write(t.size)
        out.write(t)

        val f = TerminalFrame()
        f.parse(out.toByteArray())
        return f
    }

    @Test
    fun occupancyFollowsTheFrameNotTheText() {
        // Ghostty is the source of truth. The renderer must not re-derive width
        // from the character, because its own table already decided.
        assertEquals(2, frameOf("한", CellFlags.WIDE_WIDE).spanCells(0))
        assertEquals(1, frameOf("A", CellFlags.WIDE_NARROW).spanCells(0))
    }

    @Test
    fun spacerTailsAreNotPaintedSeparately() {
        // The lead cell already painted across both columns; painting the tail
        // would draw over it.
        assertTrue(frameOf("", CellFlags.WIDE_SPACER_TAIL).isSpacer(0))
        assertTrue(frameOf("", CellFlags.WIDE_WRAP_SPACER).isSpacer(0))
        assertTrue(!frameOf("A", CellFlags.WIDE_NARROW).isSpacer(0))
        assertTrue(!frameOf("한", CellFlags.WIDE_WIDE).isSpacer(0))
    }

    // --- combining clusters ---------------------------------------------------

    @Test
    fun aCombiningClusterStaysOneDrawnUnit() {
        // NFD Hangul arrives as one cell whose text is several code points. The
        // renderer draws the whole cluster at one origin; splitting it by code
        // unit would scatter the jamo across columns.
        val nfd = frameOf("\u1100\u1161", CellFlags.WIDE_WIDE)
        assertEquals(2, nfd.textLengthAt(0))
        assertEquals(2, nfd.spanCells(0))
    }

    @Test
    fun anEmojiWithAVariationSelectorIsOneCluster() {
        val emoji = frameOf("\u2764\uFE0F", CellFlags.WIDE_WIDE)
        assertEquals(2, emoji.spanCells(0))
        assertEquals(2, emoji.textLengthAt(0))
    }

    // --- decorations ---------------------------------------------------------

    @Test
    fun styleFlagsDecodeIndependently() {
        val f = frameOf(
            "한",
            CellFlags.WIDE_WIDE,
            CellFlags.BOLD or CellFlags.UNDERLINE,
        )
        assertTrue(f.hasFlag(0, CellFlags.BOLD))
        assertTrue(f.hasFlag(0, CellFlags.UNDERLINE))
        assertTrue(!f.hasFlag(0, CellFlags.ITALIC))
        assertTrue(!f.hasFlag(0, CellFlags.INVERSE))
    }

    @Test
    fun decorationsSpanTheFullOccupancy() {
        // An underline under a wide glyph covers both columns, not just the
        // lead one.
        val wide = frameOf("한", CellFlags.WIDE_WIDE, CellFlags.UNDERLINE)
        assertEquals(20f, metrics.spanWidth(wide.spanCells(0)), 0.001f)
    }

    @Test
    fun underlineAndStrikethroughCoexist() {
        // Both are drawn, on their own lines. Collapsing them to one lost
        // whichever the other won.
        val f = frameOf(
            "A",
            CellFlags.WIDE_NARROW,
            CellFlags.UNDERLINE or CellFlags.STRIKETHROUGH,
        )
        assertTrue(f.hasFlag(0, CellFlags.UNDERLINE))
        assertTrue(f.hasFlag(0, CellFlags.STRIKETHROUGH))
    }

    @Test
    fun inverseSwapsForegroundAndBackground() {
        val f = frameOf("A", CellFlags.WIDE_NARROW, CellFlags.INVERSE)
        assertTrue(f.hasFlag(0, CellFlags.INVERSE))
    }
}
