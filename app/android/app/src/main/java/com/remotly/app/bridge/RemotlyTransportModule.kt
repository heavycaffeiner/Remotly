package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReadableMap
import com.remotly.app.specs.NativeRemotlyTransportSpec
import com.remotly.app.transport.Base64Std
import com.remotly.app.transport.TransportHub

// Error codes the transport bridge reports to JS, matching the
// kindFromBridgeCode mapping in src/lib/errors.ts: 4000-4004 are protocol
// close codes, 1006 an abnormal close, 0 a generic bridge failure, -3 an
// invalid parameter.
internal object BridgeCodes {
    const val FAIL = 0
    const val INVALID_PARAM = -3
}

// Pure parameter parsing and result mapping for the transport module, kept
// free of Android types so the JVM unit tests exercise them directly.
internal object TransportBridge {
    // Channel ids are 32-bit unsigned; 0 is reserved for the control channel,
    // so user channels start at 1. JS numbers arrive as doubles.
    fun parseChannelId(value: Double): Long? {
        if (value.isNaN() || value.isInfinite()) return null
        val v = value.toLong()
        if (v.toDouble() != value) return null
        return if (v in 1L..0xFFFFFFFFL) v else null
    }

    fun connectResultMap(daemonName: String, daemonPub: String): Map<String, Any?> =
        mapOf("daemonName" to daemonName, "daemonPub" to daemonPub)

    fun statusMap(status: TransportHub.Status): Map<String, Any?> = buildMap {
        put("connected", status.connected)
        put("state", status.state)
        status.daemonName?.let { put("daemonName", it) }
        status.daemonPub?.let { put("daemonPub", it) }
    }

    fun controlResultMap(response: String): Map<String, Any?> = mapOf("response" to response)
}

// One host's secure connection. The hub runs its own threads, results marshal
// back through the promise, and the event sink is bound per host so every
// payload carries the hostId.
class RemotlyTransportModule(reactContext: ReactApplicationContext) :
    NativeRemotlyTransportSpec(reactContext) {

    init {
        // The hub outlives the JS runtime: after a reload, hosts that are
        // still connected need their event sink re-attached to this module.
        TransportHub.activeHostIds().forEach { bindEvents(it) }
    }

    override fun connect(
        hostId: String,
        target: String,
        options: ReadableMap,
        promise: Promise,
    ) {
        if (hostId.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "hostId is required")
            return
        }
        if (target.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "target is required")
            return
        }
        bindEvents(hostId)
        TransportHub.connect(
            hostId = hostId,
            target = target,
            daemonPubB64 = options.optStringOrNull("daemonPub"),
            tokenIDB64 = options.optStringOrNull("tokenID"),
            secretB64 = options.optStringOrNull("psk"),
            onSuccess = { daemonName, daemonPub ->
                promise.resolve(
                    Arguments.makeNativeMap(
                        TransportBridge.connectResultMap(daemonName, daemonPub),
                    ),
                )
            },
            onFailure = { code, reason ->
                promise.reject(code.toString(), reason)
            },
            relayTarget = options.optStringOrNull("relayTarget"),
            relayIdB64 = options.optStringOrNull("relayId"),
            relayOnly = options.optRelayOnly(),
        )
    }

    override fun close(hostId: String, promise: Promise) {
        TransportHub.close(hostId)
        promise.resolve(null)
    }

    override fun status(hostId: String, promise: Promise) {
        val status = TransportHub.status(hostId)
        promise.resolve(Arguments.makeNativeMap(TransportBridge.statusMap(status)))
    }

    override fun control(hostId: String, request: String, promise: Promise) {
        if (request.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "request is required")
            return
        }
        TransportHub.sendControl(hostId, request) { result ->
            result.fold(
                onSuccess = { response ->
                    promise.resolve(
                        Arguments.makeNativeMap(TransportBridge.controlResultMap(response)),
                    )
                },
                onFailure = { e ->
                    promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "control failed")
                },
            )
        }
    }

    override fun writeTerm(
        hostId: String,
        channelId: Double,
        data: String,
        promise: Promise,
    ) {
        val id = TransportBridge.parseChannelId(channelId)
        if (id == null) {
            promise.reject(
                BridgeCodes.INVALID_PARAM.toString(),
                "channelId must be a positive channel id",
            )
            return
        }
        val bytes = try {
            Base64Std.decode(data)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "data is not valid base64")
            return
        }
        if (bytes.isEmpty()) {
            promise.resolve(null)
            return
        }
        if (!TransportHub.writeTerm(hostId, id, bytes)) {
            promise.reject(BridgeCodes.FAIL.toString(), "not connected")
            return
        }
        promise.resolve(null)
    }

    override fun openFile(hostId: String, channelId: Double, promise: Promise) {
        val id = TransportBridge.parseChannelId(channelId)
        if (id == null) {
            promise.reject(
                BridgeCodes.INVALID_PARAM.toString(),
                "channelId must be a positive channel id",
            )
            return
        }
        TransportHub.openFile(hostId, id)
        promise.resolve(null)
    }

    override fun writeFile(
        hostId: String,
        channelId: Double,
        data: String,
        promise: Promise,
    ) {
        val id = TransportBridge.parseChannelId(channelId)
        if (id == null) {
            promise.reject(
                BridgeCodes.INVALID_PARAM.toString(),
                "channelId must be a positive channel id",
            )
            return
        }
        val bytes = try {
            Base64Std.decode(data)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "data is not valid base64")
            return
        }
        if (bytes.isEmpty()) {
            promise.resolve(null)
            return
        }
        if (!TransportHub.writeFile(hostId, id, bytes)) {
            promise.reject(BridgeCodes.FAIL.toString(), "not connected")
            return
        }
        promise.resolve(null)
    }

    private fun bindEvents(hostId: String) {
        TransportHub.setEventSink(hostId) { name, data -> emitEvent(name, data) }
    }

    // The hub posts events on the main thread, and the codegen emitters are
    // safe to call from any thread, so no extra hop is added.
    private fun emitEvent(name: String, data: Map<String, Any?>) {
        val map = Arguments.makeNativeMap(data)
        when (name) {
            "connected" -> emitOnConnected(map)
            "disconnected" -> emitOnDisconnected(map)
            "sessionUpdate" -> emitOnSessionUpdate(map)
            "channelClose" -> emitOnChannelClose(map)
            "replayComplete" -> emitOnReplayComplete(map)
            "termData" -> emitOnTermData(map)
            "fileData" -> emitOnFileData(map)
            "sessionEvent" -> emitOnSessionEvent(map)
        }
    }

    private fun ReadableMap.optStringOrNull(key: String): String? =
        if (hasKey(key) && !isNull(key)) getString(key) else null

    // relayOnly is optional and defaults to false.
    private fun ReadableMap.optRelayOnly(): Boolean =
        if (hasKey("relayOnly") && !isNull("relayOnly")) getBoolean("relayOnly") else false
}
