package com.remotly.app.session

data class FilesTabState(
    val cwd: String? = null,
    val history: List<String> = emptyList(),
    val query: String = "",
    val searchOpen: Boolean = false,
)

object FilesTabs {
    private val states = HashMap<String, FilesTabState>()

    @Synchronized
    fun state(tabId: String): FilesTabState = states[tabId] ?: FilesTabState()

    @Synchronized
    fun setState(tabId: String, state: FilesTabState) {
        if (tabId.isNotEmpty()) states[tabId] = state.copy(history = state.history.toList())
    }

    @Synchronized
    fun remembered(tabId: String): String? = states[tabId]?.cwd

    @Synchronized
    fun setCwd(tabId: String, path: String) {
        if (tabId.isNotEmpty()) states[tabId] = state(tabId).copy(cwd = path)
    }

    @Synchronized
    fun forget(tabId: String) { states.remove(tabId) }

    @Synchronized
    fun reset() { states.clear() }
}
