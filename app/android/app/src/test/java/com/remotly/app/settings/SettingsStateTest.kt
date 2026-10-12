package com.remotly.app.settings

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsStateTest {
    @Test fun saveChangeWaitsForItsOwnDurableOutcome() = runTest {
        var disk = AppSettings()
        val controller = SettingsController(backgroundScope, { disk }, { value ->
            if (value.terminalFontSize == 22) error("cannot write")
            disk = value
            value
        }, { AppSettings() })
        assertTrue(controller.saveChange { it.copy(terminalFontSize = 18) })
        assertEquals(18, disk.terminalFontSize)
        assertFalse(controller.saveChange { it.copy(terminalFontSize = 22) })
        assertEquals(18, controller.settings.value.terminalFontSize)
        assertEquals(18, disk.terminalFontSize)
        assertEquals("cannot write", controller.error.value)
    }

    @Test fun `queued writes remain ordered and old successes never overwrite live preference`() = runTest {
        val writes = mutableListOf<Int>()
        lateinit var controller: SettingsController
        controller = SettingsController(backgroundScope, { AppSettings() }, { value ->
            writes += value.terminalFontSize
            assertEquals(22, controller.settings.value.terminalFontSize)
            value
        }, { AppSettings() })
        controller.update { it.copy(terminalFontSize = 18) }
        controller.update { it.copy(terminalFontSize = 22) }
        advanceUntilIdle()
        // backgroundScope is drained explicitly by a foreground barrier.
        launch { controller.load() }.join()
        assertEquals(listOf(18, 22), writes)
    }

    @Test fun `older failure does not roll back newer request or invoke its failure callback`() = runTest {
        var callbacks = 0
        var disk = AppSettings()
        val controller = SettingsController(backgroundScope, { disk }, { value ->
            if (value.terminalFontSize == 18) error("first write failed")
            disk = value
            value
        }, { AppSettings() })
        controller.update(onFailure = { callbacks++ }) { it.copy(terminalFontSize = 18) }
        controller.update { it.copy(terminalFontSize = 22) }
        controller.load()
        assertEquals(22, controller.settings.value.terminalFontSize)
        assertEquals(0, callbacks)
        assertNull(controller.error.value)
    }

    @Test fun `failure callback delayed behind a newer request is suppressed`() = runTest {
        var callbacks = 0
        lateinit var controller: SettingsController
        controller = SettingsController(backgroundScope, { AppSettings() }, { value ->
            if (value.terminalFontSize == 18) error("cannot write")
            value
        }, { AppSettings() }, reportFailure = { callback, cause ->
            controller.update { it.copy(terminalFontSize = 22) }
            callback(cause)
        })
        controller.update(onFailure = { callbacks++ }) { it.copy(terminalFontSize = 18) }
        runCurrent()
        assertEquals(0, callbacks)
        assertEquals(22, controller.settings.value.terminalFontSize)
    }

    @Test fun `latest failure restores last durable value and surfaces error`() = runTest {
        var disk = AppSettings()
        var callbacks = 0
        val controller = SettingsController(backgroundScope, { disk }, { value ->
            if (value.terminalFontSize == 22) error("cannot write")
            disk = value
            value
        }, { AppSettings() })
        controller.update { it.copy(terminalFontSize = 18) }
        controller.update(onFailure = { callbacks++ }) { it.copy(terminalFontSize = 22) }
        // A no-change update must not suppress the pending error.
        controller.update { it }
        advanceUntilIdle()
        runCurrent()
        assertEquals(18, controller.settings.value.terminalFontSize)
        assertEquals("cannot write", controller.error.value)
        assertEquals(1, callbacks)
    }
}
