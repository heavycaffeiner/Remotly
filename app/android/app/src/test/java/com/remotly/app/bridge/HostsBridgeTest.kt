package com.remotly.app.bridge

import com.remotly.app.hosts.HostHint
import com.remotly.app.hosts.HostRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostsBridgeTest {

    @Test
    fun parseHintsAcceptsAJsonArrayOfHints() {
        val hints = HostsBridge.parseHints(
            """[{"kind":0,"addr":"192.168.1.5","port":8788}]""",
        )
        assertEquals(1, hints?.size)
        assertEquals(0, hints?.first()?.kind)
        assertEquals("192.168.1.5", hints?.first()?.addr)
        assertEquals(8788, hints?.first()?.port)
    }

    @Test
    fun parseHintsAcceptsAnEmptyArray() {
        assertEquals(emptyList<HostHint>(), HostsBridge.parseHints("[]"))
    }

    @Test
    fun parseHintsRejectsUnparseableAndNonArrayInput() {
        assertNull(HostsBridge.parseHints("not json"))
        assertNull(HostsBridge.parseHints("null"))
        // A valid JSON object is still not a JSON array of hints.
        assertNull(HostsBridge.parseHints("""{"kind":0}"""))
    }

    @Test
    fun addResultMapCarriesIdAndDuplicateFlag() {
        val map = HostsBridge.addResultMap("abc", true)
        assertEquals(mapOf("id" to "abc", "duplicate" to true), map)
    }

    @Test
    fun listResultMapWrapsTheJsonArrayString() {
        val json = HostsBridge.toJson(emptyList<HostRecord>())
        assertEquals(HostsBridge.listResultMap(json), mapOf("hosts" to "[]"))
    }
}
