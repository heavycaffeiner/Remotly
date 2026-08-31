package com.remotly.app.transport

import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// One secure connection to a daemon: a versioned Noise handshake over a
// binary WebSocket, hello authorization, and the channel multiplexer (control
// requests and responses, notifications, and term channels).
//
// Every state transition runs on a single executor, so Wire callbacks and
// public calls may come from any thread. Listener events are delivered on the
// executor thread.

interface WireListener {
    fun onOpen()
    fun onBinary(data: ByteArray)
    fun onClosed(code: Int, reason: String)
    fun onFailure(message: String)
}

// One connection's byte pipe. Each sendBinary carries exactly one Remotly
// message (a handshake message or one sealed frame), mirroring the daemon's
// one-message-per-WebSocket-message rule. The protocol is binary-only, so a
// text frame must be reported through onFailure.
interface Wire {
    var listener: WireListener
    fun connect()
    fun sendBinary(data: ByteArray)
    fun close(code: Int, reason: String)
}

sealed class ConnectParams {
    // Reconnect of a paired device. daemonPub is the daemon's long-term
    // public key, learned from the hello response at pairing time.
    class IK(val daemonPub: ByteArray) : ConnectParams()

    // First-time pairing from a pairing URI. tokenID and secret are the URI's
    // token fields; the secret is the handshake PSK.
    class Pair(val tokenID: ByteArray, val secret: ByteArray) : ConnectParams()
}

interface TransportListener {
    // The hello response arrived; the connection is authorized.
    fun onConnected(daemonName: String, daemonPub: String)

    // The connection ended. code is the WebSocket close code from CloseCode,
    // or ABNORMAL when the transport failed with no close frame.
    fun onDisconnected(code: Int, reason: String)

    fun onSessionUpdate(session: SessionMeta)
    fun onChannelClose(channelId: Long, reason: String)
    fun onTermData(channelId: Long, data: ByteArray)
    fun onFileData(channelId: Long, data: ByteArray)
    fun onSessionEvent(event: SessionEvent)

    // The replay/live boundary of one term channel was crossed. Offset is
    // the resume cursor at that point. Like all notifications it may arrive
    // out of order with term data: the mux serves control frames first.
    fun onReplayComplete(channelId: Long, offset: Long)
}

class TransportException(message: String) : Exception(message)

// WebSocket close codes. 4000-4004 are the Remotly range shared with the
// daemon's protocol package.
object CloseCode {
    const val NORMAL = 1000
    const val GOING_AWAY = 1001
    const val ABNORMAL = 1006
    const val INTERNAL = 1011
    const val VERSION = 4000
    const val AUTH = 4001
    const val PROTOCOL = 4002
    const val TOKEN = 4003
    const val LIMIT = 4004
}

