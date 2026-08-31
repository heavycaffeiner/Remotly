package com.remotly.app.bridge

import android.content.pm.PackageManager
import android.os.Build
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.specs.NativeRemotlyAppInfoSpec

// Build identity for the Settings screen. Read from the package manager rather
// than baked into JS, so the reported version cannot drift from the installed
// APK.
class RemotlyAppInfoModule(reactContext: ReactApplicationContext) :
    NativeRemotlyAppInfoSpec(reactContext) {

    override fun get(promise: Promise) {
        val ctx = reactApplicationContext
        val info = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            promise.reject(BridgeCodes.FAIL.toString(), "package info unavailable")
            return
        }
        val versionCode =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            }
        promise.resolve(
            Arguments.createMap().apply {
                putString("versionName", info.versionName ?: "")
                // A long does not survive the bridge as a number, and this is
                // display-only.
                putString("versionCode", versionCode.toString())
                putString("protocolVersion", PROTOCOL_VERSION)
                putInt("androidSdk", Build.VERSION.SDK_INT)
            },
        )
    }

    private companion object {
        // The wire protocol the app speaks, from docs/protocol.md. Not the app
        // version: they move independently.
        const val PROTOCOL_VERSION = "1.0.0"
    }
}
