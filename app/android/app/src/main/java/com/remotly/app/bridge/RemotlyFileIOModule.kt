package com.remotly.app.bridge

import android.net.Uri
import androidx.fragment.app.FragmentActivity
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.remotly.app.fileio.FileModule
import com.remotly.app.fileio.FilePickFragment
import com.remotly.app.specs.NativeRemotlyFileIOSpec
import com.remotly.app.transport.Base64Std

// One MiB is the maximum chunk; read/write clamp to it so a single bridge call
// never moves an unbounded buffer.
private const val MAX_CHUNK = 1 shl 20

// The file I/O bridge (remotly.file.*). The app never holds a local path: pick
// launches the system picker (a FragmentActivity result via FilePickFragment)
// and the chosen content URI arrives through onPicked/onSink; readChunk and
// writeChunk move chunks through the ContentResolver. Every ContentResolver and
// picker call is defensive: a denied grant or missing stream is a coded
// rejection, never a crash.
class RemotlyFileIOModule(reactContext: ReactApplicationContext) :
    NativeRemotlyFileIOSpec(reactContext) {

    // TurboModule methods run on the React JS thread, but a FragmentManager
    // transaction must run on the main thread, so the whole pick is posted
    // there. Without this every pick threw "Must be called from main thread of
    // fragment host" and the picker never appeared.
    override fun pick(mode: String, name: String, promise: Promise) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pickOnMainThread(mode, name, promise)
        }
    }

    private fun pickOnMainThread(mode: String, name: String, promise: Promise) {
        val current = reactApplicationContext.currentActivity
        val activity = current as? FragmentActivity
        if (activity == null || activity.isFinishing) {
            // Naming what was actually there: "no activity" hid the difference
            // between no foreground activity and one of the wrong type.
            val detail = when {
                current == null -> "no foreground activity"
                activity == null -> "activity is ${current.javaClass.name}, not a FragmentActivity"
                else -> "activity is finishing"
            }
            android.util.Log.e("RemotlyFileIO", "picker unavailable: $detail")
            promise.reject(BridgeCodes.FAIL.toString(), "picker unavailable: $detail")
            return
        }
        val suggested = name.takeIf { it.isNotBlank() } ?: "download"
        val tag = when (mode) {
            "download" -> TAG_DOWNLOAD
            "folder" -> TAG_FOLDER
            else -> TAG_UPLOAD
        }
        // A folder is chosen as a download destination, so its result travels
        // the same event as one.
        val isDownload = mode == "download" || mode == "folder"
        // Replace any in-flight picker for this mode so a stale result cannot
        // answer a new pick.
        val fm = activity.supportFragmentManager
        fm.findFragmentByTag(tag)?.let { fm.beginTransaction().remove(it).commitNow() }
        val fragment = FilePickFragment(mode, suggested) { uri, fileName, size ->
            val payload = Arguments.makeNativeMap(
                mapOf("uri" to (uri ?: ""), "name" to (fileName ?: ""), "size" to size),
            )
            if (isDownload) emitOnSink(payload) else emitOnPicked(payload)
        }
        try {
            fm.beginTransaction().add(fragment, tag).commitNow()
        } catch (e: IllegalStateException) {
            // commitNow throws once the host has saved its state, which is
            // ordinary if the pick races a configuration change.
            android.util.Log.e("RemotlyFileIO", "picker commit failed", e)
            promise.reject(BridgeCodes.FAIL.toString(), e.message ?: "picker commit failed")
            return
        }
        promise.resolve(null)
    }

    override fun readChunk(uri: String, offset: Double, maxBytes: Double, promise: Promise) {
        if (uri.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "uri is required")
            return
        }
        val limit = maxBytes.toInt().coerceIn(1, MAX_CHUNK)
        val (bytes, n) = try {
            FileModule.readChunk(reactApplicationContext, Uri.parse(uri), offset.toLong(), limit)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.FAIL.toString(), "read failed")
            return
        }
        promise.resolve(
            Arguments.makeNativeMap(
                mapOf("data" to Base64Std.encode(bytes.copyOf(n)), "bytesRead" to n),
            ),
        )
    }

    override fun writeChunk(uri: String, data: String, promise: Promise) {
        if (uri.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "uri is required")
            return
        }
        val bytes = try {
            if (data.isEmpty()) ByteArray(0) else Base64Std.decode(data)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "data is not valid base64")
            return
        }
        val n = try {
            FileModule.writeChunk(reactApplicationContext, Uri.parse(uri), bytes)
        } catch (e: Exception) {
            promise.reject(BridgeCodes.FAIL.toString(), "write failed")
            return
        }
        promise.resolve(Arguments.makeNativeMap(mapOf("bytesWritten" to n)))
    }

    override fun release(uri: String, promise: Promise) {
        if (uri.isNotBlank()) {
            runCatching { FileModule.release(reactApplicationContext, Uri.parse(uri)) }
        }
        promise.resolve(null)
    }

    // Removes a destination the picker created for a download that then failed
    // or was cancelled. Without this a partial file is left behind under the
    // real name, which reads as a complete download that is quietly truncated.
    override fun pickFolder(promise: Promise) {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            pickOnMainThread("folder", "", promise)
        }
    }

    override fun hasFolderAccess(treeUri: String, promise: Promise) {
        if (treeUri.isBlank()) {
            promise.resolve(false)
            return
        }
        val held = runCatching {
            reactApplicationContext.contentResolver.persistedUriPermissions.any {
                it.uri.toString() == treeUri && it.isWritePermission && it.isReadPermission
            }
        }.getOrDefault(false)
        promise.resolve(held)
    }

    override fun findInFolder(treeUri: String, name: String, promise: Promise) {
        if (treeUri.isBlank() || name.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "folder and name are required")
            return
        }
        val found = runCatching {
            FileModule.findInTree(reactApplicationContext, Uri.parse(treeUri), name)
        }.getOrNull()
        promise.resolve(found?.toString() ?: "")
    }

    override fun createInFolder(treeUri: String, name: String, promise: Promise) {
        if (treeUri.isBlank() || name.isBlank()) {
            promise.reject(BridgeCodes.INVALID_PARAM.toString(), "folder and name are required")
            return
        }
        val created = runCatching {
            FileModule.createInTree(reactApplicationContext, Uri.parse(treeUri), name)
        }.getOrNull()
        if (created == null) {
            promise.reject(BridgeCodes.FAIL.toString(), "could not create the file")
            return
        }
        promise.resolve(created.toString())
    }

    override fun discard(uri: String, promise: Promise) {
        if (uri.isNotBlank()) {
            runCatching { FileModule.discard(reactApplicationContext, Uri.parse(uri)) }
        }
        promise.resolve(null)
    }

    // Holds the transfer service up for a transfer the JS side is driving,
    // which is how a daemon transfer gets the same background guarantee an
    // SFTP one has. The SFTP path reports through SftpTransfers instead,
    // because its work runs on a native thread the JS side cannot see.
    override fun setTransfersActive(active: Boolean, promise: Promise) {
        runCatching {
            com.remotly.app.ssh.SftpTransferService.setActive(
                reactApplicationContext,
                com.remotly.app.ssh.SftpTransferService.OWNER_DAEMON,
                active,
            )
        }
        promise.resolve(null)
    }

    private companion object {
        const val TAG_UPLOAD = "remotly-file-upload"
        const val TAG_DOWNLOAD = "remotly-file-download"
        const val TAG_FOLDER = "remotly-file-folder"
    }
}
