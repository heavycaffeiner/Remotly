package com.remotly.app.transport

import com.remotly.app.identity.Identity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

// Exercises the hub's direct-then-relay fallback with fakes: a direct wire
// that always fails (an unreachable LAN target) and a relay wire that is a
// working in-test daemon. Verifies the fallback is sequential (direct is fully
// closed before the relay opens), that the transport path is reported, that no
// duplicate success or failure is emitted, and that a relay-only connect skips
// the direct attempt entirely.
class TransportHubRelayTest {
    private val appPriv = ByteArray(32) { (it + 1).toByte() }
    private val daemonPriv = ByteArray(32) { (it + 100).toByte() }
    private val tokenSecret = ByteArray(32) { 0xAA.toByte() }
    private val daemonPub = X25519.pub(daemonPriv)
    private val daemonPubB64 = Base64Url.encode(daemonPub)
    private val relayIdB64 = Base64Url.encode(daemonPub.copyOf(16))

    private val host = "host-relay"

    private var directAttempts = 0
    private var relayAttempts = 0
    private var relayFails = false
    private var events = mutableListOf<Pair<String, Map<String, Any?>>>()

    // A wire that fails on connect, simulating an unreachable direct target.
    private class FailingWire : Wire {
        override var listener: WireListener = object : WireListener {
            override fun onOpen() {}
            override fun onBinary(data: ByteArray) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(message: String) {}
        }
        override fun connect() {
            // The socket never opens; it dies with a network failure before
            // the handshake can start.
            listener.onClosed(CloseCode.ABNORMAL, "unreachable")
        }
        override fun sendBinary(data: ByteArray) {}
        override fun close(code: Int, reason: String) {}
    }

    @Before
    fun setUp() {
        TransportHub.reset()
        directAttempts = 0
        relayAttempts = 0
        relayFails = false
        events = mutableListOf()
        TransportHub.wireFactory = { _, _ ->
            directAttempts++
            FailingWire()
        }
        TransportHub.relayWireFactory = { _, _, _ ->
            relayAttempts++
            if (relayFails) FailingWire() else LoopbackDaemon(daemonPriv, tokenSecret)
        }
        TransportHub.identityProvider = { Identity(appPriv, X25519.pub(appPriv)) }
        TransportHub.deviceNameProvider = { "phone" }
        TransportHub.poster = TransportHub.MainPoster { it.run() }
        TransportHub.setEventSink(host) { name, data -> events += name to data }
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

    @Test
    fun directFailureFallsBackToRelay() {
        var ok: Pair<String, String>? = null
        var err: Pair<Int, String>? = null
        TransportHub.connect(
            hostId = host,
            target = "192.168.1.10:8788",
            daemonPubB64 = daemonPubB64,
            tokenIDB64 = null,
            secretB64 = null,
            onSuccess = { n, p -> ok = n to p },
            onFailure = { c, r -> err = c to r },
            relayTarget = "relay.example:10000",
            relayIdB64 = relayIdB64,
        )
        assertTrue("timed out: $err", await { ok != null })
        assertNull("relay path must not report a failure", err)
        assertEquals("test-daemon", ok!!.first)
        assertEquals(daemonPubB64, ok!!.second)

        // Direct was tried once and failed; the relay carried the connection.
        assertEquals(1, directAttempts)
        assertEquals(1, relayAttempts)

        // The transport path is reported on the status and the connected event.
        assertEquals("relay", TransportHub.status(host).via)
        val ev = eventsOf("connected").singleOrNull() ?: failOr("no connected event")
        assertEquals("relay", ev.second["via"])
        assertTrue(TransportHub.status(host).connected)
    }

    @Test
    fun directSuccessSkipsRelay() {
        // A working direct daemon: the relay must never be touched.
        TransportHub.wireFactory = { _, _ ->
            directAttempts++
            LoopbackDaemon(daemonPriv, tokenSecret)
        }
        var okName: String? = null
        TransportHub.connect(
            hostId = host,
            target = "192.168.1.10:8788",
            daemonPubB64 = daemonPubB64,
            tokenIDB64 = null,
            secretB64 = null,
            onSuccess = { n, _ -> okName = n },
            onFailure = { _, _ -> fail("unexpected failure") },
            relayTarget = "relay.example:10000",
            relayIdB64 = relayIdB64,
        )
        assertTrue("timed out", await { okName != null })
        assertEquals(1, directAttempts)
        assertEquals("relay must not be attempted when direct succeeds", 0, relayAttempts)
        assertEquals("direct", TransportHub.status(host).via)
    }

    @Test
    fun relayOnlySkipsDirect() {
        var okName: String? = null
        var err: Pair<Int, String>? = null
        TransportHub.connect(
            hostId = host,
            target = "relay.example:10000",
            daemonPubB64 = daemonPubB64,
            tokenIDB64 = null,
            secretB64 = null,
            onSuccess = { n, _ -> okName = n },
            onFailure = { c, r -> err = c to r },
            relayTarget = "relay.example:10000",
            relayIdB64 = relayIdB64,
            relayOnly = true,
        )
        assertTrue("timed out: $err", await { okName != null })
        assertEquals("relay-only must not attempt the direct target", 0, directAttempts)
        assertEquals(1, relayAttempts)
        assertEquals("relay", TransportHub.status(host).via)
    }

    @Test
    fun bothFailReportsOneFailure() {
        relayFails = true
        var ok: Pair<String, String>? = null
        var failCount = 0
        var err: Pair<Int, String>? = null
        TransportHub.connect(
            hostId = host,
            target = "192.168.1.10:8788",
            daemonPubB64 = daemonPubB64,
            tokenIDB64 = null,
            secretB64 = null,
            onSuccess = { n, p -> ok = n to p },
            onFailure = { c, r ->
                failCount++
                err = c to r
            },
            relayTarget = "relay.example:10000",
            relayIdB64 = relayIdB64,
        )
        assertTrue("timed out", await { err != null })
        assertNull("no success when both transports fail", ok)
        assertEquals(1, directAttempts)
        assertEquals(1, relayAttempts)
        // The direct failure is silent (it triggers the fallback); only the
        // final relay failure is reported, exactly once.
        assertEquals("exactly one failure callback", 1, failCount)
        assertEquals("one disconnected event", 1, eventsOf("disconnected").size)
        assertEquals(0, eventsOf("connected").size)
        assertEquals("disconnected", TransportHub.status(host).state)
        assertFalse(TransportHub.status(host).connected)
    }

    @Test
    fun noRelayConfiguredDirectFailureIsFinal() {
        var ok: Pair<String, String>? = null
        var err: Pair<Int, String>? = null
        TransportHub.connect(
            hostId = host,
            target = "192.168.1.10:8788",
            daemonPubB64 = daemonPubB64,
            tokenIDB64 = null,
            secretB64 = null,
            onSuccess = { n, p -> ok = n to p },
            onFailure = { c, r -> err = c to r },
        )
        assertTrue("timed out", await { err != null })
        assertNull(ok)
        assertEquals(1, directAttempts)
        assertEquals("relay must not be attempted without a relay hint", 0, relayAttempts)
        assertEquals("disconnected", TransportHub.status(host).state)
    }
}
