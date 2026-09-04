package com.remotly.app.transport

import com.google.gson.JsonParser
import com.remotly.app.identity.Identity
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Exercises the hub against the in-test daemon with an inline poster, so
// callbacks and events observe single-threaded ordering. The wire, identity,
// and event sink are all fakes, matching the production wiring contract.
class TransportHubTest {
    private val appPriv = ByteArray(32) { (it + 1).toByte() }
    private val daemonPriv = ByteArray(32) { (it + 100).toByte() }
    private val tokenSecret = ByteArray(32) { 0xAA.toByte() }
    private val tokenID = "token-token-id".encodeToByteArray()
    private val daemonPubB64 = Base64Url.encode(X25519.pub(daemonPriv))
    private val sessionId = "0".repeat(64)

    private val hostA = "host-a"
    private val hostB = "host-b"

    private val daemons = mutableListOf<LoopbackDaemon>()
    private val events = mutableListOf<Pair<String, Map<String, Any?>>>()
    private var factoryHost: String? = null
    private var factoryPort: Int? = null
    private var daemonConfig: (LoopbackDaemon) -> Unit = {}

    private val daemon: LoopbackDaemon
        get() = daemons.last()

    @Before
    fun setUp() {
        TransportHub.reset()
        daemons.clear()
        events.clear()
        factoryHost = null
        factoryPort = null
        TransportHub.wireFactory = { host, port ->
            factoryHost = host
            factoryPort = port
            val d = LoopbackDaemon(daemonPriv, tokenSecret)
            daemonConfig(d)
            daemons += d
            d
        }
        TransportHub.identityProvider = { Identity(appPriv, X25519.pub(appPriv)) }
        TransportHub.deviceNameProvider = { "phone" }
        TransportHub.poster = TransportHub.MainPoster { it.run() }
        TransportHub.setEventSink(hostA) { name, data -> events += name to data }
    }

    private fun <T> failOr(message: String): T = throw AssertionError(message)

