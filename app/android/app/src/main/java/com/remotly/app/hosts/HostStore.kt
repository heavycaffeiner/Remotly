package com.remotly.app.hosts

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64

// A persisted daemon host: the pinned identity and the validated LAN hints a
// reconnect needs. The pairing secret and token are single-use handshake
// material and never reach this file.
data class HostHint(val kind: Int, val addr: String, val port: Int)

data class HostRecord(
    val id: String,
    val daemonName: String,
    val daemonPub: String,
    val hints: List<HostHint>,
    val pairedAt: Long,
    val lastConnectedAt: Long,
)

class HostStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AddResult(val id: String, val duplicate: Boolean)

// Persistent daemon host records, schema version 1:
//
//   { "version": 1,
//     "hosts": [ { "id", "daemonName", "daemonPub",
//                  "hints": [ { "kind", "addr", "port" } ],
//                  "pairedAt", "lastConnectedAt" } ] }
//
// Timestamps are unix seconds so the JSON bridge never widens them to
// imprecise doubles. `id` is the unpadded base64url of `daemonPub` and is the
// stable identity of a host: re-pairing with the same key refreshes the name
// and hints but never replaces the pin, and a changed key creates a new record
// instead of touching the old one.
//
// The store is a trust boundary for its own file. Any structural violation
// (bad JSON, unknown version, an id that does not match its public key, a
// control character in a name) quarantines the whole file under a
// side-name and starts fresh, so corrupt data is preserved for recovery
// rather than partially loaded or overwritten.
class HostStore(
    private val file: File,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    @Synchronized
    fun list(): List<HostRecord> = load()

    @Synchronized
    fun add(daemonName: String, daemonPub: String, hints: List<HostHint>): AddResult {
        val pubBytes = decodePub(daemonPub)
        validateName(daemonName)
        validateHints(hints)
        val id = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes)
        val hosts = load().toMutableList()
        val now = clock()
        val idx = hosts.indexOfFirst { it.id == id }
        return if (idx >= 0) {
            hosts[idx] = hosts[idx].copy(daemonName = daemonName, hints = hints, lastConnectedAt = now)
            persist(hosts)
            AddResult(id, duplicate = true)
        } else {
            hosts.add(HostRecord(id, daemonName, daemonPub, hints, now, now))
            persist(hosts)
            AddResult(id, duplicate = false)
        }
    }

    @Synchronized
    fun remove(id: String): Boolean {
        val hosts = load()
        val next = hosts.filterNot { it.id == id }
        if (next.size == hosts.size) return false
        persist(next)
        return true
    }

    @Synchronized
    fun touch(id: String): Boolean {
        val hosts = load().toMutableList()
        val idx = hosts.indexOfFirst { it.id == id }
        if (idx < 0) return false
        hosts[idx] = hosts[idx].copy(lastConnectedAt = clock())
        persist(hosts)
        return true
    }

    // --- persistence ---------------------------------------------------------

    private fun load(): List<HostRecord> {
        if (!file.exists()) return emptyList()
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return quarantine("unreadable hosts file")
        }
        return try {
            parse(text)
        } catch (e: HostStoreException) {
            quarantine("corrupt hosts file: ${e.message}")
        }
    }

    private fun parse(text: String): List<HostRecord> {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw HostStoreException("hosts file is not valid JSON")
        }
        val obj =
            if (root.isJsonObject) root.asJsonObject
            else throw HostStoreException("hosts file is not a JSON object")
        if (obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt != VERSION) {
            throw HostStoreException("hosts file has unsupported version")
        }
        val arr = obj.get("hosts")
            ?: throw HostStoreException("hosts file is missing hosts")
        if (!arr.isJsonArray) throw HostStoreException("hosts is not a JSON array")
        return arr.asJsonArray.map { parseRecord(it) }
    }

    private fun parseRecord(el: JsonElement): HostRecord {
        val o =
            if (el.isJsonObject) el.asJsonObject
            else throw HostStoreException("record is not a JSON object")
        fun str(field: String): String =
            o.get(field)?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw HostStoreException("record field $field is not a string")
        val id = str("id")
        val name = str("daemonName")
        val pub = str("daemonPub")
        val pubBytes = decodePub(pub)
        val expectedId = Base64.getUrlEncoder().withoutPadding().encodeToString(pubBytes)
        if (id != expectedId) throw HostStoreException("record id does not match daemon pub")
        validateName(name)
        val hintsEl = o.get("hints") ?: throw HostStoreException("record is missing hints")
        if (!hintsEl.isJsonArray) throw HostStoreException("hints is not a JSON array")
        val hints = hintsEl.asJsonArray.map { h ->
            val ho =
                if (h.isJsonObject) h.asJsonObject
                else throw HostStoreException("hint is not a JSON object")
            HostHint(
                kind = ho.get("kind")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: throw HostStoreException("hint kind is not a number"),
                addr = ho.get("addr")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw HostStoreException("hint addr is not a string"),
                port = ho.get("port")?.takeIf { it.isJsonPrimitive }?.asInt
                    ?: throw HostStoreException("hint port is not a number"),
            )
        }
        validateHints(hints)
        val pairedAt = o.get("pairedAt")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: throw HostStoreException("pairedAt is missing")
        val lastConnectedAt = o.get("lastConnectedAt")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: throw HostStoreException("lastConnectedAt is missing")
        if (pairedAt <= 0 || lastConnectedAt <= 0) {
            throw HostStoreException("record timestamps are invalid")
        }
        return HostRecord(id, name, pub, hints, pairedAt, lastConnectedAt)
    }

    private fun persist(hosts: List<HostRecord>) {
        val obj = JsonObject().apply {
            addProperty("version", VERSION)
            add("hosts", JsonArray().apply {
                hosts.forEach { r ->
                    add(
                        JsonObject().apply {
                            addProperty("id", r.id)
                            addProperty("daemonName", r.daemonName)
                            addProperty("daemonPub", r.daemonPub)
                            add(
                                "hints",
                                JsonArray().apply {
                                    r.hints.forEach { h ->
                                        add(
                                            JsonObject().apply {
                                                addProperty("kind", h.kind)
                                                addProperty("addr", h.addr)
                                                addProperty("port", h.port)
                                            }
                                        )
                                    }
                                }
                            )
                            addProperty("pairedAt", r.pairedAt)
                            addProperty("lastConnectedAt", r.lastConnectedAt)
                        }
                    )
                }
            })
        }
        writeAtomic(GSON.toJson(obj).toByteArray())
    }

    // Renames the corrupt file out of the way. If the rename fails the store
    // refuses to continue, because the next persist would overwrite the
    // unreadable data.
    private fun quarantine(reason: String): List<HostRecord> {
        val quarantined =
            File(file.parentFile, "${file.nameWithoutExtension}.corrupt-${clock()}${file.extension}")
        if (!file.renameTo(quarantined)) {
            throw HostStoreException("cannot quarantine $reason")
        }
        return emptyList()
    }

    private fun writeAtomic(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!tmp.renameTo(file)) {
                throw HostStoreException("cannot replace hosts file")
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is HostStoreException) throw e
            throw HostStoreException("cannot write hosts file", e)
        }
    }

    // --- validation ----------------------------------------------------------

    private fun decodePub(pub: String): ByteArray {
        // 32 bytes are exactly 43 unpadded base64url characters.
        if (pub.length != PUB_B64_LEN) throw HostStoreException("daemon pub has bad length")
        val b = try {
            Base64.getUrlDecoder().decode(pub)
        } catch (e: IllegalArgumentException) {
            throw HostStoreException("daemon pub is not valid base64url")
        }
        if (b.size != PUB_LEN) throw HostStoreException("daemon pub must be 32 bytes")
        if (b.all { it == 0.toByte() }) throw HostStoreException("daemon pub is all zeros")
        return b
    }

    private fun validateName(name: String) {
        if (name.length < 1 || name.length > MAX_NAME) {
            throw HostStoreException("daemon name out of range")
        }
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7f) {
                throw HostStoreException("daemon name has a control character")
            }
        }
    }

    private fun validateHints(hints: List<HostHint>) {
        if (hints.size > MAX_HINTS) throw HostStoreException("too many hints")
        for (h in hints) {
            // 0 = IPv4, 1 = IPv6, 2 = name, 3 = relay target.
            if (h.kind !in 0..3) throw HostStoreException("bad hint kind")
            if (h.addr.length < 1 || h.addr.length > MAX_HINT_ADDR) {
                throw HostStoreException("hint address out of range")
            }
            if (h.port !in 1..65535) throw HostStoreException("hint port out of range")
        }
    }

    companion object {
        const val FILE_NAME = "hosts.json"

        private const val TMP_SUFFIX = ".tmp"
        private const val VERSION = 1
        private const val PUB_LEN = 32
        private const val PUB_B64_LEN = 43
        private const val MAX_NAME = 100
        private const val MAX_HINTS = 8
        private const val MAX_HINT_ADDR = 255

        private val GSON = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    }
}
