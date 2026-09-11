package com.remotly.app.herdr

import com.remotly.app.ssh.HerdrBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The transport half of the herdr layer. What matters to a caller is that a
// failure arrives as a HerdrError it can tell apart: an unreachable host, a
// herdr command that reported its own error, and a command that failed with
// nothing but stderr all lead to different messages on screen.

// HerdrBridge.Outcome.ok is derived from code.isEmpty(), not a separate flag,
// so a connect-level failure is built by giving it a non-empty code; there is
// no "failed but code is blank" state to construct.
private fun outcome(
    exitCode: Int = 0,
    stdout: String = "",
    stderr: String = "",
    code: String = "",
    message: String = "",
): HerdrBridge.Outcome = HerdrBridge.Outcome(
    exitCode = exitCode,
    stdout = stdout.toByteArray(Charsets.UTF_8),
    stderr = stderr.toByteArray(Charsets.UTF_8),
    code = code,
    message = message,
)

class HerdrClientTest {

    private fun clientWith(exec: (String, String) -> HerdrBridge.Outcome): HerdrClient =
        HerdrClient(
            transport = HerdrTransport { hostId, command -> exec(hostId, command) },
            ioDispatcher = Dispatchers.Unconfined,
        )

    @Test
    fun `reports a connect failure under the code the bridge gave`() = runTest {
        val client = clientWith { _, _ ->
            outcome(code = "ssh_auth_failed", message = "The credential was rejected.")
        }
        val e = runCatching { client.execHerdr("h1", "herdr api snapshot") }.exceptionOrNull()
        assertTrue(e is HerdrError)
        assertEquals("ssh_auth_failed", (e as HerdrError).code)
        assertEquals("The credential was rejected.", e.detail)
    }

    @Test
    fun `falls back to a default message when the bridge gave none`() = runTest {
        val client = clientWith { _, _ -> outcome(code = "ssh_network") }
        val e = runCatching { client.execHerdr("h1", "herdr api snapshot") }.exceptionOrNull()
        assertEquals("ssh_network", (e as HerdrError).code)
        assertEquals("The host could not be reached.", e.detail)
    }

    // herdr prints a typed error document to stdout and exits non-zero. Losing
    // its code would leave every herdr-level failure looking the same.
    @Test
    fun `carries herdr's own error code and message`() = runTest {
        val client = clientWith { _, _ ->
            outcome(
                exitCode = 1,
                stdout = "{\"error\":{\"code\":\"workspace_not_found\",\"message\":\"No workspace w9.\"}}",
            )
        }
        val e = runCatching { client.execHerdr("h1", "herdr workspace focus w9") }.exceptionOrNull()
        assertEquals("workspace_not_found", (e as HerdrError).code)
        assertEquals("No workspace w9.", e.detail)
    }

    // Which stream carries the error document depends on the command: herdr
    // 0.9.0 answers `session list --json` on stdout but writes `api snapshot`
    // failures to stderr and exits one. Read from stdout alone, a stopped
    // server reached the screen as the raw document printed as prose.
    @Test
    fun `carries an error document that arrived on stderr`() = runTest {
        val client = clientWith { _, _ ->
            outcome(
                exitCode = 1,
                stderr = "{\"id\":\"cli:api:snapshot\",\"error\":{\"code\":\"server_not_running\"," +
                    "\"message\":\"no herdr server is running at /home/dev/.config/herdr/herdr.sock\"}}",
            )
        }
        val e = runCatching { client.execHerdr("h1", "herdr api snapshot") }.exceptionOrNull()
        assertEquals("server_not_running", (e as HerdrError).code)
        assertEquals("no herdr server is running at /home/dev/.config/herdr/herdr.sock", e.detail)
    }

    @Test
    fun `falls back to stderr when the command printed no document`() = runTest {
        val client = clientWith { _, _ ->
            outcome(exitCode = 1, stderr = "herdr: permission denied\n")
        }
        val e = runCatching { client.execHerdr("h1", "herdr session list") }.exceptionOrNull()
        assertEquals("herdr_cli", (e as HerdrError).code)
        assertEquals("herdr: permission denied", e.detail)
    }

    // The exec channel's shell is not the one that set the user's PATH, so a
    // herdr under ~/.local/bin or a version manager's shims is invisible to it.
    @Test
    fun `asks the shell where herdr is, then repeats the command with it`() = runTest {
        val sent = mutableListOf<String>()
        val client = clientWith { _, command ->
            sent += command
            when {
                command.contains("-ilc") -> outcome(
                    stdout = "Welcome back\n__remotly_herdr_path__\n/home/dev/.local/bin/herdr\n__remotly_herdr_end__\n",
                )
                command.startsWith("PATH=") -> outcome(stdout = "done\n")
                else -> outcome(exitCode = 127, stderr = "zsh:1: command not found: herdr\n")
            }
        }

        assertEquals("done\n", client.execHerdr("hzsh", "herdr session list"))
        assertEquals("PATH='/home/dev/.local/bin':\"\$PATH\" herdr session list", sent[2])
    }

    @Test
    fun `remembers where herdr is, so a later call looks nothing up`() = runTest {
        val sent = mutableListOf<String>()
        val client = clientWith { _, command ->
            sent += command
            when {
                command.contains("-ilc") -> outcome(
                    stdout = "__remotly_herdr_path__\n/opt/herdr/bin/herdr\n__remotly_herdr_end__\n",
                )
                command.startsWith("PATH=") -> outcome(stdout = "done\n")
                else -> outcome(exitCode = 127, stderr = "herdr: not found\n")
            }
        }

        client.execHerdr("hcache", "herdr session list")
        sent.clear()
        client.execHerdr("hcache", "herdr api snapshot")

        assertEquals(listOf("PATH='/opt/herdr/bin':\"\$PATH\" herdr api snapshot"), sent)
    }

