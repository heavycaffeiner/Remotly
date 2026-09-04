package com.remotly.app.transport

import android.content.Context
import com.google.gson.Gson
import com.remotly.app.identity.Identity
import com.remotly.app.notify.EventNotifier
import com.remotly.app.settings.SettingsModule
import com.remotly.app.terminal.TerminalStore
import java.util.concurrent.ConcurrentHashMap

// Owns the set of active daemon connections, one per host id, and exposes
// them to the bridge layer.
//
// M2 generalizes the M1 single-connection hub into a per-host connection
// manager so a workspace can hold one connection per paired daemon while the
// sessions overview reaches several hosts at once. Each host id maps to at
// most one [Transport]; a repeated connect for the same host fails with
// LIMIT, and the total number of hosts is bounded by [MAX_HOSTS] (well under
// the daemon's MaxConnections so several app processes can still share a
// daemon).
//
// The hub is a process singleton because there is one app identity. It is
// deliberately dependency-injected so the JVM tests can swap the wire,
// identity, and main-thread poster for fakes: production sets the real
// implementations in RemotlyCore, tests set fakes. Event and callback
// delivery is always posted through [poster], so listeners and bridge
// callbacks observe a single-threaded ordering.
//
// One event to the JS container that owns the connection. name is the short
// event name (the bridge prefixes it); data is JSON-safe and always carries
// the hostId so a container that holds several hosts can route it.
typealias EventSink = (name: String, data: Map<String, Any?>) -> Unit

object TransportHub {
    fun interface MainPoster {
        fun post(r: Runnable)
    }

    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    data class Status(
        val connected: Boolean,
        val state: String,
        val daemonName: String?,
        val daemonPub: String?,
        // "direct" or "relay": how the live (or last) connection reached the
        // daemon. Null when there is no connection.
        val via: String?,
    )

    private class Pending(
        val onSuccess: (String, String) -> Unit,
        val onFailure: (Int, String) -> Unit,
    )

    private class Conn(val transport: Transport)

    // One host's connect plan: the direct target always, and the relay target
    // (host, port, 16-byte id) when the caller configured a relay. The relay
    // id is derived by the caller from the daemon public key, so it is never
    // stored separately here.
    private class Plan(
        val directHost: String,
        val directPort: Int,
        var relayHost: String?,
        var relayPort: Int,
        var relayId: ByteArray?,
    )

    // --- injectable dependencies (set once in production and tests) ---

    var wireFactory: (host: String, port: Int) -> Wire = { host, port -> OkHttpWire(host, port) }
    var relayWireFactory: (host: String, port: Int, relayId: ByteArray) -> Wire =
        { host, port, relayId -> RelayWire(host, port, relayId) }
    var identityProvider: () -> Identity = { throw IllegalStateException("identity not configured") }
    var deviceNameProvider: () -> String = { "remotly" }
    var poster: MainPoster = MainPoster { it.run() }
    // Application context only. Completion notices are generated here so they
    // still work when React is backgrounded but the native transport remains
    // connected.
    var appContext: Context? = null

    // --- state ---

    private val lock = Any()
    private val gson = Gson()

