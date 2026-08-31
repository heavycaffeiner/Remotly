package com.remotly.app.workspace

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

// Per-host workspace tab documents, schema version 1. The document is the
// app's own UI state; the JS layer owns its meaning and this store owns the
// file: atomic write, structural validation, and quarantine on corruption.
//
//   { "v": 1,
//     "hostId": "<id>",
//     "activeSessionId": <string | null>,
//     "tabs": [ { "sessionId", "title", "kind", "cursor",
//                 "state", "exitCode" } ] }
//
// `sessionId` is the daemon's hex session id (64 lowercase hex characters in
// practice; the store bounds the shape, not the exact length). `cursor` is a
// cumulative output byte count and the daemon rejects anything at or above
// 2^53, so the store enforces the same bound on what it persists.
open class WorkspaceStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

// A structurally invalid host id or document: the caller's mistake, not a
// storage fault. The store never writes or quarantines in response to one.
class WorkspaceValidationException(message: String) : WorkspaceStoreException(message)

class WorkspaceStore(private val dir: File) {

    // Returns the stored document verbatim, or null when the host has no
    // workspace yet. A corrupt file is quarantined and reported as absent:
    // losing tab metadata is recoverable, loading half-parsed state is not.
    @Synchronized
    fun load(hostId: String): String? {
        requireHostId(hostId)
        val file = fileFor(hostId)
        if (!file.exists()) return null
        val text = try {
            file.readText()
        } catch (e: Exception) {
            quarantine(file)
            return null
        }
        return try {
            validateDocument(text, hostId)
            text
        } catch (e: WorkspaceStoreException) {
            quarantine(file)
            null
        }
    }

    // Validates the host id and document, then writes it atomically. Invalid
    // input is rejected without touching the existing file.
    @Synchronized
    fun save(hostId: String, json: String) {
        requireHostId(hostId)
        validateDocument(json, hostId)
        val file = fileFor(hostId)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { it.write(json.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                throw WorkspaceStoreException("cannot replace workspace file")
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is WorkspaceStoreException) throw e
            throw WorkspaceStoreException("cannot write workspace file", e)
        }
    }

    // Deletes the host's document. A host removal must not leave tabs that
    // could be restored by re-pairing the same key.
    @Synchronized
    fun clear(hostId: String) {
        requireHostId(hostId)
        val file = fileFor(hostId)
        if (file.exists() && !file.delete()) {
            throw WorkspaceStoreException("cannot delete workspace file")
        }
    }

    // --- validation ----------------------------------------------------------

    private fun fileFor(hostId: String): File = File(dir, "workspace-$hostId.json")

    private fun requireHostId(hostId: String) {
        // The host id also lands in a file name, so keep it to characters
        // that are safe in both.
        if (hostId.length < 1 || hostId.length > MAX_HOST_ID) {
            throw WorkspaceValidationException("host id out of range")
        }
        for (c in hostId) {
            val ok = c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' || c == '_'
            if (!ok) throw WorkspaceValidationException("host id has an invalid character")
        }
    }

    private fun validateDocument(json: String, hostId: String) {
        val root = try {
            JsonParser.parseString(json)
        } catch (e: Exception) {
            throw WorkspaceValidationException("workspace is not valid JSON")
        }
        val o =
            if (root.isJsonObject) root.asJsonObject
            else throw WorkspaceValidationException("workspace is not a JSON object")
        if (o.get("v")?.takeIf { it.isJsonPrimitive }?.asInt != VERSION) {
            throw WorkspaceValidationException("workspace has unsupported version")
        }
        if (o.get("hostId")?.takeIf { it.isJsonPrimitive }?.asString != hostId) {
            throw WorkspaceValidationException("workspace host id does not match")
        }
        val tabs = o.get("tabs")
            ?: throw WorkspaceValidationException("workspace is missing tabs")
        if (!tabs.isJsonArray) throw WorkspaceValidationException("workspace tabs is not an array")
        val list = tabs.asJsonArray
        if (list.size() > MAX_TABS) throw WorkspaceValidationException("workspace has too many tabs")
        val seen = HashSet<String>()
        for (el in list) {
            val t =
                if (el.isJsonObject) el.asJsonObject
                else throw WorkspaceValidationException("tab is not a JSON object")
            val sid = t.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw WorkspaceValidationException("tab sessionId is not a string")
            if (!SESSION_ID.matches(sid)) {
                throw WorkspaceValidationException("tab sessionId is not a session id")
            }
            if (!seen.add(sid)) throw WorkspaceValidationException("duplicate tab session")
            val state = t.get("state")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw WorkspaceValidationException("tab state is not a string")
            if (state !in TAB_STATES) throw WorkspaceValidationException("tab state is unknown")
            val cursor = t.get("cursor")?.takeIf { it.isJsonPrimitive }?.asLong
                ?: throw WorkspaceValidationException("tab cursor is not a number")
            if (cursor !in 0L..MAX_SAFE) {
                throw WorkspaceValidationException("tab cursor out of range")
            }
            val title = t.get("title")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw WorkspaceValidationException("tab title is not a string")
            if (title.length > MAX_TITLE) throw WorkspaceValidationException("tab title too long")
            for (c in title) {
                if (c.code < 0x20 || c.code == 0x7f) {
                    throw WorkspaceValidationException("tab title has a control character")
                }
            }
            val kind = t.get("kind")?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw WorkspaceValidationException("tab kind is not a string")
            if (kind.length > MAX_KIND) throw WorkspaceValidationException("tab kind too long")
        }
        val active = o.get("activeSessionId")
        if (active != null && !active.isJsonNull) {
            val sid = active.takeIf { it.isJsonPrimitive }?.asString
                ?: throw WorkspaceValidationException("activeSessionId is not a string")
            if (!SESSION_ID.matches(sid)) {
                throw WorkspaceValidationException("activeSessionId is not a session id")
            }
        }
    }

    // Renames the corrupt file out of the way. If the rename fails the store
    // refuses to continue, because the next save would overwrite the
    // unreadable data.
    private fun quarantine(file: File) {
        val quarantined =
            File(file.parentFile, file.name + ".corrupt-" + System.currentTimeMillis())
        if (!file.renameTo(quarantined)) {
            throw WorkspaceStoreException("cannot quarantine corrupt workspace file")
        }
    }

    companion object {
        const val DIR_NAME = "remotly"

        private const val TMP_SUFFIX = ".tmp"
        private const val VERSION = 1
        private const val MAX_HOST_ID = 64
        private const val MAX_TABS = 16
        // 2^53 - 1: byte cursors must stay representable as JavaScript numbers.
        private const val MAX_SAFE = (1L shl 53) - 1
        private const val MAX_TITLE = 120
        private const val MAX_KIND = 32

        private val TAB_STATES = setOf("attaching", "attached", "exited", "stale")

        // Session ids are daemon-minted hex strings (64 chars in practice).
        // The bounds keep a hostile document from storing arbitrary strings.
        private val SESSION_ID = Regex("^[0-9a-f]{16,128}$")
    }
}
