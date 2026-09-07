package com.remotly.app.camera

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.remotly.app.specs.NativeRemotlyCameraSpec

// Clipboard reads for the terminal's paste actions, and the link out to this
// app's system settings page.
class RemotlyCameraModule(reactContext: ReactApplicationContext) :
    NativeRemotlyCameraSpec(reactContext) {

    private fun scanResult(value: String): WritableMap =
        Arguments.createMap().apply { putString("value", value) }

    /**
     * Reads plain text from the clipboard.
     *
     * Resolves "" when it holds nothing readable rather than rejecting: an
     * empty clipboard is an ordinary state, not an error. Bounded so a
     * pathological clipboard cannot be pasted into the terminal.
     */
    override fun readClipboard(promise: Promise) {
        mainHandler.post {
            val text = runCatching {
                val cm = reactApplicationContext.getSystemService(
                    Context.CLIPBOARD_SERVICE,
                ) as? android.content.ClipboardManager
                val clip = cm?.primaryClip
                if (clip == null || clip.itemCount == 0) {
                    ""
                } else {
                    clip.getItemAt(0)
                        .coerceToText(reactApplicationContext)
                        .toString()
                        .take(MAX_CLIPBOARD_CHARS)
                }
            }.getOrDefault("")
            promise.resolve(scanResult(text))
        }
    }

    override fun openAppSettings(promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.resolve(null)
            return
        }
        mainHandler.post {
            runCatching {
                val intent =
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", activity.packageName, null)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                activity.startActivity(intent)
            }
            promise.resolve(null)
        }
    }

    companion object {
        /** Far longer than any pasted host string or command; bounds the read. */
        private const val MAX_CLIPBOARD_CHARS = 8192
        private val mainHandler = Handler(Looper.getMainLooper())
    }
}
