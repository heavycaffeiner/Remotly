package com.remotly.app.terminal

import android.graphics.Paint

/**
 * Advance widths for cell text, keyed by the glyph and its face.
 *
 * A cell's width does not change between frames, but a full-screen repaint
 * measures every occupied cell and measuring crosses into the text shaper.
 *
 * Three tiers, cheapest first: a flat table for printable ASCII, which is most
 * of what a terminal draws; an open-addressed table keyed by char and face for
 * the rest of the BMP, which answers CJK without boxing a key or building a
 * String; and a map for clusters of more than one char.
 *
 * Valid for one font size only. The owner clears it when the size changes.
 */
class GlyphWidthCache {

  private val ascii = FloatArray(ASCII_SLOTS * FACES)
  private val asciiSet = BooleanArray(ASCII_SLOTS * FACES)

  // Open addressing with linear probing. Keys are (char shl 2) or face, which
  // is never 0 for a drawable glyph, so 0 marks an empty slot.
  private var keys = IntArray(BMP_CAPACITY)
  private var values = FloatArray(BMP_CAPACITY)
  private var count = 0

  // Astral characters and clusters of more than one char.
  private val clusters = HashMap<String, Float>()

  /**
   * The advance width of [length] chars at [offset], measured with [paint] on
   * a miss.
   *
   * [paint] must already carry the face [face] names, because the measurement
   * is stored against it. Faces are [TerminalFontSet] indices, so a symbol or
   * CJK cell never reads back the width measured for the text face.
   */
  fun width(
    chars: CharArray,
    offset: Int,
    length: Int,
    face: Int,
    paint: Paint,
  ): Float {
    if (length <= 0) return 0f
    require(face in 0 until FACES) { "face out of range: $face" }

    if (length == 1) {
      val c = chars[offset].code
      if (c in ASCII_FIRST until ASCII_SLOTS) {
        val slot = c * FACES + face
        if (asciiSet[slot]) return ascii[slot]
        val w = paint.measureText(chars, offset, length)
        ascii[slot] = w
        asciiSet[slot] = true
        return w
      }
      return bmpWidth(chars, offset, (c shl FACE_BITS) or face, paint)
    }

    // A multi-char cluster: the String is built once per distinct glyph, not
    // once per draw.
    val key = FACE_TAGS[face] + String(chars, offset, length)
    clusters[key]?.let { return it }
    val w = paint.measureText(chars, offset, length)
    if (clusters.size < MAX_CLUSTERS) clusters[key] = w
    return w
  }

  private fun bmpWidth(chars: CharArray, offset: Int, key: Int, paint: Paint): Float {
    val mask = keys.size - 1
    var i = hash(key) and mask
    while (true) {
      val k = keys[i]
      if (k == key) return values[i]
      if (k == 0) break
      i = (i + 1) and mask
    }

    val w = paint.measureText(chars, offset, 1)
    keys[i] = key
    values[i] = w
    count++
    // Kept below three quarters full, past which linear probing degrades.
    if (count * 4 > keys.size * 3) grow()
    return w
  }

  private fun grow() {
    val oldKeys = keys
    val oldValues = values
    keys = IntArray(oldKeys.size * 2)
    values = FloatArray(oldKeys.size * 2)
    val mask = keys.size - 1
    for (j in oldKeys.indices) {
      val k = oldKeys[j]
      if (k == 0) continue
      var i = hash(k) and mask
      while (keys[i] != 0) i = (i + 1) and mask
      keys[i] = k
      values[i] = oldValues[j]
    }
  }

  // Fibonacci hashing: multiply and take the high bits, which spreads keys
  // that differ only in their low bits, as adjacent code points do.
  private fun hash(key: Int): Int = (key * PHI) ushr SHIFT

  /** Drops every measurement. Called when the font size changes. */
  fun clear() {
    asciiSet.fill(false)
    keys.fill(0)
    count = 0
    clusters.clear()
  }

  private companion object {
    // Face indices are packed into the low bits of a BMP key, so the field has
    // to be wide enough for every face TerminalFontSet can hand out: the four
    // text styles, the shared symbol face, and the CJK faces. Too narrow a
    // field would overflow into the char bits and alias one glyph's cached
    // width onto another's.
    const val FACE_BITS = 3
    const val FACES = 1 shl FACE_BITS

    /** Printable ASCII through the end of Latin-1. */
    const val ASCII_FIRST = 0x20
    const val ASCII_SLOTS = 0x100

    const val BMP_CAPACITY = 1024

    const val PHI = -1640531527 // 2654435769 as a signed int
    const val SHIFT = 16

    /**
     * Cap on distinct clusters held. A terminal drawing endlessly varied emoji
     * would otherwise grow this without bound; past the cap those cells are
     * measured each time, which is the old behaviour rather than a failure.
     */
    const val MAX_CLUSTERS = 4096

    // Prefixes that keep one cluster's faces apart in the map. Not characters
    // a terminal cell can hold.
    val FACE_TAGS = Array(FACES) { it.toChar().toString() }
  }
}
