package com.remotly.app.ssh

import android.util.Log
import com.remotly.app.terminal.TerminalStore
import com.remotly.app.transport.Base64Std
import java.util.concurrent.ConcurrentHashMap

// Manages live SSH terminal sessions (M4-03). Each session is an SshSession
// (single-use) that connects with the M4-02 credential and runs the host-key
// verification flow. State changes and terminal bytes are delivered to the
// container through per-host sinks, posted on the main thread, mirroring the
// transport hub. A retry is a new session.
//
// Sessions are keyed by (hostId, sessionId), not by host, so one host can back
// several terminal tabs at once. Every event still carries its hostId, so the
// per-host sink keeps routing exactly as before.
typealias SshEventSink = (name: String, data: Map<String, Any?>) -> Unit

object SshHub {

    fun interface MainPoster {
        fun post(r: Runnable)
    }

    // Production posts to the main looper; tests and the JVM default run inline.
    var poster: MainPoster = MainPoster { it.run() }

    // Keyed by sessionKey(hostId, sessionId).
    private val sessions = ConcurrentHashMap<String, SshSession>()
    private val sinks = ConcurrentHashMap<String, SshEventSink?>()
    // The grid each session runs at, so a terminal created for output that
    // arrives before the view mounts is sized to the pty rather than guessed.
    private val sizes = ConcurrentHashMap<String, Pair<Int, Int>>()

    // A buggy or hostile caller must not grow this map without bound; the
    // app's own tab strip stops well below this.
    private const val MAX_SESSIONS = 64

    /**
     * The map key for one terminal.
     *
     * Session ids are opaque here. The separator is rejected inside a session
     * id at the bridge, so the pair cannot be ambiguous.
     */
    fun sessionKey(hostId: String, sessionId: String): String = "$hostId:$sessionId"

    fun setEventSink(hostId: String, sink: SshEventSink?) {
        sinks[hostId] = sink
    }

    // Starts a terminal to a stored host. The credential is resolved from the
    // store; a host without a credential fails to the state sink as "failed".
    fun connect(hostId: String, sessionId: String, cols: Int, rows: Int) {
        val key = sessionKey(hostId, sessionId)
        val store = SshModule.store
        if (store == null) {
            emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_store_unavailable", "reason" to "ssh store unavailable")))
            return
        }
        val engineFactory = SshModule.engineFactory
        if (engineFactory == null) {
            emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_engine_unavailable", "reason" to "ssh engine unavailable")))
            return
        }
        // Counted before the slot is taken. Replacing an existing session for
        // the same key is a reconnect, not growth.
        if (!sessions.containsKey(key) && sessions.size >= MAX_SESSIONS) {
            emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_too_many_sessions", "reason" to "too many open sessions")))
            return
        }
        // Any exception here must surface as a failed state, never crash the
        // bridge thread: the credential path touches the Android KeyStore, and
        // an unexpected throw would otherwise take the process down on entry.
        try {
            val host = store.get(hostId)
            if (host == null) {
                emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_no_host", "reason" to "no such host")))
                return
            }
            val credential = try {
                store.credential(hostId)
            } catch (e: SshHostStoreException) {
                emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_no_credential", "reason" to (e.message ?: "no credential"))))
                return
            }
            closeLocked(key)
            lateinit var session: SshSession
            session = SshSession(
                host = host,
                credential = credential,
                store = store,
                engineFactory = engineFactory,
                onState = { state ->
                    emit(hostId, sessionId, "state", stateMap(state))
                    if (state is SshSessionState.Closed || state is SshSessionState.Failed) {
                        retire(key, session)
                    }
                },
                onTerminal = { data -> deliverTerminal(hostId, sessionId, data) },
            )
            sessions[key] = session
            sizes[key] = cols to rows
            keepAliveStarted()
            // Debug only, and identifiers only: no hostname, username, or
            // terminal content ever reaches a log.
            Log.d(TAG, "ssh session started host=${hostId.take(8)} session=${sessionId.take(8)}")
            try {
                session.start(cols, rows)
            } catch (e: Exception) {
                if (sessions.remove(key, session)) {
                    session.release()
                    keepAliveStoppedIfIdle()
                }
                emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_connect_error", "reason" to "connect failed")))
            }
        } catch (e: Exception) {
            Log.w(TAG, "ssh connect failed host=${hostId.take(8)}")
            emit(hostId, sessionId, "state", stateMap("failed", mapOf("code" to "ssh_connect_error", "reason" to (e.message ?: "connect failed"))))
        }
    }

    fun write(hostId: String, sessionId: String, data: ByteArray) {
        sessions[sessionKey(hostId, sessionId)]?.write(data)
    }

