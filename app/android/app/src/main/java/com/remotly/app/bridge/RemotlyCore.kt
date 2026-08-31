package com.remotly.app.bridge

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.remotly.app.MainActivity
import com.remotly.app.hosts.HostStore
import com.remotly.app.hosts.HostsModule
import com.remotly.app.identity.IdentityStore
import com.remotly.app.notify.EventNotifier
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
import com.remotly.app.transport.TransportHub
import com.remotly.app.workspace.WorkspaceModule
import com.remotly.app.workspace.WorkspaceStore
import java.io.File

// Process-wide core singletons. Called from MainApplication.onCreate on the
// main thread, before any TurboModule is constructed; every step is idempotent.
object RemotlyCore {
    @Volatile
    private var initialized = false

    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val app = context.applicationContext
            val dir = appRemotlyDir(app)
            initHostsStore(dir)
            initWorkspaceStore(dir)
            initSettingsStore(dir)
            initSshStore(app, dir)
            initTransportHub(app)
            // A notification tap re-opens the single RN activity.
            EventNotifier.targetActivity = MainActivity::class.java
            initialized = true
        }
    }

    // Without a host store the pairing flow cannot persist hosts; fail the
    // app loudly instead of pairing into a black hole.
    private fun initHostsStore(dir: File) {
        runCatching {
            HostsModule.store = HostStore(File(dir, HostStore.FILE_NAME))
        }.onFailure {
            throw IllegalStateException("failed to initialize host store", it)
        }
    }

    // Workspace and settings are conveniences on top of the host store: their
    // absence degrades the app (no tab restore, notifications default off)
    // but never blocks pairing or connecting, so they initialize leniently.
    private fun initWorkspaceStore(dir: File) {
        runCatching {
            WorkspaceModule.store = WorkspaceStore(dir)
        }.onFailure {
            Log.w("RemotlyCore", "workspace store unavailable", it)
        }
    }

    private fun initSettingsStore(dir: File) {
        runCatching {
            SettingsModule.store = SettingsStore(File(dir, SettingsStore.FILE_NAME))
            SettingsModule.notifyEnabled = SettingsModule.store?.load()?.notifyEnabled == true
        }.onFailure {
            SettingsModule.notifyEnabled = false
            Log.w("RemotlyCore", "settings store unavailable", it)
        }
    }

    // The SSH store degrades like workspace and settings: without it the
    // terminal and SFTP paths report an error state, but pairing and connecting
    // to a daemon are unaffected.
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
        // Bridge events must reach JS on the main thread, matching the transport hub.
        val mainHandler = Handler(Looper.getMainLooper())
        SshHub.poster = SshHub.MainPoster { r -> mainHandler.post(r) }
    }

    private fun initTransportHub(app: Context) {
        TransportHub.appContext = app
        val identityStore = runCatching { IdentityStore.create(app) }.getOrNull()
        if (identityStore != null) {
            TransportHub.identityProvider = { identityStore.loadOrCreate() }
        }
        TransportHub.deviceNameProvider = { "remotly-android" }
        // Bridge callbacks and events must reach JS on the main thread.
        val mainHandler = Handler(Looper.getMainLooper())
        TransportHub.poster = TransportHub.MainPoster { r -> mainHandler.post(r) }
    }

    private fun appRemotlyDir(context: Context): File {
        val dir = File(context.filesDir, "remotly")
        if (!dir.exists() && !dir.mkdirs()) {
            throw java.io.IOException("cannot create ${dir.absolutePath}")
        }
        return dir
    }
}
