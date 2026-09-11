package com.remotly.app.ui.theme

import android.app.Activity
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.remotly.app.settings.AppSettings

// Tones of one teal seed with a warm sand tertiary. Surfaces are near
// neutral so the terminal's own black grid does not sit inside a tinted frame.
private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD3E3),
    onPrimary = Color(0xFF00363E),
    primaryContainer = Color(0xFF004F5A),
    onPrimaryContainer = Color(0xFFA9EDFB),
    secondary = Color(0xFFB0CBD1),
    onSecondary = Color(0xFF1B343A),
    secondaryContainer = Color(0xFF324B51),
    onSecondaryContainer = Color(0xFFCCE7ED),
    tertiary = Color(0xFFDDC59F),
    onTertiary = Color(0xFF3D2F13),
    tertiaryContainer = Color(0xFF554528),
    onTertiaryContainer = Color(0xFFFAE2BA),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    background = Color(0xFF0E1315),
    onBackground = Color(0xFFDEE3E5),
    surface = Color(0xFF0E1315),
    onSurface = Color(0xFFDEE3E5),
    surfaceVariant = Color(0xFF3B4548),
    onSurfaceVariant = Color(0xFFBAC4C7),
    outline = Color(0xFF858E91),
    outlineVariant = Color(0xFF3B4548),
    inverseSurface = Color(0xFFDEE3E5),
    inverseOnSurface = Color(0xFF2B3133),
    inversePrimary = Color(0xFF006876),
    surfaceTint = Color(0xFF7FD3E3),
    surfaceDim = Color(0xFF0E1315),
    surfaceBright = Color(0xFF34393B),
    surfaceContainerLowest = Color(0xFF090E10),
    surfaceContainerLow = Color(0xFF161B1D),
    surfaceContainer = Color(0xFF1A2022),
    surfaceContainerHigh = Color(0xFF242A2C),
    surfaceContainerHighest = Color(0xFF2F3537),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF006876),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFA9EDFB),
    onPrimaryContainer = Color(0xFF001F25),
    secondary = Color(0xFF4A6268),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCE7ED),
    onSecondaryContainer = Color(0xFF051F24),
    tertiary = Color(0xFF6D5C3C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFFAE2BA),
    onTertiaryContainer = Color(0xFF251A02),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    background = Color(0xFFF5FAFC),
    onBackground = Color(0xFF171C1E),
    surface = Color(0xFFF5FAFC),
    onSurface = Color(0xFF171C1E),
    surfaceVariant = Color(0xFFDBE4E7),
    onSurfaceVariant = Color(0xFF3B4548),
    outline = Color(0xFF6C7679),
    outlineVariant = Color(0xFFBAC4C7),
    inverseSurface = Color(0xFF2B3133),
    inverseOnSurface = Color(0xFFECF1F3),
    inversePrimary = Color(0xFF7FD3E3),
    surfaceTint = Color(0xFF006876),
    surfaceDim = Color(0xFFD5DBDD),
    surfaceBright = Color(0xFFF5FAFC),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFEFF4F6),
    surfaceContainer = Color(0xFFE9EEF0),
    surfaceContainerHigh = Color(0xFFE3E9EB),
    surfaceContainerHighest = Color(0xFFDEE3E5),
)

// One step smaller than the Material defaults at the top of the scale. A
// phone screen holds a host list or a form, not a headline.
private val RemotlyTypography = Typography(
    headlineSmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.2.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelLarge = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp),
)

// The Material 3 shape scale, one step rounder at the small end so fields,
// chips, and menus match the cards and sheets around them.
private val RemotlyShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
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

        dark -> DarkColors
        else -> LightColors
    }
    // The status and navigation bar glyphs follow the app's theme, not the
    // system's: a dark app under a light system otherwise draws them black
    // on black.
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>()
                .firstOrNull() ?: return@SideEffect
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = colors,
        typography = RemotlyTypography,
        shapes = RemotlyShapes,
        content = content,
    )
}