    private val conns = HashMap<String, Conn>()
    private val pending = HashMap<String, Pending>()
    private val states = HashMap<String, State>()
    private val daemonNames = HashMap<String, String>()
    private val daemonPubs = HashMap<String, String>()
    // How the current connection reached the daemon: "direct" or "relay".
    private val vias = HashMap<String, String>()
    // Whether the current attempt ever reached READY. A pre-READY disconnect
    // is a connect failure (eligible for relay fallback); a post-READY one is
    // a live drop (reported, not retried as a different transport).
    private val wasConnected = HashMap<String, Boolean>()
    // The relay attempt to run when the direct attempt for a host fails before
    // connecting: (relayTarget, relayId). Absent when no relay is configured.
    private val pendingRelay = HashMap<String, Pair<String, ByteArray>>()
    // The sink a host's events go to. It outlives individual connections
    // (the bridge binds it in the connect call, before the conn exists), so a
    // reconnect to the same host keeps delivering to the same container.
    private val sinks = HashMap<String, EventSink?>()
    // The connect plan per host, held for the duration of a connect attempt
    // (including any relay fallback) so the fallback can find the relay target.
    private val plans = HashMap<String, Plan>()
    // The handshake credentials per host, held for the attempt's duration so a
    // relay fallback can re-run the Noise handshake. In-memory only; cleared
    // when the attempt ends.
    private val paramsMap = HashMap<String, ConnectParams>()
    // `session.update` is documented as an exit transition, but a reconnect or
    // a daemon retransmission must never alert twice. Session ids are unique;
    // keep a bounded process-local set so this cannot grow with daemon uptime.
    private val completionNotices = LinkedHashSet<String>()
    // Channel to session id mapping for native direct terminal feed.
    private val channelToSession = ConcurrentHashMap<Pair<String, Long>, String>()
    private val replayingChannels = ConcurrentHashMap.newKeySet<Pair<String, Long>>()
    private class ReplayBuffer {
        val chunks = ArrayList<ByteArray>()
        var totalBytes = 0
        var targetOffset: Long? = null
        var replayedFrom: Long = 0L
        var droppedGap: Boolean = false
    }
    private val replayBuffers = ConcurrentHashMap<Pair<String, Long>, ReplayBuffer>()
    private val earlyChannelChunks = ConcurrentHashMap<Pair<String, Long>, ArrayList<ByteArray>>()
    private const val NATIVE_REPLAY_CAP = 1024 * 1024
    private val sessionSizes = ConcurrentHashMap<Pair<String, String>, Pair<Int, Int>>()

    fun bindTermChannel(hostId: String, channelId: Long, sessionId: String, replayedFrom: Long = 0L) {
        val key = hostId to channelId
        if (sessionId.isEmpty()) {
            channelToSession.remove(key)
            replayingChannels.remove(key)
            replayBuffers.remove(key)
            earlyChannelChunks.remove(key)
        } else {
            channelToSession[key] = sessionId
            replayingChannels.add(key)
            val buf = ReplayBuffer()
            buf.replayedFrom = replayedFrom
            val early = earlyChannelChunks.remove(key)
            if (early != null) {
                synchronized(early) {
                    for (chunk in early) {
                        buf.chunks.add(chunk)
                        buf.totalBytes += chunk.size
                    }
                }
            }
            replayBuffers[key] = buf
        }
    }
    private fun flushReplayBuffer(key: Pair<String, Long>, sessionId: String) {
        replayingChannels.remove(key)
        val buf = replayBuffers.remove(key) ?: return
        val combined = synchronized(buf) {
            if (buf.chunks.isEmpty()) {
                null
            } else {
                val out = ByteArray(buf.totalBytes)
                var at = 0
                for (chunk in buf.chunks) {
                    System.arraycopy(chunk, 0, out, at, chunk.size)
                    at += chunk.size
                }
                out
            }
        }
        if (combined != null && combined.isNotEmpty()) {
            val size = sessionSizes[key.first to sessionId]
            TerminalStore.feed(sessionId, combined, size?.first ?: 80, size?.second ?: 24) {}
        }
    }


    // Binds (or clears) the event sink for one host. The calling bridge
    // method sets it so events reach the container that owns the connection.
    fun setEventSink(hostId: String, sink: EventSink?) {
        synchronized(lock) {
            sinks[hostId] = sink
        }
    }

    // Snapshot of one host's connection state.
    fun status(hostId: String): Status {
        synchronized(lock) {
            val state = states[hostId] ?: State.DISCONNECTED
            return Status(
                connected = state == State.CONNECTED,
                state = state.name.lowercase(),
                daemonName = daemonNames[hostId],
                daemonPub = daemonPubs[hostId],
                via = if (state == State.CONNECTED) vias[hostId] else null,
            )
        }
    }

    // Ids of hosts that currently hold a connection (connecting or
    // connected). Used by the app to reconcile workspace state.
    fun activeHostIds(): List<String> {
        synchronized(lock) {
            return conns.keys.toList()
        }
    }

