package com.remotly.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsModule
import com.remotly.app.settings.SettingsState
import com.remotly.app.settings.SettingsStore
import com.remotly.app.ui.screens.SettingsContent
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsLayoutTest {
    @get:Rule val compose = createComposeRule()
    private val previous = SettingsModule.store
    private val directory = Files.createTempDirectory("settings-layout-test").toFile()

    @Before fun prepare() {
        SettingsModule.store = SettingsStore(directory.resolve("settings.json")).also { it.save(AppSettings()) }
        runBlocking { SettingsState.load() }
    }

    @After fun restore() {
        SettingsModule.store = previous
        runBlocking { SettingsState.load() }
        directory.deleteRecursively()
    }

    @Test fun compactSectionPickerAndThemeCardsPersistSelectionAtLargeFontScale() {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 2f)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp).height(800.dp)) { SettingsContent() }
                }
            }
        }
        compose.onNodeWithContentDescription("Choose settings section", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Terminal appearance").performClick()
        compose.onNodeWithText("Nord").performScrollTo().performClick()
        compose.waitUntil { SettingsState.settings.value.terminalTheme.id == "nord" }
        compose.onNodeWithText("Nord").assertIsSelected()
        assertEquals("nord", requireNotNull(SettingsModule.store).load().terminalTheme.id)
    }

    @Test fun wideSectionNavigationBecomesPickerWhenTheWindowNarrows() {
        val width = mutableStateOf(1200f)
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(0.5f, 1f)) {
                MaterialTheme {
                    Box(Modifier.width(width.value.dp).height(800.dp)) { SettingsContent() }
                }
            }
        }
        compose.onNodeWithText("Terminal appearance").performClick()
        compose.onNodeWithText("Classic").assertIsDisplayed()
        compose.runOnIdle { width.value = 360f }
        compose.onNodeWithContentDescription("Choose settings section", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText("Nord").performScrollTo().assertIsDisplayed()
    }
}
