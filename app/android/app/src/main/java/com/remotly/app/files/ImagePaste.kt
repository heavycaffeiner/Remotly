package com.remotly.app.files

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.remotly.app.session.SshSessions
import com.remotly.app.ssh.SftpBridge
import com.remotly.app.ssh.SftpTransfers
import com.remotly.app.transfers.TransferDirection
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRegistry
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Uploads a picked image over SFTP and types the remote path into the
 * session.
 *
 * A terminal carries text, so a picked image cannot go into it directly. The
 * file is written under the remote home directory instead and its path is
 * typed, which is what an agent reading files from disk expects.
 */
object ImagePaste {

    /** Refuses anything larger, so one paste cannot stall a session. */
    const val MAX_IMAGE_BYTES: Long = 12L * 1024 * 1024

    /** Where pasted images are written, relative to the remote home directory. */
    const val PASTE_DIR = ".remotly"

    private val EXTENSION_PATTERN = Regex("^[a-z0-9]{1,5}$")
    private val STAMP_FORMAT: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private const val CONNECT_POLL_MS = 150L
    private const val CONNECT_POLL_MAX = 60
    private const val UPLOAD_TIMEOUT_MS = 5 * 60_000L

    /** What a picker reported for the chosen image, both fields untrusted. */
    data class PickedImage(val name: String, val size: Long)

    sealed class Result {
        data class Ok(val path: String) : Result()
        data class Failed(val message: String) : Result()
    }

    /**
     * The extension to write, taken from the picked file's own name.
     *
     * The name comes from a content provider and is not ours to trust:
     * anything that is not a short alphanumeric run becomes png rather than
     * being carried through onto the remote filesystem.
     */
    fun imageExtension(sourceName: String): String {
        val dot = sourceName.lastIndexOf('.')
        if (dot < 0 || dot == sourceName.length - 1) return "png"
        val ext = sourceName.substring(dot + 1).lowercase()
        return if (EXTENSION_PATTERN.matches(ext)) ext else "png"
    }

    /**
     * Names the file for a pasted image.
     *
     * The timestamp keeps two pastes in one session apart. [isPlainName]
     * guards the result before it is ever joined onto a remote directory:
     * the extension is already restricted above, but this is the last line
     * of defence between a generated name and the filesystem.
     */
    fun pastedImageName(now: Long, sourceName: String = ""): String {
        val name = "paste_${stampOf(now)}.${imageExtension(sourceName)}"
        check(isPlainName(name)) { "generated paste name is not plain: $name" }
        return name
    }

    private fun stampOf(now: Long): String {
        val iso = STAMP_FORMAT.format(Instant.ofEpochMilli(now))
        return iso.replace(Regex("[:.]"), "-").replace("T", "_").replace("Z", "")
    }

    /** Why a paste must not proceed, or null when [sourceSize] is acceptable. */
    fun sizeRefusal(sourceSize: Long): String? = when {
        sourceSize <= 0 -> "That image is empty."
        sourceSize > MAX_IMAGE_BYTES -> "That image is too large to paste."
        else -> null
    }

    /**
     * Reads the display name and byte size a content provider reports for a
     * picked image.
     *
     * Both are untrusted and either may be absent: a missing name falls back
     * to empty, which [imageExtension] turns into png, and a missing size
     * falls back to -1, which [sizeRefusal] rejects outright.
     */
    fun queryPickedImage(context: Context, uri: Uri): PickedImage {
        var name = ""
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (cursor.moveToFirst()) {
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: ""
                    if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
                }
            }
        }
        return PickedImage(name, size)
    }

    /**
     * Uploads [uri] to the remote home directory and returns the path
     * written, or why it did not happen.
     *
     * Connects SFTP for [hostId] when no session is open yet: a paste can be
     * the first thing that touches SFTP on this host, since Files may never
     * have been opened. The host key store is shared with the shell session
     * already running on this screen, so an already-accepted key is reused
     * rather than prompted for again; a key that still needs a decision is
     * reported as a failure instead of silently guessing.
     */
    suspend fun paste(context: Context, hostId: String, uri: Uri): Result {
        val picked = withContext(Dispatchers.IO) { queryPickedImage(context, uri) }
        sizeRefusal(picked.size)?.let { return Result.Failed(it) }

        if (!withContext(Dispatchers.IO) { awaitSftpReady(hostId) }) {
            return Result.Failed("Could not connect for the upload.")
        }

        val home = withContext(Dispatchers.IO) {
            runCatching { SftpBridge.realPath(hostId, ".") }.getOrDefault("")
        }
        // An empty answer would put the upload at the filesystem root, which
        // is refused rather than guessed at.
        if (home.isEmpty()) return Result.Failed("The remote home directory is unknown.")

        val dir = joinPath(home, PASTE_DIR)
        withContext(Dispatchers.IO) {
            // Tolerates a directory that already exists; a real failure
            // surfaces on the upload that follows instead.
            runCatching { SftpBridge.mkdir(hostId, dir) }
        }

        val name = pastedImageName(System.currentTimeMillis(), picked.name)
        val path = joinPath(dir, name)

        val id = try {
            withContext(Dispatchers.IO) {
                SftpBridge.startUploadFromUri(hostId, path, context, uri, replace = true, resumeFrom = 0L)
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "The image could not be uploaded.")
        }

        TransferRegistry.register(
            id = id,
            direction = TransferDirection.Upload,
            path = path,
            name = name,
            hostId = hostId,
            total = picked.size,
            resumable = false,
            cancel = { SftpTransfers.cancel(id) },
        )

        val outcome = CompletableDeferred<Result>()
        TransferEvents.watch(id) { transferred, done, error ->
            when {
                error != null -> {
                    TransferRegistry.settle(id, TransferPhase.Error, error)
                    outcome.complete(Result.Failed(error))
                }
                done -> {
                    TransferRegistry.settle(id, TransferPhase.Done)
                    outcome.complete(Result.Ok(path))
                }
                else -> TransferRegistry.advance(id, transferred)
            }
        }

        val settled = withTimeoutOrNull(UPLOAD_TIMEOUT_MS) { outcome.await() }
        if (settled == null) {
            TransferEvents.stopWatching(id)
            SftpTransfers.cancel(id)
            TransferRegistry.settle(id, TransferPhase.Error, "The upload timed out.")
            return Result.Failed("The upload timed out.")
        }
        return settled
    }

    /** Types the uploaded path into the session, as a paste, with a trailing space. */
    fun typeResult(hostId: String, path: String) {
        SshSessions.sendInput(hostId, "$path ".toByteArray(Charsets.UTF_8))
    }

    private suspend fun awaitSftpReady(hostId: String): Boolean {
        if (SftpBridge.status(hostId)?.state == SftpBridge.State.READY) return true
        runCatching { SftpBridge.connect(hostId) }
        repeat(CONNECT_POLL_MAX) {
            when (SftpBridge.status(hostId)?.state) {
                SftpBridge.State.READY -> return true
                SftpBridge.State.FAILED, SftpBridge.State.HOST_KEY -> return false
                else -> delay(CONNECT_POLL_MS)
            }
        }
        return false
    }
}
