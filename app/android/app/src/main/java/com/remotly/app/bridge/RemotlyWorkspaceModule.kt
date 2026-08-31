package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.specs.NativeRemotlyWorkspaceSpec
import com.remotly.app.terminal.TerminalStore
import com.remotly.app.workspace.WorkspaceModule
import com.remotly.app.workspace.WorkspaceOpenParams
import com.remotly.app.workspace.WorkspaceStoreException
import com.remotly.app.workspace.WorkspaceValidationException

// Error codes the workspace bridge reports to JS.
internal object WorkspaceCodes {
    const val CONTEXT = -1
    const val STORE = -2
    const val VALIDATION = -3
}

// Pure validation and result mapping for the workspace module.
internal object WorkspaceBridge {
    // The one-shot open handoff accepts 1..64 character host ids, matching
    // the store's own id range.
    fun hostIdValid(hostId: String): Boolean = hostId.length in 1..64

    fun loadResultMap(json: String): Map<String, Any?> = mapOf("json" to json)

    fun takeOpenResultMap(hostId: String): Map<String, Any?> = mapOf("hostId" to hostId)
}

// The per-host workspace store (remotly.workspace.*). The document travels as
// one JSON string; the store owns validation of its shape.
class RemotlyWorkspaceModule(reactContext: ReactApplicationContext) :
    NativeRemotlyWorkspaceSpec(reactContext) {

    override fun load(hostId: String, promise: Promise) {
        val store = WorkspaceModule.store
        if (store == null) {
            promise.reject(WorkspaceCodes.STORE.toString(), "workspace store unavailable")
            return
        }
        try {
            promise.resolve(
                Arguments.makeNativeMap(
                    WorkspaceBridge.loadResultMap(store.load(hostId) ?: ""),
                ),
            )
        } catch (e: WorkspaceValidationException) {
            promise.reject(WorkspaceCodes.VALIDATION.toString(), e.message ?: "invalid host id")
        } catch (e: WorkspaceStoreException) {
            promise.reject(WorkspaceCodes.STORE.toString(), e.message ?: "workspace load failed")
        }
    }

    override fun save(hostId: String, json: String, promise: Promise) {
        val store = WorkspaceModule.store
        if (store == null) {
            promise.reject(WorkspaceCodes.STORE.toString(), "workspace store unavailable")
            return
        }
        try {
            store.save(hostId, json)
            promise.resolve(null)
        } catch (e: WorkspaceValidationException) {
            // Invalid documents are the caller's mistake, not a storage fault.
            promise.reject(
                WorkspaceCodes.VALIDATION.toString(),
                e.message ?: "invalid workspace document",
            )
        } catch (e: WorkspaceStoreException) {
            promise.reject(WorkspaceCodes.STORE.toString(), e.message ?: "workspace save failed")
        }
    }

    override fun clear(hostId: String, promise: Promise) {
        val store = WorkspaceModule.store
        if (store == null) {
            promise.reject(WorkspaceCodes.STORE.toString(), "workspace store unavailable")
            return
        }
        try {
            store.clear(hostId)
            promise.resolve(null)
        } catch (e: WorkspaceValidationException) {
            promise.reject(WorkspaceCodes.VALIDATION.toString(), e.message ?: "invalid host id")
        } catch (e: WorkspaceStoreException) {
            promise.reject(WorkspaceCodes.STORE.toString(), e.message ?: "workspace clear failed")
        }
    }

    // Frees the terminal retained for a session. The handle outlives the view
    // so its scrollback survives navigation; closing the tab is what ends it.
    override fun releaseTerminal(sessionId: String, promise: Promise) {
        TerminalStore.release(sessionId)
        promise.resolve(null)
    }

    override fun open(hostId: String, promise: Promise) {
        if (!WorkspaceBridge.hostIdValid(hostId)) {
            promise.reject(WorkspaceCodes.VALIDATION.toString(), "host id out of range")
            return
        }
        WorkspaceOpenParams.store(hostId)
        promise.resolve(null)
    }

    override fun takeOpen(promise: Promise) {
        promise.resolve(
            Arguments.makeNativeMap(
                WorkspaceBridge.takeOpenResultMap(WorkspaceOpenParams.take() ?: ""),
            ),
        )
    }
}
