package com.remotly.app.transport

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// A minimal in-test daemon: the responder side of the handshake plus a
// scripted control and term responder. It reuses the app's crypto classes,
// whose wire bytes are independently pinned against the daemon's Go
// reference in TransportVectors, so these tests exercise the framing and the
// state machine rather than the cipher suite.
class LoopbackDaemon(
    private val staticPriv: ByteArray,
    private val tokenSecret: ByteArray?,
    private val daemonName: String = "test-daemon",
) : Wire {
    override var listener: WireListener = NOOP
    private val gson = Gson()
    private val random = SecureRandom()

    private var hs: HandshakeState? = null
    private var cipher: FrameCipher? = null
    private var expectMsg3 = false
    private var nextChannel = 1L

    // Test hooks.
    var silent = false
    var blackhole = false
    var badVersion = false
    var createError = false

    var receivedTokenID: ByteArray? = null
    var observedAppPub: ByteArray? = null
    var helloName: String? = null
    val termInput = mutableListOf<Pair<Long, ByteArray>>()

    override fun connect() {
        listener.onOpen()
    }

    override fun sendBinary(data: ByteArray) {
        val c = cipher
        if (c != null) {
            val f = c.openFrame(data)
            when (f.chType) {
                Transport.CHANNEL_CTRL -> handleCtrl(f.payload)
                Transport.CHANNEL_TERM -> termInput += f.chId to f.payload
            }
            return
        }
        if (silent) return
        if (expectMsg3) {
            val st = hs ?: return
            install(st, st.readMessage(data))
            expectMsg3 = false
            return
        }
        if (data.size < 2) return
        if (data[0].toInt() and 0xff != 1) return
        val mode = data[1].toInt() and 0xff
        var off = 2
        var secret = tokenSecret
        if (mode == 1) {
            val (len, n) = Varint.decode(data, off)
            off += n
            receivedTokenID = data.copyOfRange(off, off + len.toInt())
            off += len.toInt()
            check(secret != null)
        }
        val body = data.copyOfRange(off, data.size)
        val eph = ByteArray(32).also { random.nextBytes(it) }
        val st = if (mode == 0) {
            HandshakeState(false, Patterns.IK, Transport.PROLOGUE, staticPriv, null, null, 0, eph)
        } else {
            HandshakeState(false, Patterns.XX, Transport.PROLOGUE, staticPriv, null, secret, 0, eph)
        }
        hs = st
        st.readMessage(body)
        val r2 = st.writeMessage(ByteArray(0))
        sendPrefixed(mode, r2.message)
        if (mode == 0) {
            install(st, r2)
        } else {
            expectMsg3 = true
        }
    }

    override fun close(code: Int, reason: String) {
        listener.onClosed(code, reason)
    }

    // --- test hooks ---

    fun pushTerm(channelId: Long, data: ByteArray) = pushFrame(Transport.CHANNEL_TERM, channelId, data)

    fun pushChannelClose(channelId: Long, reason: String) {
        val json = gson.toJson(mapOf(
            "type" to ControlType.CHANNEL_CLOSE,
            "channel_id" to channelId,
            "reason" to reason,
        ))
        pushCtrl(json)
    }

    fun pushSessionUpdate(meta: SessionMeta) {
        val json = gson.toJson(mapOf("type" to ControlType.SESSION_UPDATE, "session" to meta))
        pushCtrl(json)
    }

    fun pushReplayComplete(channelId: Long, offset: Long) {
        val json = gson.toJson(mapOf(
            "type" to ControlType.CHANNEL_REPLAY_COMPLETE,
            "channel_id" to channelId,
            "offset" to offset,
        ))
        pushCtrl(json)
    }

    fun pushSessionEvent(
        sessionId: String,
        seq: Long,
        kind: String,
        pattern: String? = null,
        text: String? = null,
        ts: Long = 1750000000L,
    ) {
        val map = mutableMapOf<String, Any?>(
            "type" to ControlType.SESSION_EVENT,
            "session_id" to sessionId,
            "seq" to seq,
            "kind" to kind,
            "ts" to ts,
        )
        if (pattern != null) map["pattern"] = pattern
        if (text != null) map["text"] = text
        pushCtrl(gson.toJson(map))
    }

    fun pushText() = listener.onFailure("text frame")

    fun failTransport(message: String) = listener.onFailure(message)

    fun pushCtrl(json: String) = pushFrame(Transport.CHANNEL_CTRL, 0L, json.toByteArray())

    private fun pushFrame(chType: Int, chId: Long, payload: ByteArray) {
        val c = cipher ?: return
        listener.onBinary(c.sealFrame(chType, chId, payload))
    }

    private fun install(st: HandshakeState, r: HandshakeResult) {
        val c1 = r.c1 ?: return
        val c2 = r.c2 ?: return
        observedAppPub = st.peerStatic()
        // The responder sends with c2 and receives with c1.
        cipher = FrameCipher(c2, c1)
    }

    private fun sendPrefixed(mode: Int, msg: ByteArray) {
        val v = if (badVersion) 2 else 1
        listener.onBinary(byteArrayOf(v.toByte(), mode.toByte()) + msg)
    }

    private fun handleCtrl(payload: ByteArray) {
        if (blackhole) return
        val obj = try {
            gson.fromJson(String(payload, Charsets.UTF_8), JsonObject::class.java)
        } catch (e: Exception) {
            return
        }
        val id = obj?.get("id")?.asLong ?: return
        val type = obj.get("type")?.asString ?: return
        when (type) {
            ControlType.HELLO -> {
                helloName = obj.get("device_name")?.asString
                val pub = obj.get("device_pub")?.asString
                val expected = observedAppPub?.let { Base64Url.encode(it) }
                if (pub != expected) {
                    listener.onClosed(CloseCode.AUTH, "bad hello")
                    return
                }
                pushCtrl(gson.toJson(mapOf(
                    "id" to id,
                    "type" to ControlType.HELLO,
                    "daemon_name" to daemonName,
                    "daemon_pub" to Base64Url.encode(X25519.pub(staticPriv)),
                )))
            }
            ControlType.SESSION_CREATE -> {
                if (createError) {
                    pushCtrl(gson.toJson(mapOf(
                        "id" to id,
                        "type" to type,
                        "error" to mapOf("code" to "spawn_failed", "message" to "spawn_failed"),
                    )))
                } else {
                    pushCtrl(gson.toJson(mapOf("id" to id, "type" to type, "session" to sampleMeta())))
                }
            }
            ControlType.SESSION_LIST -> {
                pushCtrl(gson.toJson(mapOf("id" to id, "type" to type, "sessions" to listOf(sampleMeta()))))
            }
            ControlType.SESSION_ATTACH -> {
                val ch = nextChannel++
                pushCtrl(gson.toJson(mapOf("id" to id, "type" to type, "channel_id" to ch)))
            }
            else -> pushCtrl(gson.toJson(mapOf("id" to id, "type" to type)))
        }
    }

    private fun sampleMeta() = SessionMeta(
        id = "0".repeat(64),
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
    )

    companion object {
        private val NOOP = object : WireListener {
            override fun onOpen() {}
            override fun onBinary(data: ByteArray) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(message: String) {}
        }
    }
}

