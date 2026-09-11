package com.remotly.app.transfers

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TransferDirection { Upload, Download }

enum class TransferPhase { Active, Done, Error, Cancelled }

data class TransferRecord(
    val id: String,
    val direction: TransferDirection,
    /** Remote path: the source for a download, the target for an upload. */
    val path: String,
    /** Basename, for display. */
    val name: String,
    /** Which host it runs against, so the sheet can group by host. */
    val hostId: String,
    val phase: TransferPhase,
    val transferred: Long,
    /** Total bytes, or -1 when the size is not known up front. */
    val total: Long,
    val error: String? = null,
    val startedAt: Long,
    val endedAt: Long? = null,
    /**
     * True when this transfer can be picked up from where it stopped.
     *
     * Set from the backend's capabilities when it is registered, so the sheet
     * offers Resume only where continuing actually keeps the bytes already
     * moved. Where it is false the same button restarts from zero and is
     * labelled Retry, which is the honest description of what happens.
     */
    val resumable: Boolean = false,
)

/**
 * Process-wide file transfers.
 *
 * A transfer outlives the screen that started it, so the user can leave the
 * browser, open a terminal, and come back to a finished download. That is why
 * this sits outside any composition and outside any ViewModel: their teardown
 * runs exactly when an upload must not be abandoned.
 *
 * Screens collect [transfers] for rendering. Nothing about a running transfer
 * depends on anyone watching.
 */
object TransferRegistry {

    /**
     * A completed transfer stays on the app-wide bar briefly so the user can
     * read the confirmation and open the settled history sheet. The record
     * itself remains in [SETTLED_CAP] history until cleared or evicted.
     */
    private const val COMPLETION_BAR_RETENTION_MS = 8_000L
    private val expiryScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    /** Finished transfers kept for the sheet before the oldest is dropped. */
    private const val SETTLED_CAP = 20

    private val lock = Any()
    private val records = LinkedHashMap<String, TransferRecord>()
    private val cancels = HashMap<String, () -> Unit>()

    /**
     * How to pick a stopped transfer back up, by id.
     *
     * Held here rather than on the record because it closes over the backend
     * and the local file handle, neither of which belongs in a snapshot.
     * Registered only for transfers whose backend can restart them.
     */
    private val restarts = HashMap<String, (Long) -> Unit>()

    private val _transfers = MutableStateFlow<List<TransferRecord>>(emptyList())
    val transfers: StateFlow<List<TransferRecord>> = _transfers.asStateFlow()
    private val _barRaised = MutableStateFlow(false)
    val barRaised: StateFlow<Boolean> = _barRaised.asStateFlow()

    private fun safeTransferred(record: TransferRecord, transferred: Long): Long {
        val nonNegative = transferred.coerceAtLeast(0L)
        return if (record.total >= 0L) minOf(nonNegative, record.total) else nonNegative
    }

    private fun snapshotLocked(): List<TransferRecord> =
        records.values.sortedByDescending { it.startedAt }

    private fun emitLocked() {
        val list = snapshotLocked()
        _barRaised.value = list.any(::raisesBar)
        _transfers.value = list
    }

    /** Drops the oldest settled transfers so the list cannot grow without bound. */
    private fun trimLocked() {
        val settled = records.values
            .filter { it.phase != TransferPhase.Active }
            .sortedBy { it.endedAt ?: 0L }
        val excess = settled.size - SETTLED_CAP
        for (i in 0 until excess) {
            val victim = settled[i]
            records.remove(victim.id)
            restarts.remove(victim.id)
        }
    }

    fun list(): List<TransferRecord> = synchronized(lock) { snapshotLocked() }

    /** Transfers still running. The badge counts these. */
    fun active(): List<TransferRecord> =
        synchronized(lock) { snapshotLocked().filter { it.phase == TransferPhase.Active } }

    /**
     * True when a record would put the app-wide bar on screen.
     *
     * The bar floats over every screen, so anything else pinned to the bottom
     * edge has to know it is there or the two draw on top of each other. Both
     * the indicator's own visibility check and the snackbar's offset run
     * through this, so they cannot disagree about whether the bar is up.
     */
    private fun completionStillVisible(record: TransferRecord): Boolean {
        val ended = record.endedAt ?: return false
        return System.currentTimeMillis() - ended < COMPLETION_BAR_RETENTION_MS
    }

    fun raisesBar(record: TransferRecord): Boolean = when (record.phase) {
        TransferPhase.Active, TransferPhase.Error -> true
        TransferPhase.Done -> completionStillVisible(record)
        TransferPhase.Cancelled -> false
    }

    fun barVisible(): Boolean = synchronized(lock) { snapshotLocked().any(::raisesBar) }

    fun register(
        id: String,
        direction: TransferDirection,
        path: String,
        name: String,
        hostId: String,
        total: Long,
        resumable: Boolean,
        cancel: () -> Unit,
        restart: ((Long) -> Unit)? = null,
    ) {
        synchronized(lock) {
            records[id] = TransferRecord(
                id = id,
                direction = direction,
                path = path,
                name = name,
                hostId = hostId,
                phase = TransferPhase.Active,
                transferred = 0,
                total = total,
                startedAt = System.currentTimeMillis(),
                resumable = resumable,
            )
            cancels[id] = cancel
            if (restart != null) restarts[id] = restart
            emitLocked()
        }
    }

