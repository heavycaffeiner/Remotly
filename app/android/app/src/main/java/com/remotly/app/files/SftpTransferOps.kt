package com.remotly.app.files

import android.content.Context
import android.net.Uri
import com.remotly.app.ssh.SftpBridge
import com.remotly.app.ssh.SftpTransfers
import com.remotly.app.transfers.TransferDirection
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs SFTP uploads and downloads for the file browser and keeps
 * [TransferRegistry] in step, so FilesScreen holds no transfer bookkeeping of
 * its own.
 *
 * Every attempt is armed on a scope this object owns, not the caller's.
 * FilesScreen is a composable, and navigating away from it must not cancel an
 * upload or download that native code is still moving bytes for; a caller
 * that stops awaiting the outcome only stops hearing about it, the transfer
 * itself keeps running and keeps [TransferRegistry] current.
 */
object SftpTransferOps {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** How one attempt ended. */
    sealed class Result {
        data object Done : Result()

        /**
         * [keepPartial] is true when a destination file already touched by
         * this attempt still holds data a resumed attempt can continue from,
         * and so must survive this failure.
         */
        data class Failed(val message: String, val keepPartial: Boolean) : Result()
    }

    /**
     * Registers one attempt with [TransferRegistry] and arms its restart.
     *
     * Both directions here are resumable: a download seeks on the Go side
     * and an upload lets the server report where it actually continues from.
     * Retry in the transfer sheet therefore always means resume, never a
     * silent restart from zero.
     */
    internal fun armTransfer(
        id: String,
        direction: TransferDirection,
        path: String,
        name: String,
        hostId: String,
        total: Long,
        cancel: () -> Unit,
        onRestart: (Long) -> Unit,
    ) {
        TransferRegistry.register(
            id = id,
            direction = direction,
            path = path,
            name = name,
            hostId = hostId,
            total = total,
            resumable = true,
            cancel = cancel,
            restart = onRestart,
        )
    }

    /**
     * Whether a transfer's local file survives a failure.
     *
     * One that never armed holds nothing SFTP wrote to it, so [armed] is
     * false and it goes. One that armed and then failed mid-flight has real
     * bytes a resumed attempt can continue from, provided the backend can
     * actually continue from them; this one always can, but the check is
     * kept explicit so the rule reads the same regardless.
     */
    internal fun keepPartialOnFailure(armed: Boolean, resumable: Boolean): Boolean = armed && resumable

    /**
     * Whether a download's destination document should be deleted after a
     * failure.
     *
     * A destination this attempt created itself is empty until the transfer
     * writes to it: failing before the transfer ever armed leaves nothing
     * behind but that empty placeholder, wearing the name the user asked
     * for, and it goes. A destination that already held a file before this
     * attempt (a Replace) is only touched once the transfer arms and
     * truncates it; failing before that point leaves the original content
     * exactly as it was, and deleting it would destroy a file nobody asked
     * to lose.
     */
    internal fun shouldDiscardDestination(freshDestination: Boolean, keepPartial: Boolean): Boolean =
        freshDestination && !keepPartial

    /**
     * The name to write for a collision choice.
     *
     * Replace keeps exactly the name asked for, so the transfer overwrites
     * whatever already uses it. Keep both finds the first name [exists] does
     * not recognize, trying "name (1)", "name (2)", and so on before falling
     * back to a name derived from the current time, the same scheme
     * [uniqueName] uses over a static listing.
     */
    internal fun resolvedTransferName(requested: String, replace: Boolean, exists: (String) -> Boolean): String {
        if (replace || !exists(requested)) return requested
        for (i in 1..999) {
            val candidate = numberedName(requested, i)
            if (!exists(candidate)) return candidate
        }
        return numberedName(requested, System.currentTimeMillis().toInt())
    }

    /**
     * Uploads [uri] to [remotePath] under [hostId].
     *
     * Always starts at the beginning: a fresh upload has no offset to resume
     * from. Only a retry driven from the transfer sheet resumes, and that
     * runs through the restart armed below rather than through this entry
     * point.
     */
    suspend fun upload(
        context: Context,
        hostId: String,
        remotePath: String,
        name: String,
        uri: Uri,
        size: Long,
        replace: Boolean,
    ): Result = scope.async {
        runUpload(context, hostId, remotePath, name, uri, size, replace, resumeFrom = 0L)
    }.await()

