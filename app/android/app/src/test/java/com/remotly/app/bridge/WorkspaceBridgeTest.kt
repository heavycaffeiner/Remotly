package com.remotly.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkspaceBridgeTest {

    @Test
    fun hostIdValidAcceptsOneToSixtyFourCharacters() {
        assertTrue(WorkspaceBridge.hostIdValid("a"))
        assertTrue(WorkspaceBridge.hostIdValid("a".repeat(64)))
    }

    @Test
    fun hostIdValidRejectsEmptyAndTooLong() {
        assertFalse(WorkspaceBridge.hostIdValid(""))
        assertFalse(WorkspaceBridge.hostIdValid("a".repeat(65)))
    }

    @Test
    fun loadResultMapWrapsTheDocument() {
        assertEquals(WorkspaceBridge.loadResultMap(""), mapOf("json" to ""))
        assertEquals(WorkspaceBridge.loadResultMap("""{"tabs":[]}"""), mapOf("json" to """{"tabs":[]}"""))
    }

    @Test
    fun takeOpenResultMapCarriesTheHostId() {
        assertEquals(WorkspaceBridge.takeOpenResultMap("h1"), mapOf("hostId" to "h1"))
        assertEquals(WorkspaceBridge.takeOpenResultMap(""), mapOf("hostId" to ""))
    }
}
