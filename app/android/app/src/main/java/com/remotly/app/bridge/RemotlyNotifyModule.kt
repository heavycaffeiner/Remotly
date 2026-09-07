package com.remotly.app.bridge

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.MainActivity
import com.remotly.app.R
import com.remotly.app.specs.NativeRemotlyNotifySpec

// Posts the desktop notifications a running program asks for with OSC 9 or
// OSC 777.
//
// The text crosses from the remote host, so it is bounded here rather than
// trusted: a program that emits a megabyte of body should not be able to hand
// that to the system notification manager.
class RemotlyNotifyModule(reactContext: ReactApplicationContext) :
    NativeRemotlyNotifySpec(reactContext) {

    override fun notify(title: String, body: String, promise: Promise) {
        val context = reactApplicationContext
        // Android 13 and later needs the runtime permission. Nothing requests
        // it on this path: a notification the user never asked for is not a
        // reason to interrupt them with a permission dialog, so it is simply
        // not shown.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            promise.resolve(false)
            return
        }
        if (body.isBlank() && title.isBlank()) {
            promise.resolve(false)
            return
        }

        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val shown = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remotly_notify)
            .setContentTitle(
                title.take(MAX_TITLE_CHARS).ifBlank { context.getString(R.string.app_name) },
            )
            .setContentText(body.take(MAX_BODY_CHARS))
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(body.take(MAX_BODY_CHARS)),
            )
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        val ok = runCatching {
            NotificationManagerCompat.from(context).notify(nextId(), shown)
        }.isSuccess
        promise.resolve(ok)
    }

    private companion object {
        const val CHANNEL_ID = "remotly-terminal-notify"
        const val CHANNEL_NAME = "Terminal notifications"

        /** Bounds on remote-supplied text, well past any sensible message. */
        const val MAX_TITLE_CHARS = 128
        const val MAX_BODY_CHARS = 1024

        /**
         * Notifications are distinct rather than replacing one another: two
         * builds finishing should both be visible.
         */
        private var counter = 0

        fun nextId(): Int {
            counter = (counter + 1) % 1000
            return 0x4E00 + counter
        }

        fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "Notifications a program running in the terminal sent"
                },
            )
        }
    }
}
