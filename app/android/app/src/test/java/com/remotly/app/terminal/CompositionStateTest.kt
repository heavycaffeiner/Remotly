package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Preedit state and its terminal-grid layout. The caret has to land on a
// grapheme boundary and advance by terminal cells, or the candidate window
// drifts away from the text being composed.
class CompositionStateTest {

    @Test
    fun emptyTextIsNoComposition() {
        assertTrue(CompositionState.of("", 1).isEmpty)
        assertTrue(CompositionState.of(null, 1).isEmpty)
        assertTrue(CompositionState.NONE.isEmpty)
    }

    @Test
    fun aPositiveCursorPositionCountsFromTheEnd() {
        // The InputConnection contract: 1 means just after the inserted text.
        val s = CompositionState.of("가나", 1)
        assertEquals(2, s.selectionEndUtf16)
    }

    @Test
    fun aNonPositiveCursorPositionCountsFromTheStart() {
        val s = CompositionState.of("가나", 0)
        assertEquals(0, s.selectionEndUtf16)
    }

    @Test
    fun anOutOfRangeCursorIsClampedRatherThanRejected() {
        // A misbehaving IME must not crash the terminal.
        assertEquals(2, CompositionState.of("ab", 99).selectionEndUtf16)
        assertEquals(0, CompositionState.of("ab", -99).selectionEndUtf16)
    }

    @Test
    fun malformedSurrogatesAreReplacedAtTheImeBoundary() {
        assertEquals("\uFFFD한\uFFFD", CompositionState.of("\uD83D한\uDE00", 1).text)
    }

    @Test
    fun validSurrogatePairsArePreserved() {
        assertEquals("😀", CompositionState.of("😀", 1).text)
    }
}

class PreeditLayoutTest {

    // --- width convention ----------------------------------------------------

    @Test
    fun asciiOccupiesOneCell() {
        assertEquals(1, PreeditLayout.cellWidth("a"))
        assertEquals(1, PreeditLayout.cellWidth("Z"))
        assertEquals(1, PreeditLayout.cellWidth("0"))
    }

    @Test
    fun hangulOccupiesTwoCells() {
        assertEquals(2, PreeditLayout.cellWidth("한"))
        assertEquals(2, PreeditLayout.cellWidth("글"))
    }

    @Test
    fun kanaAndHanOccupyTwoCells() {
        assertEquals(2, PreeditLayout.cellWidth("あ"))
        assertEquals(2, PreeditLayout.cellWidth("ア"))
        assertEquals(2, PreeditLayout.cellWidth("中"))
    }

    @Test
    fun fullwidthFormsOccupyTwoCells() {
        assertEquals(2, PreeditLayout.cellWidth("Ａ"))
        assertEquals(2, PreeditLayout.cellWidth("："))
    }

    @Test
    fun boxDrawingStaysOneCell() {
        // Box drawing must not be treated as wide, or every TUI frame doubles.
        assertEquals(1, PreeditLayout.cellWidth("─"))
        assertEquals(1, PreeditLayout.cellWidth("│"))
        assertEquals(1, PreeditLayout.cellWidth("┼"))
    }

    @Test
    fun anEmptyClusterHasNoWidth() {
        assertEquals(0, PreeditLayout.cellWidth(""))
    }

    // --- clustering ----------------------------------------------------------

    @Test
    fun asciiSplitsIntoOneClusterPerCharacter() {
        val clusters = PreeditLayout.clusters("abc")
        assertEquals(3, clusters.size)
        assertEquals("a", clusters[0].text)
        assertEquals(0, clusters[0].startUtf16)
        assertEquals(2, clusters[2].startUtf16)
    }

    @Test
    fun anNfdHangulSyllableStaysOneCluster() {
        // Composed from jamo. Splitting it by code point would scatter the
        // pieces across separate columns.
        val nfd = "\u1112\u1161\u11AB" // 한
        val clusters = PreeditLayout.clusters(nfd)
        assertEquals(1, clusters.size)
        assertEquals(nfd, clusters[0].text)
        assertEquals(2, clusters[0].cells)
    }

