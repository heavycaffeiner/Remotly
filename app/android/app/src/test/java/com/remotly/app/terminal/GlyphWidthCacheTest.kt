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

    private companion object {
        // The renderer's face indices: the four text styles, then the faces
        // that do not come from the style bits.
        const val FACE_REGULAR = 0
        const val FACE_BOLD = 1
        const val FACE_ITALIC = 2
        const val FACE_BOLD_ITALIC = 3
        const val FACE_SYMBOLS = TerminalFontSet.FACE_SYMBOLS
        const val FACE_CJK = TerminalFontSet.FACE_CJK
        const val FACE_CJK_BOLD = TerminalFontSet.FACE_CJK_BOLD
    }

    @Test
    fun returnsTheMeasuredWidth() {
        val cache = GlyphWidthCache()
        val paint = paintOf(12.5f)

        assertEquals(12.5f, cache.width("A".toCharArray(), 0, 1, FACE_REGULAR, paint), 0.001f)
    }

    @Test
    fun measuresOnceForRepeatsOfTheSameGlyph() {
        val cache = GlyphWidthCache()
        val paint = paintOf(8f)
        val chars = "AAAA".toCharArray()

        repeat(4) { i -> cache.width(chars, i, 1, FACE_REGULAR, paint) }

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

        val regular = cache.width(chars, 0, 1, FACE_REGULAR, paint)
        bolder = true
        val bold = cache.width(chars, 0, 1, FACE_BOLD, paint)
        val italic = cache.width(chars, 0, 1, FACE_ITALIC, paint)
        val boldItalic = cache.width(chars, 0, 1, FACE_BOLD_ITALIC, paint)

        assertEquals(8f, regular, 0.001f)
        assertEquals(10f, bold, 0.001f)
        assertEquals(10f, italic, 0.001f)
        assertEquals(10f, boldItalic, 0.001f)
        assertEquals(4, paint.calls)
    }

    @Test
    fun everyFaceKeepsItsOwnSlotForOneChar() {
        // The face is packed into the low bits of the key. Too narrow a field
        // would carry into the char bits, so one face would read back another
        // face's advance for the same character. Each face is measured at a
        // width only it returns, then every face is asked again.
        val cache = GlyphWidthCache()
        val faces = listOf(
            FACE_REGULAR, FACE_BOLD, FACE_ITALIC, FACE_BOLD_ITALIC,
            FACE_SYMBOLS, FACE_CJK, FACE_CJK_BOLD,
        )
        var current = 0
        val paint = CountingPaint { 10f + current }
        // A BMP char outside the ASCII tier, so this exercises the packed key.
        val chars = "\u4E00".toCharArray()

        for (face in faces) {
            current = face
            assertEquals(10f + face, cache.width(chars, 0, 1, face, paint), 0.001f)
        }
        // Nothing may be measured again, and each face must still answer with
        // the width it was measured at.
        val before = paint.calls
        for (face in faces) {
            assertEquals(10f + face, cache.width(chars, 0, 1, face, paint), 0.001f)
        }

        assertEquals(faces.size, before)
        assertEquals(before, paint.calls)
    }

    @Test
    fun everyFaceKeepsItsOwnSlotForAsciiAndClusters() {
        // The ASCII tier indexes as c * FACES + face and the cluster tier tags
        // the key with the face, so both have to hold every face apart too.
        val cache = GlyphWidthCache()
        val faces = listOf(
            FACE_REGULAR, FACE_BOLD, FACE_ITALIC, FACE_BOLD_ITALIC,
            FACE_SYMBOLS, FACE_CJK, FACE_CJK_BOLD,
        )
        var current = 0
        val paint = CountingPaint { 10f + current }
        val ascii = "A".toCharArray()
        val cluster = "\u1100\u1161".toCharArray()

        for (face in faces) {
            current = face
            assertEquals(10f + face, cache.width(ascii, 0, 1, face, paint), 0.001f)
            assertEquals(10f + face, cache.width(cluster, 0, 2, face, paint), 0.001f)
        }
        for (face in faces) {
            assertEquals(10f + face, cache.width(ascii, 0, 1, face, paint), 0.001f)
            assertEquals(10f + face, cache.width(cluster, 0, 2, face, paint), 0.001f)
        }

        assertEquals(faces.size * 2, paint.calls)
    }

    @Test
    fun clusteredTextIsCachedApartFromItsFirstChar() {
        val cache = GlyphWidthCache()
        val paint = CountingPaint { if (it.length > 1) 20f else 10f }
        // An NFD Hangul syllable: two chars, one drawn unit.
        val nfd = "\u1100\u1161".toCharArray()

        val first = cache.width(nfd, 0, 2, FACE_REGULAR, paint)
        val again = cache.width(nfd, 0, 2, FACE_REGULAR, paint)
        val single = cache.width(nfd, 0, 1, FACE_REGULAR, paint)

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

        val regular = cache.width(nfd, 0, 2, FACE_REGULAR, paint)
        bolder = true
        val bold = cache.width(nfd, 0, 2, FACE_BOLD, paint)

        assertEquals(20f, regular, 0.001f)
        assertEquals(22f, bold, 0.001f)
    }

    @Test
    fun clearingForcesAFreshMeasurement() {
        // The font size changed, so every stored advance is wrong.
        val cache = GlyphWidthCache()
        val paint = paintOf(8f)
        val chars = "A".toCharArray()

        cache.width(chars, 0, 1, FACE_REGULAR, paint)
        cache.clear()
        cache.width(chars, 0, 1, FACE_REGULAR, paint)

        assertEquals(2, paint.calls)
    }

    @Test
    fun emptyTextIsZeroWithoutMeasuring() {
        val cache = GlyphWidthCache()
        val paint = paintOf(8f)

        assertEquals(0f, cache.width(CharArray(0), 0, 0, FACE_REGULAR, paint), 0.001f)
        assertEquals(0, paint.calls)
    }

    @Test
    fun astralCharactersAreMeasuredAsOneCluster() {
        val cache = GlyphWidthCache()
        val paint = paintOf(20f)
        val emoji = "\uD83D\uDE00".toCharArray()

        assertEquals(20f, cache.width(emoji, 0, 2, FACE_REGULAR, paint), 0.001f)
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
            assertEquals(16f, cache.width(chars, 0, 1, FACE_REGULAR, paint), 0.001f)
            assertEquals(16f, cache.width(chars, 0, 1, FACE_REGULAR, paint), 0.001f)
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
            assertEquals((first + i).toFloat(), cache.width(chars, 0, 1, FACE_REGULAR, paint), 0.001f)
        }
        for (i in 0 until n) {
            val chars = charArrayOf((first + i).toChar())
            assertEquals((first + i).toFloat(), cache.width(chars, 0, 1, FACE_REGULAR, paint), 0.001f)
        }

        assertEquals(n, paint.calls)
    }
}
