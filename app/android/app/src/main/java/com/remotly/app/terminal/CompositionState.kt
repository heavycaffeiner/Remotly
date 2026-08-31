package com.remotly.app.terminal

import java.text.BreakIterator

/**
 * IME preedit, as immutable state.
 *
 * The selection indices are UTF-16 offsets into [text], which is what the IME
 * reports. Rendering needs grapheme clusters and terminal cell widths, so the
 * mapping between the two lives here rather than in the draw pass.
 */
data class CompositionState(
    val text: String,
    val selectionStartUtf16: Int,
    val selectionEndUtf16: Int,
) {
    val isEmpty: Boolean get() = text.isEmpty()

    companion object {
        val NONE = CompositionState("", 0, 0)

        /**
         * Builds a state from an IME callback.
         *
         * `newCursorPosition` follows the InputConnection contract: greater
         * than zero counts forward from the end of the inserted text, zero or
         * less counts backward from its start. Out-of-range values are clamped
         * rather than rejected, because an IME sending one must not crash the
         * terminal.
         */
        fun of(text: CharSequence?, newCursorPosition: Int): CompositionState {
            val s = sanitizeUtf16(text)
            if (s.isEmpty()) return NONE
            val caret = if (newCursorPosition > 0) {
                s.length + newCursorPosition - 1
            } else {
                newCursorPosition
            }
            val clamped = caret.coerceIn(0, s.length)
            return CompositionState(s, clamped, clamped)
        }

        /**
         * Android's CharSequence contract does not promise well-formed UTF-16.
         * Several real IMEs briefly expose a leading or trailing surrogate
         * while replacing an emoji/candidate. Canvas, BreakIterator and JNI do
         * not all handle that transient value consistently, so normalize it at
         * the one boundary where foreign text enters the terminal.
         */
        fun sanitizeUtf16(text: CharSequence?): String {
            if (text == null || text.isEmpty()) return ""
            val out = StringBuilder(text.length)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    Character.isHighSurrogate(c) && i + 1 < text.length &&
                        Character.isLowSurrogate(text[i + 1]) -> {
                        out.append(c).append(text[i + 1])
                        i += 2
                    }
                    Character.isSurrogate(c) -> {
                        out.append('\uFFFD')
                        i += 1
                    }
                    else -> {
                        out.append(c)
                        i += 1
                    }
                }
            }
            return out.toString()
        }

        /**
         * True when every cluster is a complete Hangul syllable.
         *
         * A Korean IME builds one syllable at a time: it holds the jamo in the
         * preedit while the syllable can still grow (a trailing consonant may
         * yet arrive, or move to the next syllable), and the composed syllable
         * appears in the Hangul Syllables block once it is well formed.
         * Isolated jamo from the Compatibility block mean the syllable is
         * still being assembled.
         */
        fun isCompleteHangul(text: String): Boolean {
            if (text.isEmpty()) return false
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                if (cp !in 0xAC00..0xD7A3) return false
                i += Character.charCount(cp)
            }
            return true
        }
    }
}

/**
 * Terminal cell widths for preedit text.
 *
 * The renderer draws preedit itself, so it needs the same width convention the
 * terminal uses for its own cells: a CJK grapheme occupies two columns, an
 * ASCII one occupies one, and a combining sequence occupies whatever its base
 * character does.
 */
object PreeditLayout {

    /** One grapheme cluster and the columns it occupies. */
    data class Cluster(val text: String, val cells: Int, val startUtf16: Int)

    /**
     * Splits text into grapheme clusters.
     *
     * Uses a break iterator rather than iterating code points, so an NFD
     * Hangul syllable or an emoji with a variation selector stays one drawn
     * unit instead of scattering across columns.
     */
    fun clusters(text: String): List<Cluster> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<Cluster>(text.length)
        val it = BreakIterator.getCharacterInstance()
        it.setText(text)
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val chunk = text.substring(start, end)
            out.add(Cluster(chunk, cellWidth(chunk), start))
            start = end
            end = it.next()
        }
        return out
    }

    /** The columns a grapheme cluster occupies. */
    fun cellWidth(cluster: String): Int {
        if (cluster.isEmpty()) return 0
        val cp = cluster.codePointAt(0)
        return if (isWide(cp)) 2 else 1
    }

    /** Total columns for a run of text. */
    fun totalCells(text: String): Int = clusters(text).sumOf { it.cells }

    /**
     * Columns before a UTF-16 offset.
     *
     * The caret sits on a grapheme boundary, so an offset landing inside a
     * cluster resolves to that cluster's start.
     */
    fun cellsBefore(text: String, utf16Offset: Int): Int {
        if (utf16Offset <= 0) return 0
        var cells = 0
        for (c in clusters(text)) {
            // Count a cluster only once the offset is past all of it. An offset
            // that lands inside one, which happens between the jamo of an NFD
            // syllable, snaps the caret to that cluster's start.
            val end = c.startUtf16 + c.text.length
            if (end > utf16Offset) break
            cells += c.cells
        }
        return cells
    }

    /**
     * True for a code point that occupies two terminal columns.
     *
     * Follows the East Asian Wide and Fullwidth ranges, matching what the
     * terminal core applies to its own cells. Kept as ranges rather than a
     * table lookup because it only has to agree at the boundaries the fixtures
     * exercise.
     */
    fun isWide(cp: Int): Boolean = when {
        cp < 0x1100 -> false
        cp <= 0x115F -> true // Hangul Jamo initial consonants
        cp == 0x2329 || cp == 0x232A -> true
        cp in 0x2E80..0x303E -> true // CJK radicals, Kangxi, CJK symbols
        cp in 0x3041..0x33FF -> true // kana, Hangul Compatibility Jamo, CJK
        cp in 0x3400..0x4DBF -> true // CJK Extension A
        cp in 0x4E00..0x9FFF -> true // CJK Unified Ideographs
        cp in 0xA000..0xA4CF -> true // Yi
        cp in 0xA960..0xA97F -> true // Hangul Jamo Extended-A
        cp in 0xAC00..0xD7A3 -> true // Hangul syllables
        cp in 0xF900..0xFAFF -> true // CJK compatibility ideographs
        cp in 0xFE10..0xFE19 -> true // vertical forms
        cp in 0xFE30..0xFE6F -> true // CJK compatibility forms
        cp in 0xFF00..0xFF60 -> true // fullwidth forms
        cp in 0xFFE0..0xFFE6 -> true // fullwidth signs
        cp in 0x1F300..0x1F64F -> true // emoji
        cp in 0x1F900..0x1F9FF -> true // supplemental emoji
        cp in 0x20000..0x3FFFD -> true // CJK Extension B and beyond
        else -> false
    }
}
