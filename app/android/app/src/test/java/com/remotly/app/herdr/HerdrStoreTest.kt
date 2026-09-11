package com.remotly.app.herdr

import com.remotly.app.ssh.HerdrBridge
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// The store that keeps a host's herdr state current. What matters here is that
// a pushed event changes the state without another read, that a line the app
// cannot apply exactly asks for a snapshot instead of guessing, that a reader
// which never acknowledges leaves the host on the fallback re-read, and that
// closing one session's screen does not drop a connection another still uses.

// These mirror HerdrStore's own private cadence constants. HerdrStore does not
// expose them; a test that hardcodes the same values it was ported from is the
// same choice the original Jest suite made.
private const val POLL_MS = 4_000L
private const val RECONCILE_MS = 3_000L
private const val ACK_MS = 4_000L
private const val STREAM_RETRY_POLLS = 5

private val SNAPSHOT_JSON = """
{"result":{"snapshot":{
  "focused_workspace_id":"w1","focused_tab_id":"w1:t1",
  "workspaces":[
    {"workspace_id":"w1","label":"api","number":1,"tab_count":1,"pane_count":1,"active_tab_id":"w1:t1","focused":true,"agent_status":"unknown"},
    {"workspace_id":"w2","label":"deploy","number":2,"tab_count":0,"pane_count":0,"active_tab_id":null,"focused":false,"agent_status":"unknown"}
  ],
  "tabs":[
    {"tab_id":"w1:t1","workspace_id":"w1","label":"shell","number":1,"pane_count":1,"focused":true,"agent_status":"unknown"}
  ],
  "panes":[]
}}}
""".trimIndent()

private val SESSIONS_JSON = """
{"sessions":[{"name":"default","default":true,"running":true,
  "session_dir":"/home/dev/.config/herdr","socket_path":"/home/dev/.config/herdr/herdr.sock"}]}
""".trimIndent()

private const val ACK_LINE = "{\"result\":{\"type\":\"subscription_started\"}}"

private fun defaultAnswer(command: String): String {
    val stripped = command.replace("'", "")
    return when {
        stripped.contains("api snapshot") -> SNAPSHOT_JSON
        stripped.contains("session list") -> SESSIONS_JSON
        else -> ""
    }
}

private fun ok(stdout: String) = HerdrBridge.Outcome(
    exitCode = 0,
    stdout = stdout.toByteArray(Charsets.UTF_8),
    stderr = ByteArray(0),
    code = "",
    message = "",
)

private fun failed(code: String, message: String) = HerdrBridge.Outcome(
    exitCode = 0,
    stdout = ByteArray(0),
    stderr = ByteArray(0),
    code = code,
    message = message,
)

/** A fake exec transport. [behavior] is swappable so a test can flip a host
 *  from unreachable to answering, the way an accepted host key would. */
private class FakeTransport : HerdrTransport {
    var behavior: (String, String) -> HerdrBridge.Outcome = { _, command -> ok(defaultAnswer(command)) }
    val calls = mutableListOf<String>()
    override fun exec(hostId: String, command: String): HerdrBridge.Outcome {
        calls += command
        return behavior(hostId, command)
    }
    fun snapshotReads(): Int = calls.count { it.replace("'", "").contains("api snapshot") }
}

/** A fake event stream transport. Every argument is shell-quoted by the real
 *  reader command, so a test strips quotes before matching on it. */
private class FakeEventTransport : HerdrEventTransport {
    val subscribeCalls = mutableListOf<Pair<String, String>>()
    val releases = mutableListOf<String>()
    var onLine: ((String) -> Unit)? = null
    var onClosed: ((String, String) -> Unit)? = null

    override fun subscribe(
        hostId: String,
        command: String,
        onLine: (String) -> Unit,
        onClosed: (String, String) -> Unit,
    ) {
        subscribeCalls += hostId to command
        this.onLine = onLine
        this.onClosed = onClosed
    }

    override fun release(hostId: String) {
        releases += hostId
    }
}

