package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.google.gson.Gson
import com.remotly.app.ssh.SshCredential
import com.remotly.app.ssh.SshEndpoint
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStore
import com.remotly.app.ssh.SshHostStoreException
import com.remotly.app.ssh.SshModule
import com.remotly.app.ssh.SshStoreInconsistentException
import sshcore.ProbeConfig
import sshcore.Sshcore
import com.remotly.app.specs.NativeRemotlySshHostSpec
import java.util.Base64

// Pure credential building and view mapping for the SSH host module, kept free
// of Android types so the JVM unit tests exercise them directly. The view
// omits the credential reference and carries no secret bytes.
internal object SshHostBridge {
    private val gson = Gson()

    data class KeyView(val algorithm: String, val fingerprint: String)

    data class HostView(
        val id: String,
        val displayName: String,
        val host: String,
        val port: Int,
        val username: String,
        val authKind: Int,
        val hasCredential: Boolean,
        val knownKeys: List<KeyView>,
        val createdAt: Long,
        val updatedAt: Long,
    )

    fun toView(host: SshHost): HostView =
        HostView(
            id = host.id,
            displayName = host.displayName,
            host = host.host,
            port = host.port,
            username = host.username,
            authKind = host.authKind,
            hasCredential = host.credentialRef.isNotEmpty(),
            knownKeys = host.knownKeys.map { KeyView(it.algorithm, it.fingerprint) },
            createdAt = host.createdAt,
            updatedAt = host.updatedAt,
        )

    fun hostsJson(hosts: List<SshHost>): String = gson.toJson(hosts.map { toView(it) })

    fun hostJson(host: SshHost): String = gson.toJson(toView(host))

    // Builds a credential from the bridge params. useKey selects key auth
    // (privateKey, base64) versus password auth; the password bytes are not
    // retained after the call.
    fun credential(
        useKey: Boolean,
        password: String?,
        privateKey: String?,
        passphrase: String?,
    ): SshCredential {
        return if (useKey) {
            val pem = privateKey
            if (pem.isNullOrBlank()) throw SshHostStoreException("key auth needs a private key")
            SshCredential.Key(
                privateKey = Base64.getDecoder().decode(pem),
                passphrase = passphrase?.takeIf { it.isNotEmpty() }?.toByteArray(),
            )
        } else {
            val pw = password
            if (pw.isNullOrBlank()) throw SshHostStoreException("password auth needs a password")
            SshCredential.Password(pw.toByteArray())
        }
    }
}

// The SSH host store (remotly.sshhost.*). Records travel as one JSON string so
// the bridge carries no nested models; the credential crosses in plaintext and
// is written to the keystore-backed SecretStore natively.
// ReadableMap has no opt* helpers; this reads an optional boolean defaulting to
// [default] when the key is absent or null.
private fun com.facebook.react.bridge.ReadableMap.optBoolean(key: String, default: Boolean): Boolean =
    if (hasKey(key) && !isNull(key)) getBoolean(key) else default

// Null means the caller did not send the field, which a patch reads as
// "leave unchanged". An empty string is a real value: it clears a label.
private fun com.facebook.react.bridge.ReadableMap.optString(key: String): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else null

// Storage is internally inconsistent. Distinct from a validation failure so the
// UI can tell the user their data was preserved rather than rejected.
private const val SSH_STORE_INCONSISTENT = "ssh_store_inconsistent"

// A test connection is a foreground action the user is waiting on, so it is
// bounded well below the interactive connect timeout.
private const val PROBE_TIMEOUT_MS = 10_000L

