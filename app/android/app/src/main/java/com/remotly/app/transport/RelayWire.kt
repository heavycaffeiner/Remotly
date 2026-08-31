package com.remotly.app.transport

import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

// The relay Wire: a plain TCP connection to the relay, speaking the relay
// protocol (RelayCodec). After the relay join it is transparent to the
// Remotly protocol: each sendBinary carries one Remotly message wrapped in a
// relay frame, and each relay frame received is delivered as one Remotly
// message. The relay strips stream ids, so the Transport sees the same
// one-message-per-call contract as a direct WebSocket.
//
// Liveness: the app sends a keepalive every KEEPALIVE_MS so the relay's idle
// timer does not close an idle app connection, and answers every keepalive it
// receives (the relay's answer to a daemon stream ping). A dead relay is
// detected by the socket: a failed read or write ends the connection.
class RelayWire(
    private val host: String,
    private val port: Int,
    private val relayId: ByteArray,
) : Wire {
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private var reader: Thread? = null
    private var keepalive: Thread? = null
    private val writeLock = Any()

    override var listener: WireListener = NOOP

    override fun connect() {
        Thread({
            if (connectInner()) {
                listener.onOpen()
            }
        }, "remotly-relay-connect").apply { isDaemon = true }.start()
    }

    // Returns true when the relay join completed and the wire is live.
    private fun connectInner(): Boolean {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), DIAL_TIMEOUT_MS)
            s.tcpNoDelay = true
        } catch (e: Exception) {
            runCatching { s.close() }
            fail("relay connect: ${e.message ?: "dial failed"}")
            return false
        }
        socket = s
        val out = s.getOutputStream()
        try {
            out.write(RelayCodec.join(relayId))
            out.flush()
        } catch (e: Exception) {
            teardown()
            fail("relay connect: ${e.message ?: "join failed"}")
            return false
        }
        // Read the join result. Only join_ack (success) or end (failure) is
        // expected here; anything else is a protocol violation.
        val buf = ByteArray(CHUNK)
        val got = readFull(s, buf, 0, 1)
        if (got < 1) {
            teardown()
            fail("relay connect: closed before join ack")
            return false
        }
        when (buf[0].toInt() and 0xff) {
            RelayCodec.T_JOIN_ACK -> {
                if (readFull(s, buf, 0, 1) < 1) {
                    teardown()
                    fail("relay connect: closed before join ack")
                    return false
                }
            }
            RelayCodec.T_END -> {
                val (code, reason) = readEnd(s)
                teardown()
                // The relay rejected the join (no daemon, limit, ...). Surface
                // the relay's close code so the hub can report or fall back.
                listener.onClosed(code, reason)
                return false
            }
            else -> {
                teardown()
                fail("relay connect: bad join reply")
                return false
            }
        }
        startReader(s)
        startKeepalive()
        return true
    }

    override fun sendBinary(data: ByteArray) {
        val out = socket?.let { it.getOutputStream() } ?: return
        val framed = RelayCodec.frame(data)
        synchronized(writeLock) {
            if (closed) return
            try {
                out.write(framed)
                out.flush()
            } catch (e: Exception) {
                teardown()
                listener.onFailure("relay send: ${e.message ?: "write failed"}")
            }
        }
    }

    override fun close(code: Int, reason: String) {
        if (closed) return
        closed = true
        teardown()
        listener.onClosed(code, reason)
    }

    private fun startReader(s: Socket) {
        val t = Thread({ readerLoop(s) }, "remotly-relay-read")
        t.isDaemon = true
        reader = t
        t.start()
    }

    private fun readerLoop(s: Socket) {
        val input = s.getInputStream()
        val chunk = ByteArray(CHUNK)
        var buf = ByteArray(CHUNK)
        var len = 0
        while (!closed) {
            val n = try {
                input.read(chunk)
            } catch (e: Exception) {
                if (!closed) listener.onFailure("relay read: ${e.message ?: "read failed"}")
                return
            }
            if (n < 0) {
                if (!closed) listener.onFailure("relay read: connection closed")
                return
            }
            if (len + n > buf.size) {
                val grown = buf.copyOf((buf.size * 2).coerceAtLeast(len + n))
                System.arraycopy(buf, 0, grown, 0, len)
                buf = grown
            }
            System.arraycopy(chunk, 0, buf, len, n)
            len += n
            // Drain every complete message in the buffer.
            while (true) {
                val decoded = try {
                    RelayCodec.decode(buf, 0, len)
                } catch (e: RelayCodec.RelayCodecException) {
                    teardown()
                    listener.onFailure("relay protocol: ${e.message}")
                    return
                }
                if (decoded == null) break
                val (msg, consumed) = decoded
                compact(buf, len, consumed).let { len = it }
                when (msg) {
                    is RelayCodec.Frame -> listener.onBinary(msg.data)
                    is RelayCodec.Keepalive -> {
                        // Answer the relay's keepalive (its ping path).
                        val ka = RelayCodec.keepalive()
                        synchronized(writeLock) {
                            if (!closed) runCatching {
                                val o = socket?.getOutputStream()
                                o?.write(ka)
                                o?.flush()
                            }
                        }
                    }
                    is RelayCodec.End -> {
                        val (c, r) = msg.code to msg.reason
                        closed = true
                        teardown()
                        listener.onClosed(c, r)
                        return
                    }
                    is RelayCodec.JoinAck -> {
                        // A join ack after open is a protocol violation.
                        teardown()
                        listener.onFailure("relay protocol: unexpected join ack")
                        return
                    }
                }
            }
        }
    }

    // Moves the unconsumed tail of [buf] to the front and returns the new length.
    private fun compact(buf: ByteArray, len: Int, consumed: Int): Int {
        if (consumed < len) {
            System.arraycopy(buf, consumed, buf, 0, len - consumed)
        }
        return len - consumed
    }

    private fun startKeepalive() {
        val t = Thread({ keepaliveLoop() }, "remotly-relay-ka")
        t.isDaemon = true
        keepalive = t
        t.start()
    }

    private fun keepaliveLoop() {
        while (!closed) {
            try {
                TimeUnit.MILLISECONDS.sleep(KEEPALIVE_MS)
            } catch (e: InterruptedException) {
                return
            }
            if (closed) return
            val ka = RelayCodec.keepalive()
            synchronized(writeLock) {
                if (closed) return
                val o = socket?.getOutputStream() ?: return
                val ok = runCatching {
                    o.write(ka)
                    o.flush()
                }.isSuccess
                if (!ok) {
                    teardown()
                    listener.onFailure("relay keepalive: write failed")
                    return
                }
            }
        }
    }

    private fun fail(message: String) {
        if (closed) return
        closed = true
        listener.onFailure(message)
    }

    private fun teardown() {
        closed = true
        runCatching { socket?.close() }
        socket = null
    }

    // Reads exactly [n] bytes into [dst] at [off], or returns the count read
    // (less than n) on EOF.
    private fun readFull(s: Socket, dst: ByteArray, off: Int, n: Int): Int {
        val input = s.getInputStream()
        var got = 0
        while (got < n) {
            val r = input.read(dst, off + got, n - got)
            if (r < 0) break
            got += r
        }
        return got
    }

    // Reads the 3-byte end body (code, reason_len, reason) following the type
    // byte already consumed.
    private fun readEnd(s: Socket): Pair<Int, String> {
        val b = ByteArray(4)
        if (readFull(s, b, 0, 3) < 3) return 1006 to "closed"
        val code = ((b[0].toInt() and 0xff) shl 8) or (b[1].toInt() and 0xff)
        val rlen = b[2].toInt() and 0xff
        val reason = if (rlen == 0) "" else {
            val rb = ByteArray(rlen)
            if (readFull(s, rb, 0, rlen) < rlen) "" else String(rb, Charsets.UTF_8)
        }
        return code to reason
    }

    companion object {
        const val DIAL_TIMEOUT_MS = 5_000
        const val KEEPALIVE_MS = 30_000L
        private const val CHUNK = 16 * 1024

        private val NOOP = object : WireListener {
            override fun onOpen() {}
            override fun onBinary(data: ByteArray) {}
            override fun onClosed(code: Int, reason: String) {}
            override fun onFailure(message: String) {}
        }
    }
}
