package com.remotly.app.ssh

import com.remotly.app.ssh.engine.SftpOps
import java.io.IOException
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * File transfers over an open SFTP connection.
 *
 * Downloads are pushed: the engine reads the file and each chunk is handed to
 * [Sink.onEvent], which ends with exactly one done or error event.
 *
 * Uploads invert. [SftpOps.upload] pulls chunks and blocks until each one
 * arrives, while the app pushes them one call at a time, so the upload
 * runs on its own thread and takes chunks through a queue of depth one. A
 * push blocks until the engine has taken the previous chunk, which is what
 * stops a fast writer from buffering the whole file in memory.
 */
object SftpTransfers {

    /**
     * Bytes per read for a transfer whose chunks are handed to the sink.
     *
     * pkg/sftp splits one read or write into `len(buf)/32768 + 1` concurrent
     * requests, capped at the client's configured limit of 64 per file. At
     * 32KB that limit was never reached, so most of the link's round-trip
     * budget went unused; 256KB keeps 8 requests in flight instead of 1.
     */
    const val CHUNK_SIZE = 256 * 1024

    /**
     * Bytes per read when the bytes stay in native.
     *
     * Larger than [CHUNK_SIZE] because nothing per chunk crosses the bridge:
     * the cost is only the SFTP round trips, and a bigger window keeps more of
     * them in flight. At 1MiB, pkg/sftp's `len(buf)/32768 + 1` split puts 32
     * requests in flight per read or write, close to its 64-per-file cap.
     * Cancellation is still checked per chunk.
     */
    const val DIRECT_CHUNK_SIZE = 1024 * 1024

    /**
     * How far back from a partial upload's end a resume starts over.
     *
     * Writes are pipelined, so an upload that died without running its own
     * repair can have left a packet from a later offset on the server with
     * an earlier one missing. Only bytes below the end minus one write's
     * worth are known to have been acknowledged in full, so the resume cuts
     * back that far and resends.
     *
     * One value for every path, never the resuming path's own chunk size:
     * what has to be covered is the chunk the *original* attempt wrote, and
     * a file uploaded directly can be resumed through the bridge. Rewinding
     * by the smaller of the two would leave the gap in place and report the
     * file complete. It must stay at or above every chunk size above, which
     * [writeChunk] also enforces at runtime for the bridged path.
     */
    const val RESUME_REWIND_BYTES = DIRECT_CHUNK_SIZE.toLong()

    /** How often a direct download reports progress, in bytes. */
    private const val PROGRESS_INTERVAL_BYTES = 512 * 1024

    /** How long a chunk handoff waits before the transfer is declared stuck. */
    private const val HANDOFF_TIMEOUT_SEC = 120L

    /** A chunk in flight, or the end of the stream. */
    private class Parcel(val offset: Long, val bytes: ByteArray?)

    private class Upload(
        val hostId: String,
        val path: String,
        val queue: ArrayBlockingQueue<Parcel>,
    ) {
        val cancelled = AtomicBoolean(false)
        val written = AtomicLong(0)
        @Volatile
        var failure: String? = null
        @Volatile
        var finished = false
        /** Offset the next chunk must start at, so a gap cannot be padded. */
        val expectedOffset = AtomicLong(0)
    }

    private class Download(val hostId: String) {
        val cancelled = AtomicBoolean(false)
        /** Bytes accepted by the destination and safe to use for a retry. */
        val safe = AtomicLong(0)
    }

    // A direct upload pulls its bytes from a content URI instead of a queue
    // fed by writeChunk/completeUpload, so it has no [Upload.queue] to unblock
    // on cancel: setting the flag and letting its pull callback see it is
    // enough.
    private class DirectUpload(val hostId: String) {
        val cancelled = AtomicBoolean(false)
    }

