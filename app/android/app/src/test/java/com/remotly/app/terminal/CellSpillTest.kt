package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A Nerd Font icon has more ink than its one-cell advance. It may borrow the
// columns beside it, but only when borrowing cannot paint over something. The
// rule decides between a full-size icon and a compressed one, so getting it
// wrong either pinches every icon or corrupts the cell next to it.
class CellSpillTest {

    private val bg = 0x101010
    private val fg = 0xE0E0E0

    // Builds a one-row frame through the real parser, so the rule is tested
    // against the same layout the native serializer produces.
    private fun frameOf(vararg cells: Cell): TerminalFrame {
        val out = java.io.ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write(v and 0xff)
            out.write((v shr 8) and 0xff)
        }
        fun rgb(v: Int) {
            out.write((v shr 16) and 0xff)
            out.write((v shr 8) and 0xff)
            out.write(v and 0xff)
        }
        u16(cells.size)
        u16(1)
        u16(0)
        u16(0)
        out.write(1)
        out.write(0)
        rgb(bg)
        rgb(fg)
        for (c in cells) {
            out.write(c.wide)
            out.write(c.flags)
            rgb(fg)
            rgb(c.bg)
            val t = c.text.toByteArray(Charsets.UTF_8)
            out.write(t.size)
            out.write(t)
        }
        val frame = TerminalFrame()
        val bytes = out.toByteArray()
        assertTrue("frame parses", frame.parse(bytes, bytes.size))
        return frame
    }

    private data class Cell(
        val text: String = "",
        val wide: Int = CellFlags.WIDE_NARROW,
        val flags: Int = 0,
        val bg: Int = 0x101010,
    )

    @Test
    fun aGlyphBetweenTwoBlanksBorrowsBothColumns() {
        val f = frameOf(Cell(), Cell("\uE0B0"), Cell())

        assertEquals(2f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun anOccupiedNeighbourStopsTheSpill() {
        // Either side is enough to block it: the ink grows from both edges, so
        // there is no way to spill into one side only.
        val right = frameOf(Cell(), Cell("\uE0B0"), Cell("x"))
        val left = frameOf(Cell("x"), Cell("\uE0B0"), Cell())

        assertEquals(0f, CellSpill.columns(right, 1, 0, right.indexOf(1, 0)), 0.001f)
        assertEquals(0f, CellSpill.columns(left, 1, 0, left.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun theRowEdgeIsNotFree() {
        // A glyph in the first or last column has no neighbour on one side, so
        // it must not paint outside the grid.
        val f = frameOf(Cell("\uE0B0"), Cell(), Cell("\uE0B0"))

        assertEquals(0f, CellSpill.columns(f, 0, 0, f.indexOf(0, 0)), 0.001f)
        assertEquals(0f, CellSpill.columns(f, 2, 0, f.indexOf(2, 0)), 0.001f)
    }

    @Test
    fun aCellTheTerminalMadeWideIsLeftAlone() {
        // Ghostty already gave this two columns. Widening it further would
        // overrun the box the terminal decided on.
        val f = frameOf(Cell(), Cell("\uD55C", wide = CellFlags.WIDE_WIDE), Cell())

        assertEquals(0f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun aSpacerNeighbourIsNotFree() {
        // The trailing half of a wide cell carries no text but is not empty:
        // the lead cell paints across it.
        val f = frameOf(
            Cell(),
            Cell("\uE0B0"),
            Cell(wide = CellFlags.WIDE_SPACER_TAIL),
        )

        assertFalse(CellSpill.isFree(f, 2, 0))
        assertEquals(0f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun aColouredOrSelectedNeighbourIsNotFree() {
        // Both paint the cell even with no text in it, so ink over them would
        // show through where the terminal asked for a flat colour.
        val coloured = frameOf(Cell(), Cell("\uE0B0"), Cell(bg = 0x445566))
        val selected = frameOf(Cell(), Cell("\uE0B0"), Cell(flags = CellFlags.SELECTED))
        val inverse = frameOf(Cell(), Cell("\uE0B0"), Cell(flags = CellFlags.INVERSE))

        assertFalse(CellSpill.isFree(coloured, 2, 0))
        assertFalse(CellSpill.isFree(selected, 2, 0))
        assertFalse(CellSpill.isFree(inverse, 2, 0))
    }

    @Test
    fun aBlankCellInTheDefaultBackgroundIsFree() {
        val f = frameOf(Cell(), Cell("\uE0B0"), Cell())

        assertTrue(CellSpill.isFree(f, 0, 0))
        assertTrue(CellSpill.isFree(f, 2, 0))
    }

    @Test
    fun offGridCoordinatesAreNotFree() {
        val f = frameOf(Cell(), Cell("\uE0B0"), Cell())

        assertFalse(CellSpill.isFree(f, -1, 0))
        assertFalse(CellSpill.isFree(f, 3, 0))
        assertFalse(CellSpill.isFree(f, 1, 1))
    }
}
