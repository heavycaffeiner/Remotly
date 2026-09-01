package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

// The classifier decides which bundled face draws a cell. A codepoint routed
// to the wrong face is drawn from a font that has no glyph for it, which is a
// blank cell or a tofu box on a real screen.
class GlyphKindTest {

    @Test
    fun asciiAndLatinAreText() {
        for (c in listOf('A', 'z', '0', '{', ' ', '~', 'é', 'Ω', 'д')) {
            assertEquals("U+%04X".format(c.code), GlyphKind.TEXT, GlyphKind.of(c.code))
        }
    }

    @Test
    fun boxDrawingAndBlocksStayOnTheTextFace() {
        // These define the grid in a TUI and are subset into the text faces,
        // not the symbol face. Routing them elsewhere would change the metrics
        // every box-drawing program depends on.
        for (cp in listOf(0x2500, 0x2502, 0x250C, 0x2588, 0x2591, 0x25A0)) {
            assertEquals("U+%04X".format(cp), GlyphKind.TEXT, GlyphKind.of(cp))
        }
    }

    @Test
    fun nerdFontRangesAreSymbols() {
        // One from each range the terminal actually draws: Powerline, Seti,
        // Devicons, Codicons, Octicons, and Material in the supplementary
        // plane.
        for (cp in listOf(0xE0B0, 0xE0B2, 0xE5FA, 0xE702, 0xEA60, 0xF400, 0xF0001)) {
            assertEquals("U+%04X".format(cp), GlyphKind.SYMBOL, GlyphKind.of(cp))
        }
    }

    @Test
    fun cjkRangesGoToTheCjkFace() {
        for (cp in listOf(
            0x1100, // Hangul jamo
            0xAC00, // Hangul syllable
            0x3042, // Hiragana
            0x30A2, // Katakana
            0x4E00, // Han, URO
            0x3400, // Han, Extension A
            0xF900, // Compatibility ideograph
            0x3001, // CJK punctuation
            0xFF21, // Fullwidth Latin
        )) {
            assertEquals("U+%04X".format(cp), GlyphKind.CJK, GlyphKind.of(cp))
        }
    }

    @Test
    fun theBoundariesOfEachRangeAreClassifiedWithIt() {
        // Off-by-one here silently moves a whole block onto the wrong face.
        assertEquals(GlyphKind.SYMBOL, GlyphKind.of(0xE000))
        assertEquals(GlyphKind.SYMBOL, GlyphKind.of(0xF8FF))
        assertEquals(GlyphKind.CJK, GlyphKind.of(0xAC00))
        assertEquals(GlyphKind.CJK, GlyphKind.of(0xD7A3))
        assertEquals(GlyphKind.CJK, GlyphKind.of(0x4E00))
        assertEquals(GlyphKind.CJK, GlyphKind.of(0x9FFF))
    }

    @Test
    fun uncoveredCodepointsFallBackToText() {
        // Nothing bundled covers these, so they take the text face and reach
        // the platform font the way they did before any face was bundled.
        for (cp in listOf(0x0600, 0x0900, 0x1F600, 0x20000)) {
            assertEquals("U+%04X".format(cp), GlyphKind.TEXT, GlyphKind.of(cp))
        }
    }
}
