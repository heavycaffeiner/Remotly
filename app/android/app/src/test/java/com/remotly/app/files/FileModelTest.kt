package com.remotly.app.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun entry(
    name: String,
    isDir: Boolean = false,
    size: Long = 0,
    mtime: Long = 0,
) = FileEntry(name = name, isDir = isDir, isSymlink = false, size = size, mtime = mtime, perm = 0)

private fun names(entries: List<FileEntry>) = entries.map { it.name }

class OrderEntriesTest {
    private val listing = listOf(
        entry("README.md", size = 300, mtime = 30),
        entry(".bashrc", size = 100, mtime = 10),
        entry("src", isDir = true, mtime = 20),
        entry(".git", isDir = true, mtime = 40),
        entry("a.txt", size = 900, mtime = 50),
    )

    @Test
    fun `hides dotfiles by default and shows them on request`() {
        assertEquals(
            listOf("src", "README.md", "a.txt"),
            names(orderEntries(listing, FileOrder())),
        )
        assertEquals(
            listOf(".git", "src", ".bashrc", "README.md", "a.txt"),
            names(orderEntries(listing, FileOrder(showHidden = true))),
        )
    }

    @Test
    fun `keeps directories ahead of files in every order`() {
        for (key in SortKey.entries) {
            for (direction in SortDirection.entries) {
                val out = orderEntries(listing, FileOrder(key, direction, showHidden = true))
                val firstFile = out.indexOfFirst { !it.isDir }
                val lastDir = out.indexOfLast { it.isDir }
                assertTrue(
                    "key=$key direction=$direction",
                    lastDir < if (firstFile == -1) out.size else firstFile,
                )
            }
        }
    }

    @Test
    fun `reverses only within a group when descending`() {
        val out = orderEntries(
            listing,
            FileOrder(SortKey.Size, SortDirection.Desc, showHidden = true),
        )
        assertEquals(listOf(".git", "src", "a.txt", "README.md", ".bashrc"), names(out))
    }

    @Test
    fun `groups by extension when sorting by kind, and a dotfile has none`() {
        val files = listOf(
            entry("b.zip"),
            entry("a.txt"),
            entry("c.md"),
            entry("d.txt"),
            entry("plain"),
            entry(".bashrc"),
        )
        assertEquals(
            listOf(".bashrc", "plain", "c.md", "a.txt", "d.txt", "b.zip"),
            names(orderEntries(files, FileOrder(SortKey.Kind, showHidden = true))),
        )
    }

    @Test
    fun `orders equal keys by raw name so the result is total`() {
        val same = listOf(entry("b", size = 5), entry("a", size = 5), entry("c", size = 5))
        assertEquals(
            listOf("a", "b", "c"),
            names(orderEntries(same, FileOrder(SortKey.Size))),
        )
    }

    @Test
    fun `sorts by UTF-8 bytes, not by UTF-16 code unit`() {
        // Uppercase sorts before lowercase in byte order (0x41 < 0x61).
        assertEquals(
            listOf("B", "a", "b"),
            names(orderEntries(listOf(entry("b"), entry("B"), entry("a")), FileOrder())),
        )
        // An astral character is four UTF-8 bytes beginning 0xF0, so it sorts
        // after a high BMP character. Comparing UTF-16 units would invert this,
        // because a surrogate lead unit is 0xD800 and below U+FFFD.
        val astral = "\uD83D\uDE00"
        val bmp = "\uFFFD"
        assertTrue(compareNames(bmp, astral) < 0)
    }

    @Test
    fun `keeps NFC and NFD spellings distinct`() {
        val nfd = "\uD558\u0315"
        val nfc = "\uD55C"
        assertEquals(
            listOf(nfd, nfc),
            names(orderEntries(listOf(entry(nfc), entry(nfd)), FileOrder())),
        )
    }

    @Test
    fun `does not mutate the input`() {
        val input = listOf(entry("b"), entry("a"))
        val copy = names(input)
        orderEntries(input, FileOrder(SortKey.Name, SortDirection.Desc))
        assertEquals(copy, names(input))
    }
}

class FilterEntriesTest {
    private val listing = listOf(entry("README.md"), entry("notes.txt"), entry("readme.bak"))

    @Test
    fun `matches case-insensitively on a substring and keeps the order`() {
        assertEquals(
            listOf("README.md", "readme.bak"),
            names(filterEntries(listing, "readme")),
        )
        assertEquals(listOf("README.md"), names(filterEntries(listing, ".MD")))
    }

    @Test
    fun `an empty query keeps everything`() {
        assertEquals(names(listing), names(filterEntries(listing, "")))
    }
}

class PathHelpersTest {
    @Test
    fun baseName() {
        assertEquals("src", baseName("/home/dev/src"))
        assertEquals("/", baseName("/"))
        assertEquals("dev", baseName("/home/dev/"))
        assertEquals("dev", baseName("C:\\Users\\dev"))
        assertEquals("C:\\", baseName("C:\\"))
    }

    @Test
    fun parentPath() {
        assertEquals("/home", parentPath("/home/dev"))
        assertEquals("/", parentPath("/home"))
        assertNull(parentPath("/"))
        assertEquals("C:\\Users", parentPath("C:\\Users\\dev"))
        assertNull(parentPath("C:\\"))
    }

    @Test
    fun joinPath() {
        assertEquals("/home/dev", joinPath("/home", "dev"))
        assertEquals("/dev", joinPath("/", "dev"))
        assertEquals("C:\\dev", joinPath("C:\\", "dev"))
        assertEquals("C:\\Users\\dev", joinPath("C:\\Users", "dev"))
    }

    @Test
    fun `isPlainName rejects anything that would act outside the directory`() {
        assertTrue(isPlainName("notes.txt"))
        assertFalse(isPlainName(""))
        assertFalse(isPlainName("   "))
        assertFalse(isPlainName("."))
        assertFalse(isPlainName(".."))
        assertFalse(isPlainName("a/b"))
        assertFalse(isPlainName("..\\x"))
        assertFalse(isPlainName("../x"))
    }

    @Test
    fun `breadcrumbs run from the root down to the path`() {
        assertEquals(
            listOf("/" to "/", "/home" to "home", "/home/dev" to "dev"),
            parseBreadcrumbs("/home/dev", "/").map { it.path to it.name },
        )
        assertEquals(listOf("/" to "/"), parseBreadcrumbs("/", "/").map { it.path to it.name })
    }

    @Test
    fun `breadcrumbs keep the server's own separator`() {
        assertEquals(
            listOf("C:\\", "C:\\Users", "C:\\Users\\dev"),
            parseBreadcrumbs("C:\\Users\\dev", "C:\\").map { it.path },
        )
    }
}