    fun advance(id: String, transferred: Long) {
        synchronized(lock) {
            val r = records[id] ?: return
            if (r.phase != TransferPhase.Active) return
            records[id] = r.copy(transferred = transferred)
            emitLocked()
        }
    }

    private fun scheduleCompletionExpiry(id: String, endedAt: Long) {
        expiryScope.launch {
            delay(COMPLETION_BAR_RETENTION_MS)
            synchronized(lock) {
                val current = records[id]
                // A newer attempt may reuse an id. Only the original settled
                // record is allowed to trigger this emission.
                if (
                    current != null &&
                    current.phase == TransferPhase.Done &&
                    current.endedAt == endedAt &&
                    !raisesBar(current)
                ) {
                    emitLocked()
                }
            }
        }
    }

    fun settle(
        id: String,
        phase: TransferPhase,
        error: String? = null,
        transferred: Long? = null,
    ) {
        require(phase != TransferPhase.Active) { "settle needs a terminal phase" }
        synchronized(lock) {
            val r = records[id] ?: return
            // A terminal callback can arrive after cancellation already
            // settled the row. It may still carry the only exact local
            // position, but it must never turn a cancellation into an error
            // or reopen a completed transfer.
            if (r.phase != TransferPhase.Active) {
                if (r.phase == phase && phase != TransferPhase.Done && transferred != null) {
                    records[id] = r.copy(
                        transferred = safeTransferred(r, transferred),
                        error = error ?: r.error,
                    )
                    emitLocked()
                }
                return
            }
            val endedAt = System.currentTimeMillis()
            records[id] = r.copy(
                phase = phase,
                transferred = transferred?.let { safeTransferred(r, it) } ?: r.transferred,
                error = error ?: r.error,
                endedAt = endedAt,
            )
            cancels.remove(id)
            trimLocked()
            emitLocked()
            if (phase == TransferPhase.Done) {
                scheduleCompletionExpiry(id, endedAt)
            }
        }
    }

    /**
     * Records the exact byte count after an error or cancellation. Allowed
     * after the row settles, because the transfer thread learns the final
     * flushed prefix only while it unwinds; a failed flush can make it lower
     * than the last progress event.
     */
    fun recordFinalProgress(id: String, transferred: Long) {
        synchronized(lock) {
            val r = records[id] ?: return
            if (r.phase != TransferPhase.Error && r.phase != TransferPhase.Cancelled) return
            records[id] = r.copy(transferred = safeTransferred(r, transferred))
            emitLocked()
        }
    }

    /**
     * Asks a running transfer to stop and marks it cancelled before invoking
     * the backend. The terminal backend callback can therefore not race this
     * transition and overwrite cancellation with Error.
     */
    fun cancel(id: String) {
        val cancel = synchronized(lock) {
            val action = cancels[id] ?: return
            settle(id, TransferPhase.Cancelled)
            action
        }
        runCatching { cancel() }
    }

    /**
     * True when a stopped transfer offers a way to pick it back up.
     *
     * A transfer that finished has nothing to pick up, and offering it
     * Resume would send the same bytes a second time.
     */
    fun canRetry(id: String): Boolean = synchronized(lock) {
        val r = records[id] ?: return false
        r.phase != TransferPhase.Active && r.phase != TransferPhase.Done && restarts.containsKey(id)
    }

    /**
     * Picks a failed or cancelled transfer back up.
     *
     * A resumable transfer continues from what already moved; the rest start
     * over. The record is dropped here and the backend registers a fresh one,
     * so the sheet never shows the abandoned attempt beside its replacement.
     *
     * A no-op for a transfer that is still running or cannot be restarted.
     */
    fun retry(id: String) {
        val resume: Pair<(Long) -> Unit, Long> = synchronized(lock) {
            val r = records[id] ?: return
            val restart = restarts[id] ?: return
            if (r.phase == TransferPhase.Active || r.phase == TransferPhase.Done) return
            val from = if (r.resumable) r.transferred else 0L
            records.remove(id)
            restarts.remove(id)
            cancels.remove(id)
            emitLocked()
            restart to from
        }
        resume.first(resume.second)
    }

    /** Clears settled transfers from the list. Running ones are left alone. */
    fun clearSettled() {
        synchronized(lock) {
            val settled = records.values.filter { it.phase != TransferPhase.Active }.map { it.id }
            for (id in settled) {
                records.remove(id)
                // The closure holds the backend and a file handle, so dropping
                // the row without it would keep both alive for the life of the
                // process.
                restarts.remove(id)
            }
            emitLocked()
        }
    }

    /** Test seam: drops everything, running or not. */
    fun reset() {
        synchronized(lock) {
            records.clear()
            cancels.clear()
            // Restart closures hold a backend and a file handle. Leaving them
            // behind kept both alive after everything they belonged to was
            // dropped, and a later id collision would resume a transfer this
            // reset ended.
            restarts.clear()
            emitLocked()
        }
    }
}
