package com.remotly.app.session

import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SshSessionsTest {

    private lateinit var ssh: FakeSshTransport

    @Before
    fun setUp() {
        ssh = FakeSshTransport()
        SshSessions.transport = ssh
    }

    // Each test gets its own host id: the store is a process-wide singleton
    // that outlives every test, and JUnit4 makes a fresh instance of this
    // class per method, so the counter has to live in the companion to stay
    // unique across the whole run rather than resetting every method.
    private fun freshHost(): String = "h${hostSeq.incrementAndGet()}"

    companion object {
        private val hostSeq = AtomicInteger()
    }

    private fun tabsOf(hostId: String) = SshSessions.state(hostId).value.tabs

    // -- session lifetime --------------------------------------------------------

    @Test
    fun `does not reopen a tab for a host that already has one`() {
        val id = freshHost()
        SshSessions.openTab(id)
        assertTrue(SshSessions.hostStarted(id))
        assertEquals(1, tabsOf(id).tabs.size)
    }

    @Test
    fun `closes exactly the tab that was asked for`() {
        val id = freshHost()
        SshSessions.openTab(id)
        SshSessions.openTab(id)
        val (first, second) = tabsOf(id).tabs

        SshSessions.closeTab(id, first.sessionId)

        assertEquals(id to first.sessionId, ssh.closeCalls.single())
        assertEquals(listOf(second.sessionId), tabsOf(id).tabs.map { it.sessionId })
    }

    @Test
    fun `closes every tab when the host disconnects`() {
        val id = freshHost()
        SshSessions.openTab(id)
        SshSessions.openTab(id)

        SshSessions.closeHost(id)

        assertEquals(2, ssh.closeCalls.size)
        assertTrue(tabsOf(id).tabs.isEmpty())
    }

    // -- sizing --------------------------------------------------------

    @Test
    fun `is unsized until the viewport reports a grid`() {
        val id = freshHost()
        assertFalse(SshSessions.hostSized(id))
        SshSessions.resizeHost(id, 100, 40)
        assertTrue(SshSessions.hostSized(id))
    }

    @Test
    fun `ignores a zero-sized report`() {
        val id = freshHost()
        SshSessions.resizeHost(id, 0, 0)
        assertFalse(SshSessions.hostSized(id))
    }

    @Test
    fun `does not resize before any tab is open`() {
        val id = freshHost()
        SshSessions.resizeHost(id, 100, 40)
        assertTrue(ssh.resizeCalls.isEmpty())
    }

    // The reason this matters: a session opened against a placeholder grid
    // gets a pty whose row count does not match the screen, and a program
    // that draws with absolute cursor moves puts its overlay in the wrong
    // place.
    @Test
    fun `connects with the measured grid, not the placeholder`() {
        val id = freshHost()
        SshSessions.resizeHost(id, 100, 40)
        SshSessions.openTab(id)

        val call = ssh.connectCalls.single()
        assertEquals(tabsOf(id).tabs[0].sessionId, call.sessionId)
        assertEquals(100, call.cols)
        assertEquals(40, call.rows)
    }

    @Test
    fun `resizes every open tab once sized`() {
        val id = freshHost()
        SshSessions.resizeHost(id, 100, 40)
        SshSessions.openTab(id)
        SshSessions.openTab(id)
        ssh.resizeCalls.clear()

        SshSessions.resizeHost(id, 90, 30)

        assertEquals(2, ssh.resizeCalls.size)
    }

    // -- renaming --------------------------------------------------------

    @Test
    fun `renames the requested tab only`() {
        val id = freshHost()
        SshSessions.openTab(id)
        SshSessions.openTab(id)
        val (first, second) = tabsOf(id).tabs

        SshSessions.renameTab(id, first.sessionId, "build")

        val tabs = tabsOf(id).tabs
        assertEquals("build", tabs[0].title)
        assertEquals(second.title, tabs[1].title)
    }

    @Test
    fun `ignores a blank rename`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val before = tabsOf(id).tabs[0].title

        SshSessions.renameTab(id, tabsOf(id).tabs[0].sessionId, "  ")

        assertEquals(before, tabsOf(id).tabs[0].title)
    }

    // -- subscription --------------------------------------------------------

    @Test
    fun `publishes a new state value when a tab opens`() {
        val id = freshHost()
        val flow = SshSessions.state(id)
        val before = flow.value

        SshSessions.openTab(id)

        assertTrue(flow.value !== before)
        assertEquals(1, flow.value.tabs.tabs.size)
    }

    @Test
    fun `keeps one host's state independent of another's`() {
        val a = freshHost()
        val b = freshHost()
        SshSessions.openTab(a)
        assertTrue(tabsOf(b).tabs.isEmpty())
    }

    // -- host key prompts --------------------------------------------------------

    @Test
    fun `surfaces a host key prompt from the transport`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val sessionId = tabsOf(id).tabs[0].sessionId

        ssh.emitState(id, sessionId, mapOf("state" to "hostKey", "algorithm" to "ed25519", "fingerprint" to "SHA256:abc", "changed" to false))

        val state = SshSessions.state(id).value
        assertEquals(SshTabPhase.HostKey, state.tabs.tabs[0].phase)
        assertEquals(sessionId, state.hostKeyPrompt?.sessionId)
        assertEquals("SHA256:abc", state.hostKeyPrompt?.fingerprint)
    }

    @Test
    fun `accepting a host key clears the prompt and forwards the decision`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val sessionId = tabsOf(id).tabs[0].sessionId
        ssh.emitState(id, sessionId, mapOf("state" to "hostKey", "algorithm" to "", "fingerprint" to "", "changed" to false))

        SshSessions.answerHostKey(id, HostKeyDecision.Accept)

        assertNull(SshSessions.state(id).value.hostKeyPrompt)
        assertEquals(Triple(id, sessionId, "accept"), ssh.hostKeyCalls.single())
        assertTrue(ssh.closeCalls.isEmpty())
    }

    @Test
    fun `rejecting a host key closes the session instead of forwarding it`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val sessionId = tabsOf(id).tabs[0].sessionId
        ssh.emitState(id, sessionId, mapOf("state" to "hostKey", "algorithm" to "", "fingerprint" to "", "changed" to false))

        SshSessions.answerHostKey(id, HostKeyDecision.Reject)

        assertNull(SshSessions.state(id).value.hostKeyPrompt)
        assertTrue(ssh.hostKeyCalls.isEmpty())
        assertEquals(id to sessionId, ssh.closeCalls.single())
        val tab = tabsOf(id).tabs[0]
        assertEquals(SshTabPhase.Closed, tab.phase)
        assertEquals("The host key was rejected.", tab.detail)
    }

    // -- a tab opened with a command --------------------------------------------------------

    @Test
    fun `types the queued command once the shell reports ready, and not before`() {
        val id = freshHost()
        SshSessions.openTab(id, title = "api", runs = "herdr")
        val sessionId = tabsOf(id).tabs[0].sessionId
        assertTrue(ssh.writeCalls.isEmpty())

        ssh.emitState(id, sessionId, mapOf("state" to "active"))

        val write = ssh.writeCalls.single()
        assertEquals("herdr\n", String(write.bytes, Charsets.UTF_8))
    }

    @Test
    fun `does not type the command again on a later ready`() {
        val id = freshHost()
        SshSessions.openTab(id, title = "api", runs = "herdr")
        val sessionId = tabsOf(id).tabs[0].sessionId

        ssh.emitState(id, sessionId, mapOf("state" to "active"))
        ssh.emitState(id, sessionId, mapOf("state" to "active"))

        assertEquals(1, ssh.writeCalls.size)
    }

    @Test
    fun `keeps the given name when the program repaints the title`() {
        val id = freshHost()
        SshSessions.openTab(id, title = "api", runs = "herdr")

        SshSessions.reportTerminalTitle(id, "host: api")

        assertEquals("api", tabsOf(id).tabs[0].title)
    }

    @Test
    fun `reveals the terminal a workspace session is already attached in`() {
        val id = freshHost()
        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")
        val first = tabsOf(id).tabs[0].sessionId
        ssh.emitState(id, first, mapOf("state" to "active"))
        SshSessions.openTab(id)
        SshSessions.selectTab(id, tabsOf(id).tabs[1].sessionId)

        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")

        assertEquals(2, tabsOf(id).tabs.size)
        assertEquals(first, tabsOf(id).activeSessionId)
    }

    @Test
    fun `retags the terminal when another workspace is entered`() {
        val id = freshHost()
        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")
        val first = tabsOf(id).tabs[0].sessionId
        ssh.emitState(id, first, mapOf("state" to "active"))

        SshSessions.openWorkspaceTab(id, workspaceId = "w2", label = "deploy", runs = "herdr")

        assertEquals(1, tabsOf(id).tabs.size)
        assertEquals("w2", tabsOf(id).tabs[0].workspaceId)
        assertEquals("deploy", tabsOf(id).tabs[0].title)
    }

    @Test
    fun `leaves the shell budget alone for a workspace tab`() {
        val id = freshHost()
        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")
        ssh.emitState(id, tabsOf(id).tabs[0].sessionId, mapOf("state" to "active"))

        repeat(MAX_SSH_TABS) { SshSessions.openTab(id) }

        assertEquals(MAX_SSH_TABS, tabsOf(id).tabs.count { it.kind == SshTabKind.Shell })
        assertFalse(SshSessions.canAddTab(id))
    }

    @Test
    fun `opens a workspace tab over a full shell strip`() {
        val id = freshHost()
        repeat(MAX_SSH_TABS) { SshSessions.openTab(id) }

        val opened = SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")

        assertNotNull(opened)
        assertEquals(1, tabsOf(id).tabs.count { it.kind == SshTabKind.Workspace })
    }

    @Test
    fun `opens a new workspace terminal once the old one has closed`() {
        val id = freshHost()
        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")
        ssh.emitState(id, tabsOf(id).tabs[0].sessionId, mapOf("state" to "closed", "userInitiated" to false))

        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")

        assertEquals(2, tabsOf(id).tabs.size)
    }

    @Test
    fun `gives a second herdr session its own terminal`() {
        val id = freshHost()
        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr")
        ssh.emitState(id, tabsOf(id).tabs[0].sessionId, mapOf("state" to "active"))

        SshSessions.openWorkspaceTab(id, workspaceId = "w1", label = "api", runs = "herdr --session work", session = "work")

        assertEquals(2, tabsOf(id).tabs.size)
    }

    // -- bare session id binding --------------------------------------------------------
    //
    // Two documented bugs came from a screen or a release call keying a
    // terminal by `hostId:sessionId` instead of the bare id: a view adopted
    // an empty terminal while the tab's real screen sat in one nobody drew,
    // and a closed tab's terminal was never released because nothing was
    // retained under the composite key. Every call this store makes into the
    // transport must carry the same bare id the tab itself carries.

    @Test
    fun `connects using the tab's own bare session id`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val sessionId = tabsOf(id).tabs[0].sessionId

        assertFalse(sessionId.contains(":"))
        assertEquals(sessionId, ssh.connectCalls.single().sessionId)
    }

    @Test
    fun `closes using the tab's own bare session id, never a host-qualified one`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val sessionId = tabsOf(id).tabs[0].sessionId

        SshSessions.closeTab(id, sessionId)

        val (closedHost, closedSession) = ssh.closeCalls.single()
        assertEquals(id, closedHost)
        assertEquals(sessionId, closedSession)
        assertFalse(closedSession.contains(":"))
        assertFalse(closedSession.contains(id))
    }

    @Test
    fun `a files tab opens no connection and is usable at once`() {
        val id = freshHost()
        SshSessions.openTab(id, kind = SshTabKind.Files)

        assertTrue(ssh.connectCalls.isEmpty())
        assertEquals(SshTabPhase.Active, tabsOf(id).tabs.single().phase)
        assertEquals("Files", tabsOf(id).tabs.single().title)
    }

    @Test
    fun `closing a files tab never closes a channel it never opened`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val shell = tabsOf(id).tabs.single().sessionId
        SshSessions.openTab(id, kind = SshTabKind.Files)
        val files = tabsOf(id).tabs.first { it.sessionId != shell }.sessionId

        SshSessions.closeTab(id, files)

        assertTrue(ssh.closeCalls.isEmpty())
        assertEquals(shell, tabsOf(id).tabs.single().sessionId)
    }

    @Test
    fun `writes input under the active tab's bare session id`() {
        val id = freshHost()
        SshSessions.openTab(id)
        val sessionId = tabsOf(id).tabs[0].sessionId

        SshSessions.sendInput(id, byteArrayOf(1, 2, 3))

        assertEquals(sessionId, ssh.writeCalls.single().sessionId)
    }
}
