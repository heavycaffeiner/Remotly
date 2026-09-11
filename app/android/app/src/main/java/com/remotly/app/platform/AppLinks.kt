package com.remotly.app.platform

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/**
 * Links out to the Android system that more than one screen needs: reading
 * the clipboard for a paste, and opening this app's own settings page.
 *
 * Kept as plain functions taking a [Context] so a screen or the terminal
 * pane can call them without a shared base class or a singleton to wire up.
 */

/**
 * Reads plain text from the clipboard.
 *
 * Null when there is nothing readable, which is an ordinary clipboard state
 * rather than an error.
 */
fun readClipboardText(context: Context): String? {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return null
    val clip = manager.primaryClip ?: return null
    if (clip.itemCount == 0) return null
    return clip.getItemAt(0).coerceToText(context)?.toString()
}

/** Opens this app's page in the system settings, where its permissions live. */
fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
        },
    )
}
