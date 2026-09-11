package com.remotly.app.transfers

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

    /**
     * Keeps the foreground service up while anything is running.
     *
     * Both directions register here, so this is the one place that knows
     * whether the app has work that must survive being backgrounded. Without
     * it Android is free to stop the process's threads once the user leaves,
     * and a transfer dies partway with nothing having failed.
     *
     * Only the transition is reported, so a burst of transfers costs one call
     * rather than one per transfer.
     */
    fun interface ServiceGate {
        fun setActive(active: Boolean)
    }

    @Volatile
    private var serviceGate: ServiceGate? = null
    private var serviceActive = false

    fun setServiceGate(gate: ServiceGate?) {
        serviceGate = gate
    }

    private fun snapshotLocked(): List<TransferRecord> =
        records.values.sortedByDescending { it.startedAt }

    private fun emitLocked() {
        val list = snapshotLocked()
        val running = list.any { it.phase == TransferPhase.Active }
        if (running != serviceActive) {
            serviceActive = running
            // A failed gate costs the guarantee, not the transfer. Nothing
            // here is worth failing a transfer over.
            runCatching { serviceGate?.setActive(running) }
        }
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
    fun raisesBar(record: TransferRecord): Boolean =
        record.phase == TransferPhase.Active || record.phase == TransferPhase.Error

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

    fun settle(id: String, phase: TransferPhase, error: String? = null) {
        require(phase != TransferPhase.Active) { "settle needs a terminal phase" }
        synchronized(lock) {
            val r = records[id] ?: return
            records[id] = r.copy(
                phase = phase,
                error = error ?: r.error,
                endedAt = System.currentTimeMillis(),
            )
            cancels.remove(id)
            trimLocked()
            emitLocked()
        }
    }

    /** Asks a running transfer to stop. Settling is left to its own callback. */
    fun cancel(id: String) {
        val cancel = synchronized(lock) { cancels.remove(id) } ?: return
        // A cancel that throws must not strand the record as active.
        runCatching { cancel() }
        settle(id, TransferPhase.Cancelled)
    }

    /** True when a stopped transfer offers a way to pick it back up. */
    fun canRetry(id: String): Boolean = synchronized(lock) {
        val r = records[id] ?: return false
        r.phase != TransferPhase.Active && restarts.containsKey(id)
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
            if (r.phase == TransferPhase.Active) return
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
