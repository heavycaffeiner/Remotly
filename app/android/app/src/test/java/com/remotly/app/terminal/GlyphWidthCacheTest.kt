package com.remotly.app.terminal

import android.graphics.Paint
import org.junit.Assert.assertEquals
import org.junit.Test

// The cache stands between the draw pass and the text shaper. What matters is
// that it answers with the measured width and that it does not measure the
// same glyph twice at one font size.
class GlyphWidthCacheTest {

    // The real Paint measures nothing under a JVM test, so widths come from
    // here and every call is counted.
    private class CountingPaint(private val widths: (String) -> Float) : Paint() {
        var calls = 0

        override fun measureText(text: CharArray, index: Int, count: Int): Float {
            calls++
            return widths(String(text, index, count))
        }
    }

    private fun paintOf(width: Float) = CountingPaint { width }

    @Test
    fun returnsTheMeasuredWidth() {
        val cache = GlyphWidthCache()
        val paint = paintOf(12.5f)

        assertEquals(12.5f, cache.width("A".toCharArray(), 0, 1, false, false, paint), 0.001f)
    }

    @Test
    fun measuresOnceForRepeatsOfTheSameGlyph() {
        val cache = GlyphWidthCache()
        val paint = paintOf(8f)
        val chars = "AAAA".toCharArray()

        repeat(4) { i -> cache.width(chars, i, 1, false, false, paint) }

        assertEquals(1, paint.calls)
    }

    @Test
    fun facesAreCachedApart() {
        // A bold 'A' is wider than a regular one. Sharing one slot would draw
        // the bold glyph against the regular advance.
        val cache = GlyphWidthCache()
        var bolder = false
        val paint = CountingPaint { if (bolder) 10f else 8f }
        val chars = "A".toCharArray()

        val regular = cache.width(chars, 0, 1, false, false, paint)
        bolder = true
        val bold = cache.width(chars, 0, 1, true, false, paint)
        val italic = cache.width(chars, 0, 1, false, true, paint)
        val boldItalic = cache.width(chars, 0, 1, true, true, paint)

        assertEquals(8f, regular, 0.001f)
        assertEquals(10f, bold, 0.001f)
        assertEquals(10f, italic, 0.001f)
        assertEquals(10f, boldItalic, 0.001f)
        assertEquals(4, paint.calls)
    }

    @Test
    fun clusteredTextIsCachedApartFromItsFirstChar() {
        val cache = GlyphWidthCache()
        val paint = CountingPaint { if (it.length > 1) 20f else 10f }
        // An NFD Hangul syllable: two chars, one drawn unit.
        val nfd = "\u1100\u1161".toCharArray()

        val first = cache.width(nfd, 0, 2, false, false, paint)
        val again = cache.width(nfd, 0, 2, false, false, paint)
        val single = cache.width(nfd, 0, 1, false, false, paint)

        assertEquals(20f, first, 0.001f)
        assertEquals(20f, again, 0.001f)
        assertEquals(10f, single, 0.001f)
        assertEquals(2, paint.calls)
    }

    @Test
    fun clusterFacesAreCachedApart() {
        val cache = GlyphWidthCache()
        var bolder = false
        val paint = CountingPaint { if (bolder) 22f else 20f }
        val nfd = "\u1100\u1161".toCharArray()

        val regular = cache.width(nfd, 0, 2, false, false, paint)
        bolder = true
        val bold = cache.width(nfd, 0, 2, true, false, paint)

        assertEquals(20f, regular, 0.001f)
        assertEquals(22f, bold, 0.001f)
    }

    @Test
    fun clearingForcesAFreshMeasurement() {
        // The font size changed, so every stored advance is wrong.
        val cache = GlyphWidthCache()
        val paint = paintOf(8f)
        val chars = "A".toCharArray()

        cache.width(chars, 0, 1, false, false, paint)
        cache.clear()
        cache.width(chars, 0, 1, false, false, paint)

        assertEquals(2, paint.calls)
    }

    @Test
    fun emptyTextIsZeroWithoutMeasuring() {
        val cache = GlyphWidthCache()
        val paint = paintOf(8f)

        assertEquals(0f, cache.width(CharArray(0), 0, 0, false, false, paint), 0.001f)
        assertEquals(0, paint.calls)
    }

    @Test
    fun astralCharactersAreMeasuredAsOneCluster() {
        val cache = GlyphWidthCache()
        val paint = paintOf(20f)
        val emoji = "\uD83D\uDE00".toCharArray()

        assertEquals(20f, cache.width(emoji, 0, 2, false, false, paint), 0.001f)
        assertEquals(1, paint.calls)
    }

    @Test
    fun charsOutsideTheAsciiTableAreStillCached() {
        // CJK, Hangul, and the fullwidth forms all take the second tier. They
        // are the densest thing a terminal draws, so a miss on every one would
        // undo the cache.
        val cache = GlyphWidthCache()
        val paint = paintOf(16f)
        val glyphs = listOf("\uD55C", "\u4E00", "\uFF21", "\u2500", "\uFFFD")

        for (g in glyphs) {
            val chars = g.toCharArray()
            assertEquals(16f, cache.width(chars, 0, 1, false, false, paint), 0.001f)
            assertEquals(16f, cache.width(chars, 0, 1, false, false, paint), 0.001f)
        }

        assertEquals(glyphs.size, paint.calls)
    }

    @Test
    fun theSecondTierSurvivesGrowing() {
        // Enough distinct glyphs to force a rehash. Every one must still
        // answer with the width it was measured at.
        val cache = GlyphWidthCache()
        val paint = CountingPaint { it[0].code.toFloat() }
        val first = 0x4E00
        val n = 3000

        for (i in 0 until n) {
            val chars = charArrayOf((first + i).toChar())
            assertEquals((first + i).toFloat(), cache.width(chars, 0, 1, false, false, paint), 0.001f)
        }
        for (i in 0 until n) {
            val chars = charArrayOf((first + i).toChar())
            assertEquals((first + i).toFloat(), cache.width(chars, 0, 1, false, false, paint), 0.001f)
        }

        assertEquals(n, paint.calls)
    }
}