    // Opens a connection for one host. onSuccess/onFailure are invoked exactly
    // once, on the poster thread, when the handshake (hello) completes or the
    // attempt fails. A non-null daemonPubB64 selects the pinned-key (IK) path;
    // otherwise a tokenID plus secret selects the pairing (XXpsk0) path.
    fun connect(
        hostId: String,
        target: String,
        daemonPubB64: String?,
        tokenIDB64: String?,
        secretB64: String?,
        onSuccess: (String, String) -> Unit,
        onFailure: (Int, String) -> Unit,
        relayTarget: String? = null,
        relayIdB64: String? = null,
        // When true and a relay is configured, the relay is the only attempt:
        // the direct target is skipped. Used when the caller already exhausted
        // the direct hints and is falling back to the relay explicitly. The
        // relay params trail the callbacks so existing positional callers are
        // unaffected.
        relayOnly: Boolean = false,
    ) {
        if (hostId.isBlank()) {
            post { safe { onFailure(CloseCode.PROTOCOL, "bad host id") } }
            return
        }
        val hostPort = parseTarget(target)
        if (hostPort == null) {
            post { safe { onFailure(CloseCode.PROTOCOL, "bad target") } }
            return
        }
        val (host, port) = hostPort

        val identity = try {
            identityProvider()
        } catch (e: Exception) {
            post { safe { onFailure(CloseCode.INTERNAL, "identity unavailable") } }
            return
        }

        val params: ConnectParams
        when {
            !daemonPubB64.isNullOrBlank() -> {
                val pub = tryDecode(daemonPubB64)
                if (pub == null || pub.size != 32) {
                    post { safe { onFailure(CloseCode.AUTH, "bad daemon key") } }
                    return
                }
                params = ConnectParams.IK(pub)
            }
            !tokenIDB64.isNullOrBlank() && !secretB64.isNullOrBlank() -> {
                val tokenID = tryDecode(tokenIDB64)
                val secret = tryDecode(secretB64)
                if (tokenID == null || tokenID.size !in 1..Transport.MAX_TOKEN_ID_LEN ||
                    secret == null || secret.size != 32
                ) {
                    post { safe { onFailure(CloseCode.AUTH, "bad pairing credentials") } }
                    return
                }
                params = ConnectParams.Pair(tokenID, secret)
            }
            else -> {
                post { safe { onFailure(CloseCode.AUTH, "missing credentials") } }
                return
            }
        }

        // Relay fallback: when a relay target and id are supplied, the direct
        // attempt runs first and the relay attempt only if the direct attempt
        // fails before connecting. The relay id is the first 16 bytes of the
        // daemon public key, which the caller derives.
        val plan = Plan(directHost = host, directPort = port,
            relayHost = null, relayPort = 0, relayId = null)
        if (!relayTarget.isNullOrBlank() && !relayIdB64.isNullOrBlank()) {
            val rp = parseTarget(relayTarget)
            val rid = tryDecode(relayIdB64)
            if (rp == null || rid == null || rid.size != 16) {
                post { safe { onFailure(CloseCode.PROTOCOL, "bad relay target") } }
                return
            }
            plan.relayHost = rp.first
            plan.relayPort = rp.second
            plan.relayId = rid
        }

        synchronized(lock) {
            // A live connection is what the caller asked for, so it is
            // reported as success rather than an error. Returning a failure
            // here left a screen that remounted over an existing connection
            // showing "disconnected" while its own transport was connected
            // and usable, and no later attempt could clear it.
            val existing = conns[hostId]
            if (existing != null && existing.transport.isReady) {
                val name = daemonNames[hostId].orEmpty()
                val pub = daemonPubs[hostId].orEmpty()
                post { safe { onSuccess(name, pub) } }
                return
            }
            if (existing != null || pending.containsKey(hostId)) {
                post { safe { onFailure(CloseCode.LIMIT, "already connecting") } }
                return
            }
            if (conns.size >= MAX_HOSTS) {
                post { safe { onFailure(CloseCode.LIMIT, "too many hosts") } }
                return
            }
            plans[hostId] = plan
            paramsMap[hostId] = params
            wasConnected[hostId] = false
            // relayOnly starts on the relay when one is configured; otherwise
            // the direct attempt runs first.
            val relayConfigured = plan.relayHost != null && plan.relayId != null
            vias[hostId] = if (relayOnly && relayConfigured) "relay" else "direct"
            launchAttemptLocked(hostId, params, onSuccess, onFailure)
        }
    }

