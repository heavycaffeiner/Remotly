package com.remotly.app.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NotifyBridgeTest {

    @Test
    fun notificationIdIsStableForOneHostSession() {
        assertEquals(
            NotifyBridge.notificationId("host", "session"),
            NotifyBridge.notificationId("host", "session"),
        )
    }

    @Test
    fun notificationIdDiffersAcrossSessions() {
        assertNotEquals(
            NotifyBridge.notificationId("host", "s1"),
            NotifyBridge.notificationId("host", "s2"),
        )
    }

    @Test
    fun notificationIdIsNonNegative() {
        // hashCode can be negative; the mask keeps the notification id in the
        // non-negative int range Android requires.
        repeat(1000) { i ->
            assertTrue(NotifyBridge.notificationId("host$i", "session$i") >= 0)
        }
    }

    @Test
    fun permissionResultMapCarriesAllFields() {
        val map = NotifyBridge.permissionResultMap(true, true, false, null)
        assertEquals(true, map["granted"])
        assertEquals(true, map["osEnabled"])
        assertEquals(false, map["requested"])
        assertEquals(null, map["lastResult"])
        assertEquals(4, map.size)
    }
}
