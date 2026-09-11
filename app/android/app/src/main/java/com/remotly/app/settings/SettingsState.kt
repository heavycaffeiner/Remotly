package com.remotly.app.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The live settings every screen reads.
 *
 * Writes apply to the flow first and persist after, so a toggle moves under
 * the finger rather than after a file write. Anything that stops the write
 * from landing rolls the flow back, because a control left in its new
 * position would claim a preference the next launch will not have.
 */
object SettingsState {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _settings = MutableStateFlow(AppSettings())

    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    /**
     * Reads the stored settings once.
     *
     * Awaited before the first frame is composed, so the theme is drawn from
     * what is on disk rather than from the defaults and then corrected, which
     * shows as a light flash to anyone who chose the dark theme.
     */
    suspend fun load() {
        val store = SettingsModule.store ?: return
        withContext(Dispatchers.IO) { runCatching { store.load() } }
            .onSuccess { _settings.value = it }
    }

    /**
     * Applies a change and persists it.
     *
     * [onFailure] is called with the restored value when the write cannot
     * land, so a screen can say so instead of silently disagreeing with disk.
     * It runs on the main thread, because its only caller shows a message.
     */
    fun update(onFailure: (Throwable) -> Unit = {}, change: (AppSettings) -> AppSettings) {
        val previous = _settings.value
        val next = change(previous)
        if (next == previous) return
        _settings.value = next

        val store = SettingsModule.store
        if (store == null) {
            // No store means the settings file failed to open at startup.
            // Keeping the new value on screen would promise a preference that
            // is not stored anywhere.
            _settings.value = previous
            scope.launch {
                withContext(Dispatchers.Main) {
                    onFailure(SettingsStoreException("settings storage is unavailable"))
                }
            }
            return
        }

        scope.launch {
            // The reload is inside the same runCatching as the save: the
            // store normalizes on write, and a throw from either one has to
            // roll the value back rather than escape into the scope.
            runCatching {
                store.save(next)
                store.load()
            }.onSuccess { stored ->
                _settings.value = stored
            }.onFailure { cause ->
                _settings.value = previous
                withContext(Dispatchers.Main) { onFailure(cause) }
            }
        }
    }
}
