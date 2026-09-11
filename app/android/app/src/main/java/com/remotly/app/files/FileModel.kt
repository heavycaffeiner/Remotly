package com.remotly.app.files

/**
 * File browser model.
 *
 * Names are untrusted, byte-faithful identifiers. Nothing here normalizes
 * NFC to NFD, folds case, or derives a local path from a remote name. Display
 * ordering is deterministic and independent of normalization; the raw name is
 * always the operation identifier.
 */
data class FileEntry(
    /** Raw basename, byte-faithful. Never normalize this. */
    val name: String,
    val isDir: Boolean,
    val isSymlink: Boolean,
    /** Bytes. 0 when the object reports none (symlink via lstat, most dirs). */
    val size: Long,
    /** Unix seconds. 0 when unknown. */
    val mtime: Long,
    /** Full POSIX mode bits. 0 when the backend has no portable perm. */
    val perm: Int,
)

enum class SortKey(val id: String) {
    Name("name"),
    Size("size"),
    Mtime("mtime"),
    Kind("kind"),
    ;

    companion object {
        fun from(id: String): SortKey = entries.firstOrNull { it.id == id } ?: Name
    }
}

enum class SortDirection(val id: String) {
    Asc("asc"),
    Desc("desc"),
    ;

    companion object {
        fun from(id: String): SortDirection = entries.firstOrNull { it.id == id } ?: Asc
    }
}

/** How a listing is ordered, without the search box. */
data class FileOrder(
    val sortKey: SortKey = SortKey.Name,
    val direction: SortDirection = SortDirection.Asc,
    /** Dotfiles are listed only when this is true. */
    val showHidden: Boolean = false,
)

/**
 * True when an entry is hidden by Unix convention.
 *
 * A leading dot, tested on the raw byte-faithful name. "." and ".." are not
 * entries a backend returns, so they need no special case.
 */
fun isHidden(entry: FileEntry): Boolean = entry.name.startsWith(".")

/**
 * UTF-8 byte-wise comparison of two raw names.
 *
 * Locale-independent and deterministic across platforms. Deliberately not
 * the String comparator, which orders by UTF-16 code unit and so puts an
 * astral character before a high BMP one, and deliberately not a collator,
 * which would fold NFC and NFD spellings that are different files.
 */
fun compareNames(a: String, b: String): Int {
    if (a == b) return 0
    val left = a.toByteArray(Charsets.UTF_8)
    val right = b.toByteArray(Charsets.UTF_8)
    val shared = minOf(left.size, right.size)
    for (i in 0 until shared) {
        val d = (left[i].toInt() and 0xff) - (right[i].toInt() and 0xff)
        if (d != 0) return d
    }
    return left.size - right.size
}

/**
 * The extension used for "sort by type", lowercased for grouping.
 *
 * A leading dot belongs to the name, not to an extension: ".bashrc" is a
 * dotfile with no type. Directories have no extension and group together
 * ahead of files anyway.
 */
private fun extensionOf(entry: FileEntry): String {
    if (entry.isDir) return ""
    val dot = entry.name.lastIndexOf('.')
    if (dot <= 0 || dot == entry.name.length - 1) return ""
    return entry.name.substring(dot + 1).lowercase()
}

private fun compareByKey(a: FileEntry, b: FileEntry, key: SortKey): Int = when (key) {
    SortKey.Size -> a.size.compareTo(b.size)
    SortKey.Mtime -> a.mtime.compareTo(b.mtime)
    SortKey.Kind -> compareNames(extensionOf(a), extensionOf(b))
    SortKey.Name -> compareNames(a.name, b.name)
}

/**
 * Orders a listing: hidden filter, then sort.
 *
 * Directories always sort ahead of files, whatever the key and direction. A
 * file browser that interleaves them by size or date is unusable for
 * navigation, and reversing the order must not bury every directory below
 * the files. Only the comparison within each group is reversed.
 *
 * Ties fall back to the byte-wise name so the order is total and stable: two
 * files of equal size or with the same timestamp keep a fixed position
 * instead of shuffling between frames.
 */
fun orderEntries(entries: List<FileEntry>, order: FileOrder): List<FileEntry> {
    val visible = entries.filter { order.showHidden || !isHidden(it) }
    val sign = if (order.direction == SortDirection.Desc) -1 else 1
    return visible.sortedWith { a, b ->
        if (a.isDir != b.isDir) {
            if (a.isDir) -1 else 1
        } else {
            val byKey = compareByKey(a, b, order.sortKey)
            if (byKey != 0) byKey * sign else compareNames(a.name, b.name)
        }
    }
}