    // Starts one connect attempt for [hostId] using the wire selected by
    // vias[hostId] ("direct" or "relay"). Called with the lock held; the
    // listener it installs reports success, or a pre-READY failure that the
    // listener turns into a relay fallback or a final failure.
    private fun launchAttemptLocked(
        hostId: String,
        params: ConnectParams,
        onSuccess: (String, String) -> Unit,
        onFailure: (Int, String) -> Unit,
    ) {
        val plan = plans[hostId] ?: return
        val via = vias[hostId] ?: "direct"
        val useRelay = via == "relay" && plan.relayHost != null && plan.relayId != null
        val identity = try {
            identityProvider()
        } catch (e: Exception) {
            abortAttemptLocked(hostId, onSuccess, onFailure, CloseCode.INTERNAL, "identity unavailable")
            return
        }
        val holder = arrayOfNulls<Transport>(1)
        val transport = try {
            Transport(
                wireFactory = {
                    if (useRelay) {
                        relayWireFactory(plan.relayHost!!, plan.relayPort, plan.relayId!!)
                    } else {
                        wireFactory(plan.directHost, plan.directPort)
                    }
                },
                deviceName = deviceNameProvider(),
                staticPriv = identity.seed,
                listener = makeListener(hostId) { holder[0] },
            )
        } catch (e: Exception) {
            abortAttemptLocked(hostId, onSuccess, onFailure, CloseCode.INTERNAL, "transport unavailable")
            return
        }
        holder[0] = transport
        conns[hostId] = Conn(transport)
        pending[hostId] = Pending(onSuccess, onFailure)
        states[hostId] = State.CONNECTING
        try {
            transport.connect(params)
        } catch (e: Exception) {
            abortAttemptLocked(hostId, onSuccess, onFailure, CloseCode.INTERNAL, e.message ?: "connect failed")
        }
    }

    // Removes the in-flight attempt for [hostId] and reports a final failure.
    // Called with the lock held.
    private fun abortAttemptLocked(
        hostId: String,
        onSuccess: (String, String) -> Unit,
        onFailure: (Int, String) -> Unit,
        code: Int,
        reason: String,
    ) {
        conns.remove(hostId)
        pending.remove(hostId)
        states.remove(hostId)
        plans.remove(hostId)
        paramsMap.remove(hostId)
        vias.remove(hostId)
        wasConnected.remove(hostId)
        post { safe { onFailure(code, reason) } }
    }

    // Closes one host's connection, if any. A pending connect that has not
    // completed is failed. The disconnect event is emitted by the transport.
    fun close(hostId: String, code: Int = CloseCode.NORMAL, reason: String = "closed") {
        post {
            val t = synchronized(lock) {
                val cur = conns[hostId]
                if (cur == null) return@post
                conns.remove(hostId)
                states.remove(hostId)
                daemonNames.remove(hostId)
                daemonPubs.remove(hostId)
                vias.remove(hostId)
                wasConnected.remove(hostId)
                plans.remove(hostId)
                paramsMap.remove(hostId)
                replayingChannels.removeIf { it.first == hostId }
                replayBuffers.keys.filter { it.first == hostId }.forEach { replayBuffers.remove(it) }
                earlyChannelChunks.keys.filter { it.first == hostId }.forEach { earlyChannelChunks.remove(it) }
                channelToSession.keys.filter { it.first == hostId }.forEach { channelToSession.remove(it) }
                sessionSizes.keys.filter { it.first == hostId }.forEach { sessionSizes.remove(it) }
                val pendingEntry = pending.remove(hostId)
                if (pendingEntry != null) safe { pendingEntry.onFailure(code, reason) }
                cur.transport
            }
            t.close(code, reason)
        }
    }

