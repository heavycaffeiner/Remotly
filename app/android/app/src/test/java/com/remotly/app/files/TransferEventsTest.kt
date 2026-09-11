package com.remotly.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TransferEventsTest {

    private fun watchOutcome(id: String): Triple<Long, Boolean, String?>? {
        var seen: Triple<Long, Boolean, String?>? = null
        TransferEvents.watch(id) { transferred, done, error -> seen = Triple(transferred, done, error) }
        return seen
    }

    @Test
    fun `a transfer that finished before anyone watched is still reported`() {
        TransferEvents.onEvent("early-done", 0, null, 12, null)
        assertEquals(Triple(12L, true, null), watchOutcome("early-done"))
    }

    @Test
    fun `a failure before the watcher arrives carries its final offset`() {
        TransferEvents.onEvent("early-fail", 7, null, null, "dropped")
        assertEquals(Triple(7L, false, "dropped"), watchOutcome("early-fail"))
    }

    @Test
    fun `progress before the watcher is not replayed and the outcome arrives once`() {
        TransferEvents.onEvent("late", 3, null, null, null)
        assertNull(watchOutcome("late"))

        val outcomes = mutableListOf<Boolean>()
        TransferEvents.watch("late") { _, done, _ -> outcomes.add(done) }
        TransferEvents.onEvent("late", 9, null, 9, null)
        TransferEvents.onEvent("late", 9, null, 9, null)
        assertEquals(listOf(true), outcomes)
    }

    @Test
    fun `stopping a watch drops a stored outcome`() {
        TransferEvents.onEvent("stopped", 0, null, 1, null)
        TransferEvents.stopWatching("stopped")
        assertNull(watchOutcome("stopped"))
    }
}
