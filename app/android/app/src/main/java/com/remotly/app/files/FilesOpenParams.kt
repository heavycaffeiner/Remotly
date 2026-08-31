package com.remotly.app.files

// One-shot hand-off of the host the files page should open, as a JSON string
// of { "hostId": ..., "kind": "daemon" | "sftp" }. The opening page stores it
// just before opening the files page; the files page drains it on mount, the
// same way the workspace and pairing hand-offs are drained.
object FilesOpenParams {
    @Volatile
    private var open: String? = null

    fun store(open: String) {
        this.open = open
    }

    // Returns and clears the stored value. null when nothing was stored, so a
    // stale signal from a previous open cannot leak into a later page.
    fun take(): String? {
        val value = open
        open = null
        return value
    }
}
