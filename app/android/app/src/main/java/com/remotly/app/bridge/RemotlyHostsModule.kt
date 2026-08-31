package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.remotly.app.hosts.AddResult
import com.remotly.app.hosts.HostHint
import com.remotly.app.hosts.HostRecord
import com.remotly.app.hosts.HostStoreException
import com.remotly.app.hosts.HostsModule
import com.remotly.app.specs.NativeRemotlyHostsSpec

// Pure parsing and result mapping for the hosts module.
internal object HostsBridge {
    private val gson = Gson()
    private val hintsType = object : TypeToken<List<HostHint>>() {}.type

    // Gson returns null for both unparseable input and literal null, which is
    // exactly the "not a JSON array" failure the bridge reports.
    fun parseHints(hints: String): List<HostHint>? =
        try {
            gson.fromJson<List<HostHint>>(hints, hintsType)
        } catch (e: Exception) {
            null
        }

    fun toJson(hosts: List<HostRecord>): String = gson.toJson(hosts)

    fun addResultMap(id: String, duplicate: Boolean): Map<String, Any?> =
        mapOf("id" to id, "duplicate" to duplicate)

    fun listResultMap(hostsJson: String): Map<String, Any?> = mapOf("hosts" to hostsJson)
}

// The persisted daemon-host store (remotly.hosts.*). Records travel as one
// JSON string so the bridge carries no nested object models.
class RemotlyHostsModule(reactContext: ReactApplicationContext) :
    NativeRemotlyHostsSpec(reactContext) {

    override fun add(
        daemonName: String,
        daemonPub: String,
        hints: String,
        promise: Promise,
    ) {
        val store = HostsModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "host store unavailable")
            return
        }
        if (daemonName.isBlank() || daemonPub.isBlank()) {
            promise.reject(
                BridgeCodes.INVALID_PARAM.toString(),
                "daemonName and daemonPub are required",
            )
            return
        }
        val parsed = HostsBridge.parseHints(hints)
        if (parsed == null) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "hints must be a JSON array")
            return
        }
        val result: AddResult = try {
            store.add(daemonName, daemonPub, parsed)
        } catch (e: HostStoreException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), e.message ?: "cannot add host")
            return
        }
        promise.resolve(
            Arguments.makeNativeMap(HostsBridge.addResultMap(result.id, result.duplicate)),
        )
    }

    override fun list(promise: Promise) {
        val store = HostsModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "host store unavailable")
            return
        }
        try {
            promise.resolve(
                Arguments.makeNativeMap(HostsBridge.listResultMap(HostsBridge.toJson(store.list()))),
            )
        } catch (e: HostStoreException) {
            promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "host store failed")
        }
    }

    override fun remove(id: String, promise: Promise) {
        val store = HostsModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "host store unavailable")
            return
        }
        if (id.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "id is required")
            return
        }
        try {
            if (!store.remove(id)) {
                promise.reject(BridgeCodes.FAIL.toString(), "no such host")
                return
            }
        } catch (e: HostStoreException) {
            promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "host store failed")
            return
        }
        promise.resolve(null)
    }

    override fun touch(id: String, promise: Promise) {
        val store = HostsModule.store
        if (store == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "host store unavailable")
            return
        }
        if (id.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "id is required")
            return
        }
        try {
            if (!store.touch(id)) {
                promise.reject(BridgeCodes.FAIL.toString(), "no such host")
                return
            }
        } catch (e: HostStoreException) {
            promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "host store failed")
            return
        }
        promise.resolve(null)
    }
}
