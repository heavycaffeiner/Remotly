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
import androidx.core.content.ContextCompat
import com.remotly.app.MainActivity
import com.remotly.app.R
import java.util.concurrent.atomic.AtomicInteger

/**
 * Desktop notifications a program running in the terminal asks for with
 * OSC 9 or OSC 777.
 *
 * The title and body cross from the remote host, so they are bounded here
 * rather than trusted: a program that emits a huge body must not be able to
 * hand that straight to the system notification manager, and neither string
 * is ever interpreted as anything but text.
 */
object TerminalNotifications {
    private const val CHANNEL_ID = "remotly-terminal-notify"
    private const val CHANNEL_NAME = "Terminal notifications"
    private const val CHANNEL_DESCRIPTION = "Notifications a program running in the terminal sent"

    /** Bounds on remote-supplied text, well past any sensible message. */
    const val MAX_TITLE_CHARS = 128
    const val MAX_BODY_CHARS = 1024

    /**
     * Notifications are distinct rather than replacing one another: two
     * builds finishing should both stay visible.
     */
    private val counter = AtomicInteger(0)

    private fun nextId(): Int = 0x4E00 + (counter.getAndIncrement() % 1000)

    /** The title, trimmed and bounded to a length a notification can actually show. */
    fun boundTitle(title: String): String = title.trim().take(MAX_TITLE_CHARS)

    /** The body, trimmed and bounded the same way as [boundTitle]. */
    fun boundBody(body: String): String = body.trim().take(MAX_BODY_CHARS)

    /** Whether posting is currently allowed: always true before API 33, the runtime grant after. */
    fun canPost(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED

    /** True on API 33 and later, where POST_NOTIFICATIONS is a runtime permission. */
    fun needsRuntimePermission(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /** Creates the notification channel once. A no-op on every call after the first. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = CHANNEL_DESCRIPTION
            },
        )
    }

    /**
     * Posts one notification, having bounded the remote-supplied text first.
     *
     * A no-op when there is nothing to say or when the permission is not
     * granted; a refused or failed post must never break the terminal
     * session that asked for it, so the outcome here is not surfaced.
     */
    fun show(context: Context, title: String, body: String) {
        val boundedTitle = boundTitle(title)
        val boundedBody = boundBody(body)
        if (boundedTitle.isEmpty() && boundedBody.isEmpty()) return
        if (!canPost(context)) return

        ensureChannel(context)
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remotly_notify)
            .setContentTitle(boundedTitle.ifEmpty { context.getString(R.string.app_name) })
            .setContentText(boundedBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(boundedBody))
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setAutoCancel(true)
            .build()

        runCatching { NotificationManagerCompat.from(context).notify(nextId(), notification) }
    }
}
