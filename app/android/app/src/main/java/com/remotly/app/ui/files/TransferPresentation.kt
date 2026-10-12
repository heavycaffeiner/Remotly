package com.remotly.app.ui.files

import com.remotly.app.files.formatSize
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRecord

fun transferProgress(record: TransferRecord): Float? =
    if (record.total <= 0) null
    else (record.transferred.toDouble() / record.total.toDouble()).coerceIn(0.0, 1.0).toFloat()

fun transferRestartLabel(record: TransferRecord): String =
    if (record.resumable) "Resume" else "Retry"

fun transferStatus(record: TransferRecord): String = when (record.phase) {
    TransferPhase.Active -> if (record.total > 0) {
        "${formatSize(record.transferred)} of ${formatSize(record.total)}"
    } else {
        "${formatSize(record.transferred)}, size unknown"
    }
    TransferPhase.Done -> "Complete"
    TransferPhase.Cancelled -> "Cancelled"
    TransferPhase.Error -> "Failed: ${record.error ?: "Transfer failed"}"
}
