package com.remotly.app.terminal

/**
 * Cell attributes as produced by [RemotlyTerminal.nativeGetFrame].
 *
 * Occupancy (`wide`) is Ghostty's decision; the renderer only rasterizes into
 * the box it names.
 */
object CellFlags {
  const val BOLD = 1 shl 0
  const val ITALIC = 1 shl 1
  const val UNDERLINE = 1 shl 2
  const val INVERSE = 1 shl 3
  const val FAINT = 1 shl 4
  const val STRIKETHROUGH = 1 shl 5
  const val SELECTED = 1 shl 6

  const val WIDE_NARROW = 0
  const val WIDE_WIDE = 1
  const val WIDE_SPACER_TAIL = 2
  const val WIDE_WRAP_SPACER = 3
}

/**
 * A terminal frame held in parallel arrays and reused across draws.
 *
 * A frame is rebuilt on every repaint. Materializing it as one object per cell
 * cost thousands of allocations per frame, which on a busy TUI is GC pressure
 * felt as dropped frames. The arrays here are grown when the grid grows and
 * never replaced otherwise, and cell text stays in a shared [chars] buffer so
 * the renderer can draw a cluster without building a String for it.
 */
class TerminalFrame {

  var cols = 0
    private set
  var rows = 0
    private set
  var cursorX = 0
    private set
  var cursorY = 0
    private set
  var cursorVisible = false
    private set
  var defaultBg = 0
    private set
  var defaultFg = 0
    private set

  /** Decoded cell text, addressed by [textOffsetAt] and [textLengthAt]. */
  var chars = CharArray(INITIAL_CHARS)
    private set

  private var wide = ByteArray(INITIAL_CELLS)
  private var flags = ByteArray(INITIAL_CELLS)
  private var fg = IntArray(INITIAL_CELLS)
  private var bg = IntArray(INITIAL_CELLS)
  private var textOffset = IntArray(INITIAL_CELLS)
  private var textLength = IntArray(INITIAL_CELLS)

  /** Bytes copied out of the native buffer, reused between frames. */
  private var scratch = ByteArray(INITIAL_CELLS * 10)

  fun indexOf(x: Int, y: Int): Int = y * cols + x

  /**
   * Marks the frame as holding nothing, keeping its buffers.
   *
   * A view handed another session must not report the previous one's screen,
   * to a reader or to a hit test, before the first draw refreshes it.
   */
  fun reset() {
    cols = 0
    rows = 0
    cursorVisible = false
    // Occupancy decides what a cell draws, so clearing it is what stops the
    // previous session's glyphs surviving in the arrays. The colours and text
    // are left: a cell that reports no width and no text is not drawn from,
    // and zeroing megabytes on every tab switch is a stall the user sees.
    java.util.Arrays.fill(textLength, 0)
    java.util.Arrays.fill(wide, 0)
    java.util.Arrays.fill(flags, 0)
  }

  fun wideAt(i: Int): Int = wide[i].toInt()
  fun flagsAt(i: Int): Int = flags[i].toInt() and 0xff
  fun hasFlag(i: Int, flag: Int): Boolean = (flags[i].toInt() and flag) != 0
  fun fgAt(i: Int): Int = fg[i]
  fun bgAt(i: Int): Int = bg[i]
  fun textOffsetAt(i: Int): Int = textOffset[i]
  fun textLengthAt(i: Int): Int = textLength[i]

  /** True for the trailing half of a wide cell, which carries no glyph. */
  fun isSpacer(i: Int): Boolean {
    val w = wide[i].toInt()
    return w == CellFlags.WIDE_SPACER_TAIL || w == CellFlags.WIDE_WRAP_SPACER
  }

  /** Columns a cell occupies: two for a wide lead cell, otherwise one. */
  fun spanCells(i: Int): Int = if (wide[i].toInt() == CellFlags.WIDE_WIDE) 2 else 1

  /** The cell's text as a String. For clipboard and tests, never for drawing. */
  fun textAt(i: Int): String = String(chars, textOffset[i], textLength[i])

