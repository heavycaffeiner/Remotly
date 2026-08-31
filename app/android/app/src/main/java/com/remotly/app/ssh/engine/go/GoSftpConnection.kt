package com.remotly.app.ssh.engine.go

import com.remotly.app.ssh.HostKeyInfo
import com.remotly.app.ssh.SftpConnectResult
import com.remotly.app.ssh.SshCredential
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.engine.SftpConnection
import com.remotly.app.ssh.engine.SftpOps
import sshcore.Config
import sshcore.Sftp
import sshcore.SftpListener

// Kotlin adapter over the Go sshcore Sftp client. Implements the SftpConnection
// seam (consumed by SftpBridge). The Go host-key callback carries only the
// algorithm and fingerprint; the host and port come from the connect call,
// captured before the handshake runs.
class GoSftpConnection(private val onHostKey: (HostKeyInfo) -> Unit) : SftpConnection {

    @Volatile
    private var curHost: String = ""

    @Volatile
    private var curPort: Int = 0

    private val client = Sftp(object : SftpListener {
        override fun onHostKey(algorithm: String, fingerprint: String) {
            onHostKey(HostKeyInfo(curHost, curPort, algorithm, fingerprint))
        }
    })

    override val sftp: SftpOps by lazy { GoSftpOps(client) }

    override fun connect(host: SshHost, credential: SshCredential): SftpConnectResult {
        curHost = host.host
        curPort = host.port
        val cfg = Config()
        cfg.host = host.host
        cfg.port = host.port.toLong()
        cfg.user = host.username
        when (val cred = credential) {
            is SshCredential.Password -> cfg.password = String(cred.value, Charsets.UTF_8)
            is SshCredential.Key -> {
                cfg.privateKey = cred.privateKey
                cfg.passphrase = cred.passphrase ?: ByteArray(0)
            }
        }
        val r = client.connect(cfg)
        return if (r.ready) {
            SftpConnectResult.Ready
        } else {
            SftpConnectResult.Failure(r.code, r.message)
        }
    }

    override fun decideHostKey(accept: Boolean) {
        client.decideHostKey(accept)
    }

    override fun close() {
        client.close()
    }
}
