package com.remotly.app.ssh.engine.go

import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ssh.engine.SftpOps
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import sshcore.Sftp

// Kotlin adapter over the Go sshcore Sftp client's operations. Each Go call is
// wrapped so failures surface as IOException carrying the server's status
// message, matching the SftpOps contract. list decodes the byte encoding
// produced by Go's encodeEntries.
class GoSftpOps(private val sftp: Sftp) : SftpOps {

    override fun list(path: String): List<SftpEntry> {
        val raw = call { sftp.list(path) }
        return decodeEntries(raw)
    }

    override fun stat(path: String): SftpEntry {
        val e = call { sftp.lstat(path) }
        return SftpEntry(
            name = e.name,
            isDirectory = e.isDirectory,
            isSymlink = e.isSymlink,
            size = e.size,
            modifyTimeMillis = e.modifyTimeMillis,
            permissions = e.permissions,
        )
    }

    override fun mkdir(path: String) {
        call { sftp.mkdir(path) }
    }

    override fun rename(oldPath: String, newPath: String) {
        call { sftp.rename(oldPath, newPath) }
    }

    override fun removeFile(path: String) {
        call { sftp.removeFile(path) }
    }

    override fun removeDir(path: String) {
        call { sftp.removeDir(path) }
    }

    override fun download(path: String, chunkSize: Int, onChunk: (Long, ByteArray) -> Unit): Long {
        val file = call { sftp.openRead(path) }
        try {
            var total = 0L
            while (true) {
                // readChunk, never read: a byte[] argument crosses the gomobile
                // binding as a copy, so bytes Go writes into it are discarded
                // and the caller keeps its own untouched buffer. Downloads that
                // way produced files of the right size full of zero bytes.
                val chunk = call { file.readChunk(chunkSize.toLong()) } ?: break
                if (chunk.isEmpty()) break
                onChunk(total, chunk)
                total += chunk.size
            }
            return total
        } finally {
            runCatching { file.close() }
        }
    }

    override fun downloadFrom(
        path: String,
        startOffset: Long,
        chunkSize: Int,
        onChunk: (Long, ByteArray) -> Unit,
    ): Long {
        val file = call { sftp.openRead(path) }
        try {
            if (startOffset > 0) call { file.seekTo(startOffset) }
            var total = startOffset
            while (true) {
                val chunk = call { file.readChunk(chunkSize.toLong()) } ?: break
                if (chunk.isEmpty()) break
                onChunk(total, chunk)
                total += chunk.size
            }
            return total
        } finally {
            runCatching { file.close() }
        }
    }

    override fun uploadAppend(
        path: String,
        chunkSize: Int,
        onChunk: (Long) -> ByteArray?,
    ): Long {
        val file = call { sftp.openAppend(path) }
        try {
            var total = call { file.offset() }
            while (true) {
                val chunk = onChunk(total) ?: break
                if (chunk.isEmpty()) continue
                val written = call { file.write(chunk) }
                if (written != chunk.size.toLong()) {
                    throw IOException("short write: $written of ${chunk.size} bytes")
                }
                total += written
            }
            return total
        } finally {
            runCatching { file.close() }
        }
    }

    override fun upload(
        path: String,
        chunkSize: Int,
        truncate: Boolean,
        exclusive: Boolean,
        onChunk: (Long) -> ByteArray?,
    ): Long {
        val file = call { sftp.openWrite(path, truncate, exclusive) }
        try {
            var total = 0L
            while (true) {
                val chunk = onChunk(total) ?: break
                if (chunk.isEmpty()) continue
                val written = call { file.write(chunk) }
                if (written != chunk.size.toLong()) {
                    throw IOException("short write: $written of ${chunk.size} bytes")
                }
                total += written
            }
            return total
        } finally {
            runCatching { file.close() }
        }
    }

    override fun close() {
        runCatching { sftp.close() }
    }

    // Runs a blocking Go call and rethrows any failure as IOException.
    private inline fun <T> call(block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        throw IOException(e.message ?: "sftp operation failed", e)
    }
}

// Decodes the byte encoding produced by Go's encodeEntries (all integers
// big-endian). The name bytes are decoded as UTF-8, preserving NFD names
// byte-for-byte.
private fun decodeEntries(raw: ByteArray): List<SftpEntry> {
    if (raw.size < 4) throw IOException("malformed directory listing")
    val bb = ByteBuffer.wrap(raw).order(ByteOrder.BIG_ENDIAN)
    val count = bb.int
    val out = ArrayList<SftpEntry>(count)
    repeat(count) {
        val nameLen = bb.int
        if (nameLen < 0 || bb.remaining() < nameLen + 1 + 1 + 8 + 8 + 4) {
            throw IOException("malformed directory listing")
        }
        val nameBytes = ByteArray(nameLen)
        bb.get(nameBytes)
        val isDir = bb.get() == 1.toByte()
        val isSym = bb.get() == 1.toByte()
        val size = bb.long
        val mtime = bb.long
        val perm = bb.int
        out.add(SftpEntry(String(nameBytes, Charsets.UTF_8), isDir, isSym, size, mtime, perm))
    }
    return out
}
