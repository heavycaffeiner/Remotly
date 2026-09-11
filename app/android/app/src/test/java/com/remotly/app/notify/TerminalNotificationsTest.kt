package com.remotly.app.notify

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalNotificationsTest {

    @Test
    fun `boundTitle trims and caps remote-supplied text`() {
        assertEquals("hello", TerminalNotifications.boundTitle("  hello  "))
        val huge = "x".repeat(TerminalNotifications.MAX_TITLE_CHARS + 500)
        val bounded = TerminalNotifications.boundTitle(huge)
        assertEquals(TerminalNotifications.MAX_TITLE_CHARS, bounded.length)
        assertEquals(huge.take(TerminalNotifications.MAX_TITLE_CHARS), bounded)
    }

    @Test
    fun `boundBody trims and caps remote-supplied text`() {
        assertEquals("hello", TerminalNotifications.boundBody("  hello  "))
        val huge = "y".repeat(TerminalNotifications.MAX_BODY_CHARS + 5000)
        val bounded = TerminalNotifications.boundBody(huge)
        assertEquals(TerminalNotifications.MAX_BODY_CHARS, bounded.length)
        assertEquals(huge.take(TerminalNotifications.MAX_BODY_CHARS), bounded)
    }
}