    /**
     * Hands local bytes to the engine one pull at a time and remembers how
     * far it got, so an interrupted upload can report a resume point.
     *
     * The first pull carries the offset the engine is starting from: zero
     * for a fresh upload, the server's end minus the rewind for an append.
     * The local stream is skipped to it rather than trusting the caller.
     */
    internal class UploadPump(
        private val read: (ByteArray) -> Int,
        chunkSize: Int,
        private val cancelled: () -> Boolean,
        private val onProgress: (Long) -> Unit,
    ) {
        private val buffer = ByteArray(chunkSize)
        private var positioned = false
        private var reportedAt = 0L

        /** Bytes handed to the engine, including the chunk it is writing now. */
        @Volatile
        var total = 0L
            private set

        /** Offset of the engine's latest pull: everything below it was taken in full. */
        @Volatile
        var committed = 0L
            private set

        fun pull(at: Long): ByteArray? {
            if (!positioned) {
                positioned = true
                total = at
                reportedAt = at
                skip(at)
                if (at > 0) onProgress(at)
            }
            committed = at
            if (cancelled()) return null
            val n = read(buffer)
            if (n < 0) return null
            total += n
            if (total - reportedAt >= PROGRESS_INTERVAL_BYTES) {
                reportedAt = total
                onProgress(total)
            }
            return buffer.copyOf(n)
        }

        private fun skip(bytes: Long) {
            var left = bytes
            while (left > 0) {
                val n = read(ByteArray(minOf(left, buffer.size.toLong()).toInt()))
                if (n < 0) throw IOException("local file is shorter than what the server already has")
                left -= n
            }
        }
    }

