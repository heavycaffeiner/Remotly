package com.remotly.app.session

/**
 * Where each file browser tab is looking.
 *
 * A tab that is not the active one is not composed, so its directory cannot
 * live in the composable's own state: coming back would drop the user at the
 * root of a tree they had walked into. This holds one directory per tab id,
 * outside composition, for the same reason the SSH sessions live outside it.
 */
object FilesTabs {
    private val cwds = HashMap<String, String>()

    /** The directory a tab is showing, or null when it has not moved yet. */
    @Synchronized
    fun remembered(tabId: String): String? = cwds[tabId]

    @Synchronized
    fun setCwd(tabId: String, path: String) {
        if (tabId.isEmpty()) return
        cwds[tabId] = path
    }

    /** Drops a closed tab's directory so the map cannot grow forever. */
    @Synchronized
    fun forget(tabId: String) {
        cwds.remove(tabId)
    }

    /** Test seam. */
    @Synchronized
    fun reset() {
        cwds.clear()
    }
}
