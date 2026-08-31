package com.remotly.app.workspace

// One-shot hand-off of the host the workspace page should open. The hosts
// page stores it just before opening the workspace bundle; the workspace
// page drains it on mount, the same way the pairing deep link is drained.
object WorkspaceOpenParams {
    @Volatile
    private var hostId: String? = null

    fun store(hostId: String) {
        this.hostId = hostId
    }

    // Returns and clears the stored id. null when nothing was stored.
    fun take(): String? {
        val value = hostId
        hostId = null
        return value
    }
}
