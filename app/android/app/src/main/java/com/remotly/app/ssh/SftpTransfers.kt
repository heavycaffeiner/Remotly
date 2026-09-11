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
 * [onEvent], which ends with exactly one done or error event.
 *
 * Uploads invert. [SftpOps.upload] pulls chunks and blocks until each one
 * arrives, while the app pushes them one bridge call at a time, so the upload
 * runs on its own thread and takes chunks through a queue of depth one. A
 * push blocks until the engine has taken the previous chunk, which is what
 * stops a fast writer from buffering the whole file in memory.
 */
object SftpTransfers {

    /**
     * Bytes per read for a bridged transfer, where each chunk still crosses
     * into JS.
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
    }

    // A direct upload pulls its bytes from a content URI instead of a queue
    // fed by writeChunk/completeUpload, so it has no [Upload.queue] to unblock
    // on cancel: setting the flag and letting its pull callback see it is
    // enough.
    private class DirectUpload(val hostId: String) {
        val cancelled = AtomicBoolean(false)
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
        SftpTransferService.setActive(context, SftpTransferService.OWNER_SFTP, running)
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
     * follows the chunks.
     */
    fun startDownload(ops: SftpOps, hostId: String, path: String): String {
        val id = newId("down")
        val down = Download(hostId)
        downloads[id] = down
        refreshService()

        executor.execute {
            try {
                val total = ops.download(path, CHUNK_SIZE) { offset, bytes ->
                    if (down.cancelled.get()) throw IOException("cancelled")
                    sink?.onEvent(id, offset, bytes, null, null)
                }
                if (down.cancelled.get()) {
                    sink?.onEvent(id, 0, null, null, "cancelled")
                } else {
                    sink?.onEvent(id, 0, null, total, null)
                }
            } catch (e: Exception) {
                val reason = if (down.cancelled.get()) "cancelled" else e.message ?: "download failed"
                sink?.onEvent(id, 0, null, null, reason)
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
     * The whole point is that file data never enters JS: no base64, no bridge
     * crossing per chunk, and no JS turn per chunk. Those three were the cost
     * of a download, and they scaled with file size rather than with anything
     * the user could see.
     *
     * Progress is reported at intervals rather than per chunk, so a fast link
     * does not spend its time emitting events.
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
                var total = resumeFrom
                var reportedAt = resumeFrom
                // Appends when resuming, so the bytes already on disk are kept
                // and only the missing tail is fetched.
                com.remotly.app.fileio.FileModule.writeStream(
                    context,
                    uri,
                    append = resumeFrom > 0,
                ) { write ->
                    ops.downloadFrom(path, resumeFrom, DIRECT_CHUNK_SIZE) { _, bytes ->
                        if (down.cancelled.get()) throw IOException("cancelled")
                        write(bytes)
                        total += bytes.size
                        if (total - reportedAt >= PROGRESS_INTERVAL_BYTES) {
                            reportedAt = total
                            sink?.onEvent(id, total, null, null, null)
                        }
                    }
                }
                if (down.cancelled.get()) {
                    sink?.onEvent(id, 0, null, null, "cancelled")
                } else {
                    sink?.onEvent(id, total, null, total, null)
                }
            } catch (e: Exception) {
                val reason =
                    if (down.cancelled.get()) "cancelled" else e.message ?: "download failed"
                sink?.onEvent(id, 0, null, null, reason)
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
     * The mirror of [startDownloadToUri]: the file is read from the content
     * URI on this thread and pulled straight into [SftpOps.upload] or
     * [SftpOps.uploadAppend], so no chunk crosses the bridge as base64 and no
     * JS turn is spent per chunk.
     *
     * [resumeFrom] only selects the append mode. Where the local stream is
     * positioned comes from the offset the engine reports on its first pull,
     * which is the point the server is known to hold in full. Trusting the
     * caller's figure, or the file's bare length, would send the wrong bytes
     * whenever they disagree, and the result would be a file that looks
     * complete and is not.
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
            try {
                // Both are set from the engine's first pull, which reports
                // where the server is: zero for a fresh upload, the file's
                // current end for an append.
                var total = 0L
                var reportedAt = 0L
                var positioned = false
                com.remotly.app.fileio.FileModule.readStream(context, uri) { read ->
                    val buffer = ByteArray(DIRECT_CHUNK_SIZE)
                    val pull = { at: Long ->
                        if (!positioned) {
                            positioned = true
                            total = at
                            reportedAt = at
                            var toSkip = at
                            while (toSkip > 0) {
                                val want = minOf(toSkip, DIRECT_CHUNK_SIZE.toLong()).toInt()
                                val n = read(ByteArray(want))
                                if (n < 0) {
                                    throw IOException("local file is shorter than what the server already has")
                                }
                                toSkip -= n
                            }
                            // Tells the app where the upload restarted, the
                            // same first event the queue-fed resume sends.
                            if (at > 0) sink?.onEvent(id, at, null, null, null)
                        }
                        if (up.cancelled.get()) {
                            null
                        } else {
                            val n = read(buffer)
                            if (n < 0) {
                                null
                            } else {
                                total += n
                                if (total - reportedAt >= PROGRESS_INTERVAL_BYTES) {
                                    reportedAt = total
                                    sink?.onEvent(id, total, null, null, null)
                                }
                                buffer.copyOf(n)
                            }
                        }
                    }
                    if (resumeFrom > 0) {
                        ops.uploadAppend(path, RESUME_REWIND_BYTES, DIRECT_CHUNK_SIZE, pull)
                    } else {
                        ops.upload(path, DIRECT_CHUNK_SIZE, truncate = replace, exclusive = !replace, onChunk = pull)
                    }
                }
                if (up.cancelled.get()) {
                    sink?.onEvent(id, 0, null, null, "cancelled")
                } else {
                    sink?.onEvent(id, total, null, total, null)
                }
            } catch (e: Exception) {
                val reason = if (up.cancelled.get()) "cancelled" else e.message ?: "upload failed"
                sink?.onEvent(id, 0, null, null, reason)
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