    // Sends one control request (a JSON object matching the control schema)
    // on one host's connection and reports the response as JSON. cb receives
    // Result.success(responseJson) or Result.failure when the request could
    // not complete.
    fun sendControl(hostId: String, requestJson: String, cb: (Result<String>) -> Unit) {
        val request = try {
            gson.fromJson(requestJson, ControlRequest::class.java)
        } catch (e: Exception) {
            null
        }
        if (request == null || request.type.isBlank()) {
            post { safe { cb(Result.failure(Exception("bad control request"))) } }
            return
        }
        val t = synchronized(lock) { conns[hostId]?.transport }
        if (t == null) {
            post { safe { cb(Result.failure(Exception("not connected"))) } }
            return
        }
        t.send(request) { result ->
            result.getOrNull()?.let { resp ->
                if (resp.error == null) {
                    when (request.type) {
                        ControlType.SESSION_ATTACH -> {
                            val sid = request.sessionId
                            val cid = resp.channelId
                            val continuity = resp.continuity
                            val replayedFrom = resp.replayedFrom ?: 0L
                            if (sid != null && cid != null) {
                                bindTermChannel(hostId, cid, sid, replayedFrom)
                            }
                        }
                        ControlType.SESSION_RESIZE -> {
                            val sid = request.sessionId
                            val cols = request.cols
                            val rows = request.rows
                            if (sid != null && cols != null && rows != null) {
                                sessionSizes[hostId to sid] = cols to rows
                            }
                        }
                        ControlType.SESSION_CREATE -> {
                            val sid = resp.session?.id ?: request.sessionId
                            val cols = resp.session?.cols ?: request.cols
                            val rows = resp.session?.rows ?: request.rows
                            if (sid != null && cols != null && rows != null) {
                                sessionSizes[hostId to sid] = cols to rows
                            }
                        }
                        ControlType.SESSION_KILL -> {
                            request.sessionId?.let { sid ->
                                sessionSizes.remove(hostId to sid)
                            }
                        }
                        else -> {}
                    }
                }
            }
            post {
                safe {
                    cb(
                        result.map { resp ->
                            // The daemon's own JSON, not a re-encode of the
                            // typed view: ControlResponse omits the fs.* and
                            // transfer.* result fields, and re-serializing it
                            // stripped them before JS ever saw them.
                            resp.raw.ifEmpty { gson.toJson(resp) }
                        }
                    )
                }
            }
        }
    }

    // Writes terminal input to an attached channel on one host's connection.
    // Returns true when the bytes were handed to a ready connection; false
    // when there is no connection for the host or it is not ready (the write
    // is then dropped, matching transport semantics).
    fun writeTerm(hostId: String, channelId: Long, data: ByteArray): Boolean {
        val t = synchronized(lock) { conns[hostId]?.transport } ?: return false
        if (!t.isReady) return false
        t.writeTerm(channelId, data)
        return true
    }

    // Registers the file channel a transfer.create/resume opened, so chunk
    // frames on it are accepted. No-op when there is no ready connection.
    fun openFile(hostId: String, channelId: Long) {
        val t = synchronized(lock) { conns[hostId]?.transport } ?: return
        if (!t.isReady) return
        t.openFileChannel(channelId)
    }

    // Writes one file-channel frame (an upload chunk) to the daemon. Returns
    // true when handed to a ready connection, false otherwise (dropped).
    fun writeFile(hostId: String, channelId: Long, data: ByteArray): Boolean {
        val t = synchronized(lock) { conns[hostId]?.transport } ?: return false
        if (!t.isReady) return false
        t.writeFile(channelId, data)
        return true
    }

