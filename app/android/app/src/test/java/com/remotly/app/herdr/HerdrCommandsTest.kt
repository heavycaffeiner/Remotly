package com.remotly.app.herdr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The exact documents the pinned herdr 0.8.2 CLI prints, captured from a live
// server. A parser that drifts from these shapes is a regression.
private const val SESSION_LIST =
    "{\"sessions\":[{\"default\":true,\"name\":\"default\",\"running\":true," +
        "\"session_dir\":\"/c/herdr\",\"socket_path\":\"/c/herdr/herdr.sock\"}]}"

private const val SNAPSHOT =
    "{\"id\":\"cli:api:snapshot\",\"result\":{\"type\":\"session_snapshot\",\"snapshot\":{" +
        "\"agents\":[],\"focused_pane_id\":\"w2:p1\",\"focused_tab_id\":\"w2:t1\"," +
        "\"focused_workspace_id\":\"w2\",\"panes\":[{\"cwd\":\"/home/x\",\"focused\":true," +
        "\"pane_id\":\"w2:p1\",\"tab_id\":\"w2:t1\",\"terminal_id\":\"term_1\"," +
        "\"workspace_id\":\"w2\"}],\"protocol\":20,\"tabs\":[{\"focused\":true,\"label\":\"1\"," +
        "\"number\":1,\"pane_count\":1,\"tab_id\":\"w2:t1\",\"workspace_id\":\"w2\"}]," +
        "\"version\":\"0.8.2\",\"workspaces\":[{\"active_tab_id\":\"w2:t1\",\"focused\":true," +
        "\"label\":\"Remotly\",\"number\":1,\"pane_count\":1,\"tab_count\":1," +
        "\"workspace_id\":\"w2\"}]}}}"

private const val CREATED =
    "{\"id\":\"cli:workspace:create\",\"result\":{\"root_pane\":{\"focused\":false," +
        "\"pane_id\":\"w8:p1\",\"tab_id\":\"w8:t1\",\"workspace_id\":\"w8\"},\"tab\":{\"label\":\"1\"," +
        "\"number\":1,\"pane_count\":1,\"tab_id\":\"w8:t1\",\"workspace_id\":\"w8\"}," +
        "\"type\":\"workspace_created\",\"workspace\":{\"active_tab_id\":\"w8:t1\",\"focused\":false," +
        "\"label\":\"__probe\",\"number\":4,\"pane_count\":1,\"tab_count\":1,\"workspace_id\":\"w8\"}}}"

class HerdrCommandsTest {

    // --- herdr lookup ----------------------------------------------------

    @Test
    fun `takes the path out of whatever the rc files printed around it`() {
        assertEquals(
            "/home/d/.local/bin/herdr",
            parseHerdrLookup(
                "p10k: warning\n__remotly_herdr_path__\n/home/d/.local/bin/herdr\n__remotly_herdr_end__\n\$ ",
            ),
        )
    }

    // `command -v` answers for an alias or a function too, and neither is
    // something the next shell can run.
    @Test
    fun `rejects an answer that is not an absolute path`() {
        assertNull(
            parseHerdrLookup(
                "__remotly_herdr_path__\nherdr: aliased to herdr --session work\n__remotly_herdr_end__\n",
            ),
        )
    }

    @Test
    fun `reads nothing when the shell never got that far`() {
        assertNull(parseHerdrLookup("zsh: permission denied\n"))
    }

    // --- shell quoting -----------------------------------------------------

    @Test
    fun `wraps a plain value in single quotes`() {
        assertEquals("'abc'", shellQuote("abc"))
    }

    @Test
    fun `escapes an embedded single quote by closing, escaping, reopening`() {
        assertEquals("'a'\\''b'", shellQuote("a'b"))
    }

    @Test
    fun `protects shell metacharacters from expansion`() {
        assertEquals("'\$HOME; rm -rf /'", shellQuote("\$HOME; rm -rf /"))
        assertEquals("'a b\$(c)'", shellQuote("a b\$(c)"))
    }

    @Test
    fun `joins every element quoted, including the program`() {
        assertEquals(
            "'herdr' 'workspace' 'create' '--label' 'O'\\''Brien x'",
            joinShell(listOf("herdr", "workspace", "create", "--label", "O'Brien x")),
        )
    }

    // --- command builders --------------------------------------------------

    @Test
    fun `targets the default session with no flag`() {
        assertEquals("'herdr' 'session' 'list' '--json'", sessionListCommand())
    }

    @Test
    fun `places a named session as a global flag before the subcommand`() {
        assertEquals(
            "'herdr' '--session' 'work' 'workspace' 'create' '--label' 'web'",
            workspaceCreateCommand(HerdrCreateWorkspace(label = "web"), "work"),
        )
    }

    @Test
    fun `quotes a user label so a metacharacter cannot run a different command`() {
        assertEquals(
            "'herdr' 'workspace' 'create' '--label' 'x; curl evil'",
            workspaceCreateCommand(HerdrCreateWorkspace(label = "x; curl evil")),
        )
    }

    @Test
    fun `builds focus, snapshot, pane-read, and prompt commands`() {
        assertEquals("'herdr' 'workspace' 'focus' 'w2'", workspaceFocusCommand("w2"))
        assertEquals("'herdr' 'api' 'snapshot'", apiSnapshotCommand())
        assertEquals(
            "'herdr' 'pane' 'read' 'w2:p1' '--lines' '3'",
            paneReadCommand(HerdrPaneRead(paneId = "w2:p1", lines = 3)),
        )
        assertEquals(
            "'herdr' 'agent' 'prompt' 'w2:p1' 'run tests' '--wait'",
            agentPromptCommand("w2:p1", "run tests", wait = true),
        )
    }

