package com.remotly.app.transport

import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// Drives RelayWire against an in-process relay: the relay speaks the exact
// wire protocol (join, join_ack, frame, keepalive, end) and the wire must
// handshake, deliver frames, answer keepalives, and close on end.
class RelayWireTest {
    private val relayId = ByteArray(16) { (it + 1).toByte() }

    private class Recorder : WireListener {
        val opened = java.util.concurrent.atomic.AtomicBoolean(false)
        val closed = java.util.concurrent.atomic.AtomicInteger(0)
        val data = java.util.concurrent.CopyOnWriteArrayList<ByteArray>()
        override fun onOpen() { opened.set(true) }
        override fun onBinary(data: ByteArray) { this.data += data }
        override fun onClosed(code: Int, reason: String) { closed.incrementAndGet() }
        override fun onFailure(message: String) { closed.incrementAndGet() }
    }

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
    fun connectsDeliversFramesAnswersKeepaliveClosesOnEnd() {
        val payload = ByteArray(25) { (it * 3).toByte() }
        val server = ServerSocket(0)
        val port = server.localPort
        val gotKeepalive = java.util.concurrent.CountDownLatch(1)
        val joinOk = java.util.concurrent.atomic.AtomicBoolean(false)

        val relay = Thread {
            val sock = server.accept()
            val sin = sock.getInputStream()
            val out = sock.getOutputStream()
            // Read the join and validate it.
            val join = readN(sin, 19)
            assertEquals(0x01, join[0].toInt() and 0xff)
            assertEquals(RelayCodec.ROLE_APP, join[2].toInt() and 0xff)
            assertEquals(relayId.toList(), join.copyOfRange(3, 19).toList())
            joinOk.set(true)
            // Ack, send a frame, then a keepalive the wire must answer.
            out.write(byteArrayOf(0x02, 0x00))
            out.write(RelayCodec.frame(payload))
            out.write(RelayCodec.keepalive())
            out.flush()
            // Expect the wire's keepalive answer.
            val resp = readN(sin, 1)
            assertEquals(0x04, resp[0].toInt() and 0xff)
            gotKeepalive.countDown()
            // Close the stream with a relay close code.
            val reason = "no daemon".toByteArray()
            out.write(byteArrayOf(0x05, (3001 shr 8).toByte(), (3001 and 0xff).toByte(), reason.size.toByte()))
            out.write(reason)
            out.flush()
            sock.close()
        }.apply { isDaemon = true; start() }

        val rec = Recorder()
        val wire = RelayWire("127.0.0.1", port, relayId)
        wire.listener = rec
        wire.connect()

        assertTrue("no open", await { rec.opened.get() })
        assertTrue("bad join", joinOk.get())
        assertTrue("no frame", await { rec.data.size == 1 })
        assertEquals(payload.toList(), rec.data.single().toList())
        assertTrue("no keepalive answer", gotKeepalive.await(5, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue("no close", await { rec.closed.get() == 1 })
        assertEquals(1, rec.data.size)

        relay.join(1000)
        server.close()
    }

    @Test
    fun joinEndBeforeAckFails() {
        val server = ServerSocket(0)
        val port = server.localPort
        Thread {
            val sock = server.accept()
            val sin = sock.getInputStream()
            val out = sock.getOutputStream()
            readN(sin, 19)
            val reason = "no daemon".toByteArray()
            out.write(byteArrayOf(0x05, (3001 shr 8).toByte(), (3001 and 0xff).toByte(), reason.size.toByte()))
            out.write(reason)
            out.flush()
            sock.close()
        }.apply { isDaemon = true; start() }

        val rec = Recorder()
        val wire = RelayWire("127.0.0.1", port, relayId)
        wire.listener = rec
        wire.connect()
        assertTrue("should close", await { rec.closed.get() == 1 })
        assertTrue("must not open", !rec.opened.get())
        server.close()
    }

    @Test
    fun connectionRefusedFails() {
        // A closed port: the connector thread must report a failure.
        val dead = ServerSocket(0)
        val port = dead.localPort
        dead.close()

        val rec = Recorder()
        val wire = RelayWire("127.0.0.1", port, relayId)
        wire.listener = rec
        wire.connect()
        assertTrue("should fail", await { rec.closed.get() == 1 })
        assertTrue("must not open", !rec.opened.get())
    }

    private fun readN(stream: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var off = 0
        while (off < n) {
            val r = stream.read(b, off, n - off)
            if (r < 0) break
            off += r
        }
        return b
    }
}
