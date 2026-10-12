package com.remotly.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.settings.*
import com.remotly.app.terminal.TerminalThemes
import com.remotly.app.ui.screens.TerminalThemeEditor
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalThemeEditorTest {
    @get:Rule val compose = createComposeRule()
    private val previous = SettingsModule.store
    private val directory = Files.createTempDirectory("theme-editor-test").toFile()

    @Before fun prepare() {
        SettingsModule.store = SettingsStore(directory.resolve("settings.json")).also { it.save(AppSettings()) }
        runBlocking { SettingsState.load() }
        TerminalThemeDraftState.cancel()
        TerminalThemeDraftState.begin(TerminalThemes.Nord)
    }
    @After fun restore() {
        TerminalThemeDraftState.cancel()
        SettingsModule.store = previous
        runBlocking { SettingsState.load() }
        directory.deleteRecursively()
    }

    @Test fun failedApplyKeepsTheDraftOpenAndCancelAsksBeforeDiscarding() {
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (open.value) TerminalThemeEditor { open.value = false } } }
        SettingsModule.store = null
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil { SettingsState.error.value != null }
        compose.onNodeWithText("Custom terminal theme").assertIsDisplayed()
        compose.runOnIdle {
            Assert.assertTrue(open.value)
            Assert.assertNotNull(TerminalThemeDraftState.draft.value)
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Custom terminal theme").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Discard").performClick()
        compose.runOnIdle {
            Assert.assertFalse(open.value)
            Assert.assertNull(TerminalThemeDraftState.draft.value)
        }
    }

    @Test fun successfulApplyPersistsThemeBeforeClosing() {
        val open = mutableStateOf(true)
        compose.setContent { MaterialTheme { if (open.value) TerminalThemeEditor { open.value = false } } }
        compose.onNodeWithText("Apply").performClick()
        compose.waitUntil { !open.value }
        val stored = requireNotNull(SettingsModule.store).load()
        Assert.assertEquals("custom", stored.terminalTheme.id)
        Assert.assertEquals(TerminalThemes.Nord, stored.terminalTheme.resolve())
        Assert.assertNull(TerminalThemeDraftState.draft.value)
    }
}