class RemotlySshHostModule(reactContext: ReactApplicationContext) :
    NativeRemotlySshHostSpec(reactContext) {

    override fun list(promise: Promise) {
        val store = SshModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "ssh store unavailable")
            return
        }
        try {
            promise.resolve(
                Arguments.makeNativeMap(mapOf("hosts" to SshHostBridge.hostsJson(store.list()))),
            )
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "list failed")
        }
    }

    override fun add(params: ReadableMap, promise: Promise) {
        val store = SshModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "ssh store unavailable")
            return
        }
        val cred = try {
            SshHostBridge.credential(
                useKey = params.optBoolean("useKey", false),
                password = params.getString("password"),
                privateKey = params.getString("privateKey"),
                passphrase = params.getString("passphrase"),
            )
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "bad credential")
            return
        }
        try {
            val record = store.add(
                displayName = params.getString("displayName").orEmpty(),
                host = params.getString("host").orEmpty(),
                port = params.getInt("port"),
                username = params.getString("username").orEmpty(),
                credential = cred,
            )
            promise.resolve(Arguments.makeNativeMap(mapOf("host" to SshHostBridge.hostJson(record))))
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "add failed")
        }
    }

    override fun update(params: ReadableMap, promise: Promise) {
        val store = SshModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "ssh store unavailable")
            return
        }
        val hostId = params.getString("hostId").orEmpty()
        // Only a field the caller actually sent is patched. Absent means leave
        // it alone, which is what keeps a label edit from touching the
        // credential or the accepted host keys.
        val credential = if (params.hasKey("replaceCredential") &&
            !params.isNull("replaceCredential") &&
            params.getBoolean("replaceCredential")
        ) {
            try {
                SshHostBridge.credential(
                    useKey = params.optBoolean("useKey", false),
                    password = params.getString("password"),
                    privateKey = params.getString("privateKey"),
                    passphrase = params.getString("passphrase"),
                )
            } catch (e: SshHostStoreException) {
                promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "bad credential")
                return
            }
        } else {
            null
        }
        val patch = SshHostStore.HostPatch(
            displayName = params.optString("displayName"),
            host = params.optString("host"),
            port = if (params.hasKey("port") && !params.isNull("port")) params.getInt("port") else null,
            username = params.optString("username"),
            credential = credential,
            clearKnownKeys = params.optBoolean("clearKnownKeys", false),
        )
        try {
            val record = store.update(hostId, patch)
            promise.resolve(Arguments.makeNativeMap(mapOf("host" to SshHostBridge.hostJson(record))))
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "update failed")
        } catch (e: SshStoreInconsistentException) {
            promise.reject(SSH_STORE_INCONSISTENT, e.message ?: "ssh storage is inconsistent")
        }
    }

    override fun setCredential(params: ReadableMap, promise: Promise) {
        val store = SshModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "ssh store unavailable")
            return
        }
        val hostId = params.getString("hostId").orEmpty()
        val cred = try {
            SshHostBridge.credential(
                useKey = params.optBoolean("useKey", false),
                password = params.getString("password"),
                privateKey = params.getString("privateKey"),
                passphrase = params.getString("passphrase"),
            )
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "bad credential")
            return
        }
        try {
            store.setCredential(hostId, cred)
            promise.resolve(null)
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "set credential failed")
        }
    }

    override fun rename(hostId: String, displayName: String, promise: Promise) {
        val store = SshModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "ssh store unavailable")
            return
        }
        try {
            store.rename(hostId, displayName)
            promise.resolve(null)
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "rename failed")
        }
    }

    /**
     * Tests an endpoint and credential without saving anything.
     *
     * The credential is not persisted, and a host key seen here is reported but
     * never pinned: trusting a key stays an explicit act in the normal connect
     * flow. A key that contradicts one already accepted fails closed.
     */
    override fun testConnection(params: ReadableMap, promise: Promise) {
        val cred = try {
            SshHostBridge.credential(
                useKey = params.optBoolean("useKey", false),
                password = params.getString("password"),
                privateKey = params.getString("privateKey"),
                passphrase = params.getString("passphrase"),
            )
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "bad credential")
            return
        }

        val host = params.getString("host").orEmpty()
        val port = if (params.hasKey("port") && !params.isNull("port")) params.getInt("port") else 22
        val username = params.getString("username").orEmpty()

        // Canonicalize the same way a save would, so the probe tests the
        // endpoint that would actually be stored.
        val canonicalHost = try {
            SshEndpoint.canonicalize(host).host
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "invalid host")
            return
        }

        // An existing host's accepted keys, so a changed key is caught here too.
        val expected = params.getString("hostId")
            ?.let { id -> SshModule.store?.get(id) }
            ?.knownKeys
            ?.joinToString("\n") { it.fingerprint }
            .orEmpty()

        val cfg = ProbeConfig()
        cfg.host = canonicalHost
        cfg.port = port.toLong()
        cfg.user = username
        when (cred) {
            is SshCredential.Password -> cfg.password = String(cred.value, Charsets.UTF_8)
            is SshCredential.Key -> {
                cfg.privateKey = cred.privateKey
                cfg.passphrase = cred.passphrase ?: ByteArray(0)
            }
        }
        cfg.expectedFingerprints = expected
        cfg.timeoutMillis = PROBE_TIMEOUT_MS

        try {
            val res = Sshcore.probe(cfg)
            promise.resolve(
                Arguments.createMap().apply {
                    putBoolean("ok", res.ok)
                    putString("code", res.code)
                    putString("stage", res.stage)
                    putString("message", res.message)
                    putString("hostKeyAlgorithm", res.hostKeyAlgorithm)
                    putString("hostKeyFingerprint", res.hostKeyFingerprint)
                    putBoolean("hostKeyKnown", res.hostKeyKnown)
                    putBoolean("hostKeyChanged", res.hostKeyChanged)
                },
            )
        } catch (e: Exception) {
            promise.reject(BridgeCodes.FAIL.toString(), "test connection failed")
        } finally {
            // The plaintext copies this call made are cleared; the Go side
            // clears its own.
            when (cred) {
                is SshCredential.Password -> cred.value.fill(0)
                is SshCredential.Key -> {
                    cred.privateKey.fill(0)
                    cred.passphrase?.fill(0)
                }
            }
        }
    }

    override fun remove(hostId: String, promise: Promise) {
        val store = SshModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "ssh store unavailable")
            return
        }
        try {
            store.remove(hostId)
            promise.resolve(null)
        } catch (e: SshHostStoreException) {
            promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "remove failed")
        }
    }
}
