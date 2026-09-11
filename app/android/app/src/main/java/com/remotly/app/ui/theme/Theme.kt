package com.remotly.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.remotly.app.settings.AppSettings

/**
 * Rounder than Material's own scale, which is what carries the look here.
 *
 * The app is lists of cards and chips, and 12dp on a card reads as a
 * rectangle with the corners taken off. Only the two tokens the app actually
 * renders through are moved: cards take medium, chips take small. Buttons are
 * left alone because 20dp against a 40dp button is already the full round,
 * and dialogs sit at 28dp.
 */
private val RemotlyShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
)

/** True when the platform can derive a palette from the wallpaper. */
val dynamicColorSupported: Boolean
    get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

@Composable
fun RemotlyTheme(
    settings: AppSettings,
    content: @Composable () -> Unit,
) {
    val dark = when (settings.themeMode) {
        AppSettings.THEME_LIGHT -> false
        AppSettings.THEME_DARK -> true
        else -> isSystemInDarkTheme()
    }
    val context = LocalContext.current
    val colors = when {
        // Dynamic color is cosmetic. Where the platform has no palette to
        // give, the built-in scheme applies rather than the setting failing.
        settings.dynamicColor && dynamicColorSupported ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, shapes = RemotlyShapes, content = content)
}
