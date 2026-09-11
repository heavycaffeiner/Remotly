package com.remotly.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotly.app.files.formatSize
import com.remotly.app.transfers.TransferDirection
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRecord
import com.remotly.app.transfers.TransferRegistry

/**
 * The app-wide transfer bar and the sheet it opens.
 *
 * Hosted above the navigation graph, not inside a screen. A transfer outlives
 * the screen that started it, so a bar mounted within one disappears the
 * moment the user navigates away, which is exactly when they most want to see
 * it.
 */

/**
 * Clearance under the bar, matching the bottom controls already reserved by
 * the shell. The existing 80dp keeps the terminal's 56dp key row clear; it is
 * placement only and does not intercept terminal input.
 */
private val TAB_BAR_HEIGHT = 80.dp

/**
 * Height to reserve under anything else pinned to the bottom of a screen,
 * so the bar does not cover it. Approximate: the bar sizes itself to its
 * content, and a value that tracked it exactly would mean measuring across
 * the whole navigation graph.
 */
val TRANSFER_BAR_HEIGHT = 52.dp

/** [TRANSFER_BAR_HEIGHT] while the bar is on screen, zero while it is not. */
@Composable
fun transferBarClearance(): Dp {
    val raised by TransferRegistry.barRaised.collectAsStateWithLifecycle()
    return if (raised) TRANSFER_BAR_HEIGHT else 0.dp
}

@Composable
fun TransferBar(modifier: Modifier = Modifier) {
    val transfers by TransferRegistry.transfers.collectAsStateWithLifecycle()
    val barRaised by TransferRegistry.barRaised.collectAsStateWithLifecycle()
    var sheetOpen by remember { mutableStateOf(false) }

    val raised = if (barRaised) transfers.filter(TransferRegistry::raisesBar) else emptyList()
    if (raised.isNotEmpty()) {
        BarSurface(raised, onOpen = { sheetOpen = true }, modifier = modifier)
    }

    if (sheetOpen) {
        TransferSheet(transfers, onDismiss = { sheetOpen = false })
    }
}

@Composable
private fun BarSurface(
    raised: List<TransferRecord>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val running = raised.count { it.phase == TransferPhase.Active }
    val failed = raised.count { it.phase == TransferPhase.Error }
    val completed = raised.count { it.phase == TransferPhase.Done }
    val summary = buildList {
        if (running > 0) add(if (running == 1) "1 transfer running" else "$running transfers running")
        if (failed > 0) add(if (failed == 1) "1 transfer failed" else "$failed transfers failed")
        if (completed > 0) {
            add(if (completed == 1) "1 transfer complete" else "$completed transfers complete")
        }
    }.joinToString(", ")
    // The tone follows the worst state, and the summary says which in words,
    // so the state is never carried by colour alone.
    val container =
        if (failed > 0) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer
    val content =
        if (failed > 0) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer

    Surface(
        color = container,
        contentColor = content,
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = TAB_BAR_HEIGHT),
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = "Show transfers", onClick = onOpen)
                .padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            val active = raised.firstOrNull { it.phase == TransferPhase.Active }
            if (active != null) {
                val fraction = progressOf(active)
                if (fraction != null) {
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clearAndSetSemantics {
                                contentDescription = "${active.name}, ${(fraction * 100).toInt()} percent"
                            },
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .clearAndSetSemantics {
                                contentDescription = "${active.name}, size unknown"
                            },
                    )
                }
            }
        }
    }
}

/** Progress as a fraction, or null when the total is not known up front. */
private fun progressOf(record: TransferRecord): Float? {
    if (record.total <= 0) return null
    return (record.transferred.toFloat() / record.total.toFloat()).coerceIn(0f, 1f)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(transfers: List<TransferRecord>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Transfers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            if (transfers.any { it.phase != TransferPhase.Active }) {
                TextButton(onClick = { TransferRegistry.clearSettled() }) { Text("Clear finished") }
            }
        }
        if (transfers.isEmpty()) {
            Text(
                "Nothing has been transferred yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
            )
        }
        // Bounded and scrollable: history keeps twenty settled rows, which at
        // a large font scale is taller than the sheet.
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(transfers, key = { it.id }) { record -> TransferRow(record) }
        }
        // SFTP carries no whole-file hash, so a finished transfer is not a
        // verified one. Saying so is cheaper than implying otherwise.
        Text(
            "Failed transfers can be resumed. SFTP carries no whole-file checksum, so none is shown.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        )
    }
}

@Composable
private fun TransferRow(record: TransferRecord) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(
            if (record.direction == TransferDirection.Upload) {
                Icons.Filled.Upload
            } else {
                Icons.Filled.Download
            },
            contentDescription = if (record.direction == TransferDirection.Upload) {
                "Upload"
            } else {
                "Download"
            },
        )
        Column(Modifier.weight(1f)) {
            Text(record.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
            Text(
                statusLine(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )
        }
        when {
            record.phase == TransferPhase.Active -> IconButton(
                onClick = { TransferRegistry.cancel(record.id) },
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel ${record.name}")
            }

            TransferRegistry.canRetry(record.id) -> IconButton(
                onClick = { TransferRegistry.retry(record.id) },
            ) {
                Icon(
                    Icons.Filled.Refresh,
                    // Resume and Retry are different promises: one keeps the
                    // bytes already moved and the other starts over.
                    contentDescription = if (record.resumable) {
                        "Resume ${record.name}"
                    } else {
                        "Retry ${record.name}"
                    },
                )
            }
        }
    }
}

private fun statusLine(record: TransferRecord): String = when (record.phase) {
    TransferPhase.Active ->
        if (record.total > 0) {
            "${formatSize(record.transferred)} of ${formatSize(record.total)}"
        } else {
            formatSize(record.transferred)
        }

    TransferPhase.Done -> "Complete"
    TransferPhase.Cancelled -> "Cancelled"
    TransferPhase.Error -> record.error ?: "Failed"
}
