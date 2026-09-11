package com.remotly.app.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionTabsTest {

    private fun withTabs(n: Int): SshTabsState {
        var state = createSshTabs("h1")
        for (i in 1..n) state = addSshTab(state, "s$i", "Tab $i").state
        return state
    }

    // -- session ids --------------------------------------------------------

    @Test
    fun `mints an id that carries no host prefix or separator`() {
        for (i in 1..20) assertFalse(mintSessionId(i).contains(":"))
    }

    @Test
    fun `mints distinct ids for distinct tabs`() {
        val ids = (1..4).map { mintSessionId(it) }.toSet()
        assertEquals(4, ids.size)
    }

    @Test
    fun `survives a nonsense sequence number`() {
        assertNotNull(mintSessionId(0))
        assertNotNull(mintSessionId(-5))
    }

    // -- adding tabs ----------------------------------------------------------

    @Test
    fun `makes the new tab active`() {
        val (state, tab) = addSshTab(createSshTabs("h1"), "s1", "Shell")
        assertEquals("s1", tab?.sessionId)
        assertEquals("s1", state.activeSessionId)
        assertEquals(1, state.tabs.size)
    }

    @Test
    fun `starts a tab in connecting`() {
        val (_, tab) = addSshTab(createSshTabs("h1"), "s1", "Shell")
        assertEquals(SshTabPhase.Connecting, tab?.phase)
    }

    @Test
    fun `returns the existing tab for a duplicate id`() {
        val first = addSshTab(createSshTabs("h1"), "s1", "Shell")
        val second = addSshTab(first.state, "s1", "Other")
        assertEquals(1, second.state.tabs.size)
        assertEquals("Shell", second.tab?.title)
    }

    @Test
    fun `refuses to exceed the cap`() {
        val full = withTabs(MAX_SSH_TABS)
        val (state, tab) = addSshTab(full, "extra", "Nope")
        assertNull(tab)
        assertEquals(MAX_SSH_TABS, state.tabs.size)
    }

    @Test
    fun `rejects an empty session id`() {
        val (_, tab) = addSshTab(createSshTabs("h1"), "", "Shell")
        assertNull(tab)
    }

    @Test
    fun `budgets shell and workspace tabs apart`() {
        var state = withTabs(MAX_SSH_TABS)
        val (withWorkspace, tab) = addSshTab(state, "w1", "api", SshTabKind.Workspace)
        assertNotNull(tab)
        assertEquals(MAX_SSH_TABS + 1, withWorkspace.tabs.size)
        state = withWorkspace
        assertEquals(MAX_SSH_TABS, surfaceCount(state, SshTabKind.Shell))
        assertEquals(1, surfaceCount(state, SshTabKind.Workspace))
    }

    // -- removing tabs --------------------------------------------------------

    @Test
    fun `moves focus to the left neighbour`() {
        val next = removeSshTab(setActiveSshTab(withTabs(3), "s2"), "s2")
        assertEquals("s1", next.activeSessionId)
        assertEquals(listOf("s1", "s3"), next.tabs.map { it.sessionId })
    }

    @Test
    fun `keeps the active tab when another one closes`() {
        val state = setActiveSshTab(withTabs(3), "s3")
        val next = removeSshTab(state, "s1")
        assertEquals("s3", next.activeSessionId)
    }

    @Test
    fun `clears the active id when the last tab closes`() {
        val next = removeSshTab(withTabs(1), "s1")
        assertTrue(next.tabs.isEmpty())
        assertNull(next.activeSessionId)
    }

    @Test
    fun `ignores an unknown id on remove`() {
        val state = withTabs(2)
        assertSame(state, removeSshTab(state, "nope"))
    }

    @Test
    fun `picks the first tab when the leftmost one closes`() {
        val state = setActiveSshTab(withTabs(3), "s1")
        val next = removeSshTab(state, "s1")
        assertEquals("s2", next.activeSessionId)
    }

    // -- phase and title --------------------------------------------------------

    @Test
    fun `records a failure detail`() {
        val state = setSshTabPhase(withTabs(1), "s1", SshTabPhase.Failed, "auth rejected")
        assertEquals(SshTabPhase.Failed, findSshTab(state, "s1")?.phase)
        assertEquals("auth rejected", findSshTab(state, "s1")?.detail)
    }

    @Test
    fun `returns the same instance when the phase does not change`() {
        val state = withTabs(1)
        assertSame(state, setSshTabPhase(state, "s1", SshTabPhase.Connecting, ""))
    }

    @Test
    fun `ignores an unknown id for phase and title`() {
        val state = withTabs(1)
        assertSame(state, setSshTabPhase(state, "nope", SshTabPhase.Active))
        assertSame(state, setSshTabTitle(state, "nope", "x"))
    }

    @Test
    fun `keeps tracking the program title`() {
        var state = withTabs(1)
        for (want in listOf("~", "~/src", "~/src/remotly")) {
            state = setSshTabTitle(state, "s1", want)
            assertEquals(want, findSshTab(state, "s1")?.title)
        }
    }

    @Test
    fun `ignores a blank title rather than clearing the label`() {
        val state = withTabs(1)
        assertSame(state, setSshTabTitle(state, "s1", "   "))
    }

    @Test
    fun `trims surrounding whitespace`() {
        val state = setSshTabTitle(withTabs(1), "s1", "  build  ")
        assertEquals("build", findSshTab(state, "s1")?.title)
    }

    @Test
    fun `bounds a long name`() {
        val state = setSshTabTitle(withTabs(1), "s1", "x".repeat(200))
        assertEquals(MAX_SSH_TAB_TITLE, findSshTab(state, "s1")?.title?.length)
    }

    @Test
    fun `leaves other tabs untouched when renaming or failing`() {
        assertEquals("Tab 2", findSshTab(setSshTabTitle(withTabs(2), "s1", "renamed"), "s2")?.title)
        assertEquals(
            SshTabPhase.Connecting,
            findSshTab(setSshTabPhase(withTabs(2), "s1", SshTabPhase.Failed, "gone"), "s2")?.phase,
        )
    }

    // -- renaming pins the name --------------------------------------------------------

    @Test
    fun `pins the name against later program titles`() {
        var state = setSshTabTitle(withTabs(1), "s1", "~/src")
        state = setSshTabTitle(state, "s1", "build logs", pin = true)
        assertEquals("build logs", findSshTab(state, "s1")?.title)

        val after = setSshTabTitle(state, "s1", "~/other")
        assertSame(state, after)
        assertEquals("build logs", findSshTab(after, "s1")?.title)
    }

    @Test
    fun `still allows a second rename`() {
        var state = setSshTabTitle(withTabs(1), "s1", "first", pin = true)
        state = setSshTabTitle(state, "s1", "second", pin = true)
        assertEquals("second", findSshTab(state, "s1")?.title)
    }

    @Test
    fun `renaming to the same pinned name changes nothing`() {
        val state = setSshTabTitle(withTabs(1), "s1", "named", pin = true)
        assertSame(state, setSshTabTitle(state, "s1", "named", pin = true))
    }

    // -- activation --------------------------------------------------------

    @Test
    fun `ignores an unknown id on activate`() {
        val state = withTabs(2)
        assertSame(state, setActiveSshTab(state, "nope"))
    }

    // -- shell numbering --------------------------------------------------------

    @Test
    fun `numbers past the highest in use rather than by count`() {
        val tabs = listOf(
            SshTab("s1", "Shell 1", SshTabPhase.Active, "", SshTabKind.Shell),
            SshTab("s3", "Shell 3", SshTabPhase.Active, "", SshTabKind.Shell),
        )
        assertEquals(2, nextShellNumber(tabs))
    }

    @Test
    fun `starts numbering at one with nothing open`() {
        assertEquals(1, nextShellNumber(emptyList()))
    }

    // -- the generic-name rule --------------------------------------------------------

    @Test
    fun `a lone generated name is generic`() {
        assertTrue(isGenericShellTitle("Shell 1"))
        assertTrue(isGenericShellTitle("Shell 42"))
        assertFalse(isGenericShellTitle("Shell"))
        assertFalse(isGenericShellTitle("build logs"))
    }

    @Test
    fun `hides the strip for a lone generic tab`() {
        val tabs = listOf(SshTab("s1", "Shell 1", SshTabPhase.Active, "", SshTabKind.Shell))
        assertFalse(shouldShowTabStrip(tabs))
    }

    @Test
    fun `shows the strip for a lone renamed tab`() {
        val tabs = listOf(SshTab("s1", "build logs", SshTabPhase.Active, "", SshTabKind.Shell))
        assertTrue(shouldShowTabStrip(tabs))
    }

    @Test
    fun `shows the strip once a second tab opens, generic or not`() {
        val tabs = listOf(
            SshTab("s1", "Shell 1", SshTabPhase.Active, "", SshTabKind.Shell),
            SshTab("s2", "Shell 2", SshTabPhase.Active, "", SshTabKind.Shell),
        )
        assertTrue(shouldShowTabStrip(tabs))
    }

    @Test
    fun `browser titles fill the lowest free slot as tabs come and go`() {
        assertEquals("Files", nextFilesTitle(emptyList()))

        val first = listOf(SshTab("f1", "Files", SshTabPhase.Active, "", SshTabKind.Files))
        assertEquals("Files 2", nextFilesTitle(first))

        val gap = listOf(
            SshTab("f1", "Files", SshTabPhase.Active, "", SshTabKind.Files),
            SshTab("f3", "Files 3", SshTabPhase.Active, "", SshTabKind.Files),
        )
        assertEquals("Files 2", nextFilesTitle(gap))

        // A shell called "Filesystem" is not a browser slot.
        val other = listOf(SshTab("s1", "Filesystem", SshTabPhase.Active, "", SshTabKind.Shell))
        assertEquals("Files", nextFilesTitle(other))
    }
}
