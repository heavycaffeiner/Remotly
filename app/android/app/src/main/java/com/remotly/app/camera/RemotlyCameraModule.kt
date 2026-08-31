package com.remotly.app.camera

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.remotly.app.specs.NativeRemotlyCameraSpec

// The camera permission bridge. The pairing screen calls
// requestCameraPermission before mounting the QR scanner; the result decides
// between the scanner and the manual-entry path.
//
// The request runs from a retained, tag-addressable fragment using the
// Activity Result API. Concurrent callers share one in-flight request instead
// of stacking duplicate fragments and duplicate system dialogs.
class RemotlyCameraModule(reactContext: ReactApplicationContext) :
    NativeRemotlyCameraSpec(reactContext) {

    override fun getCameraPermissionStatus(promise: Promise) {
        val activity = reactApplicationContext.currentActivity as? FragmentActivity
        val context = activity ?: reactApplicationContext
        val status = permissionStatus(context, activity)
        promise.resolve(status.copyMap())
    }

    override fun requestCameraPermission(promise: Promise) {
        val activity = reactApplicationContext.currentActivity as? FragmentActivity
        if (activity == null) {
            // No activity means no dialog is possible, but the user can still
            // retry from the screen, so this is not a permanent denial.
            promise.resolve(result(granted = false, canAskAgain = true))
            return
        }
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            promise.resolve(result(granted = true, canAskAgain = false))
            return
        }
        // A rationale is false both before the first request and after a
        // permanent denial. Persisting only whether *this app* has asked
        // distinguishes those two cases without guessing from Android's
        // ambiguous rationale answer.
        if (hasRequested(activity) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(
                activity,
                Manifest.permission.CAMERA,
            )
        ) {
            promise.resolve(result(granted = false, canAskAgain = false))
            return
        }
        mainHandler.post {
            val manager = activity.supportFragmentManager
            if (manager.isStateSaved || activity.isFinishing) {
                // Committing after state save throws. Report a retryable
                // outcome rather than crashing the app.
                promise.resolve(result(granted = false, canAskAgain = true))
                return@post
            }
            val existing = manager.findFragmentByTag(FRAGMENT_TAG) as? CameraPermissionFragment
            if (existing != null) {
                existing.addWaiter(promise)
                return@post
            }
            val fragment = CameraPermissionFragment()
            fragment.addWaiter(promise)
            manager.beginTransaction().add(fragment, FRAGMENT_TAG).commit()
        }
    }

    /**
     * Launches Google's code scanner.
     *
     * It runs as its own activity and owns the camera, the preview, and the
     * camera permission. Hosting a preview inside a Fabric view meant supplying
     * a surface and a lifecycle that React's view tree does not have, which is
     * what never worked.
     *
     * Resolves "" when the user backs out. Rejects when Play services cannot
     * supply the module, which is the caller's signal to offer the paste path.
     */
    override fun scanCode(promise: Promise) {
        val activity = reactApplicationContext.currentActivity
        if (activity == null) {
            promise.reject(SCAN_UNAVAILABLE, "no activity")
            return
        }
        mainHandler.post {
            val options = GmsBarcodeScannerOptions.Builder()
                .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                // Auto-zoom helps with the small, dense codes `remotly pair`
                // prints into a terminal.
                .enableAutoZoom()
                .build()
            runCatching { GmsBarcodeScanning.getClient(activity, options) }
                .onSuccess { client ->
                    client.startScan()
                        .addOnSuccessListener { barcode ->
                            promise.resolve(scanResult(barcode.rawValue ?: ""))
                        }
                        .addOnCanceledListener { promise.resolve(scanResult("")) }
                        .addOnFailureListener { e ->
                            promise.reject(SCAN_UNAVAILABLE, e.message ?: "scan failed")
                        }
                }
                .onFailure { e ->
                    promise.reject(SCAN_UNAVAILABLE, e.message ?: "scanner unavailable")
                }
        }
    }

    private fun scanResult(value: String): WritableMap =
        Arguments.createMap().apply { putString("value", value) }

    /**
     * Reads plain text from the clipboard.
     *
     * Resolves "" when it holds nothing readable rather than rejecting: an
     * empty clipboard is an ordinary state, not an error. Bounded so a
     * pathological clipboard cannot be pasted into a pairing field.
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

    /**
     * Reads an image from the clipboard as base64 PNG.
     *
     * Everything is re-encoded to PNG so the caller handles one format rather
     * than whatever the source application put on the clipboard. Decoding is
     * bounded: an oversized bitmap is scaled down rather than allocated in
     * full, since a clipboard image comes from outside the app.
     */
    override fun readClipboardImage(promise: Promise) {
        mainHandler.post {
            val result = runCatching { decodeClipboardImage() }
                .getOrNull()
            if (result == null) {
                promise.resolve(
                    Arguments.makeNativeMap(
                        mapOf("data" to "", "width" to 0.0, "height" to 0.0),
                    ),
                )
                return@post
            }
            promise.resolve(
                Arguments.makeNativeMap(
                    mapOf(
                        "data" to result.first,
                        "width" to result.second.toDouble(),
                        "height" to result.third.toDouble(),
                    ),
                ),
            )
        }
    }

    private fun decodeClipboardImage(): Triple<String, Int, Int>? {
        val cm = reactApplicationContext.getSystemService(
            Context.CLIPBOARD_SERVICE,
        ) as? android.content.ClipboardManager ?: return null
        val clip = cm.primaryClip ?: return null
        if (clip.itemCount == 0) return null

        val uri = clip.getItemAt(0).uri ?: return null
        val resolver = reactApplicationContext.contentResolver

        // Measure first so a hostile or simply huge image is never decoded at
        // full size.
        val bounds = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, bounds)
        } ?: return null
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (
            (bounds.outWidth / sample) > MAX_IMAGE_EDGE ||
            (bounds.outHeight / sample) > MAX_IMAGE_EDGE
        ) {
            sample *= 2
        }

        val opts = android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
        }
        val bitmap = resolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        } ?: return null

        return try {
            val out = java.io.ByteArrayOutputStream()
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            Triple(
                android.util.Base64.encodeToString(
                    out.toByteArray(),
                    android.util.Base64.NO_WRAP,
                ),
                bitmap.width,
                bitmap.height,
            )
        } finally {
            bitmap.recycle()
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

    // Runs one CAMERA request and reports it to every promise waiting on it.
    // Removes itself once the result is delivered, so re-entering the screen
    // after a denial does not accumulate fragments.
    class CameraPermissionFragment : Fragment() {
        private val waiters = mutableListOf<Promise>()
        private var launcher: ActivityResultLauncher<String>? = null
        private var reported = false
        private var launched = false

        fun addWaiter(promise: Promise) {
            if (reported) {
                promise.resolve(currentResult())
                return
            }
            waiters.add(promise)
        }

        override fun onCreate(savedInstanceState: Bundle?) {
            super.onCreate(savedInstanceState)
            retainInstance = false
            launcher =
                registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
                    report(granted)
                }
        }

        override fun onStart() {
            super.onStart()
            if (launched) return
            launched = true
            context?.let { markRequested(it) }
            launcher?.launch(Manifest.permission.CAMERA)
        }

        override fun onDestroy() {
            // The activity can be torn down before a result arrives. Resolve
            // every waiter exactly once so no promise is left pending.
            if (!reported) report(granted = false)
            super.onDestroy()
        }

        private fun report(granted: Boolean) {
            if (reported) return
            reported = true
            val payload = resultFor(granted)
            val pending = waiters.toList()
            waiters.clear()
            for (p in pending) p.resolve(payload.copyMap())
            removeSelf()
        }

        private fun currentResult(): WritableMap {
            val ctx = context ?: return result(granted = false, canAskAgain = true)
            val granted =
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED
            return resultFor(granted).copyMap()
        }

        // After a denial, a false shouldShowRequestPermissionRationale means the
        // system will not ask again and only app settings can change it.
        private fun resultFor(granted: Boolean): ResultPayload {
            val ctx = context ?: return ResultPayload(granted, canAskAgain = true)
            return permissionStatus(ctx, activity, granted)
        }

        private fun removeSelf() {
            val manager = activity?.supportFragmentManager ?: return
            if (manager.isStateSaved || manager.isDestroyed) return
            runCatching { manager.beginTransaction().remove(this).commitAllowingStateLoss() }
        }
    }

    // A WritableMap can only cross the bridge once, so the payload is kept as
    // plain data and a fresh map is built per waiter.
    data class ResultPayload(val granted: Boolean, val canAskAgain: Boolean) {
        fun copyMap(): WritableMap = result(granted, canAskAgain)
    }

    companion object {
        /** Play services could not supply the scanner; offer the paste path. */
        const val SCAN_UNAVAILABLE = "scan_unavailable"

        /** A pairing link is far shorter than this; the cap bounds the read. */
        private const val MAX_CLIPBOARD_CHARS = 8192

        /** Longest edge kept when decoding a clipboard image. */
        private const val MAX_IMAGE_EDGE = 2048
        private const val FRAGMENT_TAG = "remotly-cam-perm"
        private const val PREFS_NAME = "remotly-camera-permission"
        private const val REQUESTED_KEY = "requested"
        private val mainHandler = Handler(Looper.getMainLooper())

        private fun hasRequested(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(REQUESTED_KEY, false)

        private fun markRequested(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(REQUESTED_KEY, true)
                .apply()
        }

        private fun permissionStatus(
            context: Context,
            activity: FragmentActivity?,
            granted: Boolean =
                ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                    PackageManager.PERMISSION_GRANTED,
        ): ResultPayload {
            if (granted) return ResultPayload(granted = true, canAskAgain = false)
            if (activity == null || !hasRequested(context)) {
                return ResultPayload(granted = false, canAskAgain = true)
            }
            return ResultPayload(
                granted = false,
                canAskAgain =
                    ActivityCompat.shouldShowRequestPermissionRationale(
                        activity,
                        Manifest.permission.CAMERA,
                    ),
            )
        }

        private fun result(granted: Boolean, canAskAgain: Boolean): WritableMap =
            Arguments.createMap().apply {
                putBoolean("granted", granted)
                putBoolean("canAskAgain", canAskAgain)
            }
    }
}