    // --- parsing -------------------------------------------------------------

    @Test
    fun `parses a bare session list document`() {
        val sessions = parseSessions(SESSION_LIST)
        assertEquals(1, sessions.size)
        assertEquals("default", sessions[0].name)
        assertTrue(sessions[0].default)
        assertTrue(sessions[0].running)
        assertEquals("/c/herdr/herdr.sock", sessions[0].socketPath)
    }

    @Test
    fun `parses an api snapshot into the full session state`() {
        val snap = parseSnapshot(SNAPSHOT)
        assertEquals("w2", snap.focusedWorkspaceId)
        assertEquals("w2:p1", snap.focusedPaneId)
        assertEquals(20, snap.protocol)
        assertEquals("0.8.2", snap.version)
        assertEquals(1, snap.workspaces.size)
        assertEquals("Remotly", snap.workspaces[0].label)
        assertEquals("w2:p1", snap.panes[0].paneId)
        assertEquals("/home/x", snap.panes[0].cwd)
    }

    @Test
    fun `tolerates an omitted snapshot field rather than throwing`() {
        val sparse =
            "{\"id\":\"cli:api:snapshot\",\"result\":{\"type\":\"session_snapshot\",\"snapshot\":{}}}"
        assertEquals(emptyList<HerdrWorkspace>(), parseSnapshot(sparse).workspaces)
        assertNull(parseSnapshot(sparse).focusedPaneId)
        assertNull(parseSnapshot(sparse).protocol)
    }

    @Test
    fun `parses a created workspace, keeping the root pane and tab`() {
        val created = parseCreatedWorkspace(CREATED)
        assertEquals("w8", created.workspace.workspaceId)
        assertEquals("__probe", created.workspace.label)
        assertEquals("w8:p1", created.rootPane?.paneId)
        assertEquals("w8:t1", created.tab?.tabId)
    }

    @Test
    fun `surfaces a CLI error document as its code and message`() {
        val doc = "{\"error\":{\"code\":\"session_stop_failed\",\"message\":\"session x is not running\"}}"
        assertEquals(HerdrCliError("session_stop_failed", "session x is not running"), parseCliError(doc))
        assertNull(parseCliError(SNAPSHOT))
        assertNull(parseCliError("not json at all"))
    }

    @Test
    fun `reports non-JSON output as bad_json rather than a raw parse throw`() {
        val e1 = runCatching { parseSnapshot("no json here") }.exceptionOrNull()
        assertTrue(e1 is HerdrError)
        assertEquals("herdr output is not JSON", (e1 as HerdrError).detail)

        val e2 = runCatching { parseSessions("[]") }.exceptionOrNull()
        assertTrue(e2 is HerdrError)
    }

    // --- the reader command --------------------------------------------------

    @Test
    fun `asks for the events the app draws from`() {
        val command = eventStreamCommand("/tmp/herdr.sock")
        assertTrue(command.contains("events.subscribe"))
        assertTrue(command.contains("workspace.focused"))
        assertTrue(command.contains("tab.created"))
        // A per-scroll event would arrive on every wheel turn, so it is not
        // asked for at all rather than filtered later.
        assertTrue(!command.contains("pane.scroll_changed"))
    }

    @Test
    fun `quotes the socket path it was given`() {
        assertTrue(eventStreamCommand("/tmp/it's here.sock").contains("'/tmp/it'\\''s here.sock'"))
    }

    @Test
    fun `tries the direct readers before the interpreters`() {
        val command = eventStreamCommand("/tmp/s")
        assertTrue(command.indexOf("socat") < command.indexOf("python3"))
        assertTrue(command.indexOf("python3") < command.indexOf("perl"))
    }

    // --- one line of the event stream ------------------------------------

    @Test
    fun `reads the acknowledgement that proves the reader works`() {
        assertEquals(HerdrEvent.Ack, parseHerdrEvent("{\"result\":{\"type\":\"subscription_started\"}}"))
    }

    @Test
    fun `keeps the new label a rename carries`() {
        assertEquals(
            HerdrEvent.TabRenamed(tabId = "w1:t1", workspaceId = "w1", label = "built"),
            parseHerdrEvent(
                "{\"event\":\"tab_renamed\",\"data\":{\"tab_id\":\"w1:t1\",\"workspace_id\":\"w1\",\"label\":\"built\"}}",
            ),
        )
    }

    @Test
    fun `drops an event missing what its own type needs`() {
        assertNull(parseHerdrEvent("{\"event\":\"tab_focused\",\"data\":{\"tab_id\":\"w1:t1\"}}"))
    }

    @Test
    fun `drops a line that is not the stream`() {
        assertNull(parseHerdrEvent("herdr: command not found"))
        assertNull(parseHerdrEvent(""))
    }

    @Test
    fun `re-reads for a move rather than guessing its order`() {
        assertEquals(
            HerdrEvent.Resync,
            parseHerdrEvent(
                "{\"event\":\"tab_moved\",\"data\":{\"tab_id\":\"w1:t1\",\"workspace_id\":\"w1\"}}",
            ),
        )
    }
}
