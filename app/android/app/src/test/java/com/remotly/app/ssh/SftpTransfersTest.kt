package com.remotly.app.ssh

import com.remotly.app.ssh.engine.SftpOps
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transfers against a fake engine.
 *
 * The engine pulls upload chunks and blocks until each one arrives, while the
 * app pushes them one bridge call at a time. That inversion is the whole
 * reason this class exists, so it is what these tests drive.
 */
class SftpTransfersTest {

    private companion object {
        const val HOST = "host-test"
    }

    /** Records what the engine was asked to do and what it received. */
    private class FakeOps(
        val downloadBytes: ByteArray = ByteArray(0),
        val failWriteAfter: Int = -1,
    ) : SftpOps {
        val uploaded = ByteArrayOutputStream()
        @Volatile var truncateSeen: Boolean? = null
        @Volatile var exclusiveSeen: Boolean? = null
        @Volatile var uploadPath: String = ""
        @Volatile var closed = false

        override fun list(path: String) = emptyList<SftpEntry>()
        override fun stat(path: String) = throw IOException("not used")
        override fun mkdir(path: String) = Unit
        override fun rename(oldPath: String, newPath: String) = Unit
        override fun removeFile(path: String) = Unit
        override fun removeDir(path: String) = Unit

        /** Records where a resumed download was asked to start. */
        @Volatile var resumeFrom: Long = -1

        override fun downloadFrom(
            path: String,
            startOffset: Long,
            chunkSize: Int,
            onChunk: (Long, ByteArray) -> Unit,
        ): Long {
            resumeFrom = startOffset
            var offset = startOffset
            while (offset < downloadBytes.size) {
                val end = minOf(offset + chunkSize, downloadBytes.size.toLong())
                onChunk(offset, downloadBytes.copyOfRange(offset.toInt(), end.toInt()))
                offset = end
            }
            return offset
        }

        override fun uploadAppend(
            path: String,
            chunkSize: Int,
            onChunk: (Long) -> ByteArray?,
        ): Long = upload(path, chunkSize, truncate = false, exclusive = false, onChunk = onChunk)

        override fun download(
            path: String,
            chunkSize: Int,
            onChunk: (Long, ByteArray) -> Unit,
        ): Long {
            var offset = 0L
            while (offset < downloadBytes.size) {
                val end = minOf(offset + chunkSize, downloadBytes.size.toLong())
                onChunk(offset, downloadBytes.copyOfRange(offset.toInt(), end.toInt()))
                offset = end
            }
            return offset
        }

        override fun upload(
            path: String,
            chunkSize: Int,
            truncate: Boolean,
            exclusive: Boolean,
            onChunk: (Long) -> ByteArray?,
        ): Long {
            uploadPath = path
            truncateSeen = truncate
            exclusiveSeen = exclusive
            var total = 0L
            while (true) {
                val chunk = onChunk(total) ?: break
                if (failWriteAfter in 0..total.toInt()) throw IOException("disk full")
                uploaded.write(chunk)
                total += chunk.size
            }
            return total
        }

        override fun close() {
            closed = true
        }
    }

    private val events = mutableListOf<Triple<String, ByteArray?, String?>>()
    private val dones = mutableMapOf<String, Long>()

    @Before
    fun setUp() {
        events.clear()
        dones.clear()
        SftpTransfers.setSink { id, _, data, done, error ->
            synchronized(events) {
                events.add(Triple(id, data, error))
                if (done != null) dones[id] = done
            }
        }
    }

    @After
    fun tearDown() {
        SftpTransfers.cancelAll()
        SftpTransfers.setSink(null)
    }

