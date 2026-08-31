package com.remotly.app.notify

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment

// Holds the runtime state of the notification permission flow. The request
// itself is asynchronous (the system dialog decides), so the bridge reports
// the current state plus the last known dialog result, and the app re-queries
// when it needs a fresh answer.
object NotifyModule {
    // Set once by the permission fragment when the dialog result arrives.
    // null means no dialog result has been seen in this process.
    @Volatile
    var lastPermissionResult: Boolean? = null
}

// Requests POST_NOTIFICATIONS and reports the result exactly once. commitNow
// is required so the request is issued synchronously with the start call.
internal class NotifyPermissionFragment(
    private val onResult: (granted: Boolean) -> Unit,
) : Fragment() {

    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            finish(true)
            return
        }
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (reported) return
        reported = true
        val granted = grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        finish(granted)
    }

    private fun finish(granted: Boolean) {
        if (reported) return
        reported = true
        NotifyModule.lastPermissionResult = granted
        onResult(granted)
    }

    companion object {
        private const val REQUEST_CODE = 0x2B
    }
}

internal fun currentPermissionState(context: android.content.Context): Boolean =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        true
    } else {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }
