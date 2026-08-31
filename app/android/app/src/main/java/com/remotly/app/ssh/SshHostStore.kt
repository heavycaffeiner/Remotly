package com.remotly.app.ssh

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.util.Base64
import java.util.UUID

class SshHostStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Storage is internally inconsistent and was preserved rather than reset. */
class SshStoreInconsistentException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

// Persistent plain-SSH host records, schema version 2.
//
// Hosts and their encrypted credentials live in one document that is replaced
// atomically. The previous layout kept them in two files and mutated them in
// sequence, so a failed write could delete the old credential while the record
// still pointed at it, leaving a saved host that could never connect. One
// document makes any host-and-credential update a single file replacement.
//
// The store is a trust boundary for its own file: structural violations
// quarantine it, and an inconsistent-but-parseable document is reported rather
// than silently repaired, because repair means discarding a credential.
class SshHostStore(
    private val file: File,
    private val cipher: CredentialCipher,
    private val clock: () -> Long = { System.currentTimeMillis() / 1000 },
    // Seam for fault injection in tests. Production writes through the real file.
    private val writer: AtomicWriter = FileAtomicWriter(),
) {

    /** The whole persisted state. Immutable; every operation builds a new one. */
    private data class Document(
        val hosts: List<SshHost>,
        /** credentialRef to base64(iv + ciphertext). */
        val credentials: Map<String, String>,
    )

    private val lock = Any()

    /** Field patch for an update. A null field means leave it unchanged. */
    data class HostPatch(
        val displayName: String? = null,
        val host: String? = null,
        val port: Int? = null,
        val username: String? = null,
        val credential: SshCredential? = null,
        val clearKnownKeys: Boolean = false,
    )

    fun list(): List<SshHost> = synchronized(lock) { load().hosts }

    fun get(id: String): SshHost? = synchronized(lock) { load().hosts.firstOrNull { it.id == id } }

    fun add(
        displayName: String,
        host: String,
        port: Int,
        username: String,
        credential: SshCredential,
    ): SshHost = synchronized(lock) {
        validateDisplayName(displayName)
        SshEndpoint.validatePort(port)
        validateUsername(username)
        val canonical = SshEndpoint.canonicalize(host)
        val id = SshEndpoint.endpointId(canonical.host, port)

        val doc = load()
        val now = clock()
        val ref = UUID.randomUUID().toString()
        val sealed = sealCredential(credential)
        val authKind = authKindOf(credential)

        val existing = doc.hosts.firstOrNull { it.id == id }
        val record = if (existing != null) {
            existing.copy(
                displayName = displayName,
                host = canonical.host,
                port = port,
                username = username,
                authKind = authKind,
                credentialRef = ref,
                updatedAt = now,
            )
        } else {
            SshHost(
                id = id,
                displayName = displayName,
                host = canonical.host,
                port = port,
                username = username,
                authKind = authKind,
                credentialRef = ref,
                knownKeys = emptyList(),
                createdAt = now,
                updatedAt = now,
            )
        }

        // The new credential is added and the old one dropped in the same
        // document, so the replacement either fully happens or not at all.
        val hosts = if (existing != null) {
            doc.hosts.map { if (it.id == id) record else it }
        } else {
            doc.hosts + record
        }
        val credentials = doc.credentials.toMutableMap().apply {
            if (existing != null && existing.credentialRef.isNotEmpty()) remove(existing.credentialRef)
            put(ref, sealed)
        }
        commit(Document(hosts, credentials))
        record
    }

    /**
     * Applies a patch atomically.
     *
     * A metadata-only patch never decrypts the credential. Changing the
     * endpoint produces a new id and drops the accepted host keys, because
     * trust was established for the old endpoint and does not transfer.
     */
    fun update(id: String, patch: HostPatch): SshHost = synchronized(lock) {
        val doc = load()
        val current = doc.hosts.firstOrNull { it.id == id }
            ?: throw SshHostStoreException("no such host")

        patch.displayName?.let { validateDisplayName(it) }
        patch.username?.let { validateUsername(it) }
        patch.port?.let { SshEndpoint.validatePort(it) }

        val canonicalHost = patch.host?.let { SshEndpoint.canonicalize(it).host } ?: current.host
        val port = patch.port ?: current.port
        val endpointChanged = canonicalHost != current.host || port != current.port
        val newId = if (endpointChanged) SshEndpoint.endpointId(canonicalHost, port) else current.id

        if (endpointChanged && doc.hosts.any { it.id == newId }) {
            throw SshHostStoreException("another host already uses that endpoint")
        }

        val now = clock()
        var credentials = doc.credentials
        var credentialRef = current.credentialRef
        var authKind = current.authKind

        if (patch.credential != null) {
            val ref = UUID.randomUUID().toString()
            val sealed = sealCredential(patch.credential)
            credentials = credentials.toMutableMap().apply {
                if (current.credentialRef.isNotEmpty()) remove(current.credentialRef)
                put(ref, sealed)
            }
            credentialRef = ref
            authKind = authKindOf(patch.credential)
        }

        val record = current.copy(
            id = newId,
            displayName = patch.displayName ?: current.displayName,
            host = canonicalHost,
            port = port,
            username = patch.username ?: current.username,
            authKind = authKind,
            credentialRef = credentialRef,
            knownKeys = if (endpointChanged || patch.clearKnownKeys) emptyList() else current.knownKeys,
            updatedAt = now,
        )

        val hosts = doc.hosts.map { if (it.id == id) record else it }
        commit(Document(hosts, credentials))
        record
    }

    fun rename(id: String, displayName: String): SshHost =
        update(id, HostPatch(displayName = displayName))

    fun setCredential(id: String, credential: SshCredential): SshHost =
        update(id, HostPatch(credential = credential))

    // Records a newly presented key after explicit first-use approval.
    // Duplicate keys (same fingerprint hash) are not re-added.
    fun acceptHostKey(id: String, key: KnownHostKey): SshHost = synchronized(lock) {
        validateKey(key)
        mutate(id) { h ->
            val known = h.knownKeys.any {
                HostKeyVerifier.isMatch(it, HostKeyInfo(h.host, h.port, key.algorithm, key.fingerprint))
            }
            if (known) h.copy(updatedAt = clock())
            else h.copy(knownKeys = h.knownKeys + key, updatedAt = clock())
        }
    }

    // Intentional changed-key replacement: the accepted set becomes exactly the
    // one presented key. The prior keys are dropped.
    fun replaceHostKeys(id: String, key: KnownHostKey): SshHost = synchronized(lock) {
        validateKey(key)
        mutate(id) { it.copy(knownKeys = listOf(key), updatedAt = clock()) }
    }

    fun clearHostKeys(id: String): SshHost = synchronized(lock) {
        mutate(id) { it.copy(knownKeys = emptyList(), updatedAt = clock()) }
    }

    // Removes the record and its credential in one replacement, so the
    // credential can never outlive the record or vice versa.
    fun remove(id: String): Boolean = synchronized(lock) {
        val doc = load()
        val record = doc.hosts.firstOrNull { it.id == id } ?: return false
        val credentials = doc.credentials.toMutableMap().apply {
            if (record.credentialRef.isNotEmpty()) remove(record.credentialRef)
        }
        commit(Document(doc.hosts.filterNot { it.id == id }, credentials))
        true
    }

    /** Decodes the stored credential so the session layer can build an auth. */
    fun credential(id: String): SshCredential = synchronized(lock) {
        val doc = load()
        val record = doc.hosts.firstOrNull { it.id == id }
            ?: throw SshHostStoreException("no such host")
        val ref = record.credentialRef
        if (ref.isEmpty()) throw SshHostStoreException("host has no credential")
        val sealed = doc.credentials[ref]
            ?: throw SshStoreInconsistentException("credential bytes missing")
        var plaintext: ByteArray? = null
        try {
            plaintext = cipher.open(decodeBase64(sealed))
            CredentialCodec.decode(plaintext)
        } catch (e: SecretStoreException) {
            throw SshHostStoreException("credential does not decode", e)
        } finally {
            plaintext?.fill(0)
        }
    }

    // --- persistence ---------------------------------------------------------

    private inline fun mutate(id: String, transform: (SshHost) -> SshHost): SshHost {
        val doc = load()
        val idx = doc.hosts.indexOfFirst { it.id == id }
        if (idx < 0) throw SshHostStoreException("no such host")
        val updated = transform(doc.hosts[idx])
        val hosts = doc.hosts.toMutableList().also { it[idx] = updated }
        commit(Document(hosts, doc.credentials))
        return updated
    }

    private fun sealCredential(credential: SshCredential): String {
        var encoded: ByteArray? = null
        try {
            encoded = CredentialCodec.encode(credential)
            return Base64.getEncoder().encodeToString(cipher.seal(encoded))
        } finally {
            encoded?.fill(0)
        }
    }

    private fun authKindOf(c: SshCredential): Int =
        if (c is SshCredential.Password) SshHost.AUTH_PASSWORD else SshHost.AUTH_KEY

    // Writes the complete document. On any failure the previous file is left
    // exactly as it was, so the prior host and credential stay usable.
    private fun commit(doc: Document) {
        verifyConsistent(doc)
        writer.write(file, serialize(doc).toByteArray())
    }

    private fun verifyConsistent(doc: Document) {
        val ids = HashSet<String>()
        for (h in doc.hosts) {
            if (!ids.add(h.id)) throw SshStoreInconsistentException("duplicate host id")
            if (h.credentialRef.isNotEmpty() && !doc.credentials.containsKey(h.credentialRef)) {
                throw SshStoreInconsistentException("host references a missing credential")
            }
        }
    }

    private fun load(): Document {
        if (!file.exists()) return migrateIfNeeded() ?: Document(emptyList(), emptyMap())
        val text = try {
            file.readText()
        } catch (e: Exception) {
            return quarantine("unreadable ssh hosts file")
        }
        return try {
            parse(text)
        } catch (e: SshHostStoreException) {
            quarantine("corrupt ssh hosts file: ${e.message}")
        }
    }

    /**
     * Brings a schema 1 pair of files forward.
     *
     * The old layout is only read here. Nothing is deleted: the old files are
     * renamed aside after the version 2 document is durable, so a failure
     * halfway leaves the original data in place to try again.
     */
    private fun migrateIfNeeded(): Document? {
        val legacyHosts = File(file.parentFile, LEGACY_HOSTS_NAME)
        val legacySecrets = File(file.parentFile, LEGACY_SECRETS_NAME)
        if (!legacyHosts.exists()) return null

        val doc = try {
            readLegacy(legacyHosts, legacySecrets)
        } catch (e: Exception) {
            throw SshStoreInconsistentException("cannot migrate ssh storage", e)
        }

        // Every referenced credential must be present before anything is
        // committed. A partial migration would silently drop a saved host.
        for (h in doc.hosts) {
            if (h.credentialRef.isNotEmpty() && !doc.credentials.containsKey(h.credentialRef)) {
                throw SshStoreInconsistentException(
                    "legacy ssh storage is missing a credential; the old files were left in place",
                )
            }
        }

        writer.write(file, serialize(doc).toByteArray())
        val stamp = clock()
        legacyHosts.renameTo(File(legacyHosts.parentFile, "$LEGACY_HOSTS_NAME.migrated-$stamp"))
        if (legacySecrets.exists()) {
            legacySecrets.renameTo(File(legacySecrets.parentFile, "$LEGACY_SECRETS_NAME.migrated-$stamp"))
        }
        return doc
    }

    // Reads the schema 1 pair of files into one document. The legacy blobs were
    // sealed with the same keystore key, so they are carried across verbatim
    // rather than decrypted and re-sealed, keeping plaintext out of memory
    // during an upgrade.
    private fun readLegacy(hostsFile: File, secretsFile: File): Document {
        val hosts = parseLegacyHosts(hostsFile.readText())
        val blobs = if (secretsFile.exists()) parseLegacyBlobs(secretsFile.readText()) else emptyMap()
        return Document(hosts, blobs)
    }

    private fun parseLegacyHosts(text: String): List<SshHost> {
        val root = JsonParser.parseString(text)
        val obj = if (root.isJsonObject) root.asJsonObject
        else throw SshHostStoreException("legacy hosts file is not an object")
        val arr = obj.get("hosts")
        if (arr == null || !arr.isJsonArray) throw SshHostStoreException("legacy hosts is not an array")
        return arr.asJsonArray.map { parseRecord(it, legacyIds = true) }
    }

    private fun parseLegacyBlobs(text: String): Map<String, String> {
        val root = JsonParser.parseString(text)
        val obj = if (root.isJsonObject) root.asJsonObject
        else throw SshHostStoreException("legacy secrets file is not an object")
        val blobs = obj.get("blobs") ?: return emptyMap()
        if (!blobs.isJsonObject) throw SshHostStoreException("legacy blobs is not an object")
        return blobs.asJsonObject.entrySet().associate { it.key to it.value.asString }
    }

    private fun parse(text: String): Document {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            throw SshHostStoreException("ssh hosts file is not valid JSON")
        }
        val obj = if (root.isJsonObject) root.asJsonObject
        else throw SshHostStoreException("ssh hosts file is not an object")
        val version = obj.get("version")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw SshHostStoreException("ssh hosts file has no version")
        if (version != VERSION) throw SshHostStoreException("ssh hosts file has unsupported version")

        val arr = obj.get("hosts") ?: throw SshHostStoreException("ssh hosts file is missing hosts")
        if (!arr.isJsonArray) throw SshHostStoreException("hosts is not a JSON array")
        val hosts = arr.asJsonArray.map { parseRecord(it, legacyIds = true) }

        val credsEl = obj.get("credentials")
        val credentials = when {
            credsEl == null -> emptyMap()
            credsEl.isJsonObject -> credsEl.asJsonObject.entrySet().associate { (k, v) ->
                k to (v.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw SshHostStoreException("credential blob is not a string"))
            }
            else -> throw SshHostStoreException("credentials is not an object")
        }

        val ids = HashSet<String>()
        for (h in hosts) {
            if (!ids.add(h.id)) throw SshHostStoreException("duplicate host id")
        }
        // A parseable document that references a credential it does not contain
        // is preserved and reported, never silently trimmed.
        for (h in hosts) {
            if (h.credentialRef.isNotEmpty() && !credentials.containsKey(h.credentialRef)) {
                throw SshStoreInconsistentException("host references a missing credential")
            }
        }
        return Document(hosts, credentials)
    }

    /**
     * Parses one record.
     *
     * [legacyIds] accepts an id produced by either derivation, so records
     * written before canonicalization still load. Re-deriving them would fail
     * the identity check and quarantine every existing host on upgrade.
     */
    private fun parseRecord(el: JsonElement, legacyIds: Boolean): SshHost {
        val o = if (el.isJsonObject) el.asJsonObject
        else throw SshHostStoreException("record is not an object")
        fun str(field: String): String =
            o.get(field)?.takeIf { it.isJsonPrimitive }?.asString
                ?: throw SshHostStoreException("record field $field is not a string")
        val host = str("host")
        val port = o.get("port")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw SshHostStoreException("port is not a number")
        val id = str("id")
        SshEndpoint.validatePort(port)
        val canonical = SshEndpoint.canonicalize(host).host
        val matches = id == SshEndpoint.endpointId(canonical, port) ||
            (legacyIds && id == SshEndpoint.legacyEndpointId(host, port))
        if (!matches) throw SshHostStoreException("record id does not match endpoint")

        val displayName = str("displayName")
        val username = str("username")
        val authKind = o.get("authKind")?.takeIf { it.isJsonPrimitive }?.asInt
            ?: throw SshHostStoreException("authKind is not a number")
        val credentialRef = str("credentialRef")
        val keysEl = o.get("knownKeys") ?: throw SshHostStoreException("knownKeys is missing")
        if (!keysEl.isJsonArray) throw SshHostStoreException("knownKeys is not an array")
        val knownKeys = keysEl.asJsonArray.map { k ->
            val ko = if (k.isJsonObject) k.asJsonObject
            else throw SshHostStoreException("key is not an object")
            KnownHostKey(
                algorithm = ko.get("algorithm")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw SshHostStoreException("key algorithm is not a string"),
                fingerprint = ko.get("fingerprint")?.takeIf { it.isJsonPrimitive }?.asString
                    ?: throw SshHostStoreException("key fingerprint is not a string"),
            )
        }
        knownKeys.forEach { validateKey(it) }
        val createdAt = o.get("createdAt")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: throw SshHostStoreException("createdAt is missing")
        val updatedAt = o.get("updatedAt")?.takeIf { it.isJsonPrimitive }?.asLong
            ?: throw SshHostStoreException("updatedAt is missing")
        if (createdAt <= 0 || updatedAt <= 0) throw SshHostStoreException("timestamps are invalid")
        validateDisplayName(displayName)
        validateUsername(username)
        if (authKind !in 0..1) throw SshHostStoreException("authKind out of range")
        return SshHost(
            id, displayName, host, port, username, authKind, credentialRef,
            knownKeys, createdAt, updatedAt,
        )
    }

    private fun serialize(doc: Document): String {
        val obj = JsonObject().apply {
            addProperty("version", VERSION)
            add("hosts", JsonArray().apply {
                doc.hosts.forEach { r ->
                    add(
                        JsonObject().apply {
                            addProperty("id", r.id)
                            addProperty("displayName", r.displayName)
                            addProperty("host", r.host)
                            addProperty("port", r.port)
                            addProperty("username", r.username)
                            addProperty("authKind", r.authKind)
                            addProperty("credentialRef", r.credentialRef)
                            add("knownKeys", JsonArray().apply {
                                r.knownKeys.forEach { k ->
                                    add(
                                        JsonObject().apply {
                                            addProperty("algorithm", k.algorithm)
                                            addProperty("fingerprint", k.fingerprint)
                                        },
                                    )
                                }
                            })
                            addProperty("createdAt", r.createdAt)
                            addProperty("updatedAt", r.updatedAt)
                        },
                    )
                }
            })
            add("credentials", JsonObject().apply {
                doc.credentials.forEach { (k, v) -> addProperty(k, v) }
            })
        }
        return GSON.toJson(obj)
    }

    private fun quarantine(reason: String): Document {
        val quarantined =
            File(file.parentFile, "${file.nameWithoutExtension}.corrupt-${clock()}.${file.extension}")
        if (!file.renameTo(quarantined)) {
            throw SshHostStoreException("cannot quarantine $reason")
        }
        return Document(emptyList(), emptyMap())
    }

    private fun decodeBase64(s: String): ByteArray =
        try {
            Base64.getDecoder().decode(s)
        } catch (e: IllegalArgumentException) {
            throw SshHostStoreException("credential blob is not valid base64", e)
        }

    // --- validation ----------------------------------------------------------

    /** The endpoint id for a raw host string, after canonicalization. */
    fun endpointId(host: String, port: Int): String {
        SshEndpoint.validatePort(port)
        return SshEndpoint.endpointId(SshEndpoint.canonicalize(host).host, port)
    }

    // An empty label is legal: the UI shows username@host instead. Storing the
    // fallback would tie the label to the endpoint and change it on an edit.
    private fun validateDisplayName(name: String) {
        if (name.length > MAX_DISPLAY) throw SshHostStoreException("display name out of range")
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7f) {
                throw SshHostStoreException("display name has a control character")
            }
        }
    }

    // Windows accounts appear as MACHINE\user, DOMAIN\user, or user@domain, so
    // only control characters are rejected.
    private fun validateUsername(name: String) {
        if (name.isEmpty() || name.length > MAX_USER) {
            throw SshHostStoreException("username out of range")
        }
        for (c in name) {
            if (c.code < 0x20 || c.code == 0x7f) {
                throw SshHostStoreException("username has a control character")
            }
        }
    }

    private fun validateKey(key: KnownHostKey) {
        if (key.algorithm.isEmpty() || key.algorithm.length > MAX_ALGO) {
            throw SshHostStoreException("key algorithm out of range")
        }
        if (key.fingerprint.isEmpty() || key.fingerprint.length > MAX_FP) {
            throw SshHostStoreException("fingerprint out of range")
        }
        for (c in key.algorithm + key.fingerprint) {
            if (c.code < 0x20 || c.code == 0x7f) {
                throw SshHostStoreException("key has a control character")
            }
        }
    }

    companion object {
        const val FILE_NAME = "ssh-store.json"
        const val LEGACY_HOSTS_NAME = "ssh-hosts.json"
        const val LEGACY_SECRETS_NAME = "ssh-secrets.json"

        private const val VERSION = 2
        private const val MAX_DISPLAY = 100
        private const val MAX_USER = 255
        private const val MAX_ALGO = 100
        private const val MAX_FP = 200

        private val GSON = com.google.gson.GsonBuilder().setPrettyPrinting().create()
    }
}

// The durable write, behind an interface so tests can fail it at each stage and
// assert the previous document survives.
interface AtomicWriter {
    fun write(target: File, bytes: ByteArray)
}

class FileAtomicWriter : AtomicWriter {
    override fun write(target: File, bytes: ByteArray) {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            tmp.outputStream().use {
                it.write(bytes)
                it.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw SshHostStoreException("cannot replace ssh store file")
            }
        } catch (e: Exception) {
            tmp.delete()
            if (e is SshHostStoreException) throw e
            throw SshHostStoreException("cannot write ssh store file", e)
        }
    }

    private companion object {
        const val TMP_SUFFIX = ".tmp"
    }
}
