package com.remotly.app.ui.files

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.focusable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remotly.app.files.FileEntry
import com.remotly.app.files.FileOrder
import com.remotly.app.files.SortDirection
import com.remotly.app.files.SortKey
import com.remotly.app.files.entryDescription
import com.remotly.app.files.formatMtime
import com.remotly.app.files.formatSize

@Composable
fun FileTableHeader(order: FileOrder, onSort: (SortKey) -> Unit) {
    val scale = LocalDensity.current.fontScale
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        SortHeading(SortKey.Name, order, onSort, Modifier.weight(1f))
        SortHeading(SortKey.Size, order, onSort, Modifier.width(fileColumnWidth(96f, scale).dp))
        SortHeading(SortKey.Mtime, order, onSort, Modifier.width(fileColumnWidth(176f, scale).dp))
        SortHeading(SortKey.Kind, order, onSort, Modifier.width(fileColumnWidth(112f, scale).dp))
        Box(Modifier.width(48.dp))
    }
}

@Composable
private fun SortHeading(
    key: SortKey,
    order: FileOrder,
    onSort: (SortKey) -> Unit,
    modifier: Modifier,
) {
    val label = fileSortLabel(key)
    val active = key == order.sortKey
    TextButton(
        onClick = { onSort(key) },
        modifier = modifier.heightIn(min = 48.dp).semantics {
            contentDescription = if (active) {
                "Sort by $label, ${if (order.direction == SortDirection.Asc) "ascending" else "descending"}"
            } else "Sort by $label"
        },
    ) {
        Text(label)
        if (active) Icon(
            if (order.direction == SortDirection.Asc) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
        )
    }
}

fun fileSortLabel(key: SortKey): String = when (key) {
    SortKey.Name -> "Name"
    SortKey.Size -> "Size"
    SortKey.Mtime -> "Modified"
    SortKey.Kind -> "Type"
}

@Composable
fun FileEntryRow(
    entry: FileEntry,
    table: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember(entry.name) { mutableStateOf(false) }
    var focused by remember(entry.name) { mutableStateOf(false) }
    val scale = LocalDensity.current.fontScale
    val focusColor = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val showTable = table && fileTableFits(maxWidth.value, scale)
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .border(2.dp, if (focused) focusColor else Color.Transparent)
            .onPreviewKeyEvent {
                if (it.type == KeyEventType.KeyDown &&
                    (it.key == Key.Menu || (it.key == Key.F10 && it.isShiftPressed))
                ) {
                    menuOpen = true
                    true
                } else false
            }
            .pointerInput(entry.name) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
                            menuOpen = true
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            .combinedClickable(
                onClick = { if (entry.isDir) onOpen() else menuOpen = true },
                onLongClick = { menuOpen = true },
                onClickLabel = if (entry.isDir) "Open folder" else "Actions",
                onLongClickLabel = "Actions for ${entry.name}",
            )
            .focusable()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                when {
                    entry.isSymlink -> Icons.Filled.LinkOff
                    entry.isDir -> Icons.Filled.Folder
                    else -> Icons.AutoMirrored.Filled.InsertDriveFile
                },
                contentDescription = fileType(entry),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!showTable) {
                    val detail = entryDescription(entry)
                    if (detail.isNotEmpty()) Text(detail, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (showTable) {
            Metadata(if (entry.isDir) "" else formatSize(entry.size), fileColumnWidth(96f, scale))
            Metadata(formatMtime(entry.mtime), fileColumnWidth(176f, scale))
            Metadata(fileType(entry), fileColumnWidth(112f, scale))
        }
        Box {
            IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${entry.name}")
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                if (!entry.isDir) DropdownMenuItem(
                    text = { Text("Download") },
                    leadingIcon = { Icon(Icons.Filled.Download, contentDescription = null) },
                    onClick = { menuOpen = false; onDownload() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                DropdownMenuItem(
                    text = { Text("Rename") },
                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                    onClick = { menuOpen = false; onRename() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
                DropdownMenuItem(
                    text = { Text("Delete", color = MaterialTheme.colorScheme.error) },
                    leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                    onClick = { menuOpen = false; onDelete() },
                    modifier = Modifier.heightIn(min = 48.dp),
                )
            }
        }
    }
    }
}

@Composable
private fun Metadata(value: String, widthDp: Float) {
    Text(
        value,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.width(widthDp.dp).padding(horizontal = 8.dp),
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
    )
}