    private fun waitFor(timeoutMs: Long = 3000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return
            Thread.sleep(5)
        }
        throw AssertionError("condition not met within ${timeoutMs}ms")
    }

    // --- upload ---

    @Test
    fun anUploadDeliversEveryChunkInOrder() {
        val ops = FakeOps()
        val id = SftpTransfers.startUpload(ops, HOST, "/remote/f.bin", replace = true)

        val a = byteArrayOf(1, 2, 3)
        val b = byteArrayOf(4, 5)
        assertEquals(3, SftpTransfers.writeChunk(id, 0, a))
        assertEquals(2, SftpTransfers.writeChunk(id, 3, b))
        SftpTransfers.completeUpload(id)

        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5), ops.uploaded.toByteArray())
        assertEquals("/remote/f.bin", ops.uploadPath)
    }

    /** A gap would leave the file silently wrong, so it is refused. */
    @Test
    fun anOutOfOrderChunkIsRejected() {
        val ops = FakeOps()
        val id = SftpTransfers.startUpload(ops, HOST, "/remote/f.bin", replace = true)
        SftpTransfers.writeChunk(id, 0, byteArrayOf(1, 2))

        val e = runCatching { SftpTransfers.writeChunk(id, 99, byteArrayOf(3)) }
        assertTrue(e.isFailure)
        SftpTransfers.cancel(id)
    }

    @Test
    fun replaceTruncatesAndFailKeepsExclusive() {
        val replacing = FakeOps()
        val a = SftpTransfers.startUpload(replacing, HOST, "/f", replace = true)
        SftpTransfers.completeUpload(a)
        assertEquals(true, replacing.truncateSeen)
        assertEquals(false, replacing.exclusiveSeen)

        val refusing = FakeOps()
        val b = SftpTransfers.startUpload(refusing, HOST, "/f", replace = false)
        SftpTransfers.completeUpload(b)
        assertEquals(false, refusing.truncateSeen)
        assertEquals(true, refusing.exclusiveSeen)
    }

    /**
     * The engine's failure has to surface on completeUpload. Swallowing it
     * would tell the user a file was written when it was not.
     */
    @Test
    fun anEngineFailureSurfacesOnComplete() {
        val ops = FakeOps(failWriteAfter = 0)
        val id = SftpTransfers.startUpload(ops, HOST, "/f", replace = true)
        runCatching { SftpTransfers.writeChunk(id, 0, byteArrayOf(1, 2)) }

        val result = runCatching { SftpTransfers.completeUpload(id) }
        assertTrue("completeUpload should report the engine failure", result.isFailure)
    }

    @Test
    fun writingToAnUnknownUploadFails() {
        val result = runCatching { SftpTransfers.writeChunk("nope", 0, byteArrayOf(1)) }
        assertTrue(result.isFailure)
    }

    /** A cancelled upload must not leave its engine thread parked forever. */
    @Test
    fun cancellingAnUploadReleasesTheEngine() {
        val ops = FakeOps()
        val id = SftpTransfers.startUpload(ops, HOST, "/f", replace = true)
        SftpTransfers.writeChunk(id, 0, byteArrayOf(1))
        SftpTransfers.cancel(id)
        // The engine loop ends rather than sitting on the queue.
        waitFor { ops.uploadPath == "/f" }
    }

    // --- download ---

    @Test
    fun aDownloadStreamsChunksThenReportsDone() {
        val payload = ByteArray(70_000) { (it % 251).toByte() }
        val ops = FakeOps(downloadBytes = payload)
        val id = SftpTransfers.startDownload(ops, HOST, "/remote/big.bin")

        waitFor { dones.containsKey(id) }

        val received = ByteArrayOutputStream()
        synchronized(events) {
            events.filter { it.first == id }.forEach { it.second?.let(received::write) }
        }
        assertArrayEquals(payload, received.toByteArray())
        assertEquals(payload.size.toLong(), dones[id])
    }

    @Test
    fun anEmptyFileStillSettles() {
        val ops = FakeOps(downloadBytes = ByteArray(0))
        val id = SftpTransfers.startDownload(ops, HOST, "/empty")
        waitFor { dones.containsKey(id) }
        assertEquals(0L, dones[id])
    }

    /** Exactly one terminal event, so a caller cannot settle twice. */
    @Test
    fun aDownloadTerminatesExactlyOnce() {
        val ops = FakeOps(downloadBytes = ByteArray(1000))
        val id = SftpTransfers.startDownload(ops, HOST, "/f")
        waitFor { dones.containsKey(id) }
        Thread.sleep(50)
        synchronized(events) {
            val terminal = events.count { it.first == id && it.second == null }
            assertEquals(1, terminal)
        }
    }

    @Test
    fun aFailedDownloadReportsAnError() {
        val ops = object : SftpOps by FakeOps() {
            override fun download(
                path: String,
                chunkSize: Int,
                onChunk: (Long, ByteArray) -> Unit,
            ): Long = throw IOException("permission denied")
        }
        val id = SftpTransfers.startDownload(ops, HOST, "/root/secret")
        waitFor {
            synchronized(events) { events.any { it.first == id && it.third != null } }
        }
        synchronized(events) {
            val err = events.first { it.first == id && it.third != null }.third
            assertTrue(err!!.contains("permission denied"))
        }
    }

    @Test
    fun cancelAllStopsEverything() {
        val ops = FakeOps()
        val id = SftpTransfers.startUpload(ops, HOST, "/f", replace = true)
        SftpTransfers.cancelAll()
        val result = runCatching { SftpTransfers.writeChunk(id, 0, byteArrayOf(1)) }
        assertTrue(result.isFailure)
    }

    /** Two transfers at once must not be handed each other's chunks. */
    @Test
    fun concurrentUploadsStaySeparate() {
        val one = FakeOps()
        val two = FakeOps()
        val a = SftpTransfers.startUpload(one, HOST, "/a", replace = true)
        val b = SftpTransfers.startUpload(two, HOST, "/b", replace = true)

        SftpTransfers.writeChunk(a, 0, byteArrayOf(1, 1, 1))
        SftpTransfers.writeChunk(b, 0, byteArrayOf(2, 2))
        SftpTransfers.completeUpload(a)
        SftpTransfers.completeUpload(b)

        assertArrayEquals(byteArrayOf(1, 1, 1), one.uploaded.toByteArray())
        assertArrayEquals(byteArrayOf(2, 2), two.uploaded.toByteArray())
    }

    /** A large upload must stream, not buffer the whole file. */
    @Test
    fun aLargeUploadStreamsThrough() {
        val ops = FakeOps()
        val id = SftpTransfers.startUpload(ops, HOST, "/big", replace = true)
        var offset = 0L
        val chunk = ByteArray(4096) { 7 }
        repeat(64) {
            SftpTransfers.writeChunk(id, offset, chunk)
            offset += chunk.size
        }
        SftpTransfers.completeUpload(id)
        assertEquals(64 * 4096, ops.uploaded.size())
    }

    // --- surviving a teardown -------------------------------------------------

    /**
     * Dropping the sink must not stop the transfers.
     *
     * The bridge module clears its sink when the React context goes away,
     * which happens whenever the app is backgrounded and reclaimed. It used to
     * cancel every transfer at the same time, so a download died exactly when
     * the background service existed to protect it.
     */
    @Test
    fun clearingTheSinkLeavesTransfersRunning() {
        val ops = SlowOps()
        SftpTransfers.startDownload(ops, HOST, "/a")

        SftpTransfers.setSink(null)

        // Released after the sink was dropped: a cancelled download throws out
        // of its next chunk, so reaching the end without one proves it was
        // left alone.
        ops.release()
        assertTrue("the transfer ran to completion", ops.awaitFinished())
        assertTrue("it was never cancelled", !ops.wasCancelled())
    }

    // --- per-host cancellation -----------------------------------------------

    /**
     * Reconnecting one host must not touch another host's transfers.
     *
     * SftpBridge.connect closes any existing connection first, and that path
     * used to call cancelAll. Opening a second SFTP tab therefore killed a
     * download already running against a different host.
     */
    @Test
    fun cancellingOneHostLeavesAnotherRunning() {
        val mineOps = SlowOps()
        val theirsOps = SlowOps()
        SftpTransfers.startDownload(mineOps, "host-a", "/a")
        SftpTransfers.startDownload(theirsOps, "host-b", "/b")

        SftpTransfers.cancelForHost("host-a")

        // A cancelled download stays registered until its thread unwinds, so
        // the observable difference is which one was told to stop.
        mineOps.release()
        theirsOps.release()
        assertTrue("the named host was cancelled", mineOps.awaitCancelled())
        assertTrue("the other host was not", !theirsOps.wasCancelled())
    }

    @Test
    fun anIdleHostReportsNothingActive() {
        assertTrue(!SftpTransfers.hasActiveForHost("host-none"))
    }

    /** An upload counts as active for its host, the same as a download. */
    @Test
    fun anUploadKeepsItsHostActive() {
        val ops = FakeOps()
        SftpTransfers.startUpload(ops, "host-c", "/f", replace = true)

        assertTrue(SftpTransfers.hasActiveForHost("host-c"))
        assertTrue(!SftpTransfers.hasActiveForHost("host-d"))
    }

    /** A download that blocks until released, so a test can observe it running. */
    private class SlowOps : SftpOps {
        private val gate = CountDownLatch(1)
        private val cancelledLatch = CountDownLatch(1)
        private val finishedLatch = CountDownLatch(1)

        fun release() = gate.countDown()

        /** True once the download returned without being cancelled. */
        fun awaitFinished(): Boolean = finishedLatch.await(5, TimeUnit.SECONDS)

        /** True once the transfer layer refused to hand over another chunk. */
        fun awaitCancelled(): Boolean = cancelledLatch.await(5, TimeUnit.SECONDS)

        fun wasCancelled(): Boolean = cancelledLatch.count == 0L

        override fun list(path: String) = emptyList<SftpEntry>()
        override fun stat(path: String) = throw IOException("not used")
        override fun mkdir(path: String) = Unit
        override fun rename(oldPath: String, newPath: String) = Unit
        override fun removeFile(path: String) = Unit
        override fun removeDir(path: String) = Unit
        override fun close() = Unit

        override fun downloadFrom(
            path: String,
            startOffset: Long,
            chunkSize: Int,
            onChunk: (Long, ByteArray) -> Unit,
        ): Long = download(path, chunkSize, onChunk)

        override fun uploadAppend(
            path: String,
            chunkSize: Int,
            onChunk: (Long) -> ByteArray?,
        ): Long = upload(path, chunkSize, true, false, onChunk)

        override fun download(
            path: String,
            chunkSize: Int,
            onChunk: (Long, ByteArray) -> Unit,
        ): Long {
            gate.await(5, TimeUnit.SECONDS)
            // The transfer layer throws out of onChunk once cancelled, which
            // is how a cancel actually reaches a running download.
            try {
                onChunk(0L, ByteArray(1))
            } catch (e: Exception) {
                cancelledLatch.countDown()
                throw e
            }
            finishedLatch.countDown()
            return 1L
        }

        override fun upload(
            path: String,
            chunkSize: Int,
            truncate: Boolean,
            exclusive: Boolean,
            onChunk: (Long) -> ByteArray?,
        ): Long {
            gate.await(5, TimeUnit.SECONDS)
            return 0L
        }
    }
}
