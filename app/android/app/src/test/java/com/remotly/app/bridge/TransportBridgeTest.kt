package com.remotly.app.bridge

import com.remotly.app.transport.TransportHub
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportBridgeTest {

    @Test
    fun parseChannelIdAcceptsUserChannelRange() {
        assertEquals(1L, TransportBridge.parseChannelId(1.0))
        assertEquals(42L, TransportBridge.parseChannelId(42.0))
        assertEquals(0xFFFFFFFFL, TransportBridge.parseChannelId(0xFFFFFFFF.toDouble()))
    }

    @Test
    fun parseChannelIdRejectsControlChannelZero() {
        assertNull(TransportBridge.parseChannelId(0.0))
    }

    @Test
    fun parseChannelIdRejectsOutOfRange() {
        assertNull(TransportBridge.parseChannelId(-1.0))
        // 2^32 is just past the 32-bit unsigned ceiling.
        assertNull(TransportBridge.parseChannelId(0xFFFFFFFF.toDouble() + 1.0))
    }

    @Test
    fun parseChannelIdRejectsNonIntegralAndNonNumeric() {
        assertNull(TransportBridge.parseChannelId(1.5))
        assertNull(TransportBridge.parseChannelId(Double.NaN))
        assertNull(TransportBridge.parseChannelId(Double.POSITIVE_INFINITY))
        assertNull(TransportBridge.parseChannelId(Double.NEGATIVE_INFINITY))
    }

    @Test
    fun connectResultMapCarriesDaemonIdentity() {
        val map = TransportBridge.connectResultMap("daemon", "pub")
        assertEquals("daemon", map["daemonName"])
        assertEquals("pub", map["daemonPub"])
        assertEquals(2, map.size)
    }

    @Test
    fun statusMapOmitsAbsentIdentity() {
        val status = TransportHub.Status(
            connected = false,
            state = "disconnected",
            daemonName = null,
            daemonPub = null,
            via = null,
        )
        val map = TransportBridge.statusMap(status)
        assertEquals(false, map["connected"])
        assertEquals("disconnected", map["state"])
        assertFalse(map.containsKey("daemonName"))
        assertFalse(map.containsKey("daemonPub"))
    }

    @Test
    fun statusMapCarriesIdentityWhenConnected() {
        val status = TransportHub.Status(
            connected = true,
            state = "connected",
            daemonName = "daemon",
            daemonPub = "pub",
            via = "direct",
        )
        val map = TransportBridge.statusMap(status)
        assertEquals(true, map["connected"])
        assertEquals("daemon", map["daemonName"])
        assertEquals("pub", map["daemonPub"])
    }

    @Test
    fun controlResultMapWrapsResponseJson() {
        val map = TransportBridge.controlResultMap("""{"ok":true}""")
        assertEquals(mapOf("response" to """{"ok":true}"""), map)
    }
}
