package com.remotly.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.terminal.TerminalThemes
import com.remotly.app.ui.screens.TerminalThemePreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalThemePreviewClipTest {
    @get:Rule val compose = createComposeRule()

    // A fill that ignores the preview bounds paints over earlier siblings and later ones
    // that draw no background of their own, which hid the Settings section tabs.
    @Test
    fun previewBackgroundStaysInsideItsOwnBounds() {
        compose.setContent {
            MaterialTheme {
                Column(Modifier.fillMaxWidth()) {
                    Box(Modifier.fillMaxWidth().height(80.dp).background(Color.White).testTag("above"))
                    TerminalThemePreview(TerminalThemes.Nord)
                    Box(Modifier.fillMaxWidth().height(80.dp).testTag("below"))
                }
            }
        }
        compose.waitForIdle()
        val above = compose.onNodeWithTag("above").captureToImage().asAndroidBitmap()
        assertEquals(
            "the earlier sibling must stay visible",
            Color.White.toArgb(), above.getPixel(above.width / 2, above.height / 2),
        )
        val below = compose.onNodeWithTag("below").captureToImage().asAndroidBitmap()
        assertNotEquals(
            "nothing below the preview may take its background",
            TerminalThemes.Nord.background, below.getPixel(below.width / 2, below.height / 2),
        )
    }
}
