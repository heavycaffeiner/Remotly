package com.remotly.app.ui.files

import com.remotly.app.transfers.TransferDirection
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferPresentationTest {
    private fun record(total: Long = 100, moved: Long = 25, phase: TransferPhase = TransferPhase.Active, resumable: Boolean = false) =
        TransferRecord("id", TransferDirection.Download, "/file", "file", "host", phase, moved, total, startedAt = 0, resumable = resumable)

    @Test
    fun `unknown and empty totals remain indeterminate`() {
        assertNull(transferProgress(record(total = -1)))
        assertNull(transferProgress(record(total = 0)))
        assertTrue(transferStatus(record(total = -1)).contains("size unknown"))
    }

    @Test
    fun `progress is bounded even when backend reports outside total`() {
        assertEquals(0.25f, transferProgress(record())!!, 0f)
        assertEquals(0f, transferProgress(record(moved = -1))!!, 0f)
        assertEquals(1f, transferProgress(record(moved = 200))!!, 0f)
    }

    @Test
    fun `restart text makes retry and resume different promises`() {
        assertEquals("Retry", transferRestartLabel(record(phase = TransferPhase.Error)))
        assertEquals("Resume", transferRestartLabel(record(phase = TransferPhase.Error, resumable = true)))
        assertEquals("Resume", transferRestartLabel(record(phase = TransferPhase.Cancelled, resumable = true)))
    }

    @Test
    fun `failure details stay readable without relying on color`() {
        val failure = record(phase = TransferPhase.Error).copy(error = "Permission denied")
        assertEquals("Failed: Permission denied", transferStatus(failure))
        assertEquals("Cancelled", transferStatus(record(phase = TransferPhase.Cancelled)))
        assertEquals("Complete", transferStatus(record(phase = TransferPhase.Done)))
    }
}
