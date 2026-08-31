package com.remotly.app.ssh

import com.remotly.app.ssh.engine.SshEngine
import java.io.File
import java.nio.file.Files
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Exercises the SSH terminal session orchestration against a fake engine:
// host-key auto-accept, first-use approval, changed-key rejection and
// replacement, close semantics, and the release() race (callbacks arriving
// after the executor is shut down are dropped, never thrown). The engine
// implementation itself is covered by the Go sshcore tests; this test pins the
// contract the session relies on.
class SshSessionTest {

    private lateinit var dir: File
    private lateinit var store: SshHostStore

    // Emulates the SshEngine contract: async connect, host-key challenge,
    // decision latch, ready, echo of writes, close reporting exactly once.
    private class FakeEngine(private val fingerprint: String) {
        private val decision = AtomicBoolean(false)
        private val latch = CountDownLatch(1)
        @Volatile private var closed = false
        @Volatile var listener: SshListener? = null
            private set

        // The factory receives the session's listener; it is installed here so
        // the engine drives the real callbacks.
        fun engineFor(listener: SshListener): SshEngine {
            this.listener = listener
            return object : SshEngine {
                override fun connect(spec: SshSpec) {
                    Thread({
                        listener.onHostKeyChallenge(HostKeyInfo(spec.host, spec.port, "ssh-ed25519", fingerprint))
                        if (!latch.await(10, TimeUnit.SECONDS)) {
                            listener.onFailure(SshCode.TIMEOUT, "host key decision timeout")
                            return@Thread
                        }
                        if (!decision.get()) {
                            listener.onFailure(SshCode.HOST_KEY_REJECTED, "host key rejected")
                            return@Thread
                        }
                        listener.onReady()
                    }, "fake-ssh-engine").apply { isDaemon = true }.start()
                }

                override fun write(data: ByteArray) {
                    if (!closed) listener.onTerminalData(data)
                }

                override fun resize(cols: Int, rows: Int) {}

                override fun decideHostKey(accept: Boolean) {
                    decision.set(accept)
                    latch.countDown()
                }

                override fun close(code: Int, reason: String) {
                    if (closed) return
                    closed = true
                    listener.onClosed(code, reason)
                }
            }
        }
    }

    private class Recorder {
        private val states = Collections.synchronizedList(mutableListOf<SshSessionState>())
        private val chunks = Collections.synchronizedList(mutableListOf<ByteArray>())

        fun record(state: SshSessionState) { synchronized(states) { states.add(state) } }
        fun record(data: ByteArray) { synchronized(chunks) { chunks.add(data) } }

        val allStates: List<SshSessionState>
            get() = synchronized(states) { states.toList() }

        fun awaitState(timeoutMs: Long = 10_000, p: (SshSessionState) -> Boolean): SshSessionState? {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                val m = synchronized(states) { states.firstOrNull(p) }
                if (m != null) return m
                Thread.sleep(20)
            }
            return synchronized(states) { states.firstOrNull(p) }
        }

