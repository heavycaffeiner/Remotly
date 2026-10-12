package com.remotly.app.ui.files

import com.remotly.app.files.FileEntry
import com.remotly.app.files.FileOrder
import com.remotly.app.files.SortDirection
import com.remotly.app.files.SortKey

// Column budgets include space for the name, three metadata fields, and actions.
fun fileTableFits(paneWidthDp: Float, fontScale: Float): Boolean =
    paneWidthDp.isFinite() && fontScale.isFinite() && fontScale > 0f &&
        paneWidthDp >= 96f + 600f * fontScale.coerceAtLeast(1f)

fun fileColumnWidth(baseDp: Float, fontScale: Float): Float =
    baseDp * fontScale.coerceAtLeast(1f)

fun selectFileSort(order: FileOrder, key: SortKey): FileOrder =
    if (order.sortKey == key) {
        order.copy(direction = if (order.direction == SortDirection.Asc) SortDirection.Desc else SortDirection.Asc)
    } else {
        order.copy(sortKey = key, direction = SortDirection.Asc)
    }

fun fileType(entry: FileEntry): String = when {
    entry.isSymlink -> "Link"
    entry.isDir -> "Folder"
    else -> entry.name.lastIndexOf('.').let { dot ->
        if (dot > 0 && dot < entry.name.lastIndex) entry.name.substring(dot + 1) else "File"
    }
}
