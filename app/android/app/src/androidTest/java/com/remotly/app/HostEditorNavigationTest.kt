package com.remotly.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HostEditorNavigationTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun toolbarBackProtectsEditsMadeAfterTheFormOpens() {
        compose.onNodeWithContentDescription("Add SSH host").performClick()
        compose.onNodeWithText("Label (optional)").performTextInput("Unsaved host")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()

        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Unsaved host").assertIsDisplayed()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Discard changes?").assertIsDisplayed()

        compose.onNodeWithText("Discard").performClick()
        compose.onNodeWithText("Search hosts").assertIsDisplayed()
    }
}
