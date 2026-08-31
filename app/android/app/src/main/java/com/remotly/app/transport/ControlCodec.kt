package com.remotly.app.transport

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.annotations.SerializedName

// The M1 control schema. Requests travel app to daemon, responses and
// notifications daemon to app. Field names and bounds mirror the daemon's
// protocol package; decoding validates at this trust boundary because the
// daemon is an untrusted peer.

// Control message types.
object ControlType {
    const val HELLO = "hello"
    const val SESSION_CREATE = "session.create"
    const val SESSION_LIST = "session.list"
    const val SESSION_ATTACH = "session.attach"
    const val SESSION_DETACH = "session.detach"
    const val SESSION_RESIZE = "session.resize"
    const val SESSION_KILL = "session.kill"
    const val PRESET_LIST = "preset.list"
    const val CHANNEL_CLOSE = "channel.close"
    const val CHANNEL_REPLAY_COMPLETE = "channel.replay_complete"
    const val SESSION_UPDATE = "session.update"
    const val SESSION_EVENT = "session.event"
    const val TRANSFER_CREATE = "transfer.create"
    const val TRANSFER_RESUME = "transfer.resume"
}

object SessionKind {
    const val SHELL = "shell"
    const val AGENT = "agent"
}

// Bounds shared with the daemon's protocol package.
object ControlLimits {
    const val MAX_ID = (1L shl 53) - 1
    const val MAX_TITLE_LEN = 200
    const val MAX_COMMAND_LEN = 4096
    const val MAX_DEVICE_NAME_LEN = 100
    const val MIN_DIMENSION = 1
    const val MAX_DIMENSION = 1000
    // Replay cursor bound, kept below 2^53 so JavaScript numbers stay exact.
    const val MAX_RESUME_FROM = (1L shl 53) - 1
    // Per-session event counter bound, also below 2^53.
    const val MAX_EVENT_SEQ = (1L shl 53) - 1
    // A session preview line and event text: plain text, at most 120 bytes.
    const val MAX_PREVIEW_LEN = 120
    // A configured output-pattern rule name.
    const val MAX_PATTERN_NAME_LEN = 50
}

// Replay continuity values carried in the session.attach response.
// "full": no cursor, the whole retained scrollback was replayed.
// "gapless": the cursor was inside the retained window; every byte from the
// cursor on is delivered exactly once.
// "gap": the cursor is older than the retained window; bytes between the
// cursor and the window's oldest byte are lost and the stream is truncated.
object Continuity {
    const val FULL = "full"
    const val GAPLESS = "gapless"
    const val GAP = "gap"
    val ALL = setOf(FULL, GAPLESS, GAP)
}

// Terminal event kinds carried in session.event notifications.
object EventKind {
    const val BELL = "bell"
    const val PATTERN = "pattern"
    val ALL = setOf(BELL, PATTERN)
}

// Thrown when a control frame is malformed or fails validation.
class ControlException(message: String) : Exception(message)

// A request the app sends. Only the fields of one type are set; the rest stay
// null and are omitted from the encoded JSON.
data class ControlRequest(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("type") val type: String = "",
    @SerializedName("device_name") val deviceName: String? = null,
    @SerializedName("device_pub") val devicePub: String? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("command") val command: String? = null,
    @SerializedName("cwd") val cwd: String? = null,
    @SerializedName("cols") val cols: Int? = null,
    @SerializedName("rows") val rows: Int? = null,
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("channel_id") val channelId: Long? = null,
    @SerializedName("resume_from") val resumeFrom: Long? = null,
    // fs.* requests.
    @SerializedName("path") val path: String? = null,
    @SerializedName("from") val from: String? = null,
    @SerializedName("to") val to: String? = null,
    @SerializedName("offset") val offset: Int? = null,
    @SerializedName("limit") val limit: Int? = null,
    @SerializedName("remove_kind") val removeKind: String? = null,
    // transfer.* requests.
    @SerializedName("direction") val direction: String? = null,
    @SerializedName("expected_size") val expectedSize: Long? = null,
    @SerializedName("hash") val hash: String? = null,
    @SerializedName("conflict") val conflict: String? = null,
    @SerializedName("transfer_id") val transferId: String? = null,
)

