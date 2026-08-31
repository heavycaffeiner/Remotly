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

// Keeps file transfers running while the app is not in the foreground.
//
// Without this the process is a background process the moment the user leaves
// the screen, and Android is free to stop its threads: a download died partway
// through with no error, because nothing had failed. The service declares the
// work as dataSync, which is the type Android defines for exactly this.
//
// Not sticky: a transfer holds an SFTP channel and an open destination stream,
// neither of which survives the process, so a restarted service would show
// progress for something that is no longer moving.
class SftpTransferService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        acquireWakeLock()
    }

    override fun onDestroy() {
        wakeLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * Keeps the CPU awake for the length of the transfer.
     *
     * A foreground service stops the process being killed, but it does not
     * stop the device suspending. Without this a download stalled as soon as
     * the screen went off and resumed only when the user woke the phone, which
     * reads as the background transfer not working at all.
     */
    private fun acquireWakeLock() {
        val manager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        try {
            wakeLock = manager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "$packageName:file-transfer",
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (e: SecurityException) {
            // The foreground service still protects the process; the transfer
            // just may pause while the device sleeps.
            Log.w(TAG, "transfer wake lock unavailable")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopForegroundCompat()
            stopSelf()
            return START_NOT_STICKY
        }
        showForegroundNotification()
        return START_NOT_STICKY
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
        // No file name or path: the notification is visible on the lock screen,
        // and what a user transfers is not something to publish there.
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_remotly_notify)
            .setContentTitle("Transferring files")
            .setContentText("A file transfer is running")
            .setContentIntent(openApp)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    companion object {
        private const val TAG = "RemotlyXferService"
        private const val CHANNEL_ID = "remotly-file-transfer"
        private const val CHANNEL_NAME = "File transfers"
        private const val NOTIFICATION_ID = 0x584645
        private const val ACTION_START = "com.remotly.app.ssh.XFER_START"
        private const val ACTION_STOP = "com.remotly.app.ssh.XFER_STOP"

        /**
         * Who currently needs the service.
         *
         * Two independent owners ask for it: native SFTP transfers, and the JS
         * side driving a daemon transfer. Without counting them, whichever
         * finished first would stop the service under the other.
         */
        private val owners = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        /** Native SFTP transfers. */
        const val OWNER_SFTP = "sftp"

        /** Daemon transfers, driven from JS. */
        const val OWNER_DAEMON = "daemon"

        /** Starts or stops the service so it runs while any owner needs it. */
        fun setActive(context: Context, owner: String, active: Boolean) {
            val wasEmpty = owners.isEmpty()
            if (active) owners.add(owner) else owners.remove(owner)
            val needed = owners.isNotEmpty()
            if (needed && wasEmpty) start(context)
            if (!needed && !wasEmpty) stop(context)
        }

        private fun start(context: Context) {
            val intent = Intent(context, SftpTransferService::class.java).setAction(ACTION_START)
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: SecurityException) {
                Log.w(TAG, "transfer foreground service not permitted")
            } catch (e: IllegalStateException) {
                // A background start is rejected on recent releases. Transfers
                // start from a visible screen, so this is the unusual path; the
                // transfer still runs, just without the guarantee.
                Log.w(TAG, "transfer foreground service start deferred")
            }
        }

        private fun stop(context: Context) {
            try {
                context.stopService(
                    Intent(context, SftpTransferService::class.java).setAction(ACTION_STOP),
                )
            } catch (e: IllegalStateException) {
                Log.w(TAG, "transfer service stop deferred")
            }
        }

        private fun ensureChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val manager =
                context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (manager.getNotificationChannel(CHANNEL_ID) != null) return
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Shown while a file transfer is running"
                    setShowBadge(false)
                },
            )
        }
    }
}
