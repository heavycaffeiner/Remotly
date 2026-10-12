package com.remotly.app.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class SettingsController(
    scope: CoroutineScope,
    private val loadStored: () -> AppSettings,
    private val saveStored: (AppSettings) -> AppSettings,
    private val recoverStored: () -> AppSettings,
    private val reportFailure: suspend ((Throwable) -> Unit, Throwable) -> Unit = { callback, cause -> callback(cause) },
) {
    private val lock = Any()
    private val live = MutableStateFlow(AppSettings())
    private val errors = MutableStateFlow<String?>(null)
    private var durable = AppSettings()
    private var revision = 0L
    private sealed interface Request {
        data class Save(val revision: Long, val settings: AppSettings, val onFailure: (Throwable) -> Unit,
            val complete: CompletableDeferred<Boolean>? = null) : Request
        data class Recover(val revision: Long, val complete: CompletableDeferred<Unit>) : Request
        data class Load(val revision: Long, val complete: CompletableDeferred<Unit>) : Request
    }
    private val requests = Channel<Request>(Channel.UNLIMITED)
    val settings = live.asStateFlow()
    val error = errors.asStateFlow()

    init {
        scope.launch {
            for (request in requests) {
                val result = runCatching {
                    when (request) {
                        is Request.Save -> saveStored(request.settings)
                        is Request.Recover -> recoverStored()
                        is Request.Load -> loadStored()
                    }
                }
                val requestRevision = when (request) {
                    is Request.Save -> request.revision
                    is Request.Recover -> request.revision
                    is Request.Load -> request.revision
                }
                var failure: Throwable? = null
                synchronized(lock) {
                    result.onSuccess { saved ->
                        durable = saved
                        if (revision == requestRevision) { live.value = saved; errors.value = null }
                    }.onFailure { cause ->
                        if (revision == requestRevision) {
                            if (request is Request.Save) live.value = durable
                            errors.value = cause.message ?: "Cannot read or save settings"
                            failure = cause
                        }
                    }
                }
                if (request is Request.Save) failure?.let { cause ->
                    reportFailure({ currentCause ->
                        synchronized(lock) {
                            if (revision == request.revision) request.onFailure(currentCause)
                        }
                    }, cause)
                }
                when (request) {
                    is Request.Recover -> request.complete.complete(Unit)
                    is Request.Load -> request.complete.complete(Unit)
                    is Request.Save -> request.complete?.complete(result.isSuccess)
                }
            }
        }
    }

    suspend fun load() {
        val complete = CompletableDeferred<Unit>()
        synchronized(lock) { check(requests.trySend(Request.Load(revision, complete)).isSuccess) }
        complete.await()
    }

    fun update(onFailure: (Throwable) -> Unit = {}, change: (AppSettings) -> AppSettings) {
        synchronized(lock) {
            val next = change(live.value)
            if (next == live.value) return
            revision++
            live.value = next
            check(requests.trySend(Request.Save(revision, next, onFailure)).isSuccess)
        }
    }

    suspend fun saveChange(change: (AppSettings) -> AppSettings): Boolean {
        val complete = CompletableDeferred<Boolean>()
        synchronized(lock) {
            val next = change(live.value)
            revision++
            live.value = next
            check(requests.trySend(Request.Save(revision, next, {}, complete)).isSuccess)
        }
        return complete.await()
    }

    suspend fun recoverWithDefaults() {
        val complete = CompletableDeferred<Unit>()
        synchronized(lock) {
            revision++
            check(requests.trySend(Request.Recover(revision, complete)).isSuccess)
        }
        complete.await()
    }
}

object SettingsState {
    private fun store(): SettingsStore = SettingsModule.store
        ?: throw SettingsStoreException("settings storage is unavailable")
    private val controller = SettingsController(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        loadStored = { store().load() },
        saveStored = { settings -> store().run { save(settings); load() } },
        recoverStored = { store().recoverWithDefaults() },
        reportFailure = { callback, cause -> withContext(Dispatchers.Main) { callback(cause) } },
    )
    val settings = controller.settings
    val error = controller.error
    suspend fun load() = controller.load()
    fun update(onFailure: (Throwable) -> Unit = {}, change: (AppSettings) -> AppSettings) = controller.update(onFailure, change)
    suspend fun saveChange(change: (AppSettings) -> AppSettings): Boolean = controller.saveChange(change)
    suspend fun recoverWithDefaults() = controller.recoverWithDefaults()
}