data class ControlError(
    @SerializedName("code") val code: String,
    @SerializedName("message") val message: String,
)

data class SessionExit(
    @SerializedName("code") val code: Int,
    @SerializedName("signal") val signal: String,
)

// A configured agent session preset. The app renders presets as one-tap
// session creation actions; all fields are bounded plain text.
data class Preset(
    @SerializedName("name") val name: String,
    @SerializedName("command") val command: String,
    @SerializedName("icon_hint") val iconHint: String,
)

data class SessionMeta(
    @SerializedName("id") val id: String,
    @SerializedName("title") val title: String,
    @SerializedName("kind") val kind: String,
    @SerializedName("command") val command: String,
    @SerializedName("cwd") val cwd: String,
    @SerializedName("cols") val cols: Int,
    @SerializedName("rows") val rows: Int,
    @SerializedName("created_at") val createdAt: String,
    @SerializedName("last_activity") val lastActivity: String,
    @SerializedName("running") val running: Boolean,
    @SerializedName("exit") val exit: SessionExit? = null,
    // The last retained output line as plain text; empty when the session
    // has no retained output.
    @SerializedName("preview") val preview: String? = null,
)

sealed class ControlMessage

data class ControlResponse(
    @SerializedName("id") val id: Long = 0,
    @SerializedName("type") val type: String = "",
    @SerializedName("error") val error: ControlError? = null,
    @SerializedName("daemon_name") val daemonName: String? = null,
    @SerializedName("daemon_pub") val daemonPub: String? = null,
    @SerializedName("session") val session: SessionMeta? = null,
    @SerializedName("sessions") val sessions: List<SessionMeta>? = null,
    @SerializedName("channel_id") val channelId: Long? = null,
    // session.attach only: the replay continuity and the output-stream byte
    // offset the replay started at.
    @SerializedName("continuity") val continuity: String? = null,
    @SerializedName("replayed_from") val replayedFrom: Long? = null,
    // preset.list only.
    @SerializedName("presets") val presets: List<Preset>? = null,
) : ControlMessage()

// A decoded session.event notification: a bell or configured output-pattern
// match on a session. Seq is the per-session monotonic counter the app uses
// for dedup. Text is terminal content and must never reach logs or analytics.
data class SessionEvent(
    val sessionId: String,
    val seq: Long,
    val kind: String,
    val pattern: String?,
    val text: String?,
    val ts: Long,
)

data class ControlNotification(
    @SerializedName("type") val type: String = "",
    @SerializedName("channel_id") val channelId: Long? = null,
    @SerializedName("reason") val reason: String? = null,
    @SerializedName("session") val session: SessionMeta? = null,
    // channel.replay_complete only: the cumulative offset just past the last
    // replayed byte, i.e. the resume cursor at the replay/live boundary.
    @SerializedName("offset") val offset: Long? = null,
    // session.event only. Text is terminal content: it must never reach
    // logs or analytics.
    @SerializedName("session_id") val sessionId: String? = null,
    @SerializedName("seq") val seq: Long? = null,
    @SerializedName("kind") val kind: String? = null,
    @SerializedName("pattern") val pattern: String? = null,
    @SerializedName("text") val text: String? = null,
    @SerializedName("ts") val ts: Long? = null,
) : ControlMessage()

class ControlCodec {
    private val gson = Gson()

    // --- Requests (app to daemon) ---

    fun hello(id: Long, deviceName: String, devicePub: ByteArray): ControlRequest =
        ControlRequest(
            id = id,
            type = ControlType.HELLO,
            deviceName = deviceName,
            devicePub = Base64Url.encode(devicePub),
        )

    fun sessionCreate(
        id: Long,
        kind: String,
        title: String? = null,
        command: String? = null,
        cwd: String? = null,
        cols: Int? = null,
        rows: Int? = null,
    ): ControlRequest = ControlRequest(id = id, type = ControlType.SESSION_CREATE,
        kind = kind, title = title, command = command, cwd = cwd, cols = cols, rows = rows)

