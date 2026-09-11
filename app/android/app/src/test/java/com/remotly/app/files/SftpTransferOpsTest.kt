package com.remotly.app.files

import com.remotly.app.transfers.TransferDirection
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRegistry
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SftpTransferOpsTest {

    @Before
    fun setUp() {
        TransferRegistry.reset()
    }

    @After
    fun tearDown() {
        TransferRegistry.reset()
    }

    // --- collision decision: Keep both vs Replace ---

    @Test
    fun `replace keeps exactly the name asked for, even when it is already taken`() {
        val name = SftpTransferOps.resolvedTransferName("photo.png", replace = true) { true }
        assertEquals("photo.png", name)
    }

    @Test
    fun `keep both returns the requested name unchanged when nothing collides`() {
        val name = SftpTransferOps.resolvedTransferName("photo.png", replace = false) { false }
        assertEquals("photo.png", name)
    }

    @Test
    fun `keep both picks the first numbered name that is free`() {
        val taken = setOf("photo.png", "photo (1).png")
        val name = SftpTransferOps.resolvedTransferName("photo.png", replace = false) { it in taken }
        assertEquals("photo (2).png", name)
    }

    @Test
    fun `keep both never reports a name it just proved is taken`() {
        val taken = setOf("report.txt", "report (1).txt", "report (2).txt", "report (3).txt")
        val name = SftpTransferOps.resolvedTransferName("report.txt", replace = false) { it in taken }
        assertFalse(name in taken)
    }

    // --- discard rule: a failed transfer's local bytes ---

    @Test
    fun `a transfer that never armed never keeps a partial, resumable backend or not`() {
        assertFalse(SftpTransferOps.keepPartialOnFailure(armed = false, resumable = true))
        assertFalse(SftpTransferOps.keepPartialOnFailure(armed = false, resumable = false))
    }

    @Test
    fun `an armed transfer keeps its partial only when the backend can resume it`() {
        assertTrue(SftpTransferOps.keepPartialOnFailure(armed = true, resumable = true))
        assertFalse(SftpTransferOps.keepPartialOnFailure(armed = true, resumable = false))
    }

    @Test
    fun `a fresh destination is discarded only when nothing resumable survived`() {
        assertTrue(SftpTransferOps.shouldDiscardDestination(freshDestination = true, keepPartial = false))
        assertFalse(SftpTransferOps.shouldDiscardDestination(freshDestination = true, keepPartial = true))
    }

    @Test
    fun `an existing file being replaced is never discarded before the transfer armed`() {
        // The destination is untouched until the transfer arms and truncates
        // it, so a failure before that point must leave the user's original
        // file exactly as it was rather than delete it.
        assertFalse(SftpTransferOps.shouldDiscardDestination(freshDestination = false, keepPartial = false))
        assertFalse(SftpTransferOps.shouldDiscardDestination(freshDestination = false, keepPartial = true))
    }

    // --- resumable vs restart: retry must continue, not silently restart ---

    @Test
    fun `a registered transfer resumes from what moved, not from zero`() {
        var seenResumeFrom = -1L
        SftpTransferOps.armTransfer(
            id = "t1",
            direction = TransferDirection.Download,
            path = "/remote/file",
            name = "file",
            hostId = "h1",
            total = 1000,
            cancel = {},
            onRestart = { from -> seenResumeFrom = from },
        )
        TransferRegistry.advance("t1", 400)
        TransferRegistry.settle("t1", TransferPhase.Error, "dropped")

        TransferRegistry.retry("t1")

        assertEquals(400L, seenResumeFrom)
    }

    @Test
    fun `a registered transfer offers retry after it settles`() {
        SftpTransferOps.armTransfer(
            id = "t2",
            direction = TransferDirection.Upload,
            path = "/remote/file",
            name = "file",
            hostId = "h1",
            total = 1000,
            cancel = {},
            onRestart = {},
        )
        assertFalse(TransferRegistry.canRetry("t2"))
        TransferRegistry.settle("t2", TransferPhase.Error, "dropped")
        assertTrue(TransferRegistry.canRetry("t2"))
    }
}
