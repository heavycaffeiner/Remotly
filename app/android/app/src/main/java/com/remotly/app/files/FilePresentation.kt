package com.remotly.app.files

import java.text.DateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Presentation for the file browser.
 *
 * Names are byte-faithful throughout: nothing here case-folds, normalizes
 * NFC to NFD, or trims. Two entries differing only by Unicode normalization
 * are different files on the server, and collapsing them in the UI would
 * send an operation to the wrong one.
 */

fun formatSize(n: Long): String {
    if (n < 0) return ""
    if (n < 1024) return "$n B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var v = n.toDouble() / 1024
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i += 1
    }
    return if (v >= 100) {
        String.format(Locale.getDefault(), "%.0f %s", v, units[i])
    } else {
        String.format(Locale.getDefault(), "%.1f %s", v, units[i])
    }
}

/**
 * A compact timestamp for a list row.
 *
 * A full locale date and time is roughly twenty characters and pushes the
 * size off the end of a narrow row. A file manager shows the time for today,
 * the day and month within the year, and the year beyond that, which is
 * enough to tell two versions of a file apart at a glance.
 *
 * [now] is injectable so the boundaries are testable.
 */
fun formatMtime(seconds: Long, now: Date = Date()): String {
    if (seconds <= 0) return ""
    val at = Date(seconds * 1000)
    val a = Calendar.getInstance().apply { time = at }
    val b = Calendar.getInstance().apply { time = now }
    val sameYear = a.get(Calendar.YEAR) == b.get(Calendar.YEAR)
    val sameDay = sameYear && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    return when {
        sameDay -> DateFormat.getTimeInstance(DateFormat.SHORT).format(at)
        sameYear -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(at)
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(at)
    }
}

fun entryDescription(entry: FileEntry, now: Date = Date()): String {
    val parts = mutableListOf<String>()
    if (!entry.isDir) {
        val size = formatSize(entry.size)
        if (size.isNotEmpty()) parts.add(size)
    }
    val when_ = formatMtime(entry.mtime, now)
    if (when_.isNotEmpty()) parts.add(when_)
    return parts.joinToString("  ")
}

/** A screen-reader label that states what the row is, not just its name. */
fun entryAccessibilityLabel(entry: FileEntry, now: Date = Date()): String {
    val kind = when {
        entry.isSymlink -> "link"
        entry.isDir -> "folder"
        else -> "file"
    }
    val detail = entryDescription(entry, now)
    return if (detail.isEmpty()) "$kind ${entry.name}" else "$kind ${entry.name}, $detail"
}

/** Why a proposed name cannot be used, or null when it is acceptable. */
fun validateName(name: String): String? {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return "Enter a name."
    if (trimmed == "." || trimmed == "..") return "That name is reserved."
    if (trimmed.contains('/') || trimmed.contains('\\')) return "Use a name without a slash."
    if (trimmed.contains('\u0000')) return "That name contains an invalid character."
    return null
}

/** True when a rename would collide with an entry already in the directory. */
fun nameExists(entries: List<FileEntry>, name: String): Boolean =
    entries.any { it.name == name }

/** Splits a name into the stem and extension a counter is inserted between. */
fun splitName(name: String): Pair<String, String> {
    val dot = name.lastIndexOf('.')
    if (dot <= 0 || dot == name.length - 1) return name to ""
    return name.substring(0, dot) to name.substring(dot)
}

/** The nth candidate name, matching what a desktop file manager produces. */
fun numberedName(name: String, n: Int): String {
    val (stem, ext) = splitName(name)
    return "$stem ($n)$ext"
}

/** The first name free in [entries], starting from the one asked for. */
fun uniqueName(entries: List<FileEntry>, name: String): String {
    if (!nameExists(entries, name)) return name
    for (i in 1..999) {
        val candidate = numberedName(name, i)
        if (!nameExists(entries, candidate)) return candidate
    }
    return numberedName(name, System.currentTimeMillis().toInt())
}
