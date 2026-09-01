package com.remotly.app.terminal

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect

/**
 * Paints a terminal frame onto a Canvas.
 *
 * Holds the paints, the font faces, the cell metrics, and the measurement
 * cache: everything a draw needs and nothing about input, IME, or session
 * lifetime. The view owns the terminal handle and calls in with the frame it
 * has and the state that decorates it.
 */
class TerminalRenderer(context: Context, private val density: Float) {

  private val fonts = TerminalFontSet.get(context)

  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    typeface = fonts.regular
  }
  private val bgPaint = Paint()

  // A separate paint for glyphs that need horizontal compression, so the
  // shared paint's textScaleX is never left modified for the next cell.
  private val fitPaint = Paint(Paint.ANTI_ALIAS_FLAG)

  // Reused across draws: the handles are rebuilt on every frame a selection
  // is up.
  private val handlePath = Path()

  // Reused by the per-cell ink measurement, so measuring a symbol allocates
  // nothing on the draw path.
  private val inkBounds = Rect()

  /**
   * Advance widths for glyphs already drawn at the current size.
   *
   * A cell's width does not change between frames, but measuring is not free
   * and a full-screen repaint measures every occupied cell. Cleared whenever
   * the font size changes, which is the only thing that invalidates it.
   */
  private val glyphWidths = GlyphWidthCache()

  var metrics = CellMetrics(8, 16, 12f, 14f)
    private set

  var fontSizePx: Float = 0f
    set(value) {
      if (field == value) return
      field = value
      textPaint.textSize = value
      glyphWidths.clear()
      textPaint.typeface = fonts.regular
      metrics = TerminalMetrics.measure(textPaint)
    }

  val cellWidthPx: Int get() = metrics.widthPx
  val cellHeightPx: Int get() = metrics.heightPx

  /**
   * Stroke width for underline and strikethrough.
   *
   * A hairline is what a zero stroke width gives, which all but disappears on
   * a dense screen. Scaled with the font instead, so a decoration stays
   * proportional to the text it marks.
   */
  private val decorationWidthPx: Float
    get() = (fontSizePx * DECORATION_RATIO).coerceAtLeast(1f)

  private val cursorBarPx: Float get() = CURSOR_BAR_DP * density
  private val cursorUnderlinePx: Float get() = CURSOR_UNDERLINE_DP * density
  private val scrollbarWidthPx: Float get() = SCROLLBAR_WIDTH_DP * density
  private val scrollbarInsetPx: Float get() = SCROLLBAR_INSET_DP * density
  private val scrollbarMinThumbPx: Float get() = SCROLLBAR_MIN_THUMB_DP * density

  /** Scrollbar geometry from the terminal, as [total, offset, visible] rows. */
  data class ScrollbarState(val total: Long, val offset: Long, val visible: Long)

  /** Draws the cell grid and the cursor. */
  fun drawFrame(
    canvas: Canvas,
    f: TerminalFrame,
    cursorStyle: TerminalView.CursorStyle,
    composing: Boolean,
  ) {
    canvas.drawColor(f.defaultBg)

    // Backgrounds first, for the whole grid, then the glyphs. In one pass a
    // cell's background rect landed on top of the glyph its left neighbour
    // had already drawn, so a glyph could only ever overhang into a cell that
    // painted no background. That ruled out the case Nerd Fonts exist for: a
    // powerline prompt, where every cell carries a background of its own.
    drawBackgrounds(canvas, f)
    drawGlyphs(canvas, f)

    drawCursor(canvas, f, cursorStyle, composing)
  }

  /** Fills every cell that is not on the default background. */
  private fun drawBackgrounds(canvas: Canvas, f: TerminalFrame) {
    val cellW = cellWidthPx.toFloat()
    val cellH = cellHeightPx.toFloat()

    for (y in 0 until f.rows) {
      val top = y * cellH
      for (x in 0 until f.cols) {
        val i = f.indexOf(x, y)
        if (f.isSpacer(i)) continue

        val selected = f.hasFlag(i, CellFlags.SELECTED)
        val bg = backgroundOf(f, i)
        // Background, selection, and inverse all span the full occupancy, or a
        // wide glyph sits on a half-painted background.
        if (bg != f.defaultBg || selected) {
          bgPaint.color = bg
          val left = x * cellW
          canvas.drawRect(
            left, top, left + metrics.spanWidth(f.spanCells(i)), top + cellH, bgPaint,
          )
        }
      }
    }
  }

  /** Draws the text and its decorations over the finished backgrounds. */
  private fun drawGlyphs(canvas: Canvas, f: TerminalFrame) {
    val lineWidth = decorationWidthPx
    val cellW = cellWidthPx.toFloat()
    val cellH = cellHeightPx.toFloat()

    for (y in 0 until f.rows) {
      val top = y * cellH
      for (x in 0 until f.cols) {
        val i = f.indexOf(x, y)
        // The tail of a wide cell carries no glyph of its own; the lead cell
        // paints across both columns.
        if (f.isSpacer(i)) continue

        // Ghostty decides occupancy; Android only rasterizes into it.
        val spanWidth = metrics.spanWidth(f.spanCells(i))
        val fg = foregroundOf(f, i)
        val left = x * cellW

        if (f.textLengthAt(i) > 0) {
          drawGlyph(
            canvas, f.chars, f.textOffsetAt(i), f.textLengthAt(i),
            f.hasFlag(i, CellFlags.BOLD), f.hasFlag(i, CellFlags.ITALIC),
            fg, left, top, spanWidth, overflowWidth(f, x, y, i, spanWidth),
          )
        }

        // Both decorations can be set at once, and each gets its own line:
        // collapsing them lost whichever the other one won.
        val underline = f.hasFlag(i, CellFlags.UNDERLINE)
        val strikethrough = f.hasFlag(i, CellFlags.STRIKETHROUGH)
        if (underline || strikethrough) {
          textPaint.color = fg
          textPaint.strokeWidth = lineWidth
          if (underline) {
            val ul = top + metrics.underlinePx
            canvas.drawLine(left, ul, left + spanWidth, ul, textPaint)
          }
          if (strikethrough) {
            val strike = top + metrics.baselinePx * STRIKE_RATIO
            canvas.drawLine(left, strike, left + spanWidth, strike, textPaint)
          }
          textPaint.strokeWidth = 0f
        }
      }
    }
  }

  /**
   * True when a cell paints with its foreground and background exchanged.
   *
   * Inverse and selection each swap the pair, so a cell carrying both swaps
   * twice and reads as ordinary text, which is what a desktop terminal shows.
   * That is an exclusive or, and writing it as one keeps the two callers below
   * from disagreeing about the order the flags apply in.
   */
  private fun isSwapped(f: TerminalFrame, i: Int): Boolean =
    f.hasFlag(i, CellFlags.INVERSE) != f.hasFlag(i, CellFlags.SELECTED)

  /** The background a cell paints, after inverse and selection. */
  private fun backgroundOf(f: TerminalFrame, i: Int): Int =
    if (isSwapped(f, i)) f.fgAt(i) else f.bgAt(i)

  /** The foreground a cell paints, after inverse, selection, and faint. */
  private fun foregroundOf(f: TerminalFrame, i: Int): Int {
    val fg = if (isSwapped(f, i)) f.bgAt(i) else f.fgAt(i)
    // Faint is a dimmed foreground, mixed toward the background it sits on.
    // Ignoring it made low-emphasis output, which agents use for hints and
    // paths, indistinguishable from ordinary text.
    if (!f.hasFlag(i, CellFlags.FAINT)) return fg
    return blend(fg, backgroundOf(f, i), FAINT_ALPHA)
  }

  /**
   * How wide a glyph in this cell may actually paint.
   *
   * A Nerd Font icon carries about one and a half cells of ink on a one-cell
   * advance, and the terminal still calls the cell one column wide.
   * Compressing it into that column is what makes the icons look pinched, so
   * it is allowed to spill into neighbouring columns that are provably empty.
   */
  private fun overflowWidth(
    f: TerminalFrame,
    x: Int,
    y: Int,
    i: Int,
    spanWidth: Float,
  ): Float =
    spanWidth + CellSpill.columns(f, x, y, i) * cellWidthPx.toFloat()

  /**
   * Draws one grapheme fitted to its cell box.
   *
   * The complete cell text is drawn at one origin, never split by code unit, so
   * a combining sequence stays one cluster. A glyph is centered in the columns
   * the terminal gave it. It is compressed only when it does not fit even the
   * room [maxWidth] allows, because overflowing would paint over a neighbour
   * that holds something.
   *
   * Text is read straight from the frame's shared char buffer, so drawing a
   * cell builds no String.
   */
  private fun drawGlyph(
    canvas: Canvas,
    chars: CharArray,
    offset: Int,
    length: Int,
    bold: Boolean,
    italic: Boolean,
    fg: Int,
    left: Float,
    top: Float,
    spanWidth: Float,
    maxWidth: Float = spanWidth,
  ) {
    val kind = GlyphKind.of(Character.codePointAt(chars, offset))
    textPaint.color = fg
    textPaint.typeface = fonts.typefaceFor(bold, italic, kind)

    val face = fonts.faceIndex(bold, italic, kind)
    val advance = glyphWidths.width(chars, offset, length, face, textPaint)
    val baseline = top + metrics.baselinePx

    // Nerd Font icons all carry a one-cell advance while their ink runs to
    // about one and a half cells, so the advance says nothing about whether
    // one will fit. Their painted extent is what has to be fitted; for text
    // and CJK the advance is the right measure and is already cached.
    val extent = if (kind == GlyphKind.SYMBOL) {
      textPaint.getTextBounds(chars, offset, length, inkBounds)
      inkBounds.width().toFloat()
    } else {
      advance
    }

    val scale = TerminalMetrics.fitScale(extent, maxWidth)

    if (scale >= 1f) {
      // A glyph that fits its own columns is centered in them. One that only
      // fits by overhanging starts at its own left edge instead, because the
      // ink grows rightward and centering would push it back over the cell
      // to the left, which was never checked and may hold anything.
      val dx =
        if (extent > spanWidth) 0f else TerminalMetrics.centerOffset(advance, spanWidth)
      canvas.drawText(chars, offset, length, left + dx, baseline, textPaint)
      return
    }

    // Compression runs on a copy, so the shared paint's textScaleX is never
    // left modified for the next cell.
    fitPaint.set(textPaint)
    fitPaint.textScaleX = scale
    canvas.drawText(chars, offset, length, left, baseline, fitPaint)
  }

  private fun drawCursor(
    canvas: Canvas,
    f: TerminalFrame,
    cursorStyle: TerminalView.CursorStyle,
    composing: Boolean,
  ) {
    // The composition caret stands in for the cursor while preedit is open.
    // Drawing both puts a block under the first composed glyph, which is what
    // hid it.
    if (composing) return
    if (!f.cursorVisible || f.cursorX >= f.cols || f.cursorY >= f.rows) return
    val i = f.indexOf(f.cursorX, f.cursorY)
    // A cursor sitting on a wide lead cell covers both of its columns.
    val spanWidth = metrics.spanWidth(f.spanCells(i))
    val left = f.cursorX * cellWidthPx.toFloat()
    val top = f.cursorY * cellHeightPx.toFloat()
    val cellH = cellHeightPx.toFloat()

    bgPaint.color = f.defaultFg
    when (cursorStyle) {
      TerminalView.CursorStyle.BAR ->
        canvas.drawRect(left, top, left + cursorBarPx, top + cellH, bgPaint)
      TerminalView.CursorStyle.UNDERLINE -> canvas.drawRect(
        left, top + cellH - cursorUnderlinePx, left + spanWidth, top + cellH, bgPaint,
      )
      TerminalView.CursorStyle.BLOCK -> {
        canvas.drawRect(left, top, left + spanWidth, top + cellH, bgPaint)
        // Redraw the glyph inverted so a block cursor does not hide the
        // character underneath it.
        if (f.textLengthAt(i) > 0) {
          drawGlyph(
            canvas, f.chars, f.textOffsetAt(i), f.textLengthAt(i),
            f.hasFlag(i, CellFlags.BOLD), f.hasFlag(i, CellFlags.ITALIC),
            f.defaultBg, left, top, spanWidth,
          )
        }
      }
    }
  }

  /**
   * Draws the IME composition at the terminal insertion point.
   *
   * Preedit is local: it is never written into the terminal or sent to the
   * PTY. It is laid out on the terminal grid, one grapheme cluster per one or
   * two columns, so a Korean syllable advances the same way a committed one
   * will and the candidate window lands in the right place.
   */
  fun drawComposingText(canvas: Canvas, f: TerminalFrame, composition: CompositionState) {
    if (composition.isEmpty) return

    val cellW = cellWidthPx.toFloat()
    val cellH = cellHeightPx.toFloat()

    var col = f.cursorX
    var row = f.cursorY
    for (cluster in PreeditLayout.clusters(composition.text)) {
      // Wrap onto the next row the way the terminal would.
      if (col + cluster.cells > f.cols) {
        col = 0
        row += 1
      }
      if (row >= f.rows) break

      val left = col * cellW
      val top = row * cellH
      val spanWidth = metrics.spanWidth(cluster.cells)

      // Clear the cells under the preedit so terminal content does not show
      // through the composition.
      bgPaint.color = f.defaultBg
      canvas.drawRect(left, top, left + spanWidth, top + cellH, bgPaint)

      // Composed text is overwhelmingly CJK, and the text face has no glyph
      // for it. Picked per cluster so a mixed composition draws each part
      // from the face that covers it.
      val kind = GlyphKind.of(cluster.text.codePointAt(0))
      textPaint.typeface = fonts.typefaceFor(bold = false, italic = false, kind = kind)
      textPaint.color = f.defaultFg
      val natural = textPaint.measureText(cluster.text)
      val scale = TerminalMetrics.fitScale(natural, spanWidth)
      val baseline = top + metrics.baselinePx
      if (scale >= 1f) {
        val dx = TerminalMetrics.centerOffset(natural, spanWidth)
        canvas.drawText(cluster.text, left + dx, baseline, textPaint)
      } else {
        fitPaint.set(textPaint)
        fitPaint.textScaleX = scale
        canvas.drawText(cluster.text, left, baseline, fitPaint)
      }

      // The underline marks the whole run as uncommitted.
      bgPaint.color = f.defaultFg
      bgPaint.strokeWidth = decorationWidthPx
      val ul = top + metrics.underlinePx
      canvas.drawLine(left, ul, left + spanWidth, ul, bgPaint)
      bgPaint.strokeWidth = 0f

      col += cluster.cells
    }

    drawCompositionCaret(canvas, f, composition)
  }

  /**
   * Draws the caret at the IME's position inside the preedit.
   *
   * A thin bar, not the terminal's own cursor style: this marks an insertion
   * point within text being composed, and a block would hide the glyph the
   * user is looking at.
   */
  private fun drawCompositionCaret(
    canvas: Canvas,
    f: TerminalFrame,
    composition: CompositionState,
  ) {
    val cellsBefore =
      PreeditLayout.cellsBefore(composition.text, composition.selectionEndUtf16)

    var col = f.cursorX + cellsBefore
    var row = f.cursorY
    while (col >= f.cols && f.cols > 0) {
      col -= f.cols
      row += 1
    }
    if (row >= f.rows) return

    bgPaint.color = f.defaultFg
    val left = col * cellWidthPx.toFloat()
    val top = row * cellHeightPx.toFloat()
    canvas.drawRect(left, top, left + cursorBarPx, top + cellHeightPx, bgPaint)
  }

  /**
   * Draws the selection handles.
   *
   * Teardrops under each end, in a fixed accent, so the selection can be
   * adjusted the way any other text selection is. The selection fill itself is
   * drawn from the terminal's own selected-cell flag.
   */
  fun drawSelectionHandles(canvas: Canvas, selection: TerminalSelection, radiusPx: Float) {
    if (cellWidthPx <= 0 || cellHeightPx <= 0) return

    // Not the foreground color: on the usual light-on-dark terminal that is
    // near-white, which disappears against the text it sits under. A fixed
    // accent reads against any palette the remote sets.
    bgPaint.color = SELECTION_HANDLE_COLOR
    // Both the body positions and the hit test come from handleCenter, so the
    // drawn handle and the one a touch grabs cannot end up on opposite sides.
    for (handle in SelectionHandle.entries) {
      val (cx, cy) = handleCenter(selection, handle, cellWidthPx, cellHeightPx, radiusPx)
      drawHandle(canvas, cx, cy, radiusPx / 2f, handle)
    }
  }

  /**
   * Draws one teardrop.
   *
   * (cx, cy) is the centre of the round body, which hangs below the text and
   * away from the selection. The square quadrant between the body and the
   * selection edge turns the circle into a tip that touches the character the
   * handle marks; a plain circle reads as a dot floating under the line and
   * marks neither end.
   */
  private fun drawHandle(
    canvas: Canvas,
    cx: Float,
    cy: Float,
    radius: Float,
    handle: SelectionHandle,
  ) {
    // The tip is on the side facing the selection: the leading handle sits to
    // the left of its text, so its tip is on its upper right.
    val tipX = if (handle == SelectionHandle.START) cx + radius else cx - radius
    val top = cy - radius

    handlePath.reset()
    handlePath.addCircle(cx, cy, radius, Path.Direction.CW)
    if (handle == SelectionHandle.START) {
      handlePath.addRect(cx, top, tipX, cy, Path.Direction.CW)
    } else {
      handlePath.addRect(tipX, top, cx, cy, Path.Direction.CW)
    }
    canvas.drawPath(handlePath, bgPaint)
  }

  /**
   * Draws the scrollback indicator, and reports whether it is mid-fade.
   *
   * Nothing is drawn when the whole scrollable area fits, so a terminal with
   * no history has no bar and an application on the alternate screen never
   * shows one. Returning true means the bar is fading and the caller has to
   * schedule the next frame; it does not schedule one itself, because that is
   * the view's business.
   */
  fun drawScrollbar(
    canvas: Canvas,
    f: TerminalFrame,
    bar: ScrollbarState,
    viewWidth: Int,
    viewHeight: Int,
    alpha: Float,
  ): Boolean {
    if (bar.total <= 0L || bar.visible <= 0L || bar.total <= bar.visible) return false
    if (alpha <= 0f) return false

    val trackHeight = (f.rows * cellHeightPx).toFloat().coerceAtMost(viewHeight.toFloat())
    if (trackHeight <= 0f) return false

    val fraction = (bar.visible.toFloat() / bar.total.toFloat()).coerceIn(0f, 1f)
    val thumbHeight = (trackHeight * fraction).coerceAtLeast(scrollbarMinThumbPx)
    val travel = trackHeight - thumbHeight
    val scrolled = (bar.total - bar.visible).toFloat()
    val progress =
      if (scrolled <= 0f) 0f else (bar.offset.toFloat() / scrolled).coerceIn(0f, 1f)
    val top = travel * progress

    val barWidth = scrollbarWidthPx
    val right = viewWidth.toFloat() - scrollbarInsetPx
    val left = right - barWidth

    bgPaint.color = f.defaultFg
    bgPaint.alpha = (alpha * SCROLLBAR_ALPHA).toInt().coerceIn(0, 255)
    canvas.drawRoundRect(
      left, top, right, top + thumbHeight, barWidth / 2f, barWidth / 2f, bgPaint,
    )
    bgPaint.alpha = 255
    return true
  }

  /**
   * Mixes [over] toward [under] by [alpha], both opaque.
   *
   * Used for faint text, which is a dimmed foreground rather than a separate
   * color the terminal reports.
   */
  private fun blend(over: Int, under: Int, alpha: Float): Int {
    val inv = 1f - alpha
    val r = ((over ushr 16 and 0xff) * alpha + (under ushr 16 and 0xff) * inv).toInt()
    val g = ((over ushr 8 and 0xff) * alpha + (under ushr 8 and 0xff) * inv).toInt()
    val b = ((over and 0xff) * alpha + (under and 0xff) * inv).toInt()
    return (0xff shl 24) or (r shl 16) or (g shl 8) or b
  }

  companion object {
    /** Selection handles. A saturated blue, legible on light and dark. */
    const val SELECTION_HANDLE_COLOR = 0xFF3B82F6.toInt()

    // Fraction of the baseline height at which a strikethrough is drawn.
    const val STRIKE_RATIO = 0.6f

    /** How far faint text is dimmed toward its background. */
    const val FAINT_ALPHA = 0.55f

    /** Underline and strikethrough thickness, as a fraction of the font size. */
    const val DECORATION_RATIO = 0.07f

    const val CURSOR_BAR_DP = 2f
    const val CURSOR_UNDERLINE_DP = 2f

    const val SCROLLBAR_WIDTH_DP = 3f
    const val SCROLLBAR_INSET_DP = 2f
    const val SCROLLBAR_MIN_THUMB_DP = 24f
    const val SCROLLBAR_ALPHA = 140f
  }
}