  /**
   * Buffer that [RemotlyTerminal.nativeGetFrame] can serialize into.
   *
   * Direct, so native writes into it without a copy through the JVM heap, and
   * kept for the life of the frame so a draw allocates nothing.
   */
  var buffer: java.nio.ByteBuffer = java.nio.ByteBuffer.allocateDirect(INITIAL_CELLS * 10)
    private set

  /** Grows [buffer] to at least [required] bytes. */
  fun growBuffer(required: Int) {
    if (required <= buffer.capacity()) return
    buffer = java.nio.ByteBuffer.allocateDirect(required)
  }

  /**
   * Copies [len] bytes out of [buffer] and parses them into this frame.
   *
   * Returns false when the buffer is malformed or short, leaving the previous
   * frame intact so a bad read shows the last good screen rather than nothing.
   */
  fun parseFromBuffer(len: Int): Boolean {
    if (len <= 0) return false
    if (scratch.size < len) scratch = ByteArray(len)
    buffer.position(0)
    buffer.get(scratch, 0, len)
    return parse(scratch, len)
  }

  /**
   * Parses the little-endian frame layout the native serializer produces.
   *
   * Every read is bounds checked against [len]: the buffer crosses a native
   * boundary, and a truncated or malformed frame must fail rather than be
   * drawn from uninitialized memory.
   */
  fun parse(src: ByteArray, len: Int = src.size): Boolean {
    if (len < HEADER_SIZE || len > src.size) return false
    var o = 0

    val newCols = (src[0].toInt() and 0xff) or ((src[1].toInt() and 0xff) shl 8)
    val newRows = (src[2].toInt() and 0xff) or ((src[3].toInt() and 0xff) shl 8)
    if (newCols <= 0 || newRows <= 0) return false
    val cells = newCols * newRows
    if (cells > MAX_CELLS) return false

    val newCursorX = (src[4].toInt() and 0xff) or ((src[5].toInt() and 0xff) shl 8)
    val newCursorY = (src[6].toInt() and 0xff) or ((src[7].toInt() and 0xff) shl 8)
    val newCursorVisible = src[8].toInt() != 0
    // src[9] is padding.
    val newBg = rgb(src, 10)
    val newFg = rgb(src, 13)
    o = HEADER_SIZE

    ensureCells(cells)
    // Cells are written in place, so a parse that gives up halfway leaves the
    // start of this frame in front of the tail of the last one. The grid is
    // marked empty for the duration: a caller that draws after a failure then
    // shows nothing rather than two screens spliced together.
    val previousCols = cols
    val previousRows = rows
    cols = 0
    rows = 0
    var charPos = 0
    for (i in 0 until cells) {
      if (o + 9 > len) {
        // Nothing has been overwritten only while i is zero. Past that the
        // arrays hold a mixture of both frames and must not be drawn.
        restoreDimensions(i, previousCols, previousRows)
        return false
      }
      val w = src[o].toInt() and 0xff
      val fl = src[o + 1].toInt() and 0xff
      val cellFg = rgb(src, o + 2)
      val cellBg = rgb(src, o + 5)
      val textLen = src[o + 8].toInt() and 0xff
      o += 9
      if (o + textLen > len) {
        restoreDimensions(i, previousCols, previousRows)
        return false
      }

      // A decoded cluster is never longer in chars than it is in bytes.
      if (chars.size < charPos + textLen) {
        chars = chars.copyOf(maxOf(chars.size * 2, charPos + textLen))
      }
      val charLen = decodeUtf8(src, o, textLen, chars, charPos)
      o += textLen

      wide[i] = w.toByte()
      flags[i] = fl.toByte()
      fg[i] = cellFg
      bg[i] = cellBg
      textOffset[i] = charPos
      textLength[i] = charLen
      charPos += charLen
    }

    cols = newCols
    rows = newRows
    cursorX = newCursorX
    cursorY = newCursorY
    cursorVisible = newCursorVisible
    defaultBg = newBg
    defaultFg = newFg
    return true
  }