    private fun await(timeoutMs: Long = 5000, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) return false
            try {
                Thread.sleep(10)
            } catch (e: InterruptedException) {
                return false
            }
        }
        return true
    }

    private fun eventsOf(name: String) = events.filter { it.first == name }

    private fun connectIk(
        hostId: String = hostA,
        target: String = "192.168.1.10",
        onOk: (String, String) -> Unit = { _, _ -> },
        onErr: (Int, String) -> Unit = { _, _ -> },
    ) = TransportHub.connect(hostId, target, daemonPubB64, null, null, onOk, onErr)

    private fun connectAndAwait(hostId: String = hostA, target: String = "192.168.1.10"): Pair<String, String> {
        var ok: Pair<String, String>? = null
        var err: Pair<Int, String>? = null
        connectIk(hostId, target, { n, p -> ok = n to p }, { c, r -> err = c to r })
        assertTrue("connect timed out: ${err?.let { "${it.first} ${it.second}" }}", await { ok != null })
        return ok!!
    }

    // --- connect ---

    @Test
    fun ikConnectSucceedsAndEmitsConnected() {
        var ok: Pair<String, String>? = null
        var err: Pair<Int, String>? = null
        connectIk(onOk = { n, p -> ok = n to p }, onErr = { c, r -> err = c to r })
        assertTrue("timed out: $err", await { ok != null })
        assertEquals("test-daemon", ok!!.first)
        assertEquals(daemonPubB64, ok!!.second)
        assertEquals("192.168.1.10", factoryHost)
        assertEquals(8788, factoryPort)

        val ev = eventsOf("connected").singleOrNull() ?: failOr("no connected event")
        assertEquals("test-daemon", ev.second["daemonName"])
        assertEquals(daemonPubB64, ev.second["daemonPub"])
        assertEquals(hostA, ev.second["hostId"])

        val st = TransportHub.status(hostA)
        assertTrue(st.connected)
        assertEquals("connected", st.state)
        assertEquals("test-daemon", st.daemonName)
        assertEquals(daemonPubB64, st.daemonPub)
    }

    @Test
    fun pairConnectSendsTokenAndSecret() {
        var ok: Pair<String, String>? = null
        var err: Pair<Int, String>? = null
        TransportHub.connect(
            hostA,
            "192.168.1.10:9000",
            null,
            Base64Url.encode(tokenID),
            Base64Url.encode(tokenSecret),
            { n, p -> ok = n to p },
            { c, r -> err = c to r },
        )
        assertTrue("timed out: $err", await { ok != null })
        assertArrayEquals(tokenID, daemon.receivedTokenID)
        assertEquals("192.168.1.10", factoryHost)
        assertEquals(9000, factoryPort)
    }

    @Test
    fun ipv6TargetParses() {
        connectAndAwait(target = "[::1]")
        assertEquals("::1", factoryHost)
        assertEquals(8788, factoryPort)
        TransportHub.close(hostA)
        assertTrue(await { TransportHub.status(hostA).state == "disconnected" })

        connectAndAwait(target = "[::1]:9001")
        assertEquals("::1", factoryHost)
        assertEquals(9001, factoryPort)
    }

    @Test
    fun badTargetFails() {
        for (target in listOf("", "   ", "host:abc", "[:1", ":9000")) {
            var err: Pair<Int, String>? = null
            connectIk(target = target, onErr = { c, r -> err = c to r })
            assertEquals("target '$target'", CloseCode.PROTOCOL, err!!.first)
            assertEquals("target '$target'", "bad target", err!!.second)
        }
    }

    @Test
    fun blankHostIdFails() {
        var err: Pair<Int, String>? = null
        connectIk(hostId = "  ", onErr = { c, r -> err = c to r })
        assertEquals(CloseCode.PROTOCOL to "bad host id", err)
    }

    @Test
    fun missingOrBadCredentialsFail() {
        var err: Pair<Int, String>? = null
        TransportHub.connect(hostA, "h", null, null, null, { _, _ -> }, { c, r -> err = c to r })
        assertEquals(CloseCode.AUTH to "missing credentials", err)

        err = null
        TransportHub.connect(hostA, "h", "!!!", null, null, { _, _ -> }, { c, r -> err = c to r })
        assertEquals(CloseCode.AUTH to "bad daemon key", err)

        err = null
        TransportHub.connect(hostA, "h", null, Base64Url.encode(ByteArray(65)), Base64Url.encode(tokenSecret), { _, _ -> }, { c, r -> err = c to r })
        assertEquals(CloseCode.AUTH to "bad pairing credentials", err)

        err = null
        TransportHub.connect(hostA, "h", null, Base64Url.encode(tokenID), Base64Url.encode(ByteArray(16)), { _, _ -> }, { c, r -> err = c to r })
        assertEquals(CloseCode.AUTH to "bad pairing credentials", err)
    }

    /**
     * Connecting to a host that is already connected reports the live
     * connection rather than an error. A screen that remounts over an existing
     * transport calls connect again, and failing it left the workspace showing
     * "disconnected" while its own socket was established and carrying data.
     */
    @Test
    fun connectWhileConnectedSucceeds() {
        val first = connectAndAwait()
        var name: String? = null
        var err: Pair<Int, String>? = null
        connectIk(onOk = { n, _ -> name = n }, onErr = { c, r -> err = c to r })
        assertTrue("no success", await { name != null })
        assertNull(err)
        assertEquals(first.first, name)
    }

    @Test
    fun handshakeFailureFailsConnect() {
        daemonConfig = { it.badVersion = true }
        var ok: String? = null
        var err: Pair<Int, String>? = null
        connectIk(onOk = { n, _ -> ok = n }, onErr = { c, r -> err = c to r })
        assertTrue("no failure", await { err != null })
        assertNull(ok)
        assertEquals(CloseCode.PROTOCOL, err!!.first)
    }

    @Test
    fun closeFailsPendingConnect() {
        daemonConfig = { it.silent = true }
        var err: Pair<Int, String>? = null
        connectIk(onErr = { c, r -> err = c to r })
        TransportHub.close(hostA, 1000, "bye")
        assertEquals(1000 to "bye", err)
        assertEquals("disconnected", TransportHub.status(hostA).state)
        assertEquals(0, eventsOf("connected").size)
    }

    // --- multi-host ---

    @Test
    fun twoHostsConnectIndependently() {
        val eventsB = mutableListOf<Pair<String, Map<String, Any?>>>()
        TransportHub.setEventSink(hostB) { name, data -> eventsB += name to data }

        connectAndAwait(hostA, "192.168.1.10")
        connectAndAwait(hostB, "192.168.1.20")

        assertEquals("192.168.1.20", factoryHost)
        assertTrue(TransportHub.status(hostA).connected)
        assertTrue(TransportHub.status(hostB).connected)
        assertEquals(2, daemons.size)

        // Events route to the sink of the host that owns the connection.
        val ch = attach(hostB)
        daemon.pushTerm(ch, byteArrayOf(1))
        assertTrue("hostB got no termData", await { eventsB.any { it.first == "termData" } })
        assertTrue("hostA must not see hostB traffic", eventsOf("termData").isEmpty())
        assertEquals(hostB, eventsB.single { it.first == "termData" }.second["hostId"])

        // Closing one host leaves the other connected.
        TransportHub.close(hostA)
        assertTrue(await { TransportHub.status(hostA).state == "disconnected" })
        assertTrue(TransportHub.status(hostB).connected)
        assertEquals(listOf(hostB), TransportHub.activeHostIds())
    }

    @Test
    fun hostLimitRejectsNinthConnection() {
        for (i in 1..8) {
            connectAndAwait("host-$i", "192.168.1.$i")
        }
        assertEquals(8, TransportHub.activeHostIds().size)
        var err: Pair<Int, String>? = null
        TransportHub.connect("host-9", "192.168.1.9", daemonPubB64, null, null, { _, _ -> }, { c, r -> err = c to r })
        assertEquals(CloseCode.LIMIT to "too many hosts", err)

        // Releasing one host makes room again.
        TransportHub.close("host-1")
        connectAndAwait("host-9", "192.168.1.9")
        assertEquals(8, TransportHub.activeHostIds().size)
    }

    // --- close / status ---

    @Test
    fun closeEmitsNothingButClearsState() {
        connectAndAwait()
        val before = events.size
        TransportHub.close(hostA, 1000, "bye")
        assertEquals(before, events.size)
        val st = TransportHub.status(hostA)
        assertFalse(st.connected)
        assertEquals("disconnected", st.state)
        assertNull(st.daemonName)
    }

    @Test
    fun daemonCloseEmitsDisconnected() {
        connectAndAwait()
        daemon.close(1001, "going away")
        val ev = await { eventsOf("disconnected").size == 1 }
        assertTrue("no disconnected event", ev)
        assertEquals(1001, eventsOf("disconnected").single().second["code"])
        assertEquals("going away", eventsOf("disconnected").single().second["reason"])
        assertEquals(hostA, eventsOf("disconnected").single().second["hostId"])
        assertFalse(TransportHub.status(hostA).connected)
    }

    @Test
    fun reconnectAfterClose() {
        connectAndAwait()
        TransportHub.close(hostA)
        assertTrue(await { TransportHub.status(hostA).state == "disconnected" })
        connectAndAwait()
        assertEquals(2, daemons.size)
        assertEquals("connected", TransportHub.status(hostA).state)
        assertEquals(2, eventsOf("connected").size)
    }

    @Test
    fun reconnectAfterDaemonClose() {
        connectAndAwait()
        daemon.close(1001, "gone")
        assertTrue(await { TransportHub.status(hostA).state == "disconnected" })
        connectAndAwait()
        assertEquals(2, daemons.size)
    }

    // --- control ---

    @Test
    fun controlRoundTrip() {
        connectAndAwait()
        var resp: Result<String>? = null
        TransportHub.sendControl(hostA, """{"type":"${ControlType.SESSION_LIST}"}""") { resp = it }
        assertTrue("timed out", await { resp != null })
        val json = resp!!.getOrNull() ?: failOr("control failed: ${resp!!.exceptionOrNull()}")
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals(ControlType.SESSION_LIST, obj.get("type").asString)
        assertEquals(1, obj.get("sessions").asJsonArray.size())
    }

    @Test
    fun controlProtocolErrorStillSucceeds() {
        connectAndAwait()
        daemon.createError = true
        var resp: Result<String>? = null
        TransportHub.sendControl(hostA, """{"type":"${ControlType.SESSION_CREATE}","kind":"shell"}""") { resp = it }
        assertTrue("timed out", await { resp != null })
        val json = resp!!.getOrNull() ?: failOr("control failed")
        val obj = JsonParser.parseString(json).asJsonObject
        assertEquals("spawn_failed", obj.get("error").asJsonObject.get("code").asString)
        assertTrue(TransportHub.status(hostA).connected)
    }

    @Test
    fun controlFailsWhenNotConnectedOrMalformed() {
        var resp: Result<String>? = null
        TransportHub.sendControl(hostA, """{"type":"${ControlType.SESSION_LIST}"}""") { resp = it }
        assertTrue(await { resp != null })
        assertTrue(resp!!.isFailure)

        connectAndAwait()
        resp = null
        TransportHub.sendControl(hostA, "not json") { resp = it }
        assertTrue(await { resp != null })
        assertTrue(resp!!.isFailure)

        resp = null
        TransportHub.sendControl(hostA, "{}") { resp = it }
        assertTrue(await { resp != null })
        assertTrue(resp!!.isFailure)
    }

    // --- terminal ---

    @Test
    fun termDataEventCarriesChannelAndFastPath() {
        connectAndAwait()
        val ch = attach()
        daemon.pushTerm(ch, byteArrayOf(0x01, 0x02, 0xFF.toByte()))
        assertTrue("no termData event", await { eventsOf("termData").size == 1 })
        val data = eventsOf("termData").single().second
        assertEquals(ch, data["channelId"])
        assertEquals(hostA, data["hostId"])
        assertEquals(3, data["length"])
        assertEquals(true, data["fastPath"])
    }

    // A reattach that lands on continuity "full" replays the daemon's whole
    // retained ring. The terminal it is fed into is retained across the
    // detach, so everything the terminal was already shown arrives a second
    // time and the user sees the same output twice. The overlap is exactly the
    // distance between the replay's first byte and what the terminal has
    // consumed, and only the tail past that may be written.
    @Test
    fun replaySkipsBytesTheTerminalAlreadyHas() {
        // Terminal has consumed through offset 500; the replay restarts at 200.
        // The first 300 bytes are old, the remaining 700 are new.
        assertEquals(300, TransportHub.replaySkipBytes(200L, 500L, 1000))
    }

    @Test
    fun replayKeepsEverythingForAFreshTerminal() {
        // Nothing consumed yet, so none of the replay is a duplicate.
        assertEquals(0, TransportHub.replaySkipBytes(0L, 0L, 1000))
    }

    @Test
    fun replaySkipsNothingWhenTheWatermarkIsBehindTheReplay() {
        // The daemon dropped output between the watermark and the replay start:
        // that is the reported gap, and every replayed byte is still new.
        assertEquals(0, TransportHub.replaySkipBytes(900L, 100L, 500))
    }

    @Test
    fun replaySkipsEverythingWhenFullyConsumed() {
        // Reattaching with no new output must write nothing at all.
        assertEquals(500, TransportHub.replaySkipBytes(1000L, 2000L, 500))
    }

    // Over the cap the oldest replay chunks are evicted. They are never
    // written, but the bytes after them are, so the surviving run starts that
    // far along the stream. Measuring the skip from replayed_from instead
    // would treat already-shown bytes as new and write them a second time.
    @Test
    fun replaySkipCountsBytesDroppedFromTheFront() {
        // Replay starts at 0, 400 bytes were evicted, 600 survive, and the
        // terminal holds through 700. The surviving run starts at 400, so 300
        // of it is old and 300 is new.
        val start = 0L + 400L
        assertEquals(300, TransportHub.replaySkipBytes(start, 700L, 600))
    }

    // A reattach that replays nothing still has to record where the stream is.
    // With no entry the next reattach reads a watermark of zero and rewrites
    // the daemon's whole retained ring, which is the duplicate this prevents.
    @Test
    fun emptyReplayStillLeavesAWatermark() {
        // Nothing to write, but the channel is positioned at 5000, so a later
        // replay from 4000 must skip the 1000 bytes already shown.
        assertEquals(1000, TransportHub.replaySkipBytes(4000L, 5000L, 2000))
    }

    // Attaches a session on one host and returns the channel the daemon
    // opened for it.
    private fun attach(hostId: String = hostA): Long {
        var attach: Result<String>? = null
        TransportHub.sendControl(
            hostId,
            """{"type":"${ControlType.SESSION_ATTACH}","session_id":"$sessionId"}""",
        ) { attach = it }
        assertTrue("attach timed out", await { attach != null })
        return JsonParser.parseString(attach!!.getOrNull() ?: failOr("attach failed"))
            .asJsonObject.get("channel_id").asLong
    }

    @Test
    fun writeTermSendsToAttachedChannel() {
        connectAndAwait()
        val ch = attach()
        assertTrue(TransportHub.writeTerm(hostA, ch, "ls\n".encodeToByteArray()))
        assertTrue("no term input", await { daemon.termInput.isNotEmpty() })
        assertEquals(ch, daemon.termInput.first().first)
        assertArrayEquals("ls\n".encodeToByteArray(), daemon.termInput.first().second)
    }

    @Test
    fun writeTermFailsWhenNotConnected() {
        assertFalse(TransportHub.writeTerm(hostA, 1L, "x".encodeToByteArray()))
    }

    // --- notifications ---

    @Test
    fun sessionUpdateEventCarriesMeta() {
        connectAndAwait()
        daemon.pushSessionUpdate(
            SessionMeta(
                id = sessionId,
                title = "s",
                kind = SessionKind.SHELL,
                command = "",
                cwd = "/",
                cols = 80,
                rows = 24,
                createdAt = "2026-01-01T00:00:00Z",
                lastActivity = "2026-01-01T00:00:00Z",
                running = true,
                exit = null,
                preview = "last line",
            ),
        )
        assertTrue("no sessionUpdate event", await { eventsOf("sessionUpdate").size == 1 })
        val session = eventsOf("sessionUpdate").single().second["session"] as? Map<*, *>
            ?: failOr("no session map")
        assertEquals(sessionId, session["id"])
        assertEquals("s", session["title"])
        assertEquals(80, session["cols"])
        assertEquals(true, session["running"])
        assertEquals("last line", session["preview"])
    }

    @Test
    fun sessionEventCarriesFieldsAndHostId() {
        connectAndAwait()
        daemon.pushSessionEvent(sessionId, 1, EventKind.BELL, text = "done")
        assertTrue("no sessionEvent", await { eventsOf("sessionEvent").size == 1 })
        val data = eventsOf("sessionEvent").single().second
        assertEquals(sessionId, data["sessionId"])
        assertEquals(1L, data["seq"])
        assertEquals(EventKind.BELL, data["kind"])
        assertEquals("done", data["text"])
        assertEquals(hostA, data["hostId"])
    }

    @Test
    fun channelCloseEventCarriesChannelAndReason() {
        connectAndAwait()
        val ch = attach()
        daemon.pushChannelClose(ch, "session_exited")
        assertTrue("no channelClose event", await { eventsOf("channelClose").size == 1 })
        val data = eventsOf("channelClose").single().second
        assertEquals(ch, data["channelId"])
        assertEquals("session_exited", data["reason"])
    }

    @Test
    fun eventsAreSuppressedAfterClose() {
        connectAndAwait()
        TransportHub.close(hostA)
        assertTrue(await { TransportHub.status(hostA).state == "disconnected" })
        daemon.pushTerm(1L, byteArrayOf(1))
        daemon.pushChannelClose(1L, "x")
        daemon.pushSessionUpdate(
            SessionMeta(
                id = sessionId, title = "s", kind = SessionKind.SHELL, command = "", cwd = "/",
                cols = 80, rows = 24, createdAt = "", lastActivity = "", running = false, exit = null,
            ),
        )
        Thread.sleep(100)
        assertEquals(0, eventsOf("termData").size)
        assertEquals(0, eventsOf("channelClose").size)
        assertEquals(0, eventsOf("sessionUpdate").size)
    }
}
