package com.remotly.app.transfers

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TransferRegistryTest {

    @Before
    fun setUp() {
        TransferRegistry.reset()
    }

    @After
    fun tearDown() {
        TransferRegistry.reset()
    }

    private fun register(
        id: String,
        resumable: Boolean = false,
        cancel: () -> Unit = {},
        restart: ((Long) -> Unit)? = null,
    ) {
        TransferRegistry.register(
            id = id,
            direction = TransferDirection.Download,
            path = "/remote/$id",
            name = id,
            hostId = "h1",
            total = 100,
            resumable = resumable,
            cancel = cancel,
            restart = restart,
        )
    }

    @Test
    fun `a cancel that throws still settles the record`() {
        register("t1", cancel = { throw IllegalStateException("boom") })
        TransferRegistry.cancel("t1")
        assertEquals(TransferPhase.Cancelled, TransferRegistry.list().single().phase)
        assertTrue(TransferRegistry.active().isEmpty())
    }

    @Test
    fun `progress is ignored once a transfer has settled`() {
        register("t1")
        TransferRegistry.advance("t1", 40)
        TransferRegistry.settle("t1", TransferPhase.Done)
        TransferRegistry.advance("t1", 90)
        assertEquals(40, TransferRegistry.list().single().transferred)
    }

    @Test
    fun `retry resumes from what moved only when the backend can resume`() {
        var resumedFrom = -1L
        register("res", resumable = true, restart = { resumedFrom = it })
        TransferRegistry.advance("res", 60)
        TransferRegistry.settle("res", TransferPhase.Error, "dropped")
        TransferRegistry.retry("res")
        assertEquals(60, resumedFrom)

        var retriedFrom = -1L
        register("fresh", resumable = false, restart = { retriedFrom = it })
        TransferRegistry.advance("fresh", 60)
        TransferRegistry.settle("fresh", TransferPhase.Error, "dropped")
        TransferRegistry.retry("fresh")
        assertEquals(0, retriedFrom)
    }

    @Test
    fun `retry uses the final safe position after failure and cancellation`() {
        var failedFrom = -1L
        register("failed", resumable = true, restart = { failedFrom = it })
        TransferRegistry.advance("failed", 80)
        TransferRegistry.settle("failed", TransferPhase.Error, "disk full")
        TransferRegistry.recordFinalProgress("failed", 37)
        TransferRegistry.retry("failed")
        assertEquals(37, failedFrom)

        var cancelledFrom = -1L
        register("cancelled", resumable = true, restart = { cancelledFrom = it })
        TransferRegistry.advance("cancelled", 80)
        TransferRegistry.cancel("cancelled")
        TransferRegistry.recordFinalProgress("cancelled", 29)
        assertEquals(TransferPhase.Cancelled, TransferRegistry.list().single().phase)
        TransferRegistry.retry("cancelled")
        assertEquals(29, cancelledFrom)
    }

    @Test
    fun `retry drops the abandoned record so it cannot sit beside its replacement`() {
        register("t1", restart = {})
        TransferRegistry.settle("t1", TransferPhase.Error, "dropped")
        TransferRegistry.retry("t1")
        assertTrue(TransferRegistry.list().isEmpty())
        assertFalse(TransferRegistry.canRetry("t1"))
    }

    @Test
    fun `a running transfer is never retried`() {
        var restarted = false
        register("t1", restart = { restarted = true })
        TransferRegistry.retry("t1")
        assertFalse(restarted)
        assertEquals(TransferPhase.Active, TransferRegistry.list().single().phase)
    }

    @Test
    fun `a finished transfer is never offered again`() {
        var restarted = false
        register("t1", restart = { restarted = true })
        TransferRegistry.settle("t1", TransferPhase.Done)
        assertFalse(TransferRegistry.canRetry("t1"))
        TransferRegistry.retry("t1")
        assertFalse(restarted)
        assertEquals(TransferPhase.Done, TransferRegistry.list().single().phase)
    }

    @Test
    fun `settled transfers are capped and the oldest goes first`() {
        for (i in 1..25) {
            register("t$i")
            TransferRegistry.settle("t$i", TransferPhase.Done)
        }
        val kept = TransferRegistry.list().map { it.id }.toSet()
        assertEquals(20, kept.size)
        assertFalse(kept.contains("t1"))
        assertTrue(kept.contains("t25"))
    }

    @Test
    fun `clearSettled leaves running transfers alone`() {
        register("running")
        register("done")
        TransferRegistry.settle("done", TransferPhase.Done)
        TransferRegistry.clearSettled()
        assertEquals(listOf("running"), TransferRegistry.list().map { it.id })
    }

    @Test
    fun `a failed transfer raises the bar so nothing else draws over it`() {
        register("t1")
        TransferRegistry.settle("t1", TransferPhase.Error, "dropped")
        assertTrue(TransferRegistry.barVisible())
        TransferRegistry.clearSettled()
        assertFalse(TransferRegistry.barVisible())
    }
}