    fun sessionList(id: Long): ControlRequest =
        ControlRequest(id = id, type = ControlType.SESSION_LIST)

    fun sessionAttach(id: Long, sessionId: String, resumeFrom: Long? = null): ControlRequest {
        if (resumeFrom != null) checkSessionAttach(sessionId, resumeFrom)
        return ControlRequest(id = id, type = ControlType.SESSION_ATTACH,
            sessionId = sessionId, resumeFrom = resumeFrom)
    }

    fun presetList(id: Long): ControlRequest =
        ControlRequest(id = id, type = ControlType.PRESET_LIST)

    fun sessionDetach(id: Long, channelId: Long): ControlRequest =
        ControlRequest(id = id, type = ControlType.SESSION_DETACH, channelId = channelId)

    fun sessionResize(id: Long, sessionId: String, cols: Int, rows: Int): ControlRequest =
        ControlRequest(id = id, type = ControlType.SESSION_RESIZE,
            sessionId = sessionId, cols = cols, rows = rows)

    fun sessionKill(id: Long, sessionId: String): ControlRequest =
        ControlRequest(id = id, type = ControlType.SESSION_KILL, sessionId = sessionId)

    // Builds the event view from a validated session.event notification.
    fun toSessionEvent(n: ControlNotification): SessionEvent = SessionEvent(
        sessionId = n.sessionId ?: throw ControlException("event missing session_id"),
        seq = n.seq ?: throw ControlException("event missing seq"),
        kind = n.kind ?: throw ControlException("event missing kind"),
        pattern = n.pattern,
        text = n.text,
        ts = n.ts ?: throw ControlException("event missing ts"),
    )

    // Encodes a request. Null fields are omitted, so only the fields of the
    // request's type appear on the wire.
    fun encodeRequest(request: ControlRequest): String = gson.toJson(request)

    // --- Responses and notifications (daemon to app) ---

    fun decode(data: String): ControlMessage {
        val obj = try {
            JsonParser.parseString(data).asJsonObject
        } catch (e: Exception) {
            throw ControlException("not a JSON object")
        }
        val typeEl = obj.get("type")
        val type = try {
            typeEl?.asString
        } catch (e: Exception) {
            throw ControlException("type is not a string")
        }
        if (type.isNullOrEmpty()) throw ControlException("missing type")
        return try {
            if (obj.has("id")) {
                val r = gson.fromJson(obj, ControlResponse::class.java)
                if (r.id < 0 || r.id > ControlLimits.MAX_ID) throw ControlException("bad id")
                if (r.channelId != null && r.channelId !in 0..0xFFFFFFFFL) {
                    throw ControlException("bad channel id")
                }
                validateResponse(r)
                r
            } else {
                val n = gson.fromJson(obj, ControlNotification::class.java)
                validateNotification(n)
                n
            }
        } catch (e: ControlException) {
            throw e
        } catch (e: Exception) {
            throw ControlException("bad control frame")
        }
    }

    private fun validateResponse(r: ControlResponse) {
        r.continuity?.let { if (it !in Continuity.ALL) throw ControlException("bad continuity") }
        r.replayedFrom?.let { if (it < 0 || it > ControlLimits.MAX_RESUME_FROM) {
            throw ControlException("bad replayed_from")
        } }
        r.presets?.let { list ->
            if (list.size > 16) throw ControlException("too many presets")
            for (p in list) {
                if (p.name.isEmpty() || p.name.length > 50) throw ControlException("bad preset name")
                if (p.command.isEmpty() || p.command.length > ControlLimits.MAX_COMMAND_LEN) {
                    throw ControlException("bad preset command")
                }
                if (p.iconHint.length > 32) throw ControlException("bad preset icon hint")
            }
        }
    }

