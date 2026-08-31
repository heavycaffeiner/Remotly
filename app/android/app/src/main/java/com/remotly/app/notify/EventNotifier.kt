package com.remotly.app.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import android.Manifest
import com.remotly.app.R

// Posts terminal event notifications (bell, pattern match, completion) to the
// OS.
//
// The app layer owns when to notify: it dedupes by session sequence, honors
// the in-app toggle, and sanitizes the text. This module only checks the
// OS-level preconditions (runtime permission, user-disabled channel) and
// builds the notification. A denied permission or an OS-level disable makes
// posting a silent no-op, not an error: the in-app banner still shows the
// event, so nothing is lost while the app is in front.
//
// Notification text is terminal-derived. It is bounded here and rendered by
// the system as plain text; it must never be logged.
object EventNotifier {
    private const val CHANNEL_ID = "remotly-events"
    private const val CHANNEL_NAME = "Terminal events"
    private const val MAX_TITLE = 100
    private const val MAX_TEXT = 300

    // The activity a notification tap opens. Set once by the app shell before
    // the first post; null posts the notification without a content intent.
    @Volatile
    var targetActivity: Class<*>? = null

    // Shared by the JS notification bridge and native daemon completion
    // handling. Repeated updates for one daemon session replace a single OS
    // notification instead of building a stack.
    @JvmStatic
    fun notificationId(hostId: String, sessionId: String): Int =
        (hostId.hashCode() * 31 + sessionId.hashCode()) and 0x7fffffff

    // Creates the notification channel once per process. Importance is high
    // so a bell can alert; the user can lower that in the system settings.
    @JvmStatic
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel =
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_HIGH)
        channel.description = "Terminal bells, output matches, and completed sessions"
        manager.createNotificationChannel(channel)
    }

    // True when the app holds the POST_NOTIFICATIONS permission. Below
    // Android 13 the permission does not exist and notifications are always
    // allowed at this layer.
    @JvmStatic
    fun permissionGranted(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    // True when the user has not disabled notifications for this app at the
    // OS level.
    @JvmStatic
    fun osEnabled(context: Context): Boolean =
        NotificationManagerCompat.from(context).areNotificationsEnabled()

    // Posts one notification, or does nothing when a precondition fails.
    // Returns true only when the notification was actually submitted.
    // [notificationId] is derived from the host and session so repeated
    // events for the same session replace one notification instead of
    // stacking.
    @JvmStatic
    fun post(
        context: Context,
        notificationId: Int,
        hostName: String,
        title: String,
        text: String,
    ): Boolean {
        ensureChannel(context)
        if (!permissionGranted(context)) return false
        if (!osEnabled(context)) return false
        val manager = NotificationManagerCompat.from(context)
        val builder =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_remotly_notify)
                .setContentTitle(hostName.take(MAX_TITLE))
                .setContentText("$title: $text".take(MAX_TEXT))
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText("$title: $text".take(MAX_TEXT))
                )
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setOngoing(false)
        targetActivity?.let { target ->
            val contentIntent = PendingIntent.getActivity(
                context,
                0,
                Intent(context, target),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.setContentIntent(contentIntent)
        }
        return try {
            manager.notify(notificationId, builder.build())
            true
        } catch (e: SecurityException) {
            // The permission may have been revoked between the check and the
            // post. Treat it as a silent skip.
            false
        }
    }
}
