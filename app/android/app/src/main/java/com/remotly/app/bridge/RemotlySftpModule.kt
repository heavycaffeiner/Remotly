package com.remotly.app.bridge

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.google.gson.Gson
import android.util.Base64
import com.remotly.app.ssh.SftpBridge
import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ssh.SftpTransfers
import com.remotly.app.ssh.SshHostStoreException
import com.remotly.app.specs.NativeRemotlySftpSpec

// Pure status and entry serialization for the SFTP module, kept free of Android
// types so the JVM unit tests exercise them directly. Entries and host keys
// travel as JSON strings so the bridge carries no nested models.
internal object SftpViewBridge {
    private val gson = Gson()

    fun entriesJson(entries: List<SftpEntry>): String = gson.toJson(entries)

    fun entryJson(entry: SftpEntry): String = gson.toJson(entry)

    fun hostKeyJson(active: SftpBridge.Active?): String? =
        active?.prompt?.let { gson.toJson(it.info) }

    fun statusMap(active: SftpBridge.Active?): Map<String, Any?> = buildMap {
        put("state", active?.state?.name ?: "NONE")
        hostKeyJson(active)?.let { put("hostKey", it) }
        active?.prompt?.let { put("changed", it.changed) }
        active?.code?.takeIf { it.isNotEmpty() }?.let { put("code", it) }
        active?.message?.takeIf { it.isNotEmpty() }?.let { put("message", it) }
    }
}

// The SFTP browser bridge (remotly.sftp.*). One live connection per host id
// (the SftpBridge invariant), driven by the Go sshcore SFTP client. Metadata and
// transfer ops block on a network round trip, so they run on SftpBridge's worker
// pool and complete their promise from the returned callback.
class RemotlySftpModule(reactContext: ReactApplicationContext) :
    NativeRemotlySftpSpec(reactContext) {

    override fun connect(hostId: String, promise: Promise) {
        if (hostId.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "hostId is required")
            return
        }
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "connect failed") },
                )
            },
            block = { SftpBridge.connect(hostId) },
        )
    }

    override fun status(hostId: String, promise: Promise) {
        promise.resolve(Arguments.makeNativeMap(SftpViewBridge.statusMap(SftpBridge.status(hostId))))
    }

    override fun hostKey(hostId: String, accept: Boolean, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "host key failed") },
                )
            },
            block = { SftpBridge.decideHostKey(hostId, accept) },
        )
    }

    override fun list(hostId: String, path: String, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { entries ->
                        promise.resolve(
                            Arguments.makeNativeMap(
                                mapOf("entries" to SftpViewBridge.entriesJson(entries)),
                            ),
                        )
                    },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "list failed") },
                )
            },
            block = { SftpBridge.list(hostId, path) },
        )
    }

    override fun stat(hostId: String, path: String, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { entry ->
                        promise.resolve(
                            Arguments.makeNativeMap(
                                mapOf("entry" to SftpViewBridge.entryJson(entry)),
                            ),
                        )
                    },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "stat failed") },
                )
            },
            block = { SftpBridge.stat(hostId, path) },
        )
    }

    override fun mkdir(hostId: String, path: String, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "mkdir failed") },
                )
            },
            block = { SftpBridge.mkdir(hostId, path) },
        )
    }

    override fun rename(hostId: String, from: String, to: String, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "rename failed") },
                )
            },
            block = { SftpBridge.rename(hostId, from, to) },
        )
    }

    override fun remove(hostId: String, path: String, isDir: Boolean, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "remove failed") },
                )
            },
            block = {
                if (isDir) SftpBridge.removeDir(hostId, path) else SftpBridge.removeFile(hostId, path)
            },
        )
    }

    override fun close(hostId: String, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "close failed") },
                )
            },
            block = { SftpBridge.close(hostId) },
        )
    }

    // --- transfers ---------------------------------------------------------

    override fun startUpload(hostId: String, path: String, conflict: String, promise: Promise) {
        if (path.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "path is required")
            return
        }
        // Anything but an explicit replace refuses to clobber an existing file.
        val replace = conflict == "replace"
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { id -> promise.resolve(id) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "upload failed") },
                )
            },
            block = { SftpBridge.startUpload(hostId, path, replace) },
        )
    }

    override fun writeChunk(id: String, offset: Double, data: String, promise: Promise) {
        val bytes = try {
            Base64.decode(data, Base64.DEFAULT)
        } catch (e: IllegalArgumentException) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "chunk is not valid base64")
            return
        }
        if (offset < 0) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "offset must not be negative")
            return
        }
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { n -> promise.resolve(n.toDouble()) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "write failed") },
                )
            },
            block = { SftpTransfers.writeChunk(id, offset.toLong(), bytes) },
        )
    }

    override fun completeUpload(id: String, promise: Promise) {
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { promise.resolve(null) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "complete failed") },
                )
            },
            block = { SftpTransfers.completeUpload(id) },
        )
    }

    override fun startDownload(hostId: String, path: String, promise: Promise) {
        if (path.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "path is required")
            return
        }
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { id -> promise.resolve(id) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "download failed") },
                )
            },
            block = { SftpBridge.startDownload(hostId, path) },
        )
    }

    override fun startUploadResume(hostId: String, path: String, promise: Promise) {
        if (path.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "path is required")
            return
        }
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { id -> promise.resolve(id) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "upload failed") },
                )
            },
            block = { SftpBridge.startUploadResume(hostId, path) },
        )
    }

    override fun startDownloadToUri(
        hostId: String,
        path: String,
        uri: String,
        resumeFrom: Double,
        promise: Promise,
    ) {
        if (path.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "path is required")
            return
        }
        if (uri.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "uri is required")
            return
        }
        val parsed = try {
            android.net.Uri.parse(uri)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "uri is not valid")
            return
        }
        // The URI comes from the system picker and is only ever written
        // through the ContentResolver, which enforces the grant the picker
        // issued. Nothing here derives a filesystem path from it.
        val context = reactApplicationContext
        SftpBridge.execute(
            onResult = { r ->
                r.fold(
                    { id -> promise.resolve(id) },
                    { e -> promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "download failed") },
                )
            },
            block = {
                SftpBridge.startDownloadToUri(
                    hostId,
                    path,
                    context,
                    parsed,
                    resumeFrom.toLong().coerceAtLeast(0L),
                )
            },
        )
    }

    override fun cancelTransfer(id: String, promise: Promise) {
        SftpTransfers.cancel(id)
        promise.resolve(null)
    }

    override fun initialize() {
        super.initialize()
        // Lets transfers hold a foreground service up, so leaving the screen or
        // backgrounding the app does not stop them partway.
        SftpTransfers.setServiceContext(reactApplicationContext)
        SftpTransfers.setSink { id, offset, data, done, error ->
            emitOnTransfer(
                Arguments.makeNativeMap(
                    buildMap<String, Any?> {
                        put("id", id)
                        put("offset", offset.toDouble())
                        data?.let { put("data", Base64.encodeToString(it, Base64.NO_WRAP)) }
                        done?.let { put("done", it.toDouble()) }
                        error?.let { put("error", it) }
                    },
                ),
            )
        }
    }

    override fun invalidate() {
        // The sink goes, because the JS side it delivers to is going. The
        // transfers themselves stay: they run on their own threads, hold the
        // foreground service up, and finish writing whether or not anything is
        // listening. Cancelling them here killed every download the moment the
        // React context was torn down, which is what happens when the app is
        // backgrounded and reclaimed.
        SftpTransfers.setSink(null)
        super.invalidate()
    }
}