    private fun validateNotification(n: ControlNotification) {
        when (n.type) {
            ControlType.CHANNEL_CLOSE -> {
                if (n.channelId == null) throw ControlException("channel.close missing channel_id")
                if (n.reason == null || n.reason !in closeReasons) {
                    throw ControlException("channel.close bad reason")
                }
            }
            ControlType.SESSION_UPDATE -> {
                if (n.session == null) throw ControlException("session.update missing session")
            }
            ControlType.CHANNEL_REPLAY_COMPLETE -> {
                if (n.channelId == null) {
                    throw ControlException("replay_complete missing channel_id")
                }
                val off = n.offset
                    ?: throw ControlException("replay_complete missing offset")
                if (off < 0 || off > ControlLimits.MAX_RESUME_FROM) {
                    throw ControlException("replay_complete bad offset")
                }
            }
            ControlType.SESSION_EVENT -> validateEvent(n)
            // Unknown types pass through so a newer daemon can probe.
        }
    }

    // Text is terminal content; it is validated at this boundary and must
    // never be logged.
    private fun validateEvent(n: ControlNotification) {
        val sid = n.sessionId ?: throw ControlException("session.event missing session_id")
        if (!sessionIds.matches(sid)) throw ControlException("session.event bad session_id")
        val seq = n.seq ?: throw ControlException("session.event missing seq")
        if (seq < 1 || seq > ControlLimits.MAX_EVENT_SEQ) throw ControlException("session.event bad seq")
        val ts = n.ts ?: throw ControlException("session.event missing ts")
        if (ts < 0) throw ControlException("session.event bad ts")
        val kind = n.kind ?: throw ControlException("session.event missing kind")
        if (kind !in EventKind.ALL) throw ControlException("session.event bad kind")
        when (kind) {
            EventKind.BELL -> if (n.pattern != null) {
                throw ControlException("bell carries a pattern")
            }
            EventKind.PATTERN -> {
                val p = n.pattern ?: throw ControlException("pattern event missing pattern")
                if (p.isEmpty() || p.length > ControlLimits.MAX_PATTERN_NAME_LEN) {
                    throw ControlException("session.event bad pattern name")
                }
            }
        }
        n.text?.let { if (it.length > ControlLimits.MAX_PREVIEW_LEN) {
            throw ControlException("session.event text too long")
        } }
    }

    companion object {
        private val closeReasons = setOf(
            "session_exited", "overflow", "detached", "closed",
        )
        private val sessionIds = Regex("^[0-9a-f]{64}$")

        // Mirrors the daemon's session.create/attach/kill value checks, so a
        // local request that would be rejected is caught before it is sent.
        fun checkSessionCreate(kind: String?, title: String?, command: String?, cwd: String?,
            cols: Int?, rows: Int?) {
            require(kind == SessionKind.SHELL || kind == SessionKind.AGENT) { "bad kind" }
            if (kind == SessionKind.SHELL) require(command == null) { "shell has no command" }
            if (kind == SessionKind.AGENT) require(command != null) { "agent needs command" }
            command?.let { require(it.length <= ControlLimits.MAX_COMMAND_LEN) { "command too long" } }
            title?.let { require(it.length <= ControlLimits.MAX_TITLE_LEN) { "title too long" } }
            cwd?.let { require(it.startsWith("/")) { "cwd must be absolute" } }
            cols?.let { require(it in ControlLimits.MIN_DIMENSION..ControlLimits.MAX_DIMENSION) { "bad cols" } }
            rows?.let { require(it in ControlLimits.MIN_DIMENSION..ControlLimits.MAX_DIMENSION) { "bad rows" } }
        }

        fun isValidSessionId(id: String): Boolean = sessionIds.matches(id)

        // Mirrors the daemon's session.attach value checks for the replay
        // cursor, so a local request that would be rejected is caught before
        // it is sent.
        fun checkSessionAttach(sessionId: String, resumeFrom: Long?) {
            require(sessionIds.matches(sessionId)) { "bad session id" }
            resumeFrom?.let { require(it in 0..ControlLimits.MAX_RESUME_FROM) { "bad resume_from" } }
        }
    }
}
