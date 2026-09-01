package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// A Nerd Font icon has more ink than its one-cell advance. It may overhang
// into the column to its right, but only when doing so cannot paint over
// something. The rule decides between a full-size icon and a compressed one,
// so getting it wrong either pinches every icon or corrupts the next cell.
//
// Overhang never changes how many columns a cell occupies: that comes from
// ghostty, and the cursor and selection are addressed in those columns.
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
    fun aGlyphWithAFreeCellToItsRightOverhangsIntoIt() {
        val f = frameOf(Cell(), Cell("\uE0B0"), Cell())

        assertEquals(1f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun anOccupiedCellToTheRightStopsTheOverhang() {
        val f = frameOf(Cell(), Cell("\uE0B0"), Cell("x"))

        assertEquals(0f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun anOccupiedCellToTheLeftDoesNotStopTheOverhang() {
        // These glyphs grow rightward from the origin, so what sits to the
        // left is irrelevant. Requiring it to be free would refuse the most
        // ordinary case there is: an icon drawn straight after a prompt.
        val f = frameOf(Cell("$"), Cell("\uE0B0"), Cell())

        assertEquals(1f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
    }

    @Test
    fun theLastColumnHasNothingToOverhangInto() {
        val f = frameOf(Cell(), Cell(), Cell("\uE0B0"))

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
    fun aColouredOrSelectedCellIsStillFree() {
        // Backgrounds are painted for the whole grid before any glyph, so a
        // coloured cell no longer erases ink that crossed into it. This is the
        // powerline case: every cell there carries its own background, and
        // treating those as occupied disabled the overhang exactly where these
        // icons are used.
        val coloured = frameOf(Cell(), Cell("\uE0B0"), Cell(bg = 0x445566))
        val selected = frameOf(Cell(), Cell("\uE0B0"), Cell(flags = CellFlags.SELECTED))
        val inverse = frameOf(Cell(), Cell("\uE0B0"), Cell(flags = CellFlags.INVERSE))

        assertTrue(CellSpill.isFree(coloured, 2, 0))
        assertTrue(CellSpill.isFree(selected, 2, 0))
        assertTrue(CellSpill.isFree(inverse, 2, 0))
        assertEquals(1f, CellSpill.columns(coloured, 1, 0, coloured.indexOf(1, 0)), 0.001f)
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

    @Test
    fun overhangNeverChangesHowManyColumnsACellOccupies() {
        // The column count comes from ghostty, which got it from the remote
        // PTY's own width accounting, and the cursor, the selection, and mouse
        // reporting are all addressed in those columns. Granting drawing room
        // must not move that number, or the rendered grid and the terminal's
        // idea of it drift apart.
        val f = frameOf(
            Cell("$"),
            Cell("\uE0B0"),
            Cell(),
            Cell("\uD55C", wide = CellFlags.WIDE_WIDE),
        )

        assertEquals(1, f.spanCells(f.indexOf(1, 0)))
        assertEquals(1f, CellSpill.columns(f, 1, 0, f.indexOf(1, 0)), 0.001f)
        // Still one column after the overhang was granted.
        assertEquals(1, f.spanCells(f.indexOf(1, 0)))
        // A cell ghostty made wide keeps its two columns and gets nothing.
        assertEquals(2, f.spanCells(f.indexOf(3, 0)))
        assertEquals(0f, CellSpill.columns(f, 3, 0, f.indexOf(3, 0)), 0.001f)
    }
}
