package com.remotly.app.ssh.engine

import com.remotly.app.ssh.HostKeyInfo
import com.remotly.app.ssh.SftpConnectResult
import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ssh.SshCredential
import com.remotly.app.ssh.SshHost

// One authenticated SFTP connection: an SSH session with the SFTP subsystem
// open and no shell channel. The host-key challenge, when it occurs, is
// reported through onHostKey and answered with decideHostKey.
interface SftpConnection {
    // Blocks on the calling thread until the subsystem is open (Ready) or the
    // connection fails. Callers run this on a worker thread.
    fun connect(host: SshHost, credential: SshCredential): SftpConnectResult

    // The app's answer to a host-key challenge.
    fun decideHostKey(accept: Boolean)

    // The SFTP primitives, valid after a Ready result. Blocking; call from a
    // worker thread.
    val sftp: SftpOps

    fun close()
}

// Creates the connection for a host. Set once by the app shell (see
// SshModule) to the Go sshcore implementation.
fun interface SftpConnectionFactory {
    fun create(onHostKey: (HostKeyInfo) -> Unit): SftpConnection
}

// Narrow SFTP operations over an authenticated session. Every method blocks on
// a network round trip and must be called from a worker thread, never the UI
// thread. Failures are thrown as IOException with the server's status
// message; callers map them to their own error vocabulary.
interface SftpOps {
    fun list(path: String): List<SftpEntry>

    // Uses lstat so a symlink reports as a symlink rather than its target.
    fun stat(path: String): SftpEntry

    // Resolves a path to an absolute one on the server. Resolving "." gives
    // the SFTP start directory, which is the user's home on OpenSSH whatever
    // the server's operating system and path separator.
    fun realPath(path: String): String

    fun mkdir(path: String)

    fun rename(oldPath: String, newPath: String)

    // Removes a file. Directories use removeDir.
    fun removeFile(path: String)

    // Removes an empty directory. Non-recursive: a non-empty directory fails.
    fun removeDir(path: String)

    // Reads the file in chunks of at most chunkSize, calling onChunk
    // (absoluteOffset, chunk) per chunk. Returns the total bytes read.
    fun download(path: String, chunkSize: Int, onChunk: (Long, ByteArray) -> Unit): Long

    // Reads from startOffset onward, so an interrupted download continues
    // rather than starting over. Offsets passed to onChunk are absolute.
    fun downloadFrom(
        path: String,
        startOffset: Long,
        chunkSize: Int,
        onChunk: (Long, ByteArray) -> Unit,
    ): Long

    // Reopens a partial upload and pulls chunks the same way upload does, so
    // an interrupted upload keeps what already arrived. [rewind] is how far
    // back from the file's end the continuation starts: writes are pipelined,
    // so the last rewind bytes may contain a gap, and only what lies below
    // them is known to have been acknowledged in full. It has to cover the
    // chunk the interrupted attempt wrote, which is not necessarily the one
    // this attempt uses, so callers pass a single shared figure rather than
    // their own chunkSize. The first onChunk reports where writing actually
    // resumed. Returns the size the file reached.
    fun uploadAppend(
        path: String,
        rewind: Long,
        chunkSize: Int,
        onChunk: (Long) -> ByteArray?,
    ): Long

    // Writes to the file, pulling each chunk from onChunk(absoluteOffset).
    // onChunk returns null to finish. truncate empties the file first;
    // exclusive fails if the file already exists. Returns the bytes written.
    fun upload(
        path: String,
        chunkSize: Int,
        truncate: Boolean,
        exclusive: Boolean,
        onChunk: (Long) -> ByteArray?,
    ): Long

    fun close()
}