    // Test hook: drops one host's state so tests start clean.
    fun reset(hostId: String) {
        synchronized(lock) {
            conns[hostId]?.let { runCatching { it.transport.close() } }
            conns.remove(hostId)
            pending.remove(hostId)
            states.remove(hostId)
            daemonNames.remove(hostId)
            daemonPubs.remove(hostId)
            vias.remove(hostId)
            wasConnected.remove(hostId)
            plans.remove(hostId)
            paramsMap.remove(hostId)
            replayingChannels.removeIf { it.first == hostId }
            replayBuffers.keys.filter { it.first == hostId }.forEach { replayBuffers.remove(it) }
            earlyChannelChunks.keys.filter { it.first == hostId }.forEach { earlyChannelChunks.remove(it) }
            channelToSession.keys.filter { it.first == hostId }.forEach { channelToSession.remove(it) }
            sessionSizes.keys.filter { it.first == hostId }.forEach { sessionSizes.remove(it) }
            completionNotices.removeAll { it.startsWith("$hostId:") }
        }
    }

    // Test hook: drops all state.
    fun reset() {
        synchronized(lock) {
            conns.values.forEach { runCatching { it.transport.close() } }
            conns.clear()
            channelToSession.clear()
            replayingChannels.clear()
            replayBuffers.clear()
            earlyChannelChunks.clear()
            pending.clear()
            states.clear()
            sessionSizes.clear()
            daemonNames.clear()
            daemonPubs.clear()
            vias.clear()
            wasConnected.clear()
            plans.clear()
            paramsMap.clear()
            sinks.clear()
            completionNotices.clear()
        }
    }

    // --- internals ---