  /**
   * Puts back the grid a failed parse was measured against.
   *
   * Only when no cell was overwritten, which is the case for a failure on the
   * very first one. Past that the arrays hold the head of this frame over the
   * tail of the last, so the dimensions stay zero and nothing is drawn from
   * them.
   */
  private fun restoreDimensions(cellsWritten: Int, previousCols: Int, previousRows: Int) {
    if (cellsWritten > 0) return
    if (previousCols <= 0 || previousRows <= 0) return
    cols = previousCols
    rows = previousRows
  }

  private fun ensureCells(n: Int) {
    if (wide.size >= n) return
    val size = maxOf(n, wide.size * 2)
    wide = ByteArray(size)
    flags = ByteArray(size)
    fg = IntArray(size)
    bg = IntArray(size)
    textOffset = IntArray(size)
    textLength = IntArray(size)
  }

  private companion object {
    const val HEADER_SIZE = 16
    const val INITIAL_CELLS = 80 * 24
    const val INITIAL_CHARS = INITIAL_CELLS * 2

    // Matches the clamp in TerminalView and the native serializer.
    const val MAX_CELLS = 512 * 512
    const val ALPHA_OPAQUE = 0xff shl 24
    const val REPLACEMENT = '\uFFFD'

    // The wire carries RGB only. A zero alpha would make every cell invisible
    // once the frame reaches the Canvas.
    fun rgb(src: ByteArray, at: Int): Int =
      ALPHA_OPAQUE or
        ((src[at].toInt() and 0xff) shl 16) or
        ((src[at + 1].toInt() and 0xff) shl 8) or
        (src[at + 2].toInt() and 0xff)

    /**
     * Decodes UTF-8 into [out], returning the number of chars written.
     *
     * A malformed byte becomes one replacement char and decoding continues at
     * the next byte, matching what the platform decoder does. Bytes reaching
     * here come from a terminal that has already validated its own input, so
     * this is a boundary check rather than an expected path.
     */
    fun decodeUtf8(src: ByteArray, start: Int, len: Int, out: CharArray, outStart: Int): Int {
      var i = start
      val end = start + len
      var o = outStart
      while (i < end) {
        val b0 = src[i].toInt() and 0xff
        when {
          b0 < 0x80 -> {
            out[o++] = b0.toChar()
            i++
          }
          b0 in 0xC2..0xDF && i + 1 < end && isCont(src[i + 1]) -> {
            out[o++] = (((b0 and 0x1f) shl 6) or cont(src[i + 1])).toChar()
            i += 2
          }
          b0 in 0xE0..0xEF && i + 2 < end && isCont(src[i + 1]) && isCont(src[i + 2]) -> {
            val cp = ((b0 and 0x0f) shl 12) or (cont(src[i + 1]) shl 6) or cont(src[i + 2])
            // Overlong forms and lone surrogates are not text.
            out[o++] = if (cp < 0x800 || cp in 0xD800..0xDFFF) REPLACEMENT else cp.toChar()
            i += 3
          }
          b0 in 0xF0..0xF4 && i + 3 < end && isCont(src[i + 1]) &&
            isCont(src[i + 2]) && isCont(src[i + 3]) -> {
            val cp = ((b0 and 0x07) shl 18) or (cont(src[i + 1]) shl 12) or
              (cont(src[i + 2]) shl 6) or cont(src[i + 3])
            if (cp in 0x10000..0x10FFFF) {
              val v = cp - 0x10000
              out[o++] = (0xD800 + (v shr 10)).toChar()
              out[o++] = (0xDC00 + (v and 0x3ff)).toChar()
            } else {
              out[o++] = REPLACEMENT
            }
            i += 4
          }
          else -> {
            out[o++] = REPLACEMENT
            i++
          }
        }
      }
      return o - outStart
    }

    fun isCont(b: Byte): Boolean = (b.toInt() and 0xC0) == 0x80

    fun cont(b: Byte): Int = b.toInt() and 0x3f
  }
}