class Transport(
    private val wireFactory: () -> Wire,
    private val deviceName: String,
    private val staticPriv: ByteArray,
    private val listener: TransportListener,
    private val handshakeTimeoutMs: Long = HANDSHAKE_TIMEOUT_MS,
) : WireListener {
    enum class State { IDLE, OPENING, HANDSHAKE, HELLO, READY, CLOSING }

    private val codec = ControlCodec()
    private val executor: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "remotly-transport").apply { isDaemon = true }
    }
    @Volatile private var state = State.IDLE

    private var wire: Wire? = null
    private var params: ConnectParams? = null
    private var hs: HandshakeState? = null
    private var cipher: FrameCipher? = null
    private var helloId = 0L
    private var nextId = 1L
    private val pending = LinkedHashMap<Long, PendingRequest>()
    private val termChannels = HashSet<Long>()
    private val fileChannels: MutableSet<Long> = ConcurrentHashMap.newKeySet()
    private val random = SecureRandom()

    private class PendingRequest(val id: Long, val cb: (Result<ControlResponse>) -> Unit)

    val isReady: Boolean
        get() = state == State.READY

    fun connect(params: ConnectParams) {
        checkDeviceName()
        check(staticPriv.size == 32) { "bad static key" }
        when (params) {
            is ConnectParams.IK -> check(params.daemonPub.size == 32) { "bad daemon key" }
            is ConnectParams.Pair -> {
                check(params.tokenID.size in 1..MAX_TOKEN_ID_LEN) { "bad token id" }
                check(params.secret.size == 32) { "bad token secret" }
            }
        }
        if (state != State.IDLE) throw TransportException("already connected")
        executor.execute { doConnect(params) }
    }

    // Sends a control request and reports the response. cb receives
    // Result.success with the response (which may itself carry a protocol
    // error) or Result.failure when the request could not complete: not
    // connected, too many pending, request timeout, or the connection dropped
    // first.
    fun send(request: ControlRequest, cb: (Result<ControlResponse>) -> Unit) {
        executor.execute {
            if (state != State.READY) {
                cb(Result.failure(TransportException("not connected")))
                return@execute
            }
            if (pending.size >= MAX_PENDING) {
                cb(Result.failure(TransportException("too many pending requests")))
                return@execute
            }
            val id = nextId
            if (id > ControlLimits.MAX_ID) {
                fail(CloseCode.LIMIT, "request id limit")
                cb(Result.failure(TransportException("request id limit")))
                return@execute
            }
            nextId = id + 1
            val payload = codec.encodeRequest(request.copy(id = id)).toByteArray(Charsets.UTF_8)
            if (payload.size > MAX_CONTROL_LEN) {
                cb(Result.failure(TransportException("control frame too large")))
                return@execute
            }
            pending[id] = PendingRequest(id, cb)
            executor.schedule({
                val pr = pending.remove(id)
                if (pr != null) emit { pr.cb(Result.failure(TransportException("request timeout"))) }
            }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            sendSealed(CHANNEL_CTRL, 0L, payload)
        }
    }

    // Writes terminal input to an attached channel. Fire and forget; the
    // daemon does not acknowledge input frames. Writes to a channel that is
    // not attached are dropped.
    fun writeTerm(channelId: Long, data: ByteArray) {
        executor.execute {
            if (state != State.READY) return@execute
            if (channelId !in termChannels) return@execute
            if (channelId > 0xFFFFFFFFL) return@execute
            if (data.size > MAX_INPUT_CHUNK) {
                fail(CloseCode.LIMIT, "input too large")
                return@execute
            }
            sendSealed(CHANNEL_TERM, channelId, data)
        }
    }

    // Registers a file channel opened by the daemon (its id comes back in a
    // transfer.create or transfer.resume response). Until it is registered the
    // transport rejects frames on it, so a forged id is not accepted.
    fun openFileChannel(channelId: Long) {
        executor.execute {
            if (state != State.READY) return@execute
            if (channelId > 0xFFFFFFFFL) return@execute
            fileChannels.add(channelId)
        }
    }

    // Writes one file-channel frame (an upload chunk: [8-byte offset][payload])
    // to the daemon. Fire and forget, like terminal input. Writes to a channel
    // that is not registered are dropped.
    fun writeFile(channelId: Long, data: ByteArray) {
        executor.execute {
            if (state != State.READY) return@execute
            if (channelId !in fileChannels) return@execute
            if (channelId > 0xFFFFFFFFL) return@execute
            if (data.size > MAX_FILE_FRAME) {
                fail(CloseCode.LIMIT, "file frame too large")
                return@execute
            }
            sendSealed(CHANNEL_FILE, channelId, data)
        }
    }

    // Closes the connection. With no open connection this is a no-op and no
    // disconnect event is delivered.
    fun close(code: Int = CloseCode.NORMAL, reason: String = "closed") {
        executor.execute {
            if (state == State.IDLE) return@execute
            fail(code, reason)
        }
    }

    // Releases the executor. Intended for process teardown.
    fun shutdown() {
        executor.execute {
            if (state != State.IDLE) fail(CloseCode.GOING_AWAY, "going away")
        }
        executor.shutdown()
    }

    private fun checkDeviceName() {
        if (deviceName.length !in 1..ControlLimits.MAX_DEVICE_NAME_LEN) {
            throw TransportException("bad device name")
        }
        for (c in deviceName) {
            if (c < ' ' || c == '\u007f') throw TransportException("bad device name")
        }
    }

    // --- state machine; executor thread only ---

    private fun doConnect(params: ConnectParams) {
        if (state != State.IDLE) return
        this.params = params
        state = State.OPENING
        val w: Wire
        try {
            w = wireFactory()
        } catch (e: Exception) {
            fail(CloseCode.INTERNAL, "wire unavailable")
            return
        }
        w.listener = this
        wire = w
        try {
            w.connect()
        } catch (e: Exception) {
            fail(CloseCode.ABNORMAL, e.message ?: "connect failed")
            return
        }
        executor.schedule({
            if (state == State.OPENING || state == State.HANDSHAKE) {
                fail(CloseCode.PROTOCOL, "handshake timeout")
            }
        }, handshakeTimeoutMs, TimeUnit.MILLISECONDS)
    }

    private fun handleOpen() {
        if (state != State.OPENING) return
        val p = params ?: return
        try {
            val eph = ByteArray(32).also { random.nextBytes(it) }
            val hsState = when (p) {
                is ConnectParams.IK -> HandshakeState(
                    true, Patterns.IK, PROLOGUE, staticPriv, p.daemonPub, null, 0, eph,
                )
                is ConnectParams.Pair -> HandshakeState(
                    true, Patterns.XX, PROLOGUE, staticPriv, null, p.secret, 0, eph,
                )
            }
            hs = hsState
            state = State.HANDSHAKE
            val mode = if (p is ConnectParams.Pair) MODE_PAIR else MODE_IK
            val noiseMsg = hsState.writeMessage(ByteArray(0)).message
            val parts = ArrayList<ByteArray>(4)
            parts.add(byteArrayOf(VERSION.toByte(), mode.toByte()))
            if (p is ConnectParams.Pair) {
                parts.add(Varint.encode(p.tokenID.size.toLong()))
                parts.add(p.tokenID)
            }
            parts.add(noiseMsg)
            wire?.sendBinary(joinArrays(parts))
        } catch (e: Exception) {
            fail(CloseCode.PROTOCOL, "handshake failed")
        }
    }

    private fun handleHandshakeMessage(data: ByteArray) {
        val hsState = hs ?: return
        try {
            if (data.size < 2) throw TransportException("short handshake")
            if (data[0].toInt() and 0xff != VERSION) throw TransportException("bad version")
            val expected = if (params is ConnectParams.Pair) MODE_PAIR else MODE_IK
            if (data[1].toInt() and 0xff != expected) throw TransportException("bad mode")
            val body = data.copyOfRange(2, data.size)
            if (body.isEmpty() || body.size > MAX_HANDSHAKE) throw TransportException("bad size")
            val result = if (params is ConnectParams.IK) {
                hsState.readMessage(body)
            } else {
                // XXpsk0 completes for the initiator when it writes msg3.
                hsState.readMessage(body)
                val msg3 = hsState.writeMessage(ByteArray(0))
                wire?.sendBinary(msg3.message)
                msg3
            }
            finishHandshake(result)
        } catch (e: Exception) {
            fail(CloseCode.PROTOCOL, "handshake failed")
        }
    }

    private fun finishHandshake(result: HandshakeResult) {
        val c1 = result.c1
        val c2 = result.c2
        if (c1 == null || c2 == null) {
            fail(CloseCode.PROTOCOL, "handshake failed")
            return
        }
        // The app is the initiator: it sends with c1 and receives with c2.
        cipher = FrameCipher(c1, c2)
        state = State.HELLO
        helloId = nextId
        nextId += 1
        executor.schedule({
            if (state == State.HELLO) fail(CloseCode.PROTOCOL, "hello timeout")
        }, REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        val request = codec.hello(helloId, deviceName, X25519.pub(staticPriv))
        sendSealed(CHANNEL_CTRL, 0L, codec.encodeRequest(request).toByteArray(Charsets.UTF_8))
    }

    private fun handleBinary(data: ByteArray) {
        when (state) {
            State.HANDSHAKE -> handleHandshakeMessage(data)
            State.HELLO, State.READY -> handleFrame(data)
            else -> {}
        }
    }

    private fun handleFrame(data: ByteArray) {
        val c = cipher ?: return
        val f: Frame
        try {
            f = c.openFrame(data)
        } catch (e: FrameException) {
            fail(CloseCode.PROTOCOL, "bad frame")
            return
        }
        when (f.chType) {
            CHANNEL_CTRL -> handleCtrlFrame(f)
            CHANNEL_TERM -> {
                if (f.chId !in termChannels) {
                    fail(CloseCode.PROTOCOL, "unknown channel")
                    return
                }
                emit { listener.onTermData(f.chId, f.payload) }
            }
            CHANNEL_FILE -> {
                if (f.chId !in fileChannels) {
                    fail(CloseCode.PROTOCOL, "unknown channel")
                    return
                }
                emit { listener.onFileData(f.chId, f.payload) }
            }
            else -> fail(CloseCode.PROTOCOL, "bad channel")
        }
    }

    private fun handleCtrlFrame(f: Frame) {
        if (f.payload.size > MAX_CONTROL_LEN) {
            fail(CloseCode.LIMIT, "control frame too large")
            return
        }
        val msg: ControlMessage
        try {
            msg = codec.decode(String(f.payload, Charsets.UTF_8))
        } catch (e: ControlException) {
            fail(CloseCode.PROTOCOL, "bad control frame")
            return
        }
        when (msg) {
            is ControlResponse -> {
                if (state == State.HELLO) {
                    if (msg.id != helloId || msg.type != ControlType.HELLO) {
                        fail(CloseCode.PROTOCOL, "unexpected response")
                        return
                    }
                    if (msg.error != null || msg.daemonName.isNullOrEmpty() || msg.daemonPub.isNullOrEmpty()) {
                        fail(CloseCode.AUTH, "hello rejected")
                        return
                    }
                    state = State.READY
                    emit { listener.onConnected(msg.daemonName, msg.daemonPub) }
                    return
                }
                val pr = pending.remove(msg.id)
                if (pr != null) {
                    // Attaching opens the channel for term traffic in both directions.
                    if (msg.error == null && msg.type == ControlType.SESSION_ATTACH) {
                        msg.channelId?.let { termChannels.add(it) }
                    }
                    // transfer.create and transfer.resume open a file channel for
                    // the transfer; register it so chunk frames are accepted.
                    if (msg.error == null &&
                        (msg.type == ControlType.TRANSFER_CREATE || msg.type == ControlType.TRANSFER_RESUME)
                    ) {
                        msg.channelId?.let { fileChannels.add(it) }
                    }
                    emit { pr.cb(Result.success(msg)) }
                }
            }
            is ControlNotification -> {
                when (msg.type) {
                    ControlType.CHANNEL_CLOSE -> {
                        val id = msg.channelId ?: return
                        termChannels.remove(id)
                        fileChannels.remove(id)
                        emit { listener.onChannelClose(id, msg.reason ?: "closed") }
                    }
                    ControlType.SESSION_UPDATE -> {
                        val s = msg.session ?: return
                        emit { listener.onSessionUpdate(s) }
                    }
                    ControlType.CHANNEL_REPLAY_COMPLETE -> {
                        val id = msg.channelId ?: return
                        val off = msg.offset ?: return
                        emit { listener.onReplayComplete(id, off) }
                    }
                    ControlType.SESSION_EVENT -> {
                        try {
                            val ev = codec.toSessionEvent(msg)
                            emit { listener.onSessionEvent(ev) }
                        } catch (e: ControlException) {
                            // A validated event that fails the view build is a
                            // coding error; drop it rather than fail the conn.
                        }
                    }
                    // Unknown types pass through so a newer daemon can probe.
                }
            }
        }
    }

    private fun sendSealed(chType: Int, chId: Long, payload: ByteArray) {
        val c = cipher ?: return
        try {
            val frame = c.sealFrame(chType, chId, payload)
            wire?.sendBinary(frame)
        } catch (e: Exception) {
            fail(CloseCode.INTERNAL, e.message ?: "send failed")
        }
    }

    private fun fail(code: Int, reason: String) {
        if (state == State.IDLE || state == State.CLOSING) return
        state = State.CLOSING
        wire?.close(code, reason)
        executor.schedule({
            if (state == State.CLOSING) finalize(code, reason)
        }, CLOSE_GRACE_MS, TimeUnit.MILLISECONDS)
    }

    private fun handleClosed(code: Int, reason: String) {
        if (state == State.IDLE) return
        finalize(code, reason)
    }

    private fun handleFailure(message: String) {
        if (state == State.IDLE) return
        finalize(CloseCode.ABNORMAL, message)
    }

    private fun finalize(code: Int, reason: String) {
        if (state == State.IDLE) return
        state = State.IDLE
        val dropped = ArrayList(pending.values)
        pending.clear()
        termChannels.clear()
        cipher = null
        hs = null
        params = null
        wire = null
        for (pr in dropped) {
            emit { pr.cb(Result.failure(TransportException("disconnected: $reason"))) }
        }
        emit { listener.onDisconnected(code, reason) }
    }

    // A throwing listener is a programming bug; fail the connection instead
    // of killing the executor thread.
    private inline fun emit(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            fail(CloseCode.INTERNAL, "listener error")
        }
    }

    // --- WireListener; callbacks arrive on the wire's threads ---

    override fun onOpen() {
        executor.execute { handleOpen() }
    }

    override fun onBinary(data: ByteArray) {
        executor.execute { handleBinary(data) }
    }

    override fun onClosed(code: Int, reason: String) {
        executor.execute { handleClosed(code, reason) }
    }

    override fun onFailure(message: String) {
        executor.execute { handleFailure(message) }
    }

    companion object {
        const val VERSION = 1
        val PROLOGUE: ByteArray = "remotly-v1".encodeToByteArray()
        const val MODE_IK = 0
        const val MODE_PAIR = 1
        const val CHANNEL_CTRL = 0
        const val CHANNEL_TERM = 1
        const val CHANNEL_FILE = 2
        const val MAX_TOKEN_ID_LEN = 64
        const val MAX_HANDSHAKE = 65535
        const val MAX_CONTROL_LEN = 64 * 1024
        const val MAX_INPUT_CHUNK = 1 shl 20
        // A file frame carries an 8-byte offset plus a payload up to the
        // daemon's MaxTransferChunk (1 MiB); the 64-byte margin covers the
        // offset and frame overhead.
        const val MAX_FILE_FRAME = (1 shl 20) + 64
        const val HANDSHAKE_TIMEOUT_MS = 10_000L
        const val REQUEST_TIMEOUT_MS = 30_000L
        const val CLOSE_GRACE_MS = 5_000L
        const val MAX_PENDING = 256
    }
}
