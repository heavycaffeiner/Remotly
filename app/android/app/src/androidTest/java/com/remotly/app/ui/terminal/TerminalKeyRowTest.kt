package com.remotly.app.ui.terminal

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
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TerminalKeyRowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun pinnedKeysStayReachableAtDoubleFontScale() {
        var density = 1f
        compose.setContent {
            density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale = 2f)) {
                MaterialTheme {
                    TerminalKeyRow({}, {}, null, 300, false, modifier = Modifier.width(360.dp))
                }
            }
        }
        val escape = compose.onNodeWithContentDescription("Escape")
        val control = compose.onNodeWithContentDescription("Control")
        val more = compose.onNodeWithContentDescription("More terminal keys")
        escape.assertIsDisplayed()
        control.assertIsDisplayed()
        more.assertIsDisplayed()
        compose.onNodeWithContentDescription("Show the keyboard").assertDoesNotExist()
        val escapeBefore = escape.fetchSemanticsNode().boundsInRoot
        val controlBefore = control.fetchSemanticsNode().boundsInRoot
        for (bounds in listOf(escapeBefore, controlBefore, more.fetchSemanticsNode().boundsInRoot)) {
            assertTrue(bounds.width >= 48f * density - 1f)
            assertTrue(bounds.height >= 48f * density - 1f)
            assertEquals(48f * density, bounds.height, 1f)
        }
        more.performClick()
        compose.waitForIdle()
        assertEquals(escapeBefore, escape.fetchSemanticsNode().boundsInRoot)
        assertEquals(controlBefore, control.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun controlHasAnAccessibleLatchedStateAndEscapeFiresOnce() {
        val active = mutableStateOf<ModifierKey?>(null)
        val keys = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                TerminalKeyRow(
                    onKey = { keys += it },
                    onModifier = { active.value = it },
                    activeModifier = active.value,
                    repeatDelayMs = 300,
                    haptics = false,
                )
            }
        }
        compose.onNodeWithContentDescription("Control").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Escape").performClick()
        compose.runOnIdle { assertEquals(listOf("esc"), keys) }
    }
}
