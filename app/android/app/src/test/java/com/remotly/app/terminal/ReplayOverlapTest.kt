package com.remotly.app.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

// A reattach that lands on continuity "full" replays the daemon's whole
// retained ring. The terminal it is fed into is retained across the detach, so
// everything the terminal was already shown arrives a second time and the user
// sees the same output twice. The overlap is exactly the distance between the
// write's first byte and what the terminal has consumed, and only the tail
// past that may be written.
class ReplayOverlapTest {

    @Test
    fun dropsBytesTheTerminalAlreadyHas() {
        // Terminal has consumed through offset 500; the replay restarts at 200.
        // The first 300 bytes are old, the remaining 700 are new.
        assertEquals(300, ReplayOverlap.bytes(200L, 500L, 1000))
    }

    @Test
    fun keepsEverythingForAFreshTerminal() {
        // Nothing consumed yet, so none of the replay is a duplicate. This is
        // also what an evicted or released terminal gets: the one standing in
        // for it is empty and needs the whole ring.
        assertEquals(0, ReplayOverlap.bytes(0L, 0L, 1000))
    }

    @Test
    fun dropsNothingWhenConsumedIsBehindTheWrite() {
        // The daemon dropped output between what the terminal has and the
        // replay start: that is the reported gap, and every replayed byte is
        // still new. Skipping into the write here would cut a hole mid-stream.
        assertEquals(0, ReplayOverlap.bytes(900L, 100L, 500))
    }

    @Test
    fun dropsEverythingWhenFullyConsumed() {
        // Reattaching with no new output must write nothing at all.
        assertEquals(500, ReplayOverlap.bytes(1000L, 2000L, 500))
    }

    // Over the cap the oldest replay chunks are evicted. They are never
    // written, but the bytes after them are, so the surviving run starts that
    // far along the stream. Measuring from replayed_from instead would treat
    // already-shown bytes as new and write them a second time.
    @Test
    fun countsBytesDroppedFromTheFront() {
        // Replay starts at 0, 400 bytes were evicted, 600 survive, and the
        // terminal holds through 700. The surviving run starts at 400, so 300
        // of it is old and 300 is new.
        assertEquals(300, ReplayOverlap.bytes(0L + 400L, 700L, 600))
    }
}
