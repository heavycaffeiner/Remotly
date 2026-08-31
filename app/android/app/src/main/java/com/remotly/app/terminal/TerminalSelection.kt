package com.remotly.app.terminal

import kotlin.math.max
import kotlin.math.min

/**
 * A selected range on the terminal grid, in viewport cells.
 *
 * The anchor is where the selection started and the focus is the end the user
 * is currently dragging. They are kept apart rather than normalized so a
 * backwards drag keeps dragging the same handle.
 *
 * Kept free of Android types so the ordering and hit-testing are unit testable.
 */
data class TerminalSelection(
    val anchorCol: Int,
    val anchorRow: Int,
    val focusCol: Int,
    val focusRow: Int,
) {
    /** True when the focus lies before the anchor in reading order. */
    val reversed: Boolean
        get() = focusRow < anchorRow ||
            (focusRow == anchorRow && focusCol < anchorCol)

    /** The earlier end in reading order. */
    val startCol: Int get() = if (reversed) focusCol else anchorCol
    val startRow: Int get() = if (reversed) focusRow else anchorRow

    /** The later end in reading order. */
    val endCol: Int get() = if (reversed) anchorCol else focusCol
    val endRow: Int get() = if (reversed) anchorRow else focusRow

    /** True when both ends are the same cell. */
    val isEmpty: Boolean get() = anchorCol == focusCol && anchorRow == focusRow

    /** Moves the focus end, which is what a handle drag does. */
    fun withFocus(col: Int, row: Int): TerminalSelection =
        copy(focusCol = col, focusRow = row)

    /** Moves the anchor end, for dragging the leading handle. */
    fun withAnchor(col: Int, row: Int): TerminalSelection =
        copy(anchorCol = col, anchorRow = row)

    /**
     * Swaps the ends so [anchorCol]/[anchorRow] becomes the other one.
     */
    fun pivoted(): TerminalSelection = TerminalSelection(
        anchorCol = focusCol,
        anchorRow = focusRow,
        focusCol = anchorCol,
        focusRow = anchorRow,
    )

    /**
     * Prepares the selection for a drag of one handle.
     *
     * The grabbed end becomes the focus and the opposite end becomes the fixed
     * anchor, whichever way round they currently are. Deciding this from the
     * anchor's position instead meant grabbing a handle could move the wrong
     * end: the range jumped to the dragged point rather than extending from
     * the other one.
     */
    fun grabbing(handle: SelectionHandle): TerminalSelection {
        val anchorIsStart = !reversed
        val grabbedIsStart = handle == SelectionHandle.START
        // The anchor must end up on the side that is not being dragged.
        return if (anchorIsStart == grabbedIsStart) pivoted() else this
    }

    companion object {
        /** A collapsed selection at one cell. */
        fun at(col: Int, row: Int): TerminalSelection =
            TerminalSelection(col, row, col, row)
    }
}

/** Which selection handle a touch grabbed, if any. */
enum class SelectionHandle { START, END }

/**
 * Decides whether a touch landed on one of the selection handles.
 *
 * Handles are drawn under their cell, so the hit area extends below the row
 * and is generous: a fingertip is far larger than a terminal cell, and a miss
 * would start a new selection instead of adjusting the current one.
 */
/**
 * Where a handle's round body is drawn, in view pixels.
 *
 * Android hangs each handle away from the selection so its tip points back
 * into the text: the leading one to the left of the first character, the
 * trailing one to the right of the last. Drawing and hit-testing both derive
 * from this, so they cannot disagree about which side a handle is on.
 */
fun handleCenter(
    selection: TerminalSelection,
    handle: SelectionHandle,
    cellWidthPx: Int,
    cellHeightPx: Int,
    radiusPx: Float,
): Pair<Float, Float> {
    val bodyRadius = radiusPx / 2f
    return when (handle) {
        SelectionHandle.START -> Pair(
            selection.startCol * cellWidthPx.toFloat() - bodyRadius,
            (selection.startRow + 1) * cellHeightPx.toFloat() + bodyRadius,
        )
        SelectionHandle.END -> Pair(
            (selection.endCol + 1) * cellWidthPx.toFloat() + bodyRadius,
            (selection.endRow + 1) * cellHeightPx.toFloat() + bodyRadius,
        )
    }
}

fun handleAt(
    selection: TerminalSelection,
    x: Float,
    y: Float,
    cellWidthPx: Int,
    cellHeightPx: Int,
    radiusPx: Float,
): SelectionHandle? {
    if (cellWidthPx <= 0 || cellHeightPx <= 0) return null

    val (startX, startY) =
        handleCenter(selection, SelectionHandle.START, cellWidthPx, cellHeightPx, radiusPx)
    val (endX, endY) =
        handleCenter(selection, SelectionHandle.END, cellWidthPx, cellHeightPx, radiusPx)

    val startDistance = distance(x, y, startX, startY)
    val endDistance = distance(x, y, endX, endY)
    if (min(startDistance, endDistance) > radiusPx) return null
    return if (startDistance <= endDistance) SelectionHandle.START
    else SelectionHandle.END
}

private fun distance(x1: Float, y1: Float, x2: Float, y2: Float): Float {
    val dx = x1 - x2
    val dy = y1 - y2
    return kotlin.math.sqrt(dx * dx + dy * dy)
}

/**
 * The rectangle the floating toolbar should avoid, in view coordinates.
 *
 * Android positions the toolbar around this, so it covers the selection rather
 * than the text being read. Returned as [left, top, right, bottom].
 */
fun selectionBounds(
    selection: TerminalSelection,
    cellWidthPx: Int,
    cellHeightPx: Int,
): IntArray {
    val left = min(selection.startCol, selection.endCol) * cellWidthPx
    val right = (max(selection.startCol, selection.endCol) + 1) * cellWidthPx
    val top = selection.startRow * cellHeightPx
    val bottom = (selection.endRow + 1) * cellHeightPx
    return intArrayOf(left, top, right, bottom)
}
