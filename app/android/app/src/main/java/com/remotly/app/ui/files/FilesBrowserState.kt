package com.remotly.app.ui.files

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver

// The shell retains one instance per tab when moving Files between panes.
class FilesBrowserState(
    cwd: String? = null,
    history: List<String> = emptyList(),
    query: String = "",
    searchOpen: Boolean = false,
) {
    val directory = mutableStateOf(cwd ?: "/")
    val visited = mutableStateOf(history)
    val searchQuery = mutableStateOf(query)
    val searching = mutableStateOf(searchOpen)
    var hasDirectory by mutableStateOf(cwd != null)

    fun goBack(ready: Boolean, onNavigate: (String) -> Unit): Boolean {
        if (searching.value || searchQuery.value.isNotEmpty()) {
            searching.value = false
            searchQuery.value = ""
            return true
        }
        if (!ready || visited.value.isEmpty()) return false
        val previous = visited.value.last()
        visited.value = visited.value.dropLast(1)
        onNavigate(previous)
        return true
    }

    companion object {
        val Saver = listSaver<FilesBrowserState, Any>(
            save = { listOf(it.directory.value, it.visited.value, it.searchQuery.value, it.searching.value, it.hasDirectory) },
            restore = {
                @Suppress("UNCHECKED_CAST")
                FilesBrowserState(
                    cwd = if (it[4] as Boolean) it[0] as String else null,
                    history = it[1] as List<String>,
                    query = it[2] as String,
                    searchOpen = it[3] as Boolean,
                )
            },
        )
    }
}
