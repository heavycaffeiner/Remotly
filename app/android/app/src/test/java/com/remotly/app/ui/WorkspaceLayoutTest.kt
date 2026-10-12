package com.remotly.app.ui

import org.junit.Assert.*
import org.junit.Test

class WorkspaceLayoutTest {
    @Test fun windowWidthControlsNavigationNotOrientation() {
        assertFalse(WorkspaceLayout(599, 900).rail)
        assertTrue(WorkspaceLayout(600, 900).rail)
        assertFalse(WorkspaceLayout(839, 900).sidebar)
        assertTrue(WorkspaceLayout(840, 900).sidebar)
        assertFalse(WorkspaceLayout(1199, 800).companion)
        assertTrue(WorkspaceLayout(1200, 800).companion)
    }
    @Test fun shortWindowsAvoidPersistentBottomNavigation() {
        assertTrue(WorkspaceLayout(360, 360).rail)
        assertTrue(WorkspaceLayout(740, 360).compactHeight)
    }
    @Test fun companionNeedsUsableContentBounds() {
        assertFalse(companionFits(959f, 600f))
        assertFalse(companionFits(1200f, 319f))
        assertTrue(companionFits(960f, 320f))
    }
}