/**
 * Keeps the entries whose name contains the query, preserving the order.
 *
 * Case folding is display behaviour, not identity: the raw name remains the
 * operation identifier everywhere else. Folding is applied to both sides so
 * a user typing "readme" finds "README", which is what a search box is for.
 *
 * The query is folded once rather than per entry. This runs over the whole
 * directory on every keystroke, and a large one is tens of thousands of
 * entries.
 */
fun filterEntries(entries: List<FileEntry>, query: String): List<FileEntry> {
    if (query.isEmpty()) return entries
    val needle = query.lowercase()
    return entries.filter { it.name.lowercase().contains(needle) }
}

// --- path helpers (Unix and Windows forms) ---------------------------------

/**
 * The separator this path uses.
 *
 * Detected from the path itself rather than from the client's platform: the
 * server answers in its own syntax, and a Windows host returns backslashes
 * to an Android client.
 */
private fun sepOf(p: String): Char = if (p.contains('\\')) '\\' else '/'

private fun isRoot(p: String): Boolean {
    if (p == "/" || p == "\\") return true
    // A drive root ("C:\") or a bare UNC share root.
    if (Regex("""^[a-zA-Z]:[\\/]?$""").matches(p)) return true
    return Regex("""^\\\\[^\\]+\\[^\\]+\\?$""").matches(p)
}

/** The last path segment, with trailing separators ignored. A root is itself. */
fun baseName(p: String): String {
    val sep = sepOf(p)
    if (isRoot(p)) return p
    val trimmed = p.trimEnd(sep)
    val parts = trimmed.split(sep).filter { it.isNotEmpty() }
    return parts.lastOrNull() ?: trimmed
}

/** The parent directory, or null when p is a root and nothing is above it. */
fun parentPath(p: String): String? {
    val sep = sepOf(p)
    val trimmed = p.trimEnd(sep)
    if (trimmed.isEmpty() || isRoot(trimmed)) return null
    val idx = trimmed.lastIndexOf(sep)
    if (idx < 0) return null
    val parent = trimmed.substring(0, idx)
    if (parent.isEmpty()) return sep.toString()
    if (Regex("""^[a-zA-Z]:$""").matches(parent)) return "$parent$sep"
    return parent
}

/** Joins a directory and a child name. Does not double the separator at a root. */
fun joinPath(dir: String, name: String): String {
    val sep = if (sepOf(dir) == '\\') '\\' else '/'
    val d = dir.trimEnd(sep)
    if (d.isEmpty()) return if (sep == '/') "/$name" else name
    if (isRoot(d) || Regex("""^[a-zA-Z]:$""").matches(d)) return "$d$sep$name"
    return "$d$sep$name"
}

/**
 * True when a user-typed name is a single entry in its directory.
 *
 * A name carrying a separator or a dot segment would act somewhere the user
 * is not looking: "a/b" nests a folder and "../x" moves the entry out of the
 * directory. Both separators are rejected whatever the server runs, because
 * the client cannot tell which one the server will honour.
 */
fun isPlainName(name: String): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty()) return false
    if (trimmed == "." || trimmed == "..") return false
    return !trimmed.contains('/') && !trimmed.contains('\\')
}

data class Breadcrumb(val path: String, val name: String)

/**
 * The chain from the root down to path, inclusive.
 *
 * Each entry's path is the directory to navigate to and its name is the
 * label. Separator-aware, so it works for a Windows drive root as well as a
 * Unix one.
 */
fun parseBreadcrumbs(path: String, root: String): List<Breadcrumb> {
    val sep = sepOf(path)
    val rootTrimmed = root.trimEnd(sep)
    val curTrimmed = path.trimEnd(sep)

    val rootPath = if (rootTrimmed.isEmpty()) sep.toString() else rootTrimmed + sep
    val rootName = if (rootTrimmed.isEmpty()) "/" else rootTrimmed + sep
    val crumbs = mutableListOf(Breadcrumb(rootPath, rootName))

    if (curTrimmed == rootTrimmed || curTrimmed.isEmpty()) return crumbs

    val rel = curTrimmed.removePrefix(rootTrimmed).trimStart(sep)
    var acc = rootPath
    for (part in rel.split(sep).filter { it.isNotEmpty() }) {
        acc = if (acc.endsWith(sep)) acc + part else acc + sep + part
        crumbs.add(Breadcrumb(acc, part))
    }
    return crumbs
}