private class Recorder : TransportListener {
    @Volatile var connected = 0
    @Volatile var daemonName: String? = null
    @Volatile var daemonPub: String? = null
    @Volatile var disconnected = 0
    @Volatile var code = 0
    @Volatile var reason = ""
    @Volatile var termChannel = -1L
    @Volatile var termData: ByteArray? = null
    @Volatile var closeChannel = -1L
    @Volatile var closeReason = ""
    @Volatile var update: SessionMeta? = null
    @Volatile var replayCompleteChannel = -1L
    @Volatile var replayCompleteOffset = -1L
    @Volatile var events: MutableList<SessionEvent> = mutableListOf()

    override fun onConnected(daemonName: String, daemonPub: String) {
        this.daemonName = daemonName
        this.daemonPub = daemonPub
        connected++
    }

    override fun onDisconnected(code: Int, reason: String) {
        this.code = code
        this.reason = reason
        disconnected++
    }

    override fun onSessionUpdate(session: SessionMeta) {
        update = session
    }

    override fun onChannelClose(channelId: Long, reason: String) {
        closeChannel = channelId
        closeReason = reason
    }

    override fun onTermData(channelId: Long, data: ByteArray) {
        termChannel = channelId
        termData = data
    }

    // File-channel frames are not asserted on by the base Recorder; the daemon
    // transfer path covers them end to end.
    override fun onFileData(channelId: Long, data: ByteArray) {
        // no-op
    }

