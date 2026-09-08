package com.remotly.app.bridge

import android.content.Context
import android.util.TypedValue
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.google.android.material.R
import com.google.android.material.color.DynamicColors
import com.remotly.app.specs.NativeRemotlyDynamicColorsSpec
// Material You support. Android 12 and later keeps a wallpaper-derived color
// scheme in the system; the Material library exposes it through the MD3
// dynamic themes. This module reads the semantic color roles out of those
// themes so the JS theme layer can use the same colors Paper draws with.
//
// The read goes through wrapContextIfAvailable with an explicit overlay:
// the host theme is AppCompat, not Material3, so the theme attribute the
// no-argument overload looks for is never set here.
class RemotlyDynamicColorsModule(reactContext: ReactApplicationContext) :
    NativeRemotlyDynamicColorsSpec(reactContext) {

    override fun get(promise: Promise) {
        val ctx = reactApplicationContext
        val result = if (!DynamicColors.isDynamicColorAvailable()) {
            Arguments.createMap().apply { putBoolean("available", false) }
        } else {
            Arguments.createMap().apply {
                putBoolean("available", true)
                putMap("light", schemeColors(ctx, true))
                putMap("dark", schemeColors(ctx, false))
            }
        }
        promise.resolve(result)
    }

    // Resolves the MD3 dynamic theme of one scheme against the app context
    // and collects the color roles the JS theme understands.
    private fun schemeColors(ctx: Context, light: Boolean): WritableMap {
        val overlay = if (light) R.style.Theme_Material3_DynamicColors_Light
            else R.style.Theme_Material3_DynamicColors_Dark
        val themed = DynamicColors.wrapContextIfAvailable(ctx, overlay)
        return Arguments.createMap().apply {
            putString("primary", colorOf(themed, R.attr.colorPrimary))
            putString("onPrimary", colorOf(themed, R.attr.colorOnPrimary))
            putString("primaryContainer", colorOf(themed, R.attr.colorPrimaryContainer))
            putString("onPrimaryContainer", colorOf(themed, R.attr.colorOnPrimaryContainer))
            putString("secondary", colorOf(themed, R.attr.colorSecondary))
            putString("onSecondary", colorOf(themed, R.attr.colorOnSecondary))
            putString("secondaryContainer", colorOf(themed, R.attr.colorSecondaryContainer))
            putString("onSecondaryContainer", colorOf(themed, R.attr.colorOnSecondaryContainer))
            putString("tertiary", colorOf(themed, R.attr.colorTertiary))
            putString("onTertiary", colorOf(themed, R.attr.colorOnTertiary))
            putString("tertiaryContainer", colorOf(themed, R.attr.colorTertiaryContainer))
            putString("onTertiaryContainer", colorOf(themed, R.attr.colorOnTertiaryContainer))
            // The background role is a platform attribute; Material does not
            // redeclare it, and `R` here is the Material one.
            putString("background", colorOf(themed, android.R.attr.colorBackground))
            putString("onBackground", colorOf(themed, R.attr.colorOnBackground))
            putString("surface", colorOf(themed, R.attr.colorSurface))
            putString("onSurface", colorOf(themed, R.attr.colorOnSurface))
            putString("surfaceVariant", colorOf(themed, R.attr.colorSurfaceVariant))
            putString("onSurfaceVariant", colorOf(themed, R.attr.colorOnSurfaceVariant))
            putString("outline", colorOf(themed, R.attr.colorOutline))
            putString("outlineVariant", colorOf(themed, R.attr.colorOutlineVariant))
            putString("error", colorOf(themed, R.attr.colorError))
            putString("onError", colorOf(themed, R.attr.colorOnError))
            putString("errorContainer", colorOf(themed, R.attr.colorErrorContainer))
            putString("onErrorContainer", colorOf(themed, R.attr.colorOnErrorContainer))
        }
    }

    // ARGB as #AARRGGBB, or an empty string when the role is not a concrete
    // color (a theme reference). The JS side drops non-hex values.
    private fun colorOf(ctx: Context, attr: Int): String {
        val outValue = TypedValue()
        if (!ctx.theme.resolveAttribute(attr, outValue, true)) return ""
        val argb =
            if (isColorType(outValue)) {
                outValue.data
            } else if (outValue.resourceId != 0) {
                // The overlay maps the role to a color resource, and that
                // resource may itself reference the system palette, so
                // resolve it against the theme rather than as a bare
                // resource id.
                try {
                    ctx.resources.getColor(outValue.resourceId, ctx.theme)
                } catch (_: Exception) {
                    return ""
                }
            } else {
                return ""
            }
        if (argb == 0) return ""
        return String.format("#%08X", argb)
    }

    private fun isColorType(v: TypedValue): Boolean =
        v.type == TypedValue.TYPE_INT_COLOR_ARGB8 ||
            v.type == TypedValue.TYPE_INT_COLOR_ARGB4 ||
            v.type == TypedValue.TYPE_INT_COLOR_RGB8
}
