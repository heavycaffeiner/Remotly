package com.remotly.app.ssh

import android.content.Context
import com.remotly.app.ssh.engine.SftpConnectionFactory
import com.remotly.app.ssh.engine.SshEngineFactory

// Process-wide SSH store instances, shared by the terminal and SFTP paths.
// Set once from the app shell before the bridge methods are reachable; null
// means initialization failed and the bridge reports an error state.
object SshModule {
    // Application context only. It lets the process-wide hub start and stop
    // its foreground service without retaining an Activity.
    @Volatile
    var appContext: Context? = null

    @Volatile
    var store: SshHostStore? = null

    // The SSH terminal engine. Until the shell wires it, SshHub reports a
    // failed state instead of guessing.
    @Volatile
    var engineFactory: SshEngineFactory? = null

    // The SFTP connection factory. Null means the SFTP paths report a failure.
    @Volatile
    var sftpConnectionFactory: SftpConnectionFactory? = null
}