    override fun onSessionEvent(event: SessionEvent) {
        synchronized(events) { events.add(event) }
    }

    override fun onReplayComplete(channelId: Long, offset: Long) {
        replayCompleteChannel = channelId
        replayCompleteOffset = offset
    }
}

class TransportTest {
    private val appPriv = ByteArray(32) { (it + 1).toByte() }
    private val daemonPriv = ByteArray(32) { (it + 100).toByte() }
    private val tokenSecret = ByteArray(32) { 0xAA.toByte() }
    private val tokenID = "token-token-id".encodeToByteArray()
    private val sessionId = "0".repeat(64)

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

    @Test
    fun ikConnectAndHello() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })
        assertEquals("test-daemon", rec.daemonName)
        assertEquals(Base64Url.encode(X25519.pub(daemonPriv)), rec.daemonPub)
        assertEquals("phone", daemon.helloName)
        assertArrayEquals(X25519.pub(appPriv), daemon.observedAppPub)
        assertTrue(t.isReady)
    }

    @Test
    fun pairConnectAndHello() {
        val daemon = LoopbackDaemon(daemonPriv, tokenSecret)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.Pair(tokenID, tokenSecret))
        assertTrue(await { rec.connected == 1 })
        assertArrayEquals(tokenID, daemon.receivedTokenID)
        assertEquals("phone", daemon.helloName)
        assertTrue(t.isReady)
    }

    @Test
    fun sessionLifecycle() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        var attach: Result<ControlResponse>? = null
        t.send(ControlRequest(type = ControlType.SESSION_ATTACH, sessionId = sessionId)) { attach = it }
        assertTrue(await { attach != null })
        assertTrue(attach!!.isSuccess)
        val resp = attach!!.getOrNull()
        assertNull(resp!!.error)
        val ch = resp.channelId ?: failOr("no channel id")
        assertTrue(ch > 0)

        daemon.pushTerm(ch, byteArrayOf(1, 2, 3))
        assertTrue(await { rec.termData != null })
        assertEquals(ch, rec.termChannel)
        val termData = rec.termData ?: failOr("no term data")
        assertArrayEquals(byteArrayOf(1, 2, 3), termData)

        t.writeTerm(ch, "ls\n".encodeToByteArray())
        assertTrue(await { daemon.termInput.isNotEmpty() })
        assertEquals(ch, daemon.termInput.first().first)
        assertArrayEquals("ls\n".encodeToByteArray(), daemon.termInput.first().second)

        var detach: Result<ControlResponse>? = null
        t.send(ControlRequest(type = ControlType.SESSION_DETACH, channelId = ch)) { detach = it }
        assertTrue(await { detach != null })
        assertTrue(detach!!.isSuccess)
        assertNull(detach!!.getOrNull()!!.error)

        daemon.pushSessionUpdate(sampleMetaForTest())
        assertTrue(await { rec.update != null })
        assertEquals(sessionId, rec.update!!.id)

        daemon.pushChannelClose(ch, "closed")
        assertTrue(await { rec.closeChannel == ch })
        assertEquals("closed", rec.closeReason)
    }

    @Test
    fun sessionEventDispatch() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.pushSessionEvent(sessionId, 1, EventKind.BELL, text = "done")
        daemon.pushSessionEvent(sessionId, 2, EventKind.PATTERN, pattern = "error", text = "error: boom")
        assertTrue(await { rec.events.size == 2 })
        val first = rec.events[0]
        assertEquals(sessionId, first.sessionId)
        assertEquals(1L, first.seq)
        assertEquals(EventKind.BELL, first.kind)
        assertNull(first.pattern)
        assertEquals("done", first.text)
        val second = rec.events[1]
        assertEquals(2L, second.seq)
        assertEquals(EventKind.PATTERN, second.kind)
        assertEquals("error", second.pattern)
        assertEquals("error: boom", second.text)
    }

    @Test
    fun replayCompleteDispatch() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.pushReplayComplete(7, 4096)
        assertTrue(await { rec.replayCompleteChannel == 7L })
        assertEquals(4096L, rec.replayCompleteOffset)
    }

    @Test
    fun replayCompleteMissingOffsetFailsProtocol() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.pushCtrl(
            """{"type":"channel.replay_complete","channel_id":7}"""
        )
        // A malformed control notification is a protocol failure: the event
        // never surfaces and the connection is closed.
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(-1L, rec.replayCompleteChannel)
        assertEquals(CloseCode.PROTOCOL, rec.code)
    }

    @Test
    fun errorResponseKeepsConnection() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })
        daemon.createError = true

        var create: Result<ControlResponse>? = null
        t.send(ControlRequest(type = ControlType.SESSION_CREATE, kind = SessionKind.SHELL)) { create = it }
        assertTrue(await { create != null })
        assertTrue(create!!.isSuccess)
        val err = create!!.getOrNull()!!.error
        assertTrue(err != null)
        assertEquals("spawn_failed", err!!.code)
        assertTrue(t.isReady)
        assertEquals(0, rec.disconnected)
    }

    @Test
    fun termOnUnknownChannelCloses() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.pushTerm(5, byteArrayOf(9))
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(CloseCode.PROTOCOL, rec.code)
        assertEquals("unknown channel", rec.reason)
    }

    @Test
    fun daemonInitiatedCloseFailsPending() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.blackhole = true
        var pending: Result<ControlResponse>? = null
        t.send(ControlRequest(type = ControlType.SESSION_LIST)) { pending = it }
        daemon.close(1001, "going away")

        assertTrue(await { pending != null })
        assertTrue(pending!!.isFailure)
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(1001, rec.code)
        assertEquals("going away", rec.reason)
    }

    @Test
    fun transportFailureReportsAbnormal() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.failTransport("connection reset")
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(CloseCode.ABNORMAL, rec.code)
        assertEquals("connection reset", rec.reason)
    }

    @Test
    fun textFrameIsAProtocolFailure() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })

        daemon.pushText()
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(CloseCode.ABNORMAL, rec.code)
        assertEquals("text frame", rec.reason)
    }

    @Test
    fun badVersionFromDaemonFailsHandshake() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        daemon.badVersion = true
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(CloseCode.PROTOCOL, rec.code)
        assertEquals("handshake failed", rec.reason)
        assertEquals(0, rec.connected)
    }

    @Test
    fun handshakeTimeout() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        daemon.silent = true
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec, handshakeTimeoutMs = 200)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(CloseCode.PROTOCOL, rec.code)
        assertEquals("handshake timeout", rec.reason)
    }

    @Test
    fun sendWhenNotConnectedFails() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        var result: Result<ControlResponse>? = null
        t.send(ControlRequest(type = ControlType.SESSION_LIST)) { result = it }
        assertTrue(await { result != null })
        assertTrue(result!!.isFailure)
        assertEquals(0, rec.connected)
    }

    @Test
    fun connectWhileConnectedThrows() {
        val daemon = LoopbackDaemon(daemonPriv, null)
        val rec = Recorder()
        val t = Transport({ daemon }, "phone", appPriv, rec)
        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 1 })
        try {
            t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
            fail("expected TransportException")
        } catch (e: TransportException) {
        }
    }

    @Test
    fun appCloseAndReconnect() {
        val daemons = mutableListOf<LoopbackDaemon>()
        val rec = Recorder()
        val t = Transport({
            val d = LoopbackDaemon(daemonPriv, tokenSecret)
            daemons += d
            d
        }, "phone", appPriv, rec)
        t.connect(ConnectParams.Pair(tokenID, tokenSecret))
        assertTrue(await { rec.connected == 1 })

        t.close(1000, "bye")
        assertTrue(await { rec.disconnected == 1 })
        assertEquals(1000, rec.code)
        assertEquals("bye", rec.reason)

        t.connect(ConnectParams.IK(X25519.pub(daemonPriv)))
        assertTrue(await { rec.connected == 2 })
        assertEquals(2, daemons.size)
    }

    private fun sampleMetaForTest() = SessionMeta(
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
    )
}
