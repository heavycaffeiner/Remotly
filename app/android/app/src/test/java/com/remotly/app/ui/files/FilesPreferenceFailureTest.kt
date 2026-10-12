package com.remotly.app.ui.files

import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsController
import java.io.IOException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class FilesPreferenceFailureTest {
    @Test
    fun `failed sort change restores durable file preferences and reports failure`() =
        verifyFailedSave { it.copy(filesSortKey = AppSettings.SORT_SIZE) }

    @Test
    fun `failed direction change restores durable file preferences and reports failure`() =
        verifyFailedSave { it.copy(filesSortDirection = AppSettings.SORT_ASC) }

    @Test
    fun `failed hidden file change restores durable file preferences and reports failure`() =
        verifyFailedSave { it.copy(filesShowHidden = false) }

    private fun verifyFailedSave(change: (AppSettings) -> AppSettings) = runTest {
        val durable = AppSettings(
            filesSortKey = AppSettings.SORT_KIND,
            filesSortDirection = AppSettings.SORT_DESC,
            filesShowHidden = true,
            terminalFontSize = 18,
        )
        val writes = mutableListOf<AppSettings>()
        val failures = mutableListOf<String?>()
        val controller = SettingsController(
            backgroundScope,
            loadStored = { durable },
            saveStored = {
                writes += it
                throw IOException("disk full")
            },
            recoverStored = { AppSettings() },
        )
        controller.load()
        controller.update(onFailure = { failures += it.message }, change = change)
        assertEquals(change(durable), controller.settings.value)
        runCurrent()
        assertEquals(listOf(change(durable)), writes)
        assertEquals(durable, controller.settings.value)
        assertEquals("disk full", controller.error.value)
        assertEquals(listOf("disk full"), failures)
    }
}
