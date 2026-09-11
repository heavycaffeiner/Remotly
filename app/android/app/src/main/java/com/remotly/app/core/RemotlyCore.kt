package com.remotly.app.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.remotly.app.settings.SettingsModule
import com.remotly.app.settings.SettingsStore
import com.remotly.app.ssh.AndroidKeyStoreCredentialCipher
import com.remotly.app.ssh.SshHostStore
import com.remotly.app.ssh.SshHub
import com.remotly.app.ssh.SshModule
import com.remotly.app.ssh.engine.SftpConnectionFactory
import com.remotly.app.ssh.engine.SshEngineFactory
import com.remotly.app.ssh.engine.go.GoSftpConnection
import com.remotly.app.ssh.engine.go.GoSshEngine
import java.io.File

// Process-wide core singletons. Called from the Application on the main
// thread, before any screen exists; every step is idempotent.
object RemotlyCore {
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val dir = appRemotlyDir(app)
            initSettingsStore(dir)
            initSshStore(app, dir)
            initialized = true
        }
    }

    // Settings degrades leniently: its absence loses saved preferences but
    // never blocks connecting to a host.
    private fun initSettingsStore(dir: File) {
        runCatching {
            SettingsModule.store = SettingsStore(File(dir, SettingsStore.FILE_NAME))
        }.onFailure {
            Log.w("RemotlyCore", "settings store unavailable", it)
        }
    }

    // The SSH store degrades the same way: without it the terminal and SFTP
    // paths report an error state instead of failing to start.
    private fun initSshStore(app: Context, dir: File) {
        SshModule.appContext = app
        runCatching {
            // Schema 2 keeps hosts and sealed credentials in one file so an
            // update is a single atomic replacement. A schema 1 pair of files
            // is migrated on first load.
            SshModule.store = SshHostStore(
                File(dir, SshHostStore.FILE_NAME),
                AndroidKeyStoreCredentialCipher(),
            )
        }.onFailure {
            Log.w("RemotlyCore", "ssh store unavailable", it)
        }
        // The SSH terminal and SFTP engines are the Go sshcore .aar.
        SshModule.engineFactory = SshEngineFactory { GoSshEngine(it) }
        SshModule.sftpConnectionFactory = SftpConnectionFactory { GoSftpConnection(it) }
        // Bridge events must reach JS on the main thread.
        val mainHandler = Handler(Looper.getMainLooper())
        SshHub.poster = SshHub.MainPoster { r -> mainHandler.post(r) }
    }

    private fun appRemotlyDir(context: Context): File {
        val dir = File(context.filesDir, "remotly")
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("cannot create ${dir.absolutePath}")
        }
        return dir
    }
}