    fun resize(hostId: String, sessionId: String, cols: Int, rows: Int) {
        val key = sessionKey(hostId, sessionId)
        sizes[key] = cols to rows
        sessions[key]?.resize(cols, rows)
    }

    // Answers a host-key prompt. decision is "accept" (first-use), "replace"
    // (intentionally accept a changed key), or "reject".
    fun hostKey(hostId: String, sessionId: String, decision: String) {
        val s = sessions[sessionKey(hostId, sessionId)] ?: return
        when (decision) {
            "accept" -> s.acceptNewHostKey()
            "replace" -> s.replaceChangedHostKey()
            else -> s.rejectHostKey()
        }
    }

    fun close(hostId: String, sessionId: String) {
        closeLocked(sessionKey(hostId, sessionId))
    }

    /** Closes every session belonging to one host. */
    fun closeHost(hostId: String) {
        val prefix = "$hostId:"
        for (key in sessions.keys.toList()) {
            if (key.startsWith(prefix)) closeLocked(key)
        }
    }

    private fun closeLocked(key: String) {
        sizes.remove(key)
        sessions.remove(key)?.let { s ->
            try {
                s.close()
            } catch (_: Exception) {
            }
            s.release()
            keepAliveStoppedIfIdle()
        }
    }

    // A remote close/failure can arrive on the session executor. Remove only
    // if this is still the current session for the key, so a late callback
    // from a replaced connection can never stop its successor's service.
    private fun retire(key: String, session: SshSession) {
        if (!sessions.remove(key, session)) return
        sizes.remove(key)
        session.release()
        keepAliveStoppedIfIdle()
    }

    private fun keepAliveStarted() {
        SshModule.appContext?.let { SshSessionService.start(it) }
    }

    private fun keepAliveStoppedIfIdle() {
        if (sessions.isNotEmpty()) return
        SshModule.appContext?.let { SshSessionService.stop(it) }
    }

    /**
     * Routes terminal output to the terminal that renders it.
     *
     * A tab with a terminal takes the native path: the bytes go straight into
     * it and the bound view repaints, so no base64 round trip through JS is
     * paid for output the user is watching. The event still crosses, carrying
     * the length only, because the container tracks activity from it.
     *
     * SSH replays nothing, so unlike the daemon path there is no history to
     * batch and no gate to hold output behind.
     *
     * A tab with no terminal yet falls back to the base64 event, which is
     * what lib/sshSessions buffers and writes once one exists.
     */
    private fun deliverTerminal(hostId: String, sessionId: String, data: ByteArray) {
        if (TerminalStore.has(sessionId)) {
            val size = sizes[sessionKey(hostId, sessionId)]
            TerminalStore.feed(sessionId, data, size?.first ?: 0, size?.second ?: 0) {}
            emit(hostId, sessionId, "data", mapOf("data" to "", "length" to data.size, "fastPath" to true))
            return
        }
        emit(hostId, sessionId, "data", mapOf("data" to Base64Std.encode(data), "length" to data.size, "fastPath" to false))
    }

    private fun emit(hostId: String, sessionId: String, name: String, data: Map<String, Any?>) {
        val sink = sinks[hostId] ?: return
        poster.post { sink(name, data + ("hostId" to hostId) + ("sessionId" to sessionId)) }
    }

    // Flattens an SshSessionState for the container. The host-key prompt carries
    // the presented key and whether it is a change (versus a first-use key).
    private fun stateMap(state: SshSessionState): Map<String, Any?> = when (state) {
        is SshSessionState.Disconnected -> stateMap("disconnected", emptyMap())
        is SshSessionState.Connecting -> stateMap("connecting", emptyMap())
        is SshSessionState.HostKey -> {
            val changed = state.verdict is HostKeyVerdict.Changed
            stateMap(
                "hostKey",
                mapOf(
                    "algorithm" to state.info.algorithm,
                    "fingerprint" to state.info.fingerprint,
                    "changed" to changed,
                ),
            )
        }
        is SshSessionState.Active -> stateMap("active", emptyMap())
        is SshSessionState.Closed -> stateMap(
            "closed",
            mapOf("code" to state.code, "reason" to state.reason, "userInitiated" to state.userInitiated),
        )
        is SshSessionState.Failed -> stateMap(
            "failed",
            mapOf("code" to state.code, "reason" to state.reason, "stage" to state.stage),
        )
    }

    private fun stateMap(state: String, extra: Map<String, Any?>): Map<String, Any?> =
        mapOf("state" to state) + extra

    private const val TAG = "RemotlySsh"
}
