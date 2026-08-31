package com.remotly.app.bridge

import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.notify.EventNotifier
import com.remotly.app.notify.NotifyModule
import com.remotly.app.notify.NotifyPermissionFragment
import com.remotly.app.notify.currentPermissionState
import com.remotly.app.specs.NativeRemotlyNotifySpec

// Pure mapping for the notify module.
internal object NotifyBridge {
    // Repeated events for the same session replace one notification, so the
    // id is derived from the host and session.
    fun notificationId(hostId: String, sessionId: String): Int =
        EventNotifier.notificationId(hostId, sessionId)

    fun permissionResultMap(
        granted: Boolean,
        osEnabled: Boolean,
        requested: Boolean,
        lastResult: Boolean?,
    ): Map<String, Any?> = mapOf(
        "granted" to granted,
        "osEnabled" to osEnabled,
        "requested" to requested,
        "lastResult" to lastResult,
    )
}

// OS notifications (remotly.notify.*). The app layer has already deduped the
// event, honored the in-app toggle, and sanitized the text.
class RemotlyNotifyModule(reactContext: ReactApplicationContext) :
    NativeRemotlyNotifySpec(reactContext) {

    // A denied permission makes posting a silent success: the in-app banner
    // still carries the event.
    override fun post(
        hostId: String,
        sessionId: String,
        hostName: String,
        title: String,
        text: String,
        promise: Promise,
    ) {
        val context = reactApplicationContext
        val id = NotifyBridge.notificationId(hostId, sessionId)
        val posted = EventNotifier.post(context, id, hostName, title, text)
        promise.resolve(Arguments.makeNativeMap(mapOf("posted" to posted)))
    }

    override fun permission(request: Boolean, promise: Promise) {
        val context = reactApplicationContext
        val granted = currentPermissionState(context)
        var requested = false
        if (request && !granted) {
            val activity = reactApplicationContext.currentActivity as? FragmentActivity
            if (activity != null && !activity.isFinishing) {
                activity.supportFragmentManager
                    .beginTransaction()
                    .add(NotifyPermissionFragment { }, "remotly-notify-permission")
                    .commitNow()
                requested = true
            }
        }
        promise.resolve(
            Arguments.makeNativeMap(
                NotifyBridge.permissionResultMap(
                    granted = granted,
                    osEnabled = EventNotifier.osEnabled(context),
                    requested = requested,
                    lastResult = NotifyModule.lastPermissionResult,
                ),
            ),
        )
    }
}
