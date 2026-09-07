package com.remotly.app.terminal

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect

/**
 * Draws Kitty graphics placements over the cell grid.
 *
 * The terminal core owns parsing, storage, and geometry; this only turns the
 * placements it reports into bitmaps and blits them. Bitmaps are cached by
 * image id and dropped when the core says the image changed, because a
 * retransmission can reuse an id with different pixels and nothing about the
 * dimensions would reveal it.
 */
class TerminalImages {

    private class Entry(val generation: Int, val bitmap: Bitmap)

    private val cache = HashMap<Int, Entry>()
    private val src = Rect()
    private val dst = Rect()

    /**
     * Draws every visible placement.
     *
     * [placements] is the flat array the native side produces; [cellWidth] and
     * [cellHeight] convert its grid coordinates to pixels. A placement whose
     * image has no pixels yet is skipped and drawn on a later frame, which is
     * how a transmission still in flight behaves.
     */
    fun draw(
        canvas: Canvas,
        handle: Long,
        placements: IntArray?,
        cellWidth: Int,
        cellHeight: Int,
    ) {
        if (placements == null || placements.size < FIELDS) {
            // Nothing on screen. The cache is kept: scrolling an image out of
            // the viewport and back must not re-decode it.
            return
        }

        var i = 0
        while (i + FIELDS <= placements.size) {
            val imageId = placements[i]
            val generation = placements[i + 1]
            val col = placements[i + 2]
            val row = placements[i + 3]
            val pixelWidth = placements[i + 6]
            val pixelHeight = placements[i + 7]
            val sourceX = placements[i + 8]
            val sourceY = placements[i + 9]
            val sourceWidth = placements[i + 10]
            val sourceHeight = placements[i + 11]
            i += FIELDS

            if (pixelWidth <= 0 || pixelHeight <= 0) continue
            if (sourceWidth <= 0 || sourceHeight <= 0) continue

            val bitmap = bitmapFor(handle, imageId, generation) ?: continue

            // Clamped to the bitmap: the core resolves the source rect against
            // the image it holds, and a stale cache entry could disagree.
            src.set(
                sourceX.coerceIn(0, bitmap.width),
                sourceY.coerceIn(0, bitmap.height),
                (sourceX + sourceWidth).coerceIn(0, bitmap.width),
                (sourceY + sourceHeight).coerceIn(0, bitmap.height),
            )
            if (src.isEmpty) continue

            // The column and row are viewport-relative and may be negative for
            // a placement scrolled partly above the top; Canvas clips the rest.
            val left = col * cellWidth
            val top = row * cellHeight
            dst.set(left, top, left + pixelWidth, top + pixelHeight)
            canvas.drawBitmap(bitmap, src, dst, null)
        }
    }

    /** Drops every cached bitmap. */
    fun clear() {
        for (e in cache.values) e.bitmap.recycle()
        cache.clear()
    }

    private fun bitmapFor(handle: Long, imageId: Int, generation: Int): Bitmap? {
        val cached = cache[imageId]
        if (cached != null && cached.generation == generation) return cached.bitmap

        val pixels = RemotlyTerminal.nativeImagePixels(handle, imageId) ?: return null
        if (pixels.size < 2) return null
        val width = pixels[0]
        val height = pixels[1]
        if (width <= 0 || height <= 0) return null
        if (pixels.size < 2 + width * height) return null

        val bitmap = try {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        } catch (_: OutOfMemoryError) {
            return null
        }
        bitmap.setPixels(pixels, 2, width, 0, 0, width, height)

        cached?.bitmap?.recycle()
        cache[imageId] = Entry(generation, bitmap)
        trim(imageId)
        return bitmap
    }

    /**
     * Bounds the cache, keeping whatever was drawn most recently.
     *
     * A terminal's image storage is capped natively, but a long session can
     * still cycle through many ids, and each cached bitmap is width * height *
     * 4 bytes of Java heap on top of that.
     */
    private fun trim(keep: Int) {
        if (cache.size <= MAX_CACHED) return
        val it = cache.entries.iterator()
        while (it.hasNext() && cache.size > MAX_CACHED) {
            val entry = it.next()
            if (entry.key == keep) continue
            entry.value.bitmap.recycle()
            it.remove()
        }
    }

    private companion object {
        /** Ints per placement, matching the native serializer. */
        const val FIELDS = 12

        /** Bitmaps held at once. Above any plausible screenful of images. */
        const val MAX_CACHED = 16
    }
}
