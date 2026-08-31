package com.remotly.app.transport

// Presentation for a daemon terminal completion signal. This intentionally
// contains only metadata from session.update, never terminal output or its
// preview, so a lock-screen notification cannot disclose shell content.
internal object SessionCompletionNotification {
    fun title(session: SessionMeta): String {
        val label = session.title.trim().ifEmpty { "Terminal" }
        return "Completed: $label"
    }

    fun text(session: SessionMeta): String {
        val exit = session.exit
        val signal = exit?.signal?.trim().orEmpty()
        return when {
            signal.isNotEmpty() -> "Stopped by $signal"
            exit != null -> "Exit code ${exit.code}"
            else -> "Terminal session ended"
        }
    }
}
