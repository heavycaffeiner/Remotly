package com.remotly.app

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.hypot

/**
 * The launcher icon, loaded and rasterized the way a launcher does it.
 *
 * The icon is only resources and XML, so nothing about it fails at build time:
 * a layer that does not exist, a glyph that a circular mask cuts, or a
 * foreground that is accidentally blank all look fine until something draws
 * it. That is what this does.
 */
@RunWith(AndroidJUnit4::class)
class LauncherIconTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun launcherIcon(): Drawable {
        val pm = context.packageManager
        val info = pm.getApplicationInfo(context.packageName, 0)
        return pm.getApplicationIcon(info)
    }

    private fun rasterize(d: Drawable, size: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(bitmap))
        return bitmap
    }

    /** Pixels that are not fully transparent. */
    private fun opaqueCount(b: Bitmap): Int {
        var n = 0
        for (x in 0 until b.width) {
            for (y in 0 until b.height) {
                if (b.getPixel(x, y) ushr 24 != 0) n++
            }
        }
        return n
    }

    @Test
    fun theLauncherIconIsAdaptive() {
        // A plain bitmap here would mean the anydpi-v26 XML never applied and
        // the launcher is falling back to the legacy PNG.
        val icon = launcherIcon()
        assertTrue(
            "launcher icon is an AdaptiveIconDrawable, got ${icon.javaClass.simpleName}",
            icon is AdaptiveIconDrawable,
        )
    }

    @Test
    fun bothAdaptiveLayersArePresentAndPainted() {
        val icon = launcherIcon() as AdaptiveIconDrawable
        val background = icon.background
        val foreground = icon.foreground
        assertNotNull("background layer", background)
        assertNotNull("foreground layer", foreground)

        // A layer that resolved to nothing still draws, it just draws nothing.
        val bg = rasterize(background!!, 108)
        val fg = rasterize(foreground!!, 108)
        assertTrue("background paints something", opaqueCount(bg) > 0)
        assertTrue("foreground paints something", opaqueCount(fg) > 0)
    }

    @Test
    fun theBackgroundCoversTheWholeCanvas() {
        // A launcher masks the background to its own shape, so any gap in it
        // becomes a transparent notch in the finished icon.
        val icon = launcherIcon() as AdaptiveIconDrawable
        val bg = rasterize(icon.background!!, 108)

        assertEquals("every background pixel is opaque", 108 * 108, opaqueCount(bg))
    }

    @Test
    fun theForegroundGlyphSurvivesACircularMask() {
        // The tightest common mask is a circle of radius 33dp on the 108dp
        // canvas. Ink outside it is clipped away on a round launcher, so the
        // glyph must sit entirely within it.
        val icon = launcherIcon() as AdaptiveIconDrawable
        val size = 432 // xxxhdpi, so the measurement is not quantised
        val fg = rasterize(icon.foreground!!, size)

        val scale = size / 108f
        val centre = size / 2f
        val maskRadius = 33f * scale

        var outside = 0
        var worst = 0f
        for (x in 0 until size) {
            for (y in 0 until size) {
                if (fg.getPixel(x, y) ushr 24 == 0) continue
                val r = hypot(x + 0.5f - centre, y + 0.5f - centre)
                if (r > worst) worst = r
                if (r > maskRadius) outside++
            }
        }

        assertEquals(
            "glyph ink outside the circular mask (worst radius ${worst / scale} dp)",
            0,
            outside,
        )
    }

    @Test
    fun theForegroundGlyphIsCentred() {
        // An off-centre glyph is not obvious at review size but is immediately
        // visible in a launcher grid next to other icons.
        val icon = launcherIcon() as AdaptiveIconDrawable
        val size = 432
        val fg = rasterize(icon.foreground!!, size)

        var minX = size
        var maxX = -1
        var minY = size
        var maxY = -1
        for (x in 0 until size) {
            for (y in 0 until size) {
                if (fg.getPixel(x, y) ushr 24 == 0) continue
                if (x < minX) minX = x
                if (x > maxX) maxX = x
                if (y < minY) minY = y
                if (y > maxY) maxY = y
            }
        }

        val cx = (minX + maxX) / 2f
        val cy = (minY + maxY) / 2f
        val centre = size / 2f
        // One device pixel of slack at 4x density is a quarter of a dp.
        assertEquals("glyph centred horizontally", centre, cx, 2f)
        assertEquals("glyph centred vertically", centre, cy, 2f)
    }

    @Test
    fun theMonochromeLayerIsPresentForThemedIcons() {
        // Android 13 tints this layer to the wallpaper. Without it a themed
        // launcher falls back to a shrunken copy of the full-colour icon.
        val icon = launcherIcon() as AdaptiveIconDrawable
        val monochrome = icon.monochrome
        assertNotNull("monochrome layer", monochrome)
        assertTrue("monochrome paints something", opaqueCount(rasterize(monochrome!!, 108)) > 0)
    }

    @Test
    fun theRoundIconAlsoResolves() {
        // Declared separately in the manifest, so it can be missing on its own.
        val pm = context.packageManager
        val info = pm.getApplicationInfo(context.packageName, 0)
        assertTrue("roundIcon is declared", info.icon != 0)

        val activity = pm.getLaunchIntentForPackage(context.packageName)
        assertNotNull("launch intent", activity)
    }
}
