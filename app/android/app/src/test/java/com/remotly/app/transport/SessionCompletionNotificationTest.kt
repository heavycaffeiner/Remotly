package com.remotly.app.transport

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCompletionNotificationTest {

    private fun session(
        title: String = "Build",
        exit: SessionExit? = SessionExit(0, ""),
    ) = SessionMeta(
        id = "0".repeat(64),
        title = title,
        kind = "agent",
        command = "",
        cwd = "",
        cols = 80,
        rows = 24,
        createdAt = "2026-08-21T00:00:00Z",
        lastActivity = "2026-08-21T00:00:00Z",
        running = false,
        exit = exit,
    )

    @Test
    fun normalExitUsesSessionTitleAndCodeWithoutOutputPreview() {
        assertEquals("Completed: Build", SessionCompletionNotification.title(session()))
        assertEquals("Exit code 0", SessionCompletionNotification.text(session()))
    }

    @Test
    fun signalExitUsesSignal() {
        assertEquals(
            "Stopped by SIGTERM",
            SessionCompletionNotification.text(session(exit = SessionExit(143, "SIGTERM"))),
        )
    }

    @Test
    fun missingExitHasSafeFallback() {
        assertEquals(
            "Terminal session ended",
            SessionCompletionNotification.text(session(exit = null)),
        )
    }
}