        fun dataContains(needle: ByteArray, timeoutMs: Long = 10_000): Boolean {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (concatenated().contains(needle)) return true
                Thread.sleep(20)
            }
            return false
        }

        private fun concatenated(): ByteArray {
            val parts: List<ByteArray> = synchronized(chunks) { chunks.toList() }
            val total = parts.sumOf { it.size }
            val out = ByteArray(total)
            var pos = 0
            for (d in parts) { System.arraycopy(d, 0, out, pos, d.size); pos += d.size }
            return out
        }

        private fun ByteArray.contains(needle: ByteArray): Boolean {
            outer@ for (i in 0..size - needle.size) {
                for (j in needle.indices) {
                    if (this[i + j] != needle[j]) continue@outer
                }
                return true
            }
            return false
        }
    }

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rq-ssh-session").toFile()
        store = SshHostStore(File(dir, SshHostStore.FILE_NAME), inMemoryCipher())
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun makeHost(withKey: Boolean, fingerprint: String): SshHost {
        val h = store.add(
            "Test", "127.0.0.1", 22, "alice",
            SshCredential.Password("secret-pw".toByteArray()),
        )
        if (withKey) store.acceptHostKey(h.id, KnownHostKey("ssh-ed25519", fingerprint))
        return store.get(h.id)!!
    }

    private fun startSession(host: SshHost, rec: Recorder, fingerprint: String): Pair<SshSession, FakeEngine> {
        val fake = FakeEngine(fingerprint)
        val session = SshSession(
            host, SshCredential.Password("secret-pw".toByteArray()), store,
            { fake.engineFor(it) }, rec::record, rec::record,
        )
        session.start(80, 24)
        return session to fake
    }

    @Test
    fun knownKeyAutoAcceptsAndStreams() {
        val host = makeHost(withKey = true, fingerprint = "SHA256:knownknownknownknownknownknownnownownownown")
        val rec = Recorder()
        val (session, _) = startSession(host, rec, host.knownKeys[0].fingerprint)
        assertNotNull("expected Active, saw ${rec.allStates}", rec.awaitState { it is SshSessionState.Active })
        assertTrue("no host-key prompt expected for a known key", rec.allStates.none { it is SshSessionState.HostKey })
        session.write("ping".toByteArray())
        assertTrue("expected echoed bytes", rec.dataContains("ping".toByteArray()))
        session.close()
        assertNotNull("expected Closed", rec.awaitState { it is SshSessionState.Closed })
        session.release()
    }

    @Test
    fun newKeyRequiresExplicitApproval() {
        val fp = "SHA256:newnewnewnewnewnewnewnewnewnewnewnewnewnewnew"
        val host = makeHost(withKey = false, fingerprint = fp)
        val rec = Recorder()
        val (session, _) = startSession(host, rec, fp)
        val hk = rec.awaitState { it is SshSessionState.HostKey } as? SshSessionState.HostKey
        assertNotNull("expected a host-key prompt", hk)
        assertTrue("expected a New verdict", hk?.verdict is HostKeyVerdict.New)
        session.acceptNewHostKey()
        assertNotNull("expected Active after approval", rec.awaitState { it is SshSessionState.Active })
        assertEquals(1, store.get(host.id)!!.knownKeys.size)
        session.close()
        session.release()
    }

    @Test
    fun changedKeyFailsClosedAndReplacementIsIntentional() {
        val host = makeHost(withKey = true, fingerprint = "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        val stale = "SHA256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        val presented = "SHA256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        val rec = Recorder()
        val (session, _) = startSession(host, rec, presented)
        val hk = rec.awaitState { it is SshSessionState.HostKey } as? SshSessionState.HostKey
        assertNotNull("expected a host-key prompt", hk)
        assertTrue("expected a Changed verdict", hk?.verdict is HostKeyVerdict.Changed)
        session.rejectHostKey()
        assertNotNull(
            "expected a terminal state after rejection",
            rec.awaitState { it is SshSessionState.Closed || it is SshSessionState.Failed },
        )
        assertTrue("expected no Active after rejection", rec.allStates.none { it is SshSessionState.Active })
        session.release()

        // A fresh session with the same stale key can be intentionally replaced.
        val rec2 = Recorder()
        val (session2, _) = startSession(host, rec2, presented)
        rec2.awaitState { it is SshSessionState.HostKey }
        session2.replaceChangedHostKey()
        assertNotNull("expected Active after replacement", rec2.awaitState { it is SshSessionState.Active })
        val keys = store.get(host.id)!!.knownKeys
        assertEquals(1, keys.size)
        assertEquals(presented, keys[0].fingerprint)
        session2.close()
        session2.release()
    }

    @Test
    fun closeDuringPendingHostKeyDoesNotHang() {
        val host = makeHost(withKey = false, fingerprint = "SHA256:ccccccccccccccccccccccccccccccccccccccccccc")
        val rec = Recorder()
        val (session, _) = startSession(host, rec, "SHA256:ccccccccccccccccccccccccccccccccccccccccccc")
        rec.awaitState { it is SshSessionState.HostKey }
        session.close()
        assertNotNull(
            "expected a terminal state after closing during a pending key",
            rec.awaitState { it is SshSessionState.Closed || it is SshSessionState.Failed },
        )
        session.release()
    }

    // Release may win the race against a late engine callback. The session
    // must drop the event silently: no exception, no state emitted after
    // release.
    @Test
    fun callbacksAfterReleaseAreDropped() {
        val host = makeHost(withKey = true, fingerprint = "SHA256:ddddddddddddddddddddddddddddddddddddddddddd")
        val rec = Recorder()
        val (session, fake) = startSession(host, rec, host.knownKeys[0].fingerprint)
        assertNotNull("expected Active", rec.awaitState { it is SshSessionState.Active })
        session.close()
        assertNotNull("expected Closed before release", rec.awaitState { it is SshSessionState.Closed })
        session.release()
        val before = rec.allStates.size
        // Drive late callbacks directly, as the engine thread would.
        val l = fake.listener
        l?.onTerminalData("late".toByteArray())
        l?.onClosed(1000, "late close")
        Thread.sleep(50)
        assertEquals("no state emitted after release", before, rec.allStates.size)
        assertFalse("late terminal data must not arrive", rec.dataContains("late".toByteArray(), timeoutMs = 1))
    }
}