    private suspend fun runUpload(
        context: Context,
        hostId: String,
        remotePath: String,
        name: String,
        uri: Uri,
        size: Long,
        replace: Boolean,
        resumeFrom: Long,
    ): Result {
        val id = try {
            withContext(Dispatchers.IO) {
                SftpBridge.startUploadFromUri(hostId, remotePath, context, uri, replace, resumeFrom)
            }
        } catch (e: Exception) {
            return Result.Failed(
                e.message ?: "The upload could not be started.",
                keepPartial = keepPartialOnFailure(armed = false, resumable = true),
            )
        }

        armTransfer(
            id = id,
            direction = TransferDirection.Upload,
            path = remotePath,
            name = name,
            hostId = hostId,
            total = size,
            cancel = { SftpTransfers.cancel(id) },
            onRestart = { from ->
                scope.launch { runUpload(context, hostId, remotePath, name, uri, size, replace, from) }
            },
        )
        // Seeded immediately: the engine's own first-pull event that reports
        // this same offset arrives asynchronously and would otherwise leave
        // the bar reading zero until then.
        if (resumeFrom > 0) TransferRegistry.advance(id, resumeFrom)
        return awaitOutcome(id)
    }

    /**
     * Downloads [remotePath] under [hostId] into [destination].
     *
     * [resumeFrom] is the byte offset to continue an already-started attempt
     * from; a fresh download passes zero. The Go side seeks the remote file
     * to that offset and the local write appends rather than truncates, so
     * nothing already on disk is refetched or discarded.
     */
    suspend fun download(
        context: Context,
        hostId: String,
        remotePath: String,
        name: String,
        destination: Uri,
        size: Long,
        resumeFrom: Long,
    ): Result = scope.async {
        val id = try {
            withContext(Dispatchers.IO) {
                SftpBridge.startDownloadToUri(hostId, remotePath, context, destination, resumeFrom)
            }
        } catch (e: Exception) {
            return@async Result.Failed(
                e.message ?: "The download could not be started.",
                keepPartial = keepPartialOnFailure(armed = false, resumable = true),
            )
        }

        armTransfer(
            id = id,
            direction = TransferDirection.Download,
            path = remotePath,
            name = name,
            hostId = hostId,
            total = size,
            cancel = { SftpTransfers.cancel(id) },
            onRestart = { from ->
                scope.launch { download(context, hostId, remotePath, name, destination, size, from) }
            },
        )
        if (resumeFrom > 0) TransferRegistry.advance(id, resumeFrom)
        awaitOutcome(id)
    }.await()

    /**
     * Waits for the terminal event [TransferEvents] delivers for [id],
     * advancing [TransferRegistry] in the meantime and settling it exactly
     * once.
     */
    private suspend fun awaitOutcome(id: String): Result {
        val outcome = CompletableDeferred<Result>()
        TransferEvents.watch(id) { transferred, done, error ->
            when {
                error != null -> {
                    // TransferRegistry.cancel() settles the record to
                    // Cancelled itself, synchronously, before the cancel
                    // closure it calls (SftpTransfers.cancel, which is what
                    // eventually produces this very event) returns. Settling
                    // again here would overwrite that with Error. A transfer
                    // the registry still shows Active reached here another
                    // way, such as a host reconnect tearing it down, and that
                    // needs settling since nothing else will.
                    val stillActive = TransferRegistry.list()
                        .firstOrNull { it.id == id }?.phase == TransferPhase.Active
                    if (stillActive) TransferRegistry.settle(id, TransferPhase.Error, error)
                    outcome.complete(
                        Result.Failed(error, keepPartial = keepPartialOnFailure(armed = true, resumable = true)),
                    )
                }
                done -> {
                    TransferRegistry.settle(id, TransferPhase.Done)
                    outcome.complete(Result.Done)
                }
                else -> TransferRegistry.advance(id, transferred)
            }
        }
        return outcome.await()
    }
}
