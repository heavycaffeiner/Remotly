package com.remotly.app.ssh

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.remotly.app.MainActivity
import com.remotly.app.R

// Owns the Android lifetime guarantee for one or more live SSH shells. The
// shell itself remains in SshHub; this service only makes that ongoing remote
// work visible to the user and prevents ordinary device sleep from suspending
// its native SSH engine. It is intentionally not sticky: an SSH shell cannot
// be safely reconstructed after the process has been killed.
class SshSessionService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        showForegroundNotification()
        // Restarting would show a running-looking service after its in-memory
        // SSH session was lost. The hub starts a new service on the next
        // explicit terminal connection instead.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun showForegroundNotification() {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remotly_notify)
            .setContentTitle("SSH session active")
            .setContentText("Keeping your terminal connection alive")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:ssh-session",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            // The foreground service still protects the process. Do not expose
            // a stack trace or any session metadata in logs.
            Log.w(TAG, "SSH wake lock unavailable")
        }
    }

    companion object {
        private const val TAG = "RemotlySshService"
        private const val CHANNEL_ID = "remotly-ssh-session"
        private const val CHANNEL_NAME = "Active SSH session"
        private const val NOTIFICATION_ID = 0x535348
        private const val ACTION_START = "com.remotly.app.ssh.START"
        private const val ACTION_STOP = "com.remotly.app.ssh.STOP"

        fun start(context: Context) {
            val intent = Intent(context, SshSessionService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: SecurityException) {
                Log.w(TAG, "SSH foreground service not permitted")
            } catch (e: IllegalStateException) {
                // Android rejects background starts on recent releases. Normal
                // starts originate from the visible terminal, and the SSH
                // connection remains usable even if the OS rejects this guard.
                Log.w(TAG, "SSH foreground service start deferred")
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SshSessionService::class.java).setAction(ACTION_STOP))
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while an SSH terminal stays connected"
                    setShowBadge(false)
                },
            )
        }
    }
}