    @Test
    fun `reports a missing herdr when the shell knows of none either`() = runTest {
        val client = clientWith { _, command ->
            if (command.contains("-ilc")) {
                outcome(stdout = "__remotly_herdr_path__\n\n__remotly_herdr_end__\n")
            } else {
                outcome(exitCode = 127, stderr = "zsh:1: command not found: herdr\n")
            }
        }
        val e = runCatching { client.execHerdr("hmiss", "herdr session list") }.exceptionOrNull()
        assertEquals("herdr_missing", (e as HerdrError).code)
    }

    @Test
    fun `reports the exit status when the command said nothing at all`() = runTest {
        val client = clientWith { _, _ -> outcome(exitCode = 3) }
        val e = runCatching { client.execHerdr("h1", "herdr session list") }.exceptionOrNull()
        assertTrue(e is HerdrError && e.detail.contains("status 3"))
    }

    @Test
    fun `decodes stdout bytes as UTF-8 text`() = runTest {
        val client = clientWith { _, _ -> outcome(stdout = "pane title: café \u2192 done\n") }
        assertEquals("pane title: café \u2192 done\n", client.execHerdr("h1", "herdr pane read w1:p1"))
    }

    @Test
    fun `herdrSnapshot returns the session state a screen renders from`() = runTest {
        val client = clientWith { _, _ ->
            outcome(
                stdout = "{\"id\":\"cli:api:snapshot\",\"result\":{\"type\":\"session_snapshot\",\"snapshot\":{" +
                    "\"focused_workspace_id\":\"w2\",\"focused_tab_id\":\"w2:t1\",\"focused_pane_id\":\"w2:p1\"," +
                    "\"panes\":[],\"tabs\":[],\"workspaces\":[{\"workspace_id\":\"w2\",\"label\":\"Remotly\"," +
                    "\"number\":1,\"tab_count\":1,\"pane_count\":1,\"active_tab_id\":\"w2:t1\",\"focused\":true}]," +
                    "\"protocol\":20,\"version\":\"0.8.2\"}}}",
            )
        }
        val snap = client.herdrSnapshot("h1")
        assertEquals("w2", snap.focusedWorkspaceId)
        assertEquals(1, snap.workspaces.size)
        assertEquals("w2", snap.workspaces[0].workspaceId)
        assertEquals("Remotly", snap.workspaces[0].label)
        assertEquals(1, snap.workspaces[0].tabCount)
        assertEquals(1, snap.workspaces[0].paneCount)
        assertTrue(snap.workspaces[0].focused)
    }

    /** A server the app does not understand must degrade, not crash a screen. */
    @Test
    fun `herdrSnapshot rejects output that is not a herdr document`() = runTest {
        val client = clientWith { _, _ -> outcome(stdout = "Welcome to Ubuntu\n") }
        val e = runCatching { client.herdrSnapshot("h1") }.exceptionOrNull()
        assertTrue(e is HerdrError)
    }

    // A sideways gesture goes over the socket because herdr emits no event for
    // a move its own key binding made, so the order the app follows and the
    // target it names are both decided in pure functions, without I/O.
    private fun tab(id: String, number: Int) = HerdrTab(
        tabId = id,
        workspaceId = "w1",
        label = "",
        number = number,
        paneCount = 1,
        focused = false,
        agentStatus = "unknown",
    )

    private val tabs = listOf(tab("w1:t9", 9), tab("w1:t3", 3), tab("w1:t6", 6))

    // herdr's numbers are not contiguous once tabs have been closed, so the
    // order is the numbers, never the ids.
    @Test
    fun `takes the next tab by number, not by id`() {
        assertEquals("w1:t6", nextHerdrTab(tabs, "w1:t3", 1)?.tabId)
    }

    @Test
    fun `wraps at both ends`() {
        assertEquals("w1:t3", nextHerdrTab(tabs, "w1:t9", 1)?.tabId)
        assertEquals("w1:t9", nextHerdrTab(tabs, "w1:t3", -1)?.tabId)
    }

    @Test
    fun `has nowhere to go with one tab`() {
        assertNull(nextHerdrTab(listOf(tab("w1:t1", 1)), "w1:t1", 1))
    }

    // A focus the app has not seen yet must still move somewhere rather than
    // leave the gesture doing nothing.
    @Test
    fun `starts from the first tab when the focused one is unknown`() {
        assertEquals("w1:t6", nextHerdrTab(tabs, null, 1)?.tabId)
    }

    private fun workspace(id: String, number: Int) = HerdrWorkspace(
        workspaceId = id,
        label = "",
        number = number,
        tabCount = 0,
        paneCount = 0,
        activeTabId = null,
        focused = false,
        agentStatus = "unknown",
    )

    @Test
    fun `wraps workspaces at both ends by number, not by id`() {
        val order = HerdrWorkspaceOrder(
            workspaces = listOf(workspace("w9", 9), workspace("w3", 3), workspace("w6", 6)),
            focusedWorkspaceId = "w9",
        )
        assertEquals("w3", nextHerdrWorkspace(order, 1)?.workspaceId)
        assertEquals("w6", nextHerdrWorkspace(order, -1)?.workspaceId)
    }
}
