package com.remotly.app.ui.files

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.longClick
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remotly.app.files.FileEntry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FileBrowserControlsTest {
    @get:Rule val compose = createComposeRule()
    private val file = FileEntry("report.txt", false, false, 1024, 0, 0)

    @Test
    fun overflowOffersDownloadRenameAndDeleteAtLargeFontScale() {
        var renamed = 0
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 2f)) {
                MaterialTheme {
                    FileEntryRow(file, table = false, onOpen = {}, onDownload = {}, onRename = { renamed++ }, onDelete = {})
                }
            }
        }
        compose.onNodeWithContentDescription("Actions for report.txt").assertIsDisplayed().performClick()
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Rename").performClick()
        compose.runOnIdle { assertEquals(1, renamed) }
    }

    @Test
    fun longPressOffersTheSameActionsAsTheVisibleOverflow() {
        compose.setContent {
            MaterialTheme {
                FileEntryRow(file, table = false, onOpen = {}, onDownload = {}, onRename = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("report.txt").performTouchInput { longClick() }
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun rightClickOffersTheSameActions() {
        compose.setContent {
            MaterialTheme {
                FileEntryRow(file, table = false, onOpen = {}, onDownload = {}, onRename = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("report.txt").performMouseInput { rightClick() }
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun shiftF10OpensActionsForTheFocusedRow() {
        compose.setContent {
            MaterialTheme {
                FileEntryRow(file, table = false, onOpen = {}, onDownload = {}, onRename = {}, onDelete = {})
            }
        }
        compose.onNodeWithText("report.txt").performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        compose.onNodeWithText("report.txt").performKeyInput {
            withKeyDown(Key.ShiftLeft) { pressKey(Key.F10) }
        }
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Rename").assertIsDisplayed()
    }

    @Test
    fun directoriesDoNotOfferAnUnsupportedDownload() {
        compose.setContent {
            MaterialTheme {
                FileEntryRow(file.copy(name = "folder", isDir = true), table = true, onOpen = {}, onDownload = {}, onRename = {}, onDelete = {})
            }
        }
        compose.onNodeWithContentDescription("Actions for folder").performClick()
        compose.onNodeWithText("Rename").assertIsDisplayed()
        compose.onNodeWithText("Delete").assertIsDisplayed()
        compose.onNodeWithText("Download").assertDoesNotExist()
    }
}
