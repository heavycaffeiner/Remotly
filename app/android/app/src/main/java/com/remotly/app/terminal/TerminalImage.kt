package com.remotly.app.terminal

import android.graphics.Bitmap
import android.graphics.BitmapFactory

/**
 * PNG decoding for the Kitty graphics protocol.
 *
 * The NDK ships no PNG decoder, so the terminal core calls back into this
 * through JNI. Kept as a static seam with a flat return type because that is
 * what the C side can consume without building objects.
 */
object TerminalImage {

    /**
     * Decodes a PNG to ARGB pixels.
     *
     * Returns [width, height, pixels...] or null when the bytes are not a
     * decodable image. The payload arrives from the remote host, so a failure
     * is an ordinary outcome rather than an error worth propagating.
     *
     * Bounded: an image larger than the cap is refused rather than decoded,
     * since the decoded size is four bytes per pixel regardless of how small
     * the compressed form was.
     */
    @JvmStatic
    fun decodePng(data: ByteArray): IntArray? {
        if (data.isEmpty()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (width.toLong() * height > MAX_PIXELS) return null

        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size, opts)
            ?: return null
        return try {
            val out = IntArray(2 + width * height)
            out[0] = bitmap.width
            out[1] = bitmap.height
            bitmap.getPixels(out, 2, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            out
        } catch (_: IllegalArgumentException) {
            null
        } catch (_: OutOfMemoryError) {
            // A decode that does not fit is a dropped image, not a dead app.
            null
        } finally {
            bitmap.recycle()
        }
    }

    /** Matches the storage cap on the native side: 32 MiB of RGBA. */
    private const val MAX_PIXELS = 8L * 1024 * 1024
}
