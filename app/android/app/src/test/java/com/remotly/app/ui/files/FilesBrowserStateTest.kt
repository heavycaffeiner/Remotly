package com.remotly.app.ui.files

import androidx.compose.runtime.saveable.SaverScope
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesBrowserStateTest {
    private val saveScope = object : SaverScope {
        override fun canBeSaved(value: Any) = true
    }

    @Test
    fun `back closes search before traversing history without closing files`() {
        val state = FilesBrowserState("/home/src", listOf("/", "/home"), "readme", true)
        val navigated = mutableListOf<String>()
        assertTrue(state.goBack(true, navigated::add))
        assertFalse(state.searching.value)
        assertEquals("", state.searchQuery.value)
        assertTrue(navigated.isEmpty())
        assertEquals(listOf("/", "/home"), state.visited.value)
        assertTrue(state.goBack(true, navigated::add))
        assertEquals(listOf("/home"), navigated)
        assertTrue(state.goBack(true, navigated::add))
        assertEquals(listOf("/home", "/"), navigated)
        assertFalse(state.goBack(true, navigated::add))
    }

    @Test
    fun `saver restores directory history and search into a new instance`() {
        val original = FilesBrowserState("/home/src", listOf("/", "/home"), "archive", true)
        val saved = with(FilesBrowserState.Saver) { saveScope.save(original) }
        val restored = FilesBrowserState.Saver.restore(requireNotNull(saved))!!

        assertNotSame(original, restored)
        assertEquals("/home/src", restored.directory.value)
        assertEquals(listOf("/", "/home"), restored.visited.value)
        assertEquals("archive", restored.searchQuery.value)
        assertTrue(restored.searching.value)
        assertTrue(restored.hasDirectory)

        val navigate: (String) -> Unit = { restored.directory.value = it }
        assertTrue(restored.goBack(true, navigate))
        assertFalse(restored.searching.value)
        assertEquals("", restored.searchQuery.value)
        assertEquals("/home/src", restored.directory.value)
        assertEquals(listOf("/", "/home"), restored.visited.value)
        assertTrue(restored.goBack(true, navigate))
        assertEquals("/home", restored.directory.value)
        assertTrue(restored.goBack(true, navigate))
        assertEquals("/", restored.directory.value)
        assertFalse(restored.goBack(true, navigate))
    }

    @Test
    fun `saver preserves an uninitialized directory so first connection resolves home`() {
        val saved = with(FilesBrowserState.Saver) { saveScope.save(FilesBrowserState()) }
        val restored = FilesBrowserState.Saver.restore(requireNotNull(saved))!!
        assertEquals("/", restored.directory.value)
        assertFalse(restored.hasDirectory)
        assertTrue(restored.visited.value.isEmpty())
        assertFalse(restored.goBack(true) { error("Must not navigate") })
    }

    @Test
    fun `history is not consumed while connecting`() {
        val state = FilesBrowserState("/home", listOf("/"))
        assertFalse(state.goBack(false) { error("Must not navigate") })
        assertEquals(listOf("/"), state.visited.value)
    }
}