// HerdrStore keeps loops (reconcile, polling, the foreground collector)
// running for as long as something is subscribed; runTest fails a test that
// still has jobs live in its own scope when the test body returns, so the
// store here runs on backgroundScope, which runTest cancels automatically.
private fun TestScope.newStore(
    transport: FakeTransport,
    events: FakeEventTransport,
    foreground: MutableStateFlow<Boolean> = MutableStateFlow(true),
): HerdrStore {
    val client = HerdrClient(transport = transport, ioDispatcher = StandardTestDispatcher(testScheduler))
    return HerdrStore(client = client, events = events, foregrounded = foreground, scope = backgroundScope)
}

/** Advances the virtual clock by one poll or reconcile tick and lets it run. */
private fun TestScope.tick(ms: Long) {
    testScheduler.advanceTimeBy(ms)
    runCurrent()
}

class HerdrStoreTest {

    @Test
    fun `installs the snapshot it bootstrapped from`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h1")
        runCurrent()

        val state = store.hostState("h1").value
        assertTrue(state.loaded)
        assertEquals(listOf("api", "deploy"), state.workspaces.map { it.label })
        assertEquals(listOf("shell"), state.tabs["w1"]?.map { it.label })
        assertEquals("w1:t1", state.focusedTabId)
    }

    @Test
    fun `follows a pushed focus without reading again`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h2")
        runCurrent()
        events.onLine?.invoke(ACK_LINE)
        val reads = transport.snapshotReads()

        events.onLine?.invoke(
            "{\"event\":\"workspace_focused\",\"data\":{\"workspace_id\":\"w2\"}}",
        )

        assertEquals("w2", store.hostState("h2").value.focusedWorkspaceId)
        assertEquals(HerdrFeed.LIVE, store.hostState("h2").value.feed)
        assertEquals(reads, transport.snapshotReads())
    }

    @Test
    fun `adds a created tab from the record the event carries`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h3")
        runCurrent()
        events.onLine?.invoke(ACK_LINE)
        events.onLine?.invoke(
            "{\"event\":\"tab_created\",\"data\":{\"tab\":{\"tab_id\":\"w2:t1\",\"workspace_id\":\"w2\"," +
                "\"label\":\"fresh\",\"number\":1,\"pane_count\":1,\"focused\":false,\"agent_status\":\"unknown\"}}}",
        )

        assertEquals(listOf("fresh"), store.hostState("h3").value.tabs["w2"]?.map { it.label })
    }

    @Test
    fun `re-reads for a move, whose order it will not guess`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h4")
        runCurrent()
        events.onLine?.invoke(ACK_LINE)
        val before = transport.snapshotReads()

        events.onLine?.invoke(
            "{\"event\":\"tab_moved\",\"data\":{\"tab_id\":\"w1:t1\",\"workspace_id\":\"w1\"}}",
        )
        runCurrent()

        assertEquals(before + 1, transport.snapshotReads())
    }

    @Test
    fun `falls back to re-reading when the stream ends`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h5")
        runCurrent()
        events.onLine?.invoke(ACK_LINE)
        assertEquals(HerdrFeed.LIVE, store.hostState("h5").value.feed)

        events.onClosed?.invoke("ssh_remote_closed", "gone")

        assertEquals(HerdrFeed.POLLING, store.hostState("h5").value.feed)
    }

    // A reader that never acknowledges is a host with none of the
    // interpreters herdr can be read from, or a herdr too old for the
    // subscription. Either way, the host cannot be left on "starting"
    // forever.
    @Test
    fun `falls back to polling when the stream never acknowledges`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("hAck")
        runCurrent()
        assertTrue(events.subscribeCalls.isNotEmpty())
        assertEquals(HerdrFeed.STARTING, store.hostState("hAck").value.feed)

        tick(ACK_MS)

        assertEquals(HerdrFeed.POLLING, store.hostState("hAck").value.feed)
    }

    // A host answers nothing until its key is accepted, and the fallback has
    // to be the state a screen sees rather than an error.
    @Test
    fun `falls back when the host answers nothing at all`() = runTest {
        val transport = FakeTransport()
        transport.behavior = { _, _ -> failed("ssh_host_key_rejected", "host key rejected") }
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h8")
        runCurrent()

        val state = store.hostState("h8").value
        assertEquals(HerdrFeed.POLLING, state.feed)
        assertNotNull(state.error)
        assertTrue(events.subscribeCalls.isEmpty())
    }

    // The user accepts the key while the screen is open. Every failed attempt
    // has to leave the next one possible, or the host stays on the timer for
    // the life of the screen.
    @Test
    fun `reaches the stream once the host starts answering`() = runTest {
        val transport = FakeTransport()
        transport.behavior = { _, _ -> failed("ssh_host_key_rejected", "host key rejected") }
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h9")
        runCurrent()
        assertEquals(HerdrFeed.POLLING, store.hostState("h9").value.feed)
        assertTrue(events.subscribeCalls.isEmpty())

        // A retry made while the fallback loop is already running fails too.
        repeat(STREAM_RETRY_POLLS + 1) { tick(POLL_MS) }

        // The key is accepted: the host answers from here on.
        transport.behavior = { _, command -> ok(defaultAnswer(command)) }

        // A poll cycle past the retry it makes every STREAM_RETRY_POLLS polls
        // carries the loop past the point it would try again.
        repeat(STREAM_RETRY_POLLS + 1) { tick(POLL_MS) }

        assertTrue(events.subscribeCalls.isNotEmpty())
        events.onLine?.invoke(ACK_LINE)
        assertEquals(HerdrFeed.LIVE, store.hostState("h9").value.feed)
    }

    @Test
    fun `subscribes with the reader command for the session socket`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("h6")
        runCurrent()

        assertEquals(
            eventStreamCommand("/home/dev/.config/herdr/herdr.sock"),
            events.subscribeCalls.singleOrNull()?.second,
        )
    }

    @Test
    fun `releases the host connection when the last screen leaves`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        val sub = store.subscribe("h7")
        runCurrent()
        sub.close()

        assertEquals(listOf("h7"), events.releases)
    }

    // Two sessions on the same host share the connection: closing one screen
    // must not take the other's stream and reads down with it.
    @Test
    fun `refcounts the connection release across two sessions on one host`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        val default = store.subscribe("h10", null)
        runCurrent()
        val work = store.subscribe("h10", "work")
        runCurrent()

        default.close()
        assertTrue(events.releases.isEmpty())

        work.close()
        assertEquals(listOf("h10"), events.releases)
    }

    @Test
    fun `leaves a refresh for a host nobody is watching alone`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.refresh("nobody", null)
        assertTrue(transport.calls.isEmpty())
    }

    // herdr publishes nothing for a move its own key bindings made, so a tab
    // switched by typing `prefix+n` inside herdr reaches no event. A live host
    // is re-read anyway while the app is in front, on its own schedule.
    @Test
    fun `re-reads a live host on its own schedule while the app is in front`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val store = newStore(transport, events)

        store.subscribe("hB")
        runCurrent()
        events.onLine?.invoke(ACK_LINE)
        val reads = transport.snapshotReads()

        tick(RECONCILE_MS)

        assertEquals(reads + 1, transport.snapshotReads())
    }

    // A phone in a pocket must not keep asking.
    @Test
    fun `stops re-reading while the app is in the background`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val foreground = MutableStateFlow(true)
        val store = newStore(transport, events, foreground)

        store.subscribe("hC")
        runCurrent()
        events.onLine?.invoke(ACK_LINE)

        foreground.value = false
        val reads = transport.snapshotReads()
        repeat(4) { tick(RECONCILE_MS) }
        assertEquals(reads, transport.snapshotReads())
    }

    // herdr publishes nothing for what the user typed while the app was away,
    // so the moment it returns is when that has to be caught up.
    @Test
    fun `re-reads every watched host the moment the app returns to the foreground`() = runTest {
        val transport = FakeTransport()
        val events = FakeEventTransport()
        val foreground = MutableStateFlow(true)
        val store = newStore(transport, events, foreground)

        store.subscribe("hA")
        runCurrent()
        foreground.value = false
        runCurrent()
        val reads = transport.snapshotReads()

        foreground.value = true
        runCurrent()

        assertEquals(reads + 1, transport.snapshotReads())
    }
}
