package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Selection ordering and handle hit-testing.
 *
 * The anchor and focus are kept apart rather than normalized so a backwards
 * drag keeps moving the handle the finger grabbed. Reading order is derived,
 * because that is what the terminal's selection API expects.
 */
class TerminalSelectionTest {

    private val cell = 20
    private val radius = 24f

    @Test
    fun aForwardSelectionReadsInOrder() {
        val s = TerminalSelection(anchorCol = 2, anchorRow = 1, focusCol = 5, focusRow = 3)
        assertFalse(s.reversed)
        assertEquals(2, s.startCol)
        assertEquals(1, s.startRow)
        assertEquals(5, s.endCol)
        assertEquals(3, s.endRow)
    }

    @Test
    fun aBackwardSelectionSwapsItsEnds() {
        val s = TerminalSelection(anchorCol = 5, anchorRow = 3, focusCol = 2, focusRow = 1)
        assertTrue(s.reversed)
        assertEquals(2, s.startCol)
        assertEquals(1, s.startRow)
        assertEquals(5, s.endCol)
        assertEquals(3, s.endRow)
    }

    // Same row, focus to the left of the anchor: column decides.
    @Test
    fun orderingOnOneRowUsesTheColumn() {
        val s = TerminalSelection(anchorCol = 8, anchorRow = 2, focusCol = 3, focusRow = 2)
        assertTrue(s.reversed)
        assertEquals(3, s.startCol)
        assertEquals(8, s.endCol)
    }

    @Test
    fun aCollapsedSelectionIsEmpty() {
        assertTrue(TerminalSelection.at(4, 4).isEmpty)
        assertFalse(TerminalSelection.at(4, 4).withFocus(5, 4).isEmpty)
    }

    @Test
    fun pivotingSwapsTheFixedEnd() {
        val s = TerminalSelection(anchorCol = 1, anchorRow = 0, focusCol = 9, focusRow = 4)
        val p = s.pivoted()
        assertEquals(9, p.anchorCol)
        assertEquals(4, p.anchorRow)
        assertEquals(1, p.focusCol)
        assertEquals(0, p.focusRow)
        // The range it covers is unchanged.
        assertEquals(s.startCol, p.startCol)
        assertEquals(s.endRow, p.endRow)
    }

    // --- handle hit-testing ---------------------------------------------------

    private val selection =
        TerminalSelection(anchorCol = 2, anchorRow = 1, focusCol = 6, focusRow = 3)

    @Test
    fun aTouchOnTheLeadingHandleGrabsIt() {
        // The teardrop hangs below the start cell's lower-left corner.
        val grabbed = handleAt(selection, 2f * cell, 2f * cell, cell, cell, radius)
        assertEquals(SelectionHandle.START, grabbed)
    }

    @Test
    fun aTouchOnTheTrailingHandleGrabsIt() {
        // The teardrop hangs below the end cell's lower-right corner.
        val grabbed = handleAt(selection, 7f * cell, 4f * cell, cell, cell, radius)
        assertEquals(SelectionHandle.END, grabbed)
    }

    // Android hangs each handle away from the selection: the leading one to
    // the left of the first character, the trailing one to the right of the
    // last. Both were placed on the inside, which reads as the two being
    // swapped. Asserted on the body position rather than through handleAt,
    // because the two handles are far apart and the nearest-one rule answers
    // the same either way.
    @Test
    fun theLeadingBodyHangsLeftOfTheSelection() {
        val (x, _) =
            handleCenter(selection, SelectionHandle.START, cell, cell, radius)
        assertTrue(
            "leading body must sit left of the first cell",
            x < selection.startCol * cell,
        )
    }

    @Test
    fun theTrailingBodyHangsRightOfTheSelection() {
        val (x, _) =
            handleCenter(selection, SelectionHandle.END, cell, cell, radius)
        assertTrue(
            "trailing body must sit right of the last cell",
            x > (selection.endCol + 1) * cell,
        )
    }

    @Test
    fun bothBodiesHangBelowTheirRow() {
        val (_, startY) =
            handleCenter(selection, SelectionHandle.START, cell, cell, radius)
        val (_, endY) =
            handleCenter(selection, SelectionHandle.END, cell, cell, radius)
        assertTrue(startY > (selection.startRow + 1) * cell)
        assertTrue(endY > (selection.endRow + 1) * cell)
    }

    // A touch on the drawn body has to grab that same handle, which is what
    // fails when drawing and hit-testing disagree about the side.
    @Test
    fun aTouchOnEachDrawnBodyGrabsThatHandle() {
        for (handle in SelectionHandle.entries) {
            val (x, y) = handleCenter(selection, handle, cell, cell, radius)
            assertEquals(handle, handleAt(selection, x, y, cell, cell, radius))
        }
    }