    private val uploads = ConcurrentHashMap<String, Upload>()
    private val downloads = ConcurrentHashMap<String, Download>()
    private val directUploads = ConcurrentHashMap<String, DirectUpload>()
    private var nextId = AtomicLong(1)

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "remotly-sftp-xfer").apply { isDaemon = true }
    }

    /** Receives download chunks and the single terminal event. */
    fun interface Sink {
        fun onEvent(id: String, offset: Long, data: ByteArray?, done: Long?, error: String?)
    }

    @Volatile
    private var sink: Sink? = null

    fun setSink(s: Sink?) {
        sink = s
    }

    /**
     * Application context used to hold the foreground service up.
     *
     * Set by the bridge module. Without a service the process is an ordinary
     * background process as soon as the user leaves the screen, and Android may
     * stop its threads: a transfer then dies partway with nothing having
     * failed.
     */
    @Volatile
    private var serviceContext: android.content.Context? = null

    fun setServiceContext(context: android.content.Context?) {
        serviceContext = context?.applicationContext
        if (context == null) refreshService()
    }

    /**
     * Starts the service on the first running transfer and stops it after the
     * last. Called on every transition, so the two counts stay in step.
     */
    private fun refreshService() {
        val context = serviceContext ?: return
        val running = uploads.isNotEmpty() || downloads.isNotEmpty() || directUploads.isNotEmpty()
        SftpTransferService.setActive(context, running)
    }

    private fun newId(prefix: String): String = "$prefix-${nextId.getAndIncrement()}"

    /**
     * Opens [path] for writing and returns the transfer id.
     *
     * The engine call runs on its own thread and blocks on the queue until
     * [writeChunk] supplies data, so this returns as soon as the upload is
     * armed rather than when the file is complete.
     */
    fun startUpload(
        ops: SftpOps,
        hostId: String,
        path: String,
        replace: Boolean,
        resume: Boolean = false,
    ): String {
        val id = newId("up")
        val up = Upload(hostId, path, ArrayBlockingQueue(1))
        uploads[id] = up
        refreshService()

        executor.execute {
            try {
                val pull = { _: Long ->
                    if (up.cancelled.get()) {
                        null
                    } else {
                        val parcel = up.queue.poll(HANDOFF_TIMEOUT_SEC, TimeUnit.SECONDS)
                            ?: throw IOException("upload stalled waiting for data")
                        val bytes = parcel.bytes
                        if (bytes != null) up.written.addAndGet(bytes.size.toLong())
                        bytes
                    }
                }
                if (resume) {
                    // The engine hands the offset it actually resumed at to
                    // its first pull, which is below the file's end by
                    // whatever the rewind discarded. Reported once, so the
                    // app writes from there rather than from what it last
                    // managed to hand over.
                    var announced = false
                    ops.uploadAppend(path, RESUME_REWIND_BYTES, CHUNK_SIZE) { at ->
                        if (!announced) {
                            announced = true
                            up.expectedOffset.set(at)
                            sink?.onEvent(id, at, null, null, null)
                        }
                        pull(at)
                    }
                } else {
                    ops.upload(path, CHUNK_SIZE, truncate = replace, exclusive = !replace, onChunk = pull)
                }
            } catch (e: Exception) {
                up.failure = e.message ?: "upload failed"
            } finally {
                up.finished = true
                refreshService()
            }
        }
        return id
    }

    /**
     * Hands one chunk to a running upload and returns the bytes accepted.
     *
     * Blocks until the engine takes the chunk. Out-of-order offsets are
     * rejected: writing a gap would leave the file silently wrong. A chunk
     * larger than [RESUME_REWIND_BYTES] is rejected for the same reason one
     * step removed: a resume rewinds by that much, so a bigger write could
     * leave an unconfirmed gap the rewind does not reach.
     */
    fun writeChunk(id: String, offset: Long, data: ByteArray): Int {
        if (directUploads.containsKey(id)) {
            throw SshHostStoreException("upload $id reads its bytes directly from a file, not from writeChunk")
        }
        val up = uploads[id] ?: throw SshHostStoreException("no such upload: $id")
        up.failure?.let { throw IOException(it) }
        if (up.cancelled.get()) throw SshHostStoreException("upload cancelled")
        if (data.size > RESUME_REWIND_BYTES) {
            throw SshHostStoreException(
                "chunk of ${data.size} bytes exceeds the $RESUME_REWIND_BYTES a resume can rewind",
            )
        }

        val expected = up.expectedOffset.get()
        if (offset != expected) {
            throw SshHostStoreException("upload out of order: expected $expected, got $offset")
        }
        if (!up.queue.offer(Parcel(offset, data), HANDOFF_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            throw IOException("upload stalled")
        }
        up.expectedOffset.addAndGet(data.size.toLong())
        return data.size
    }

    /**
     * Ends the stream and waits for the engine to close the file.
     *
     * The failure is rethrown here rather than swallowed, because this is the
     * call that tells the app its file is safely written.
     */
    fun completeUpload(id: String) {
        if (directUploads.containsKey(id)) {
            throw SshHostStoreException("upload $id reads its bytes directly from a file and completes on its own")
        }
        val up = uploads.remove(id) ?: throw SshHostStoreException("no such upload: $id")
        refreshService()
        up.queue.offer(Parcel(up.expectedOffset.get(), null), HANDOFF_TIMEOUT_SEC, TimeUnit.SECONDS)

        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(HANDOFF_TIMEOUT_SEC)
        while (!up.finished && System.nanoTime() < deadline) {
            Thread.sleep(10)
        }
        up.failure?.let { throw IOException(it) }
        if (!up.finished) throw IOException("upload did not finish")
    }

    /**
     * Starts reading [path], streaming chunks to the sink.
     *
     * Returns as soon as the read is armed. Exactly one done or error event
     * follows the chunks. The terminal event carries the last accepted byte
     * count, including when cancellation interrupts the engine.
     */
    fun startDownload(ops: SftpOps, hostId: String, path: String): String {
        val id = newId("down")
        val down = Download(hostId)
        downloads[id] = down
        refreshService()

        executor.execute {
            try {
                ops.download(path, CHUNK_SIZE) { offset, bytes ->
                    if (down.cancelled.get()) throw IOException("cancelled")
                    sink?.onEvent(id, offset, bytes, null, null)
                    down.safe.set(offset + bytes.size.toLong())
                }
                if (down.cancelled.get()) {
                    sink?.onEvent(id, down.safe.get(), null, null, "cancelled")
                } else {
                    val safe = down.safe.get()
                    sink?.onEvent(id, safe, null, safe, null)
                }
            } catch (e: Exception) {
                val reason = if (down.cancelled.get()) "cancelled" else e.message ?: "download failed"
                sink?.onEvent(id, down.safe.get(), null, null, reason)
            } finally {
                downloads.remove(id)
                refreshService()
            }
        }
        return id
    }

    /**
     * Reads [path] straight into [uri], reporting progress but not bytes.
     *
     * The file module first proves the destination can be truncated and
     * positioned at [resumeFrom], restarting from zero otherwise, and the
     * offset it settled on is what [SftpOps.downloadFrom] reads from.
     * Progress is throttled; each terminal event carries the exact flushed
     * prefix, not the last throttled report.
     */
    fun startDownloadToUri(
        ops: SftpOps,
        hostId: String,
        path: String,
        context: android.content.Context,
        uri: android.net.Uri,
        resumeFrom: Long = 0L,
    ): String {
        val id = newId("down")
        val down = Download(hostId)
        downloads[id] = down
        refreshService()

        executor.execute {
            try {
                var reportedAt = 0L
                com.remotly.app.fileio.FileModule.writeStream(
                    context,
                    uri,
                    resumeFrom = resumeFrom,
                ) { actualOffset, write ->
                    down.safe.set(actualOffset)
                    reportedAt = actualOffset
                    // This also corrects the registry when a provider had to
                    // fall back from the requested resume offset to zero.
                    ops.downloadFrom(path, actualOffset, DIRECT_CHUNK_SIZE) { offset, bytes ->
                        if (down.cancelled.get()) throw IOException("cancelled")
                        val expected = down.safe.get()
                        if (offset != expected) {
                            throw IOException("download out of order: expected $expected, got $offset")
                        }
                        write(bytes)
                        // FileModule returns only after the chunk was flushed,
                        // so this is the first point at which it is safe to
                        // publish the new retry position.
                        val safe = down.safe.addAndGet(bytes.size.toLong())
                        if (safe - reportedAt >= PROGRESS_INTERVAL_BYTES) {
                            reportedAt = safe
                            sink?.onEvent(id, safe, null, null, null)
                        }
                    }
                }
                val safe = down.safe.get()
                if (down.cancelled.get()) {
                    sink?.onEvent(id, safe, null, null, "cancelled")
                } else {
                    sink?.onEvent(id, safe, null, safe, null)
                }
            } catch (e: Exception) {
                val reason =
                    if (down.cancelled.get()) "cancelled" else e.message ?: "download failed"
                sink?.onEvent(id, down.safe.get(), null, null, reason)
            } finally {
                downloads.remove(id)
                refreshService()
            }
        }
        return id
    }

    /**
     * Uploads [uri] straight to [path], reporting progress but not bytes.
     *
     * [resumeFrom] only selects the append mode. The local stream is positioned
     * at the offset the engine reports on its first pull, which is the point
     * the server is known to hold in full; the file's bare length is not.
     */
    fun startUploadFromUri(
        ops: SftpOps,
        hostId: String,
        path: String,
        context: android.content.Context,
        uri: android.net.Uri,
        replace: Boolean,
        resumeFrom: Long = 0L,
    ): String {
        val id = newId("up")
        val up = DirectUpload(hostId)
        directUploads[id] = up
        refreshService()

        executor.execute {
            var pump: UploadPump? = null
            try {
                val finished = com.remotly.app.fileio.FileModule.readStream(context, uri) { read ->
                    val p = UploadPump(read, DIRECT_CHUNK_SIZE, up.cancelled::get) { offset ->
                        sink?.onEvent(id, offset, null, null, null)
                    }
                    pump = p
                    if (resumeFrom > 0) {
                        ops.uploadAppend(path, RESUME_REWIND_BYTES, DIRECT_CHUNK_SIZE, p::pull)
                    } else {
                        ops.upload(path, DIRECT_CHUNK_SIZE, truncate = replace, exclusive = !replace, onChunk = p::pull)
                    }
                    p
                }
                if (up.cancelled.get()) {
                    sink?.onEvent(id, finished.committed, null, null, "cancelled")
                } else {
                    sink?.onEvent(id, finished.total, null, finished.total, null)
                }
            } catch (e: Exception) {
                val reason = if (up.cancelled.get()) "cancelled" else e.message ?: "upload failed"
                // The engine's latest pull marks what it took in full; a retry
                // from there selects append mode and rewinds the rest itself.
                sink?.onEvent(id, pump?.committed ?: 0L, null, null, reason)
            } finally {
                directUploads.remove(id)
                refreshService()
            }
        }
        return id
    }

    /** Cancels a transfer in either direction. Unknown ids are ignored. */
    fun cancel(id: String) {
        uploads[id]?.let {
            it.cancelled.set(true)
            // Unblock the engine if it is waiting for the next chunk.
            it.queue.offer(Parcel(0, null))
            uploads.remove(id)
        }
        downloads[id]?.cancelled?.set(true)
        directUploads[id]?.cancelled?.set(true)
        refreshService()
    }

    /** Drops every transfer for a connection that is going away. */
    fun cancelAll() {
        uploads.keys.toList().forEach { cancel(it) }
        downloads.keys.toList().forEach { cancel(it) }
        directUploads.keys.toList().forEach { cancel(it) }
    }

    /**
     * Drops the transfers belonging to one host.
     *
     * Reconnecting a host used to cancel every transfer in the process,
     * including ones on a different host, because the only option was
     * [cancelAll]. Opening another SFTP tab killed a download that was already
     * running.
     */
    fun cancelForHost(hostId: String) {
        uploads.entries.filter { it.value.hostId == hostId }
            .map { it.key }
            .forEach { cancel(it) }
        downloads.entries.filter { it.value.hostId == hostId }
            .map { it.key }
            .forEach { cancel(it) }
        directUploads.entries.filter { it.value.hostId == hostId }
            .map { it.key }
            .forEach { cancel(it) }
    }

    /** True when any transfer is still running for a host. */
    fun hasActiveForHost(hostId: String): Boolean =
        uploads.values.any { it.hostId == hostId } ||
            downloads.values.any { it.hostId == hostId } ||
            directUploads.values.any { it.hostId == hostId }
}
