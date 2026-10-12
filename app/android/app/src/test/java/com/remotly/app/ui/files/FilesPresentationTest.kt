package com.remotly.app.ui.files

import com.remotly.app.files.FileEntry
import com.remotly.app.files.FileOrder
import com.remotly.app.files.SortDirection
import com.remotly.app.files.SortKey
import com.remotly.app.files.filterEntries
import com.remotly.app.files.orderEntries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesPresentationTest {
    @Test
    fun `table fits the actual pane and grows its budget with font scale`() {
        assertFalse(fileTableFits(320f, 1f))
        assertFalse(fileTableFits(695f, 1f))
        assertTrue(fileTableFits(696f, 1f))
        assertFalse(fileTableFits(900f, 2f))
        assertTrue(fileTableFits(1296f, 2f))
        assertFalse(fileTableFits(Float.NaN, 1f))
        assertFalse(fileTableFits(1600f, 0f))
    }

    @Test
    fun `metadata columns scale rather than squeezing a table into a companion`() {
        assertEquals(352f, fileColumnWidth(176f, 2f), 0f)
        assertEquals(176f, fileColumnWidth(176f, 0.8f), 0f)
    }

    @Test
    fun `column headers toggle direction only for the selected column`() {
        val order = FileOrder(SortKey.Size, SortDirection.Desc, showHidden = true)
        assertEquals(order.copy(direction = SortDirection.Asc), selectFileSort(order, SortKey.Size))
        assertEquals(order.copy(sortKey = SortKey.Name, direction = SortDirection.Asc), selectFileSort(order, SortKey.Name))
    }

    @Test
    fun `sorting and filtering include entries beyond the first visible page`() {
        val listing = (0 until 10_000).map { index ->
            FileEntry("file-$index", false, false, index.toLong(), index.toLong(), 0)
        } + FileEntry(".hidden-last", false, false, 20_000, 0, 0)
        val ordered = orderEntries(listing, FileOrder(SortKey.Size, SortDirection.Desc))
        assertEquals("file-9999", ordered.first().name)
        assertEquals(listOf("file-9999"), filterEntries(ordered, "FILE-9999").map { it.name })
        assertEquals(10_000, ordered.size)
        val withHidden = orderEntries(listing, FileOrder(SortKey.Size, SortDirection.Desc, true))
        assertEquals(".hidden-last", withHidden.first().name)
        assertEquals(listOf(".hidden-last"), filterEntries(withHidden, "hidden").map { it.name })
    }

    @Test
    fun `type keeps folders links and extensionless files distinct`() {
        fun entry(name: String, directory: Boolean = false, link: Boolean = false) =
            FileEntry(name, directory, link, 0, 0, 0)
        assertEquals("Folder", fileType(entry("src", directory = true)))
        assertEquals("Link", fileType(entry("src", directory = true, link = true)))
        assertEquals("gz", fileType(entry("archive.tar.gz")))
        assertEquals("File", fileType(entry(".bashrc")))
        assertEquals("File", fileType(entry("README")))
    }
}