    // --- dragging either end --------------------------------------------------

    // The bug this replaced: which end moved depended on where the anchor
    // happened to be, so grabbing a handle could jump the range to the dragged
    // point instead of extending from the opposite end.
    @Test
    fun grabbingTheTrailingHandleExtendsFromTheStart() {
        val s = TerminalSelection(anchorCol = 2, anchorRow = 1, focusCol = 6, focusRow = 3)
        val dragged = s.grabbing(SelectionHandle.END).withFocus(9, 5)
        assertEquals(2, dragged.startCol)
        assertEquals(1, dragged.startRow)
        assertEquals(9, dragged.endCol)
        assertEquals(5, dragged.endRow)
    }

    @Test
    fun grabbingTheLeadingHandleExtendsFromTheEnd() {
        val s = TerminalSelection(anchorCol = 2, anchorRow = 1, focusCol = 6, focusRow = 3)
        val dragged = s.grabbing(SelectionHandle.START).withFocus(0, 0)
        assertEquals(0, dragged.startCol)
        assertEquals(0, dragged.startRow)
        assertEquals(6, dragged.endCol)
        assertEquals(3, dragged.endRow)
    }

    // The same must hold when the selection was made backwards, which is where
    // the old anchor-based rule broke.
    @Test
    fun eitherHandleWorksOnABackwardSelection() {
        val s = TerminalSelection(anchorCol = 6, anchorRow = 3, focusCol = 2, focusRow = 1)

        val end = s.grabbing(SelectionHandle.END).withFocus(9, 5)
        assertEquals(2, end.startCol)
        assertEquals(1, end.startRow)
        assertEquals(9, end.endCol)
        assertEquals(5, end.endRow)

        val start = s.grabbing(SelectionHandle.START).withFocus(0, 0)
        assertEquals(0, start.startCol)
        assertEquals(0, start.startRow)
        assertEquals(6, start.endCol)
        assertEquals(3, start.endRow)
    }

    @Test
    fun grabbingLeavesTheRangeUnchangedUntilItMoves() {
        val s = TerminalSelection(anchorCol = 2, anchorRow = 1, focusCol = 6, focusRow = 3)
        for (handle in SelectionHandle.entries) {
            val g = s.grabbing(handle)
            assertEquals(s.startCol, g.startCol)
            assertEquals(s.startRow, g.startRow)
            assertEquals(s.endCol, g.endCol)
            assertEquals(s.endRow, g.endRow)
        }
    }

    // Dragging one handle past the other shrinks and then inverts the range
    // rather than refusing to move.
    @Test
    fun aHandleDraggedPastTheOtherInverts() {
        val s = TerminalSelection(anchorCol = 2, anchorRow = 1, focusCol = 6, focusRow = 3)
        val dragged = s.grabbing(SelectionHandle.END).withFocus(0, 0)
        assertEquals(0, dragged.startCol)
        assertEquals(0, dragged.startRow)
        assertEquals(2, dragged.endCol)
        assertEquals(1, dragged.endRow)
    }

    @Test
    fun aTouchAwayFromBothGrabsNothing() {
        assertNull(handleAt(selection, 200f, 200f, cell, cell, radius))
    }

    // A fingertip is far larger than a cell, so the nearer handle wins rather
    // than the touch falling through and starting a new selection.
    @Test
    fun theNearerHandleWins() {
        val tight = TerminalSelection(anchorCol = 3, anchorRow = 2, focusCol = 4, focusRow = 2)
        val nearStart = handleAt(tight, 3f * cell + 1f, 3f * cell, cell, cell, radius)
        assertEquals(SelectionHandle.START, nearStart)
        val nearEnd = handleAt(tight, 5f * cell - 1f, 3f * cell, cell, cell, radius)
        assertEquals(SelectionHandle.END, nearEnd)
    }

    @Test
    fun anUnmeasuredCellGrabsNothing() {
        assertNull(handleAt(selection, 0f, 0f, 0, 0, radius))
    }

    // --- toolbar placement ----------------------------------------------------

    @Test
    fun boundsCoverTheWholeSelection() {
        val b = selectionBounds(selection, cell, cell)
        assertEquals(2 * cell, b[0])
        assertEquals(1 * cell, b[1])
        assertEquals(7 * cell, b[2])
        assertEquals(4 * cell, b[3])
    }

    @Test
    fun boundsAreTheSameForABackwardSelection() {
        val forward = selectionBounds(selection, cell, cell)
        val backward = selectionBounds(selection.pivoted(), cell, cell)
        assertTrue(forward.contentEquals(backward))
    }
}
