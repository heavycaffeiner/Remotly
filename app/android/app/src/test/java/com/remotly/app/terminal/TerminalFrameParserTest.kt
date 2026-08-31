package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalFrameParserTest {

  // Builds the little-endian buffer in the exact layout the native
  // nativeGetFrame serializer produces.
  private class FrameBuilder {
    val bytes = java.io.ByteArrayOutputStream()

    fun header(
      cols: Int,
      rows: Int,
      cursorX: Int = 0,
      cursorY: Int = 0,
      cursorVisible: Boolean = true,
      defaultBg: Int = 0x101010,
      defaultFg: Int = 0xE0E0E0,
    ) {
      fun u16(v: Int) {
        bytes.write(v and 0xff)
        bytes.write((v shr 8) and 0xff)
      }
      fun rgb(v: Int) {
        bytes.write((v shr 16) and 0xff)
        bytes.write((v shr 8) and 0xff)
        bytes.write(v and 0xff)
      }
      u16(cols)
      u16(rows)
      u16(cursorX)
      u16(cursorY)
      bytes.write(if (cursorVisible) 1 else 0)
      bytes.write(0)
      rgb(defaultBg)
      rgb(defaultFg)
    }

    fun cell(
      wide: Int = CellFlags.WIDE_NARROW,
      flags: Int = 0,
      fg: Int = 0xE0E0E0,
      bg: Int = 0x101010,
      text: String = "",
    ) {
      bytes.write(wide)
      bytes.write(flags)
      bytes.write((fg shr 16) and 0xff)
      bytes.write((fg shr 8) and 0xff)
      bytes.write(fg and 0xff)
      bytes.write((bg shr 16) and 0xff)
      bytes.write((bg shr 8) and 0xff)
      bytes.write(bg and 0xff)
      val t = text.toByteArray(Charsets.UTF_8)
      bytes.write(t.size)
      bytes.write(t)
    }

    fun cellWithRawText(raw: ByteArray) {
      bytes.write(CellFlags.WIDE_NARROW)
      bytes.write(0)
      for (i in 0 until 3) bytes.write((0xE0 shr (8 * (2 - i))) and 0xff)
      for (i in 0 until 3) bytes.write((0x10 shr (8 * (2 - i))) and 0xff)
      bytes.write(raw.size)
      bytes.write(raw)
    }

    fun toByteArray(): ByteArray = bytes.toByteArray()
  }

  private fun parse(buf: ByteArray): TerminalFrame? {
    val f = TerminalFrame()
    return if (f.parse(buf)) f else null
  }

  @Test
  fun parsesValidFrame() {
    val b = FrameBuilder()
    b.header(cols = 2, rows = 2, cursorX = 1, cursorY = 1)
    b.cell(text = "a")
    b.cell(wide = CellFlags.WIDE_WIDE, text = "한")
    b.cell(text = "b")
    b.cell(wide = CellFlags.WIDE_SPACER_TAIL)
    val f = parse(b.toByteArray())!!

    assertEquals(2, f.cols)
    assertEquals(2, f.rows)
    assertEquals(1, f.cursorX)
    assertEquals(1, f.cursorY)
    assertTrue(f.cursorVisible)
    assertEquals(0xff101010.toInt(), f.defaultBg)
    assertEquals(0xffE0E0E0.toInt(), f.defaultFg)
    assertEquals("a", f.textAt(f.indexOf(0, 0)))
    assertEquals("한", f.textAt(f.indexOf(1, 0)))
    assertEquals(CellFlags.WIDE_WIDE, f.wideAt(f.indexOf(1, 0)))
    assertEquals(CellFlags.WIDE_SPACER_TAIL, f.wideAt(f.indexOf(1, 1)))
    assertEquals("b", f.textAt(f.indexOf(0, 1)))
  }

  @Test
  fun parsesStyleFlags() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cell(
      flags = CellFlags.BOLD or CellFlags.ITALIC or CellFlags.UNDERLINE or
        CellFlags.INVERSE or CellFlags.FAINT or CellFlags.STRIKETHROUGH or
        CellFlags.SELECTED,
    )
    val f = parse(b.toByteArray())!!

    assertTrue(f.hasFlag(0, CellFlags.BOLD))
    assertTrue(f.hasFlag(0, CellFlags.ITALIC))
    assertTrue(f.hasFlag(0, CellFlags.UNDERLINE))
    assertTrue(f.hasFlag(0, CellFlags.INVERSE))
    assertTrue(f.hasFlag(0, CellFlags.FAINT))
    assertTrue(f.hasFlag(0, CellFlags.STRIKETHROUGH))
    assertTrue(f.hasFlag(0, CellFlags.SELECTED))
  }

  @Test
  fun parsesClearStyleFlags() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cell()
    val f = parse(b.toByteArray())!!

    assertFalse(f.hasFlag(0, CellFlags.BOLD))
    assertFalse(f.hasFlag(0, CellFlags.ITALIC))
    assertFalse(f.hasFlag(0, CellFlags.UNDERLINE))
    assertFalse(f.hasFlag(0, CellFlags.INVERSE))
    assertFalse(f.hasFlag(0, CellFlags.FAINT))
    assertFalse(f.hasFlag(0, CellFlags.STRIKETHROUGH))
    assertFalse(f.hasFlag(0, CellFlags.SELECTED))
  }

  @Test
  fun cursorCanBeInvisible() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1, cursorVisible = false)
    b.cell()
    assertFalse(parse(b.toByteArray())!!.cursorVisible)
  }

  @Test
  fun invalidUtf8BecomesReplacementCharacter() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cellWithRawText(byteArrayOf(0xff.toByte(), 0xfe.toByte()))
    assertEquals("\uFFFD\uFFFD", parse(b.toByteArray())!!.textAt(0))
  }

  @Test
  fun overlongAndSurrogateEncodingsAreRejected() {
    // A decoder that accepts these hands the Canvas a code unit that is not
    // text. Both come back as the replacement character.
    val overlong = byteArrayOf(0xE0.toByte(), 0x80.toByte(), 0xAF.toByte())
    val surrogate = byteArrayOf(0xED.toByte(), 0xA0.toByte(), 0x80.toByte())
    for (raw in listOf(overlong, surrogate)) {
      val b = FrameBuilder()
      b.header(cols = 1, rows = 1)
      b.cellWithRawText(raw)
      assertEquals("\uFFFD", parse(b.toByteArray())!!.textAt(0))
    }
  }

  @Test
  fun astralCharactersDecodeToASurrogatePair() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cell(wide = CellFlags.WIDE_WIDE, text = "\uD83D\uDE00")
    val f = parse(b.toByteArray())!!

    assertEquals("\uD83D\uDE00", f.textAt(0))
    assertEquals(2, f.textLengthAt(0))
  }

  @Test
  fun shortBufferIsRejected() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    val buf = b.toByteArray()
    for (len in 0 until buf.size) {
      assertFalse(TerminalFrame().parse(buf.copyOfRange(0, len)))
    }
  }

  @Test
  fun zeroColsOrRowsIsRejected() {
    val b = FrameBuilder()
    b.header(cols = 0, rows = 1)
    assertFalse(TerminalFrame().parse(b.toByteArray()))

    val c = FrameBuilder()
    c.header(cols = 1, rows = 0)
    assertFalse(TerminalFrame().parse(c.toByteArray()))
  }

  @Test
  fun truncatedCellRecordIsRejected() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cell(text = "x")
    val buf = b.toByteArray()
    // Chop the last 4 bytes of the single cell record.
    assertFalse(TerminalFrame().parse(buf.copyOfRange(0, buf.size - 4)))
  }

  @Test
  fun truncatedCellTextIsRejected() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cell(text = "xy")
    val buf = b.toByteArray()
    // Declare 2 text bytes but provide only 1.
    assertFalse(TerminalFrame().parse(buf.copyOfRange(0, buf.size - 1)))
  }

  @Test
  fun aRejectedFrameLeavesTheLastGoodOneIntact() {
    // The renderer keeps drawing the frame it has. Clearing it on a bad read
    // would blank the screen on a single malformed buffer.
    val good = FrameBuilder()
    good.header(cols = 1, rows = 1)
    good.cell(text = "a")
    val f = TerminalFrame()
    assertTrue(f.parse(good.toByteArray()))

    val bad = FrameBuilder()
    bad.header(cols = 1, rows = 1)
    bad.cell(text = "z")
    val truncated = bad.toByteArray().let { it.copyOfRange(0, it.size - 1) }
    assertFalse(f.parse(truncated))

    assertEquals(1, f.cols)
    assertEquals("a", f.textAt(0))
  }

  @Test
  fun trailingBytesAreIgnored() {
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1)
    b.cell(text = "a")
    val withTrailing = b.toByteArray() + byteArrayOf(0, 1, 2, 3)
    assertEquals("a", parse(withTrailing)!!.textAt(0))
  }

  @Test
  fun colorsAreOpaque() {
    // The wire carries RGB only. A zero alpha would make every cell invisible
    // once the frame reaches the Canvas.
    val b = FrameBuilder()
    b.header(cols = 1, rows = 1, defaultBg = 0x000000, defaultFg = 0xFFFFFF)
    b.cell(fg = 0x123456, bg = 0x654321, text = "a")
    val f = parse(b.toByteArray())!!

    assertEquals(0xff, (f.defaultBg ushr 24) and 0xff)
    assertEquals(0xff, (f.defaultFg ushr 24) and 0xff)
    assertEquals(0xff123456.toInt(), f.fgAt(0))
    assertEquals(0xff654321.toInt(), f.bgAt(0))
  }

  @Test
  fun parsesLargeFrame() {
    val cols = 80
    val rows = 24
    val b = FrameBuilder()
    b.header(cols = cols, rows = rows)
    repeat(cols * rows) { i -> b.cell(text = "a" + (i % 26)) }
    val f = parse(b.toByteArray())!!

    assertEquals("a0", f.textAt(f.indexOf(0, 0)))
    assertEquals("a" + ((cols * rows - 1) % 26), f.textAt(f.indexOf(cols - 1, rows - 1)))
  }

  @Test
  fun aFrameIsReusedAcrossParses() {
    // The renderer keeps one frame for the life of the view, so parsing into
    // one that already holds a larger grid must not leak the old contents.
    val f = TerminalFrame()
    val big = FrameBuilder()
    big.header(cols = 4, rows = 1)
    repeat(4) { big.cell(text = "x") }
    assertTrue(f.parse(big.toByteArray()))

    val small = FrameBuilder()
    small.header(cols = 2, rows = 1)
    small.cell(text = "a")
    small.cell(text = "b")
    assertTrue(f.parse(small.toByteArray()))

    assertEquals(2, f.cols)
    assertEquals("a", f.textAt(0))
    assertEquals("b", f.textAt(1))
  }

  @Test
  fun aGrowingGridIsAccommodated() {
    val f = TerminalFrame()
    val small = FrameBuilder()
    small.header(cols = 1, rows = 1)
    small.cell(text = "a")
    assertTrue(f.parse(small.toByteArray()))

    val cols = 120
    val rows = 40
    val big = FrameBuilder()
    big.header(cols = cols, rows = rows)
    repeat(cols * rows) { big.cell(text = "z") }
    assertTrue(f.parse(big.toByteArray()))

    assertEquals(cols, f.cols)
    assertEquals(rows, f.rows)
    assertEquals("z", f.textAt(f.indexOf(cols - 1, rows - 1)))
  }

  @Test
  fun spacerCellsAreRecognized() {
    val b = FrameBuilder()
    b.header(cols = 4, rows = 1)
    b.cell(wide = CellFlags.WIDE_WIDE, text = "한")
    b.cell(wide = CellFlags.WIDE_SPACER_TAIL)
    b.cell(wide = CellFlags.WIDE_WRAP_SPACER)
    b.cell(text = "a")
    val f = parse(b.toByteArray())!!

    assertFalse(f.isSpacer(0))
    assertTrue(f.isSpacer(1))
    assertTrue(f.isSpacer(2))
    assertFalse(f.isSpacer(3))
    assertEquals(2, f.spanCells(0))
    assertEquals(1, f.spanCells(3))
  }

  /**
   * A parse that gives up partway must not leave half of each frame.
   *
   * Cells are written in place, so a truncation after the first one left the
   * head of the new screen sitting in front of the tail of the old. That is
   * the two screens appearing mixed together after a tab switch.
   */
  @Test
  fun aFrameTruncatedPartwayIsNotDrawable() {
    val good = FrameBuilder()
    good.header(cols = 4, rows = 1)
    repeat(4) { good.cell(text = "a") }
    val f = TerminalFrame()
    assertTrue(f.parse(good.toByteArray()))

    val bad = FrameBuilder()
    bad.header(cols = 4, rows = 1)
    bad.cell(text = "z")
    bad.cell(text = "z")
    bad.cell(text = "z")
    bad.cell(text = "z")
    val whole = bad.toByteArray()
    // Cut inside the third cell, so the first two have already been written.
    assertFalse(f.parse(whole.copyOfRange(0, whole.size - 12)))

    // Reported as holding nothing rather than as a four-cell grid, so the
    // renderer draws neither screen instead of both.
    assertEquals(0, f.cols)
    assertEquals(0, f.rows)
  }

  /** A failure before any cell was touched keeps the frame that was there. */
  @Test
  fun aFrameTruncatedAtTheFirstCellKeepsTheLastGoodOne() {
    val good = FrameBuilder()
    good.header(cols = 2, rows = 1)
    good.cell(text = "a")
    good.cell(text = "b")
    val f = TerminalFrame()
    assertTrue(f.parse(good.toByteArray()))

    val bad = FrameBuilder()
    bad.header(cols = 2, rows = 1)
    val headerOnly = bad.toByteArray()
    assertFalse(f.parse(headerOnly))

    assertEquals(2, f.cols)
    assertEquals("a", f.textAt(0))
    assertEquals("b", f.textAt(1))
  }

  /**
   * Emptying a frame has to drop the glyphs, not just the dimensions.
   *
   * The arrays are reused across sessions, so a reset that left the text in
   * place let the previous session's screen reappear the moment a grid was
   * reported again.
   */
  @Test
  fun resettingDropsTheCellsAsWellAsTheGrid() {
    val b = FrameBuilder()
    b.header(cols = 2, rows = 1)
    b.cell(text = "a")
    b.cell(wide = CellFlags.WIDE_WIDE, flags = CellFlags.BOLD, text = "\uD55C")
    val f = TerminalFrame()
    assertTrue(f.parse(b.toByteArray()))

    f.reset()

    assertEquals(0, f.cols)
    assertEquals(0, f.textLengthAt(0))
    assertEquals(0, f.textLengthAt(1))
    assertEquals(CellFlags.WIDE_NARROW, f.wideAt(1))
    assertEquals(0, f.flagsAt(1))
  }
}