    @Test
    fun nfcAndNfdHangulAgreeOnWidth() {
        assertEquals(
            PreeditLayout.totalCells("한"),
            PreeditLayout.totalCells("\u1112\u1161\u11AB"),
        )
    }

    @Test
    fun anEmojiWithAVariationSelectorStaysOneCluster() {
        val clusters = PreeditLayout.clusters("\u2764\uFE0F")
        assertEquals(1, clusters.size)
    }

    @Test
    fun aSurrogatePairIsOneCluster() {
        val emoji = "\uD83D\uDE00" // grinning face
        val clusters = PreeditLayout.clusters(emoji)
        assertEquals(1, clusters.size)
        assertEquals(2, clusters[0].cells)
    }

    @Test
    fun combiningLatinMarksStayWithTheirBase() {
        val clusters = PreeditLayout.clusters("e\u0301") // e with acute
        assertEquals(1, clusters.size)
        assertEquals(1, clusters[0].cells)
    }

    // --- totals and caret ----------------------------------------------------

    @Test
    fun mixedScriptTotalsCountWideCellsTwice() {
        // "a한b" is 1 + 2 + 1 columns.
        assertEquals(4, PreeditLayout.totalCells("a한b"))
    }

    @Test
    fun caretAtTheStartIsZeroCells() {
        assertEquals(0, PreeditLayout.cellsBefore("한글", 0))
        assertEquals(0, PreeditLayout.cellsBefore("한글", -5))
    }

    @Test
    fun caretAdvancesTwoCellsPerHangulSyllable() {
        assertEquals(2, PreeditLayout.cellsBefore("한글", 1))
        assertEquals(4, PreeditLayout.cellsBefore("한글", 2))
    }

    @Test
    fun caretAdvancesOneCellPerAsciiCharacter() {
        assertEquals(1, PreeditLayout.cellsBefore("abc", 1))
        assertEquals(3, PreeditLayout.cellsBefore("abc", 3))
    }

    @Test
    fun caretMapsAcrossASurrogatePair() {
        // The offset is in UTF-16 units, so a pair counts as two of them but
        // only one cluster.
        val text = "\uD83D\uDE00a"
        assertEquals(0, PreeditLayout.cellsBefore(text, 0))
        assertEquals(2, PreeditLayout.cellsBefore(text, 2))
        assertEquals(3, PreeditLayout.cellsBefore(text, 3))
    }

    @Test
    fun anOffsetInsideAClusterResolvesToItsStart() {
        // Landing mid-cluster must not place the caret between jamo.
        val nfd = "\u1112\u1161\u11AB"
        assertEquals(0, PreeditLayout.cellsBefore(nfd, 1))
        assertEquals(0, PreeditLayout.cellsBefore(nfd, 2))
        assertEquals(2, PreeditLayout.cellsBefore(nfd, 3))
    }

    @Test
    fun anOffsetPastTheEndCountsEverything() {
        assertEquals(4, PreeditLayout.cellsBefore("한글", 99))
    }

    @Test
    fun emptyTextHasNoCells() {
        assertEquals(0, PreeditLayout.totalCells(""))
        assertEquals(0, PreeditLayout.cellsBefore("", 3))
        assertTrue(PreeditLayout.clusters("").isEmpty())
    }

    @Test
    fun wideRangesDoNotSwallowLatin() {
        assertFalse(PreeditLayout.isWide('a'.code))
        assertFalse(PreeditLayout.isWide(' '.code))
        assertFalse(PreeditLayout.isWide(0x2500)) // box drawing
        assertTrue(PreeditLayout.isWide(0xAC00)) // 가
        assertTrue(PreeditLayout.isWide(0x4E00)) // 一
    }
}
