package com.remotly.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
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
import com.remotly.app.ui.files.transferProgress
import com.remotly.app.ui.files.transferRestartLabel
import com.remotly.app.ui.files.transferStatus
import com.remotly.app.transfers.TransferDirection
import com.remotly.app.transfers.TransferPhase
import com.remotly.app.transfers.TransferRecord
import com.remotly.app.transfers.TransferRegistry

// Compatibility entry points reserve no space. The shell measures and places the indicator.
val TRANSFER_BAR_HEIGHT = 0.dp

@Composable
fun transferBarClearance(): Dp = 0.dp

@Composable
fun TransferBar(modifier: Modifier = Modifier) {
    var historyOpen by remember { mutableStateOf(false) }
    TransferIndicator(onOpen = { historyOpen = true }, modifier = modifier)
    TransferHistory(open = historyOpen, onDismiss = { historyOpen = false })
}

@Composable
fun TransferIndicator(onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val transfers by TransferRegistry.transfers.collectAsStateWithLifecycle()
    val barRaised by TransferRegistry.barRaised.collectAsStateWithLifecycle()
    val raised = if (barRaised) transfers.filter(TransferRegistry::raisesBar) else emptyList()
    if (raised.isNotEmpty()) BarSurface(raised, onOpen, modifier)
}

@Composable
fun TransferHistory(open: Boolean, onDismiss: () -> Unit) {
    if (!open) return
    val transfers by TransferRegistry.transfers.collectAsStateWithLifecycle()
    TransferSheet(transfers, onDismiss)
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
        else MaterialTheme.colorScheme.surfaceContainerHigh
    val content =
        if (failed > 0) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSurface

    Surface(
        color = container,
        contentColor = content,
        shape = MaterialTheme.shapes.medium,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            Modifier
                .clickable(onClickLabel = "Show transfers", onClick = onOpen)
                .heightIn(min = 48.dp)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Text(summary, style = MaterialTheme.typography.bodyMedium)
            val active = raised.firstOrNull { it.phase == TransferPhase.Active }
            if (active != null) {
                val fraction = transferProgress(active)
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TransferSheet(transfers: List<TransferRecord>, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(
                "Transfers",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            if (transfers.any { it.phase != TransferPhase.Active }) {
                TextButton(onClick = { TransferRegistry.clearSettled() }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Clear finished") }
            }
        }
        LazyColumn(Modifier.weight(1f, fill = false)) {
            items(transfers, key = { it.id }) { record -> TransferRow(record) }
        }
        TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Close") }
    }
}

@Composable
private fun TransferRow(record: TransferRecord) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
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
            Text(record.name, style = MaterialTheme.typography.bodyLarge)
            Text(
                transferStatus(record),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            record.phase == TransferPhase.Active -> IconButton(
                onClick = { TransferRegistry.cancel(record.id) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Cancel ${record.name}")
            }

            TransferRegistry.canRetry(record.id) -> TextButton(
                onClick = { TransferRegistry.retry(record.id) },
                modifier = Modifier.heightIn(min = 48.dp).semantics {
                    contentDescription = "${transferRestartLabel(record)} ${record.name}"
                },
            ) { Text(transferRestartLabel(record)) }
        }
    }
}