    private fun post(r: Runnable) {
        try {
            poster.post(r)
        } catch (e: Exception) {
            runCatching { r.run() }
        }
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // A dead or throwing bridge callback must not take down the
            // transport; the connection state is already consistent.
        }
    }

    // Delivers one event to the host's sink. Events other than the terminal
    // "disconnected" require the connection to still exist; "disconnected"
    // arrives after the conn entry is removed, so the caller captures the
    // sink first and delivers through [emitTo].
    private fun emit(hostId: String, name: String, data: Map<String, Any?>) {
        val sink = synchronized(lock) {
            if (!conns.containsKey(hostId)) null else sinks[hostId]
        } ?: return
        emitTo(sink, hostId, name, data)
    }

    private fun emitTo(sink: EventSink?, hostId: String, name: String, data: Map<String, Any?>) {
        if (sink == null) return
        safe { sink(name, data + ("hostId" to hostId)) }
    }

    private fun makeListener(hostId: String, self: () -> Transport?): TransportListener {
        return object : TransportListener {
            override fun onConnected(name: String, pub: String) {
                post {
                    var isCurrent = false
                    var via: String? = null
                    val p = synchronized(lock) {
                        val conn = conns[hostId]
                        if (conn != null && self() === conn.transport) {
                            isCurrent = true
                            via = vias[hostId]
                            wasConnected[hostId] = true
                            states[hostId] = State.CONNECTED
                            daemonNames[hostId] = name
                            daemonPubs[hostId] = pub
                            plans.remove(hostId)
                            paramsMap.remove(hostId)
                            pending.remove(hostId)
                        } else {
                            null
                        }
                    }
                    // A superseded or removed transport must not emit events
                    // for a connection that no longer belongs to this host.
                    if (isCurrent) {
                        emit(hostId, "connected", mapOf(
                            "daemonName" to name,
                            "daemonPub" to pub,
                            "via" to via,
                        ))
                    }
                    val pend = p
                    if (pend != null) safe { pend.onSuccess(name, pub) }
                }
            }

            override fun onDisconnected(code: Int, reason: String) {
                post {
                    var fallback = false
                    var sink: EventSink? = null
                    val p: Pending? = synchronized(lock) {
                        val conn = conns[hostId]
                        if (conn != null && self() === conn.transport) {
                            val was = wasConnected[hostId] == true
                            if (was || vias[hostId] != "direct" || plans[hostId]?.relayHost == null) {
                                // A live drop, the final (relay) attempt, or no
                                // relay configured: clean up and report.
                                sink = sinks[hostId]
                                conns.remove(hostId)
                                states.remove(hostId)
                                daemonNames.remove(hostId)
                                daemonPubs.remove(hostId)
                                plans.remove(hostId)
                                paramsMap.remove(hostId)
                                vias.remove(hostId)
                                wasConnected.remove(hostId)
                                pending.remove(hostId)
                            } else {
                                // Direct attempt failed before connecting and a
                                // relay is configured: switch to the relay and
                                // relaunch. Keep pending, plan, and params.
                                conns.remove(hostId)
                                states.remove(hostId)
                                vias[hostId] = "relay"
                                fallback = true
                                null
                            }
                        } else {
                            null
                        }
                    }
                    if (fallback) {
                        val planParams = synchronized(lock) { paramsMap[hostId] }
                        val pend = synchronized(lock) { pending[hostId] }
                        if (planParams != null && pend != null) {
                            synchronized(lock) {
                                launchAttemptLocked(hostId, planParams, pend.onSuccess, pend.onFailure)
                            }
                        }
                        return@post
                    }
                    // A user-initiated close or a superseded transport must
                    // not emit an event for the connection that replaced it.
                    if (sink != null) emitTo(sink, hostId, "disconnected", mapOf("code" to code, "reason" to reason))
                    val pend = p
                    if (pend != null) safe { pend.onFailure(code, reason) }
                }
            }

            override fun onSessionUpdate(session: SessionMeta) {
                sessionSizes[hostId to session.id] = session.cols to session.rows
                post {
                    emit(hostId, "sessionUpdate", mapOf("session" to sessionToMap(session)))
                    postCompletionNotice(hostId, session)
                }
            }

            override fun onChannelClose(channelId: Long, reason: String) {
                bindTermChannel(hostId, channelId, "")
                post { emit(hostId, "channelClose", mapOf("channelId" to channelId, "reason" to reason)) }
            }

            override fun onTermData(channelId: Long, data: ByteArray) {
                val key = hostId to channelId
                val sessionId = channelToSession[key]
                if (sessionId == null) {
                    val list = earlyChannelChunks.computeIfAbsent(key) { ArrayList() }
                    synchronized(list) {
                        list.add(data)
                    }
                    post {
                        emit(
                            hostId,
                            "termData",
                            mapOf("channelId" to channelId, "length" to data.size, "data" to "", "fastPath" to true),
                        )
                    }
                    return
                }

                if (replayingChannels.contains(key)) {
                    val buf = replayBuffers[key]
                    var shouldFlush = false
                    if (buf != null) {
                        synchronized(buf) {
                            buf.chunks.add(data)
                            buf.totalBytes += data.size
                            while (buf.totalBytes > NATIVE_REPLAY_CAP && buf.chunks.size > 1) {
                                val dropped = buf.chunks.removeAt(0)
                                buf.totalBytes -= dropped.size
                                buf.droppedGap = true
                            }
                            val target = buf.targetOffset
                            if (target != null) {
                                val targetBytes = target - buf.replayedFrom
                                if (buf.totalBytes >= targetBytes) {
                                    shouldFlush = true
                                }
                            }
                        }
                    }
                    if (shouldFlush) {
                        flushReplayBuffer(key, sessionId)
                    }
                    post {
                        emit(
                            hostId,
                            "termData",
                            mapOf("channelId" to channelId, "length" to data.size, "data" to "", "fastPath" to true),
                        )
                    }
                    return
                }

                // Live output: direct fast-path feed to native terminal
                val size = sessionSizes[hostId to sessionId]
                TerminalStore.feed(sessionId, data, size?.first ?: 80, size?.second ?: 24) {}
                post {
                    emit(
                        hostId,
                        "termData",
                        mapOf("channelId" to channelId, "length" to data.size, "data" to "", "fastPath" to true),
                    )
                }
            }

            override fun onFileData(channelId: Long, data: ByteArray) {
                // File content crosses to the container but is never logged.
                post {
                    emit(
                        hostId,
                        "fileData",
                        mapOf("channelId" to channelId, "data" to Base64Std.encode(data)),
                    )
                }
            }

            override fun onReplayComplete(channelId: Long, offset: Long) {
                val key = hostId to channelId
                val sessionId = channelToSession[key]
                val buf = replayBuffers[key]
                var shouldFlush = false
                var hasGap = false
                if (buf != null) {
                    synchronized(buf) {
                        buf.targetOffset = offset
                        hasGap = buf.droppedGap
                        val targetBytes = offset - buf.replayedFrom
                        if (buf.totalBytes >= targetBytes || targetBytes <= 0) {
                            shouldFlush = true
                        }
                    }
                } else {
                    shouldFlush = true
                }
                if (shouldFlush && sessionId != null) {
                    flushReplayBuffer(key, sessionId)
                }
                post {
                    emit(
                        hostId,
                        "replayComplete",
                        mapOf("channelId" to channelId, "offset" to offset, "gap" to hasGap),
                    )
                }
            }



            override fun onSessionEvent(event: SessionEvent) {
                // Text is terminal content; it crosses to the container but is
                // never logged.
                post {
                    emit(
                        hostId,
                        "sessionEvent",
                        mapOf(
                            "sessionId" to event.sessionId,
                            "seq" to event.seq,
                            "kind" to event.kind,
                            "pattern" to event.pattern,
                            "text" to event.text,
                            "ts" to event.ts,
                        ),
                    )
                }
            }
        }
    }

    private fun sessionToMap(s: SessionMeta): Map<String, Any?> = mapOf(
        "id" to s.id,
        "title" to s.title,
        "kind" to s.kind,
        "command" to s.command,
        "cwd" to s.cwd,
        "cols" to s.cols,
        "rows" to s.rows,
        "createdAt" to s.createdAt,
        "lastActivity" to s.lastActivity,
        "running" to s.running,
        "exit" to s.exit?.let { mapOf("code" to it.code, "signal" to it.signal) },
        "preview" to s.preview,
    )

    // `session.update` with running=false is the daemon's terminal completion
    // signal. Keep it native: JS may be suspended while the app is in the
    // background, whereas the secure transport is still receiving this frame.
    private fun postCompletionNotice(hostId: String, session: SessionMeta) {
        if (session.running || !SettingsModule.notifyEnabled) return
        val context = appContext ?: return
        val key = "$hostId:${session.id}"
        synchronized(lock) {
            if (!completionNotices.add(key)) return
            while (completionNotices.size > MAX_COMPLETION_NOTICES) {
                completionNotices.iterator().run {
                    if (hasNext()) {
                        next()
                        remove()
                    }
                }
            }
        }
        val hostName = synchronized(lock) { daemonNames[hostId] }.orEmpty().ifBlank { "Remotly" }
        EventNotifier.post(
            context,
            EventNotifier.notificationId(hostId, session.id),
            hostName,
            SessionCompletionNotification.title(session),
            SessionCompletionNotification.text(session),
        )
    }

    private fun tryDecode(b64: String): ByteArray? =
        try {
            Base64Url.decode(b64)
        } catch (e: Exception) {
            null
        }

    private const val MAX_COMPLETION_NOTICES = 256

    // Parses "host", "host:port", "[ipv6]", or "[ipv6]:port". A missing port
    // defaults to the daemon's LAN port.
    private fun parseTarget(target: String): Pair<String, Int>? {
        val t = target.trim()
        if (t.isEmpty()) return null
        val host: String
        val portStr: String?
        if (t.startsWith("[")) {
            val end = t.indexOf(']')
            if (end < 1) return null
            host = t.substring(1, end)
            val rest = t.substring(end + 1)
            portStr = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> rest.substring(1)
                else -> return null
            }
        } else {
            val idx = t.lastIndexOf(':')
            if (idx < 0) {
                host = t
                portStr = null
            } else {
                host = t.substring(0, idx)
                portStr = t.substring(idx + 1)
            }
        }
        if (host.isEmpty()) return null
        val port = portStr?.let {
            val p = it.toIntOrNull() ?: return null
            if (p !in 1..65535) null else p
        } ?: DEFAULT_PORT
        return host to port
    }

    private const val DEFAULT_PORT = 8788

    // The app caps its own concurrent daemon hosts; the daemon's
    // MaxConnections (16) is a per-daemon budget shared by all clients.
    private const val MAX_HOSTS = 8
}
