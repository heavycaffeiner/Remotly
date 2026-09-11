package com.remotly.app.fileio

import android.net.Uri
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import java.io.InputStream
import java.io.OutputStream

// Native file I/O for the transfer UI (M4-06). The app never derives local
// paths from remote names: it only ever holds content URIs handed back by the
// system picker (ACTION_OPEN_DOCUMENT for upload, ACTION_CREATE_DOCUMENT for
// download), and reads/writes them through the ContentResolver. Streams are
// cached per URI so chunked reads/writes stay sequential; a position mismatch
// (a resume) reopens the stream and skips to the requested offset.

object FileModule {
    private val lock = Any()
    private val inputs = HashMap<String, InputStream>()
    private val inputPos = HashMap<String, Long>()
    private val outputs = HashMap<String, OutputStream>()

    /**
     * Looks up a name in a picked download folder.
     *
     * The create-document picker resolves a collision itself by renaming, so
     * the app never sees that one happened and cannot offer a choice. With a
     * folder the user granted once, the directory can be read first and the
     * decision put back in their hands.
     *
     * Returns the existing document's URI, or null when the name is free.
     * Names are compared exactly: a provider that keeps two normalization
     * forms has two files, and folding them would target the wrong one.
     */
    fun findInTree(
        context: android.content.Context,
        treeUri: Uri,
        name: String,
    ): Uri? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val children = android.provider.DocumentsContract
            .buildChildDocumentsUriUsingTree(treeUri, docId)
        val projection = arrayOf(
            android.provider.DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            android.provider.DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        )
        context.contentResolver.query(children, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                if (c.getString(1) == name) {
                    return android.provider.DocumentsContract.buildDocumentUriUsingTree(
                        treeUri,
                        c.getString(0),
                    )
                }
            }
        }
        return null
    }

    /**
     * Creates a document in a picked folder and returns its URI.
     *
     * The provider still renames on a collision, so callers resolve the name
     * through [findInTree] first and only reach here once they know what they
     * are creating.
     */
    fun createInTree(
        context: android.content.Context,
        treeUri: Uri,
        name: String,
    ): Uri? {
        val docId = android.provider.DocumentsContract.getTreeDocumentId(treeUri)
        val parent = android.provider.DocumentsContract
            .buildDocumentUriUsingTree(treeUri, docId)
        return android.provider.DocumentsContract.createDocument(
            context.contentResolver,
            parent,
            "application/octet-stream",
            name,
        )
    }

    // Opens (or reopens and seeks) the read stream for [uri] to [offset] and
    // reads up to [maxBytes]. Returns the bytes read and the count.
    fun readChunk(context: android.content.Context, uri: Uri, offset: Long, maxBytes: Int): Pair<ByteArray, Int> {
        val buf = ByteArray(maxBytes)
        val n = synchronized(lock) {
            var stream = inputs[uri.toString()]
            var pos = inputPos[uri.toString()] ?: 0L
            if (stream == null || pos != offset) {
                stream?.let { runCatching { it.close() } }
                stream = context.contentResolver.openInputStream(uri)
                if (stream != null) {
                    skipTo(stream, offset)
                    pos = offset
                }
            }
            if (stream == null) {
                inputs.remove(uri.toString())
                inputPos.remove(uri.toString())
                return@synchronized 0
            }
            val read = readFully(stream, buf, maxBytes)
            pos += read
            inputs[uri.toString()] = stream
            inputPos[uri.toString()] = pos
            read
        }
        return buf to n
    }

    fun writeChunk(context: android.content.Context, uri: Uri, data: ByteArray): Int {
        return synchronized(lock) {
            val stream = outputs[uri.toString()] ?: openOutput(context, uri)
                ?: return@synchronized 0
            // Not flushed per chunk. The stream is buffered and release()
            // closes it, which flushes; flushing every 32KB defeated the
            // buffer and cost a syscall per chunk.
            stream.write(data)
            return@synchronized data.size
        }
    }

    /**
     * Opens the write stream for [uri], buffered so chunks coalesce into one
     * provider write. Resumes go through [openOutputAt] instead.
     */
    private fun openOutput(context: android.content.Context, uri: Uri): OutputStream? {
        val raw = context.contentResolver.openOutputStream(uri, "wt") ?: return null
        val buffered = java.io.BufferedOutputStream(raw, OUTPUT_BUFFER_BYTES)
        outputs[uri.toString()] = buffered
        return buffered
    }

    private data class PositionedOutput(
        val stream: OutputStream,
        val offset: Long,
    )

    /**
     * Opens [uri] at exactly [requestedOffset], or truncates it and starts at
     * zero when the provider cannot prove that positioning is safe.
     *
     * Append mode trusts the provider's current end, which can be longer than
     * the last durable byte after an interrupted write. A read/write
     * descriptor checks the length, truncates the unverified tail, and
     * positions the next write.
     */
    private fun openOutputAt(
        context: android.content.Context,
        uri: Uri,
        requestedOffset: Long,
    ): PositionedOutput {
        val offset = requestedOffset.coerceAtLeast(0L)
        if (offset == 0L) {
            return PositionedOutput(openTruncating(context, uri), 0L)
        }

        val descriptor = runCatching {
            context.contentResolver.openFileDescriptor(uri, "rw")
        }.getOrNull()
        if (descriptor != null) {
            val raw = android.os.ParcelFileDescriptor.AutoCloseOutputStream(descriptor)
            try {
                val channel = raw.channel
                // A shorter destination cannot contain the recorded prefix.
                // It is not safe to seek past its end and create a hole.
                if (channel.size() < offset) throw java.io.IOException(
                    "destination is shorter than the recorded resume offset",
                )
                channel.truncate(offset)
                channel.position(offset)
                if (channel.size() != offset || channel.position() != offset) {
                    throw java.io.IOException("destination could not be positioned safely")
                }
                return PositionedOutput(raw, offset)
            } catch (_: Exception) {
                // Positioning support is optional for a DocumentsProvider.
                // Close this attempt before reopening in truncating mode.
                runCatching { raw.close() }
            }
        }
        return PositionedOutput(openTruncating(context, uri), 0L)
    }

    private fun openTruncating(context: android.content.Context, uri: Uri): OutputStream =
        context.contentResolver.openOutputStream(uri, "wt")
            ?: throw java.io.IOException("could not open the destination file")

    /**
     * Streams a download straight into [uri].
     *
     * [pump] receives the offset actually made safe locally, then a writer for
     * bytes from that offset. The writer flushes each chunk before returning,
     * so the caller publishes only bytes that are safe to resume from. A
     * provider that cannot seek and truncate is reopened empty and reports 0.
     */
    fun writeStream(
        context: android.content.Context,
        uri: Uri,
        resumeFrom: Long = 0L,
        pump: (Long, (ByteArray) -> Unit) -> Unit,
    ) {
        val opened = openOutputAt(context, uri, resumeFrom)
        val out = java.io.BufferedOutputStream(opened.stream, OUTPUT_BUFFER_BYTES)
        var failure: Throwable? = null
        try {
            pump(opened.offset) { bytes ->
                out.write(bytes)
                // Returning from this callback acknowledges the chunk only
                // after the provider accepted its flush.
                out.flush()
            }
            out.flush()
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            try {
                out.close()
            } catch (closeFailure: Throwable) {
                if (failure != null) {
                    failure.addSuppressed(closeFailure)
                } else {
                    throw closeFailure
                }
            } finally {
                synchronized(lock) { outputs.remove(uri.toString()) }
            }
        }
    }

    /**
     * Streams an upload straight from [uri].
     *
     * [block] is handed a puller for the transfer thread: each call fills the
     * buffer as far as the stream allows and returns the count, or -1 at end.
     * The stream is closed here on both the success and the failure path.
     */
    fun <T> readStream(
        context: android.content.Context,
        uri: Uri,
        block: ((ByteArray) -> Int) -> T,
    ): T {
        val raw = context.contentResolver.openInputStream(uri)
            ?: throw java.io.IOException("could not open the source file")
        try {
            return block { buffer ->
                val n = readFully(raw, buffer, buffer.size)
                if (n == 0) -1 else n
            }
        } finally {
            runCatching { raw.close() }
        }
    }

    /**
     * Deletes a destination the app created but did not fill.
     *
     * Only a document this app created through the picker is removed, and only
     * through the provider that owns it. A provider that refuses is left
     * alone rather than retried: the file is the user's either way, and the
     * transfer has already reported its failure.
     */
    fun discard(context: android.content.Context, uri: Uri) {
        synchronized(lock) {
            outputs.remove(uri.toString())?.let { runCatching { it.close() } }
        }
        runCatching {
            android.provider.DocumentsContract.deleteDocument(
                context.contentResolver,
                uri,
            )
        }
    }

    /** Write buffer size. One provider write per this many bytes, at most. */
    private const val OUTPUT_BUFFER_BYTES = 512 * 1024

    // Closes and forgets any cached streams for [uri]. The URI grant itself is
    // released by the OS when the activity is torn down; this only drops our
    // open handles so a repeated pick is not served a stale stream.
    fun release(context: android.content.Context, uri: Uri) {
        val key = uri.toString()
        synchronized(lock) {
            inputs.remove(key)?.let { runCatching { it.close() } }
            inputPos.remove(key)
            outputs.remove(key)?.let { runCatching { it.close() } }
        }
    }

    private fun skipTo(stream: InputStream, offset: Long) {
        var remaining = offset
        val buf = ByteArray(64 * 1024)
        while (remaining > 0) {
            val toRead = minOf(remaining.toLong(), buf.size.toLong()).toInt()
            val n = stream.read(buf, 0, toRead)
            if (n <= 0) break
            remaining -= n
        }
    }

    private fun readFully(stream: InputStream, buf: ByteArray, maxBytes: Int): Int {
        var total = 0
        while (total < maxBytes) {
            val n = stream.read(buf, total, maxBytes - total)
            if (n <= 0) break
            total += n
        }
        return total
    }
}

