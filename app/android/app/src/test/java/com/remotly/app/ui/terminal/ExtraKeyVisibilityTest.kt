package com.remotly.app.ui.terminal

import com.remotly.app.settings.ExtraKeyVisibility
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtraKeyVisibilityTest {
    @Test fun automaticUsesSoftwareKeyboardOrAnExplicitRequest() {
        assertFalse(extraKeysVisible(ExtraKeyVisibility.Automatic, true, false, false))
        assertFalse(extraKeysVisible(ExtraKeyVisibility.Automatic, true, false, true))
        assertTrue(extraKeysVisible(ExtraKeyVisibility.Automatic, true, true, false))
        assertTrue(extraKeysVisible(ExtraKeyVisibility.Automatic, true, true, true))
        assertTrue(extraKeysVisible(ExtraKeyVisibility.Automatic, true, false, true, keyboardExplicitlyRequested = true))
        assertFalse(extraKeysVisible(ExtraKeyVisibility.Automatic, false, true, false))
    }

    @Test fun alwaysAndHiddenDoNotFollowKeyboardChanges() {
        for (focused in listOf(false, true)) {
            for (ime in listOf(false, true)) {
                for (hardware in listOf(false, true)) {
                    assertTrue(extraKeysVisible(ExtraKeyVisibility.Always, focused, ime, hardware))
                    assertFalse(extraKeysVisible(ExtraKeyVisibility.Hidden, focused, ime, hardware))
                }
            }
        }
        assertFalse(extraKeysVisible(ExtraKeyVisibility.Hidden, true, true, true, keyboardExplicitlyRequested = true))
    }

    @Test fun temporaryPanelOverridesEveryPreference() {
        for (policy in ExtraKeyVisibility.entries) {
            assertTrue(extraKeysVisible(policy, false, false, true, temporarilyRequested = true))
        }
    }
}
