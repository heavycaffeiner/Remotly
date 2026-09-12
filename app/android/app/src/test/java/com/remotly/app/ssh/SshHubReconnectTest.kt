package com.remotly.app.ssh

import com.remotly.app.session.SshHubTransport
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTabPhase
import com.remotly.app.ssh.engine.SshEngine
import com.remotly.app.ssh.engine.SshEngineFactory
import java.nio.file.Files
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SshHubReconnectTest {
    @Test
    fun queuedEventsFromReplacedConnectionCannotConsumeWorkspaceAttach() {
        val directory = Files.createTempDirectory("remotly-reconnect").toFile()
        val previousStore = SshModule.store
        val previousFactory = SshModule.engineFactory
        val previousPoster = SshHub.poster
        val previousTransport = SshSessions.transport
        val queued = LinkedBlockingQueue<Runnable>()
        val writes = mutableListOf<String>()
        val listeners = mutableListOf<SshListener>()
        val store = SshHostStore(java.io.File(directory, SshHostStore.FILE_NAME), inMemoryCipher())
        val host = store.add("Reconnect", "localhost", 22, "user", SshCredential.Password("test".toByteArray()))
        try {
            SshModule.store = store
            SshHub.poster = SshHub.MainPoster { queued.add(it) }
            SshModule.engineFactory = SshEngineFactory { listener ->
                listeners += listener
                object : SshEngine {
                    override fun connect(spec: SshSpec) { listener.onReady() }
                    override fun write(data: ByteArray) { writes += data.toString(Charsets.UTF_8) }
                    override fun resize(cols: Int, rows: Int) = Unit
                    override fun decideHostKey(accept: Boolean) = Unit
                    override fun close(code: Int, reason: String) { listener.onClosed(code, reason) }
                }
            }
            SshSessions.transport = SshHubTransport
            val sessionId = SshSessions.openWorkspaceTab(host.id, "w1", "Workspace", "herdr")!!
            val staleConnecting = queued.poll(5, TimeUnit.SECONDS)
            val staleActive = queued.poll(5, TimeUnit.SECONDS)
            assertNotNull(staleConnecting)
            assertNotNull(staleActive)
            listeners.single().onTerminalData("\u001b[?1003h".toByteArray())
            val staleOutput = queued.poll(5, TimeUnit.SECONDS)
            assertNotNull(staleOutput)

            SshSessions.reconnectTab(host.id, sessionId)
            staleConnecting!!.run()
            staleActive!!.run()
            staleOutput!!.run()
            assertTrue("A replaced connection must not launch the command", writes.isEmpty())
            assertEquals(SshTabPhase.Connecting, SshSessions.state(host.id).value.tabs.tabs.single().phase)

            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (writes.isEmpty() && System.nanoTime() < deadline) {
                queued.poll(100, TimeUnit.MILLISECONDS)?.run()
            }
            assertEquals(listOf("herdr\n"), writes)
            assertEquals(SshTabPhase.Active, SshSessions.state(host.id).value.tabs.tabs.single().phase)
        } finally {
            SshSessions.closeHost(host.id)
            SshSessions.transport = previousTransport
            SshHub.poster = previousPoster
            SshModule.store = previousStore
            SshModule.engineFactory = previousFactory
            directory.deleteRecursively()
        }
    }
}