// Launches a system picker and reports the chosen URI (plus its display name
// and size, when the provider exposes them) through [onResult]. The callback
// runs on the main thread. [mode] is "upload" (open an existing document) or
// "download" (create a destination document).
internal class FilePickFragment(
    private val mode: String,
    private val suggestedName: String,
    private val onResult: (uri: String?, name: String?, size: Long) -> Unit,
) : Fragment() {

    private var reported = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        when (mode) {
            "upload" -> {
                val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    finish(uri)
                }
                launcher.launch(arrayOf("*/*"))
            }
            // Same open-document contract as an upload, filtered to images so
            // the picker opens on the gallery rather than on every document.
            "image" -> {
                val launcher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                    finish(uri)
                }
                launcher.launch(arrayOf("image/*"))
            }
            "folder" -> {
                val launcher = registerForActivityResult(
                    ActivityResultContracts.OpenDocumentTree()
                ) { uri ->
                    // Persisted, so the folder stays usable across restarts
                    // and the user is asked once rather than per download.
                    if (uri != null) {
                        runCatching {
                            context?.contentResolver?.takePersistableUriPermission(
                                uri,
                                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                                    android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                            )
                        }
                    }
                    finishTree(uri)
                }
                launcher.launch(null)
            }
            else -> {
                val launcher = registerForActivityResult(
                    ActivityResultContracts.CreateDocument("application/octet-stream")
                ) { uri ->
                    finish(uri)
                }
                launcher.launch(suggestedName)
            }
        }
    }

    // A tree has no display name or size worth reporting; the URI is the
    // whole result.
    private fun finishTree(uri: Uri?) {
        if (reported) return
        reported = true
        onResult(uri?.toString(), "", -1L)
    }

    private fun finish(uri: Uri?) {
        if (reported) return
        reported = true
        if (uri == null) {
            onResult(null, null, -1L)
            return
        }
        var name = suggestedName
        var size = -1L
        runCatching {
            context?.contentResolver?.query(uri, null, null, null, null)?.use { c ->
                val nameIdx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (c.moveToFirst()) {
                    if (nameIdx >= 0) name = c.getString(nameIdx) ?: suggestedName
                    if (sizeIdx >= 0) size = c.getLong(sizeIdx)
                }
            }
        }
        onResult(uri.toString(), name, size)
    }
}
