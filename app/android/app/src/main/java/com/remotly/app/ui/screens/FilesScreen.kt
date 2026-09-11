package com.remotly.app.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotly.app.core.ErrorKind
import com.remotly.app.core.toRemotlyError
import com.remotly.app.fileio.FileModule
import com.remotly.app.files.Breadcrumb
import com.remotly.app.files.FileEntry
import com.remotly.app.files.FileOrder
import com.remotly.app.files.SftpTransferOps
import com.remotly.app.files.SortDirection
import com.remotly.app.files.SortKey
import com.remotly.app.files.entryAccessibilityLabel
import com.remotly.app.files.entryDescription
import com.remotly.app.files.filterEntries
import com.remotly.app.files.isPlainName
import com.remotly.app.files.joinPath
import com.remotly.app.files.nameExists
import com.remotly.app.files.orderEntries
import com.remotly.app.files.parentPath
import com.remotly.app.files.parseBreadcrumbs
import com.remotly.app.settings.AppSettings
import com.remotly.app.settings.SettingsState
import com.remotly.app.ssh.SftpBridge
import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ui.components.EmptyState
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.LoadingState
import com.remotly.app.ui.components.NoticeBar
import com.remotly.app.ui.components.NoticeTone
import com.remotly.app.ui.components.RemotlyScreen
import com.remotly.app.ui.components.ScreenAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Height of one entry row before the system text scale is applied. */
private val ROW_HEIGHT = 56.dp

/**
 * How many listings are held for instant redisplay.
 *
 * Walking a tree revisits the same handful of directories, and the cost of a
 * stale listing is bounded: it is shown while the refresh is already in
 * flight.
 */
private const val DIR_CACHE_MAX = 24

private const val SFTP_POLL_MS = 150L
private const val SFTP_POLL_MAX = 60

private enum class Phase { Connecting, HostKey, Ready, Error }

private data class HostKeyPrompt(val algorithm: String, val fingerprint: String, val changed: Boolean)

private sealed interface Prompt {
    data object Mkdir : Prompt
    data class Rename(val target: String) : Prompt
    data class Remove(val target: String, val isDir: Boolean) : Prompt
}

private fun SftpEntry.toFileEntry() = FileEntry(
    name = name,
    isDir = isDirectory,
    isSymlink = isSymlink,
    size = size,
    // SFTP reports milliseconds; the model uses seconds.
    mtime = modifyTimeMillis / 1000,
    perm = permissions,
)

private data class DownloadTarget(val name: String, val remotePath: String, val size: Long)

/** A download whose name already exists in the destination folder. */
private data class DownloadCollision(val target: DownloadTarget, val folder: Uri, val existing: Uri)

private data class PickedUpload(val name: String, val size: Long)

/** A picked upload whose name already exists in the current remote directory. */
private data class UploadPick(val uri: Uri, val name: String, val size: Long)

/** True when a previously granted download folder can still be written to. */
private fun hasFolderAccess(context: Context, folder: Uri): Boolean =
    context.contentResolver.persistedUriPermissions.any {
        it.uri == folder && it.isReadPermission && it.isWritePermission
    }

/** The display name and byte size a content provider reports for a picked upload. */
private fun queryPickedUpload(context: Context, uri: Uri): PickedUpload {
    var name = ""
    var size = -1L
    runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (cursor.moveToFirst()) {
                if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: ""
                if (sizeIndex >= 0) size = cursor.getLong(sizeIndex)
            }
        }
    }
    return PickedUpload(name, size)
}

/**
 * The SFTP file browser.
 *
 * A directory is read once and held whole. SFTP readdir has no cursor a
 * client can resume from, so a page was only ever a slice of a listing the
 * server had already sent, and asking for the next one re-read the directory
 * from the start. Ordering and search run over the full list and the lazy
 * column virtualizes the rows.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(hostId: String, onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var phase by remember { mutableStateOf(Phase.Connecting) }
    var hostKey by remember { mutableStateOf<HostKeyPrompt?>(null) }
    var cwd by remember { mutableStateOf("/") }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // The search is about the folder in front of you, not a preference, so it
    // lives here rather than in settings.
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<FileEntry?>(null) }
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    var promptText by remember { mutableStateOf("") }
    var promptError by remember { mutableStateOf("") }

    // Listings already fetched. A directory still here is drawn immediately
    // and refreshed behind the list, which is what makes walking back up a
    // tree instant instead of a spinner per level.
    val cache = remember { SnapshotStateMap<String, List<FileEntry>>() }
    val cacheOrder = remember { mutableListOf<String>() }
    // Bumped on every navigation, so a listing that arrives after the user
    // has moved on is dropped instead of replacing what they are looking at.
    var loadGen by remember { mutableStateOf(0) }

    fun cacheDir(path: String, listed: List<FileEntry>) {
        cacheOrder.remove(path)
        cacheOrder.add(path)
        cache[path] = listed
        while (cacheOrder.size > DIR_CACHE_MAX) {
            cache.remove(cacheOrder.removeAt(0))
        }
    }

    suspend fun loadDir(path: String, showSpinner: Boolean) {
        loadGen += 1
        val gen = loadGen
        if (showSpinner) loading = true
        error = ""
        try {
            val listed = withContext(Dispatchers.IO) {
                SftpBridge.list(hostId, path).map { it.toFileEntry() }
            }
            if (loadGen != gen) return
            cacheDir(path, listed)
            entries = listed
        } catch (e: Exception) {
            if (loadGen != gen) return
            error = toRemotlyError(e, ErrorKind.Network).message
            // A cached listing stays up under the error banner: it is what
            // the directory held a moment ago, which beats an empty screen.
            if (!cache.containsKey(path)) entries = null
        } finally {
            if (loadGen == gen) {
                loading = false
                refreshing = false
            }
        }
    }

    fun openDir(path: String) {
        cwd = path
        // Navigating abandons a pull refresh of the old directory; its load
        // is dropped by the generation check and would never clear the control.
        refreshing = false
        val cached = cache[path]
        entries = cached
        scope.launch { loadDir(path, cached == null) }
    }

    val context = LocalContext.current
    var downloadCollision by remember { mutableStateOf<DownloadCollision?>(null) }
    var pendingDownload by remember { mutableStateOf<DownloadTarget?>(null) }
    var uploadCollision by remember { mutableStateOf<UploadPick?>(null) }

    fun runDownload(destination: Uri, name: String, remotePath: String, size: Long, freshDestination: Boolean) {
        scope.launch {
            val result = SftpTransferOps.download(context, hostId, remotePath, name, destination, size, resumeFrom = 0L)
            if (result is SftpTransferOps.Result.Failed) {
                if (SftpTransferOps.shouldDiscardDestination(freshDestination, result.keepPartial)) {
                    withContext(Dispatchers.IO) { FileModule.discard(context, destination) }
                    snackbar.showSnackbar(result.message)
                } else {
                    snackbar.showSnackbar("${result.message} The partial file was kept and can be resumed.")
                }
            }
        }
    }

    fun createAndDownload(folder: Uri, target: DownloadTarget) {
        scope.launch {
            val dest = withContext(Dispatchers.IO) {
                runCatching { FileModule.createInTree(context, folder, target.name) }.getOrNull()
            }
            if (dest == null) {
                snackbar.showSnackbar("Could not create ${target.name} in the download folder.")
                return@launch
            }
            runDownload(dest, target.name, target.remotePath, target.size, freshDestination = true)
        }
    }

    fun resolveDownloadDestination(folder: Uri, target: DownloadTarget) {
        scope.launch {
            val existing = withContext(Dispatchers.IO) {
                runCatching { FileModule.findInTree(context, folder, target.name) }.getOrNull()
            }
            if (existing == null) {
                createAndDownload(folder, target)
            } else {
                downloadCollision = DownloadCollision(target, folder, existing)
            }
        }
    }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
        val target = pendingDownload
        pendingDownload = null
        if (uri == null || target == null) return@rememberLauncherForActivityResult
        val granted = try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            true
        } catch (e: SecurityException) {
            false
        }
        if (!granted) {
            scope.launch { snackbar.showSnackbar("Could not get permission to use that folder.") }
            return@rememberLauncherForActivityResult
        }
        SettingsState.update(
            onFailure = { scope.launch { snackbar.showSnackbar("Could not save the download folder.") } },
        ) { it.copy(downloadFolderUri = uri.toString()) }
        resolveDownloadDestination(uri, target)
    }

    fun beginDownload(target: DownloadTarget) {
        val stored = settings.downloadFolderUri
        val folder = if (stored.isEmpty()) null else Uri.parse(stored)
        if (folder == null || !hasFolderAccess(context, folder)) {
            pendingDownload = target
            folderPicker.launch(null)
            return
        }
        resolveDownloadDestination(folder, target)
    }

    fun runUpload(uri: Uri, name: String, size: Long, replace: Boolean) {
        scope.launch {
            val result = SftpTransferOps.upload(context, hostId, joinPath(cwd, name), name, uri, size, replace)
            if (result is SftpTransferOps.Result.Failed) snackbar.showSnackbar(result.message)
            loadDir(cwd, false)
        }
    }

    val uploadPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val picked = withContext(Dispatchers.IO) { queryPickedUpload(context, uri) }
            when {
                !isPlainName(picked.name) ->
                    snackbar.showSnackbar("That file's name cannot be uploaded as-is.")
                picked.size <= 0 ->
                    snackbar.showSnackbar("This file has an unknown size and cannot be uploaded.")
                nameExists(entries.orEmpty(), picked.name) ->
                    uploadCollision = UploadPick(uri, picked.name, picked.size)
                else -> runUpload(uri, picked.name, picked.size, replace = false)
            }
        }
    }

    LaunchedEffect(hostId) {
        if (hostId.isEmpty()) {
            phase = Phase.Error
            error = "No host to open. Open the file browser from a host."
            return@LaunchedEffect
        }
        withContext(Dispatchers.IO) { runCatching { SftpBridge.connect(hostId) } }
        repeat(SFTP_POLL_MAX) {
            val active = SftpBridge.status(hostId)
            when (active?.state) {
                SftpBridge.State.READY -> {
                    phase = Phase.Ready
                    val home = withContext(Dispatchers.IO) {
                        runCatching { SftpBridge.realPath(hostId, ".") }.getOrDefault("/")
                    }
                    openDir(home.ifEmpty { "/" })
                    return@LaunchedEffect
                }

                SftpBridge.State.FAILED -> {
                    error = active.message.ifEmpty { "SFTP connection failed" }
                    phase = Phase.Error
                    return@LaunchedEffect
                }

                SftpBridge.State.HOST_KEY -> {
                    val info = active.prompt
                    if (info != null) {
                        hostKey = HostKeyPrompt(
                            info.info.algorithm,
                            info.info.fingerprint,
                            info.changed,
                        )
                        phase = Phase.HostKey
                        return@LaunchedEffect
                    }
                }

                else -> Unit
            }
            delay(SFTP_POLL_MS)
        }
        error = "Timed out waiting for the SFTP session."
        phase = Phase.Error
    }

    val order = FileOrder(
        sortKey = SortKey.from(settings.filesSortKey),
        direction = SortDirection.from(settings.filesSortDirection),
        showHidden = settings.filesShowHidden,
    )
    // Ordering and searching are derived apart so a keystroke only re-filters.
    // Re-sorting a directory of tens of thousands of entries per character is
    // what made the search box lag.
    val ordered = remember(entries, order) { orderEntries(entries.orEmpty(), order) }
    val shown = remember(ordered, query) { filterEntries(ordered, query) }

    val actions = buildList {
        add(
            ScreenAction(
                key = "search",
                icon = Icons.Filled.Search,
                title = if (searchOpen) "Hide the search box" else "Search this folder",
                onClick = {
                    searchOpen = !searchOpen
                    if (!searchOpen) query = ""
                },
                enabled = phase == Phase.Ready,
            ),
        )
        add(
            ScreenAction(
                key = "refresh",
                icon = Icons.Filled.Refresh,
                // A pull gesture also refreshes, but it is out of reach from
                // a screen reader or an external keyboard.
                title = "Refresh this folder",
                onClick = {
                    refreshing = true
                    scope.launch { loadDir(cwd, false) }
                },
                enabled = phase == Phase.Ready,
            ),
        )
        add(
            ScreenAction(
                key = "mkdir",
                icon = Icons.Filled.Add,
                title = "New folder",
                onClick = {
                    promptText = ""
                    promptError = ""
                    prompt = Prompt.Mkdir
                },
                enabled = phase == Phase.Ready,
            ),
        )
        add(
            ScreenAction(
                key = "upload",
                icon = Icons.Filled.Upload,
                title = "Upload a file",
                onClick = { uploadPicker.launch(arrayOf("*/*")) },
                enabled = phase == Phase.Ready,
            ),
        )
    }

    RemotlyScreen(
        title = "Files",
        onBack = onBack,
        actions = actions,
        snackbarHostState = snackbar,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (phase) {
                Phase.Connecting -> LoadingState("Opening files")

                Phase.Error -> ErrorState(
                    title = "Could not open files",
                    message = error.ifEmpty { "The connection could not be opened." },
                    retryLabel = "Close",
                    onRetry = onBack,
                )

                Phase.HostKey -> HostKeyApproval(
                    prompt = hostKey,
                    onReject = onBack,
                    onAccept = {
                        phase = Phase.Connecting
                        hostKey = null
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { SftpBridge.decideHostKey(hostId, true) }
                            }
                        }
                    },
                )

                Phase.Ready -> {
                    Breadcrumbs(parseBreadcrumbs(cwd, "/"), onNavigate = { openDir(it) })
                    if (error.isNotEmpty()) NoticeBar(error, NoticeTone.Danger)
                    if (searchOpen) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            label = { Text("Search this folder") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    SortBar(
                        order = order,
                        shown = shown.size,
                        total = ordered.size,
                        loading = loading,
                        onOrderChange = { next ->
                            SettingsState.update {
                                it.copy(
                                    filesSortKey = next.sortKey.id,
                                    filesSortDirection = next.direction.id,
                                    filesShowHidden = next.showHidden,
                                )
                            }
                        },
                    )
                    PullToRefreshBox(
                        isRefreshing = refreshing,
                        onRefresh = {
                            refreshing = true
                            scope.launch { loadDir(cwd, false) }
                        },
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        when {
                            loading && entries == null -> LoadingState("Reading this folder")

                            entries != null && shown.isEmpty() && entries!!.isNotEmpty() ->
                                EmptyState(
                                    icon = Icons.Filled.Search,
                                    title = "Nothing matches",
                                    message = "No entry in this folder matches the search " +
                                        "and hidden-file settings.",
                                )

                            entries != null && entries!!.isEmpty() -> EmptyState(
                                icon = Icons.Filled.FolderOpen,
                                title = "This folder is empty",
                                message = "Upload a file to get started.",
                            )

                            else -> LazyColumn(Modifier.fillMaxSize()) {
                                if (parentPath(cwd) != null) {
                                    item(key = "..") {
                                        UpRow(onClick = {
                                            parentPath(cwd)?.let { openDir(it) }
                                        })
                                    }
                                }
                                items(shown, key = { "$cwd\u0000${it.name}" }) { entry ->
                                    FileRow(
                                        entry = entry,
                                        onOpen = {
                                            if (entry.isDir) openDir(joinPath(cwd, entry.name))
                                            else menuFor = entry
                                        },
                                        onMenu = { menuFor = entry },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    val target = menuFor
    if (target != null) {
        ModalBottomSheet(onDismissRequest = { menuFor = null }) {
            Text(
                target.name,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            MenuRow(Icons.Filled.Download, "Download") {
                menuFor = null
                beginDownload(
                    DownloadTarget(
                        name = target.name,
                        remotePath = joinPath(cwd, target.name),
                        size = if (target.size > 0) target.size else -1,
                    ),
                )
            }
            MenuRow(Icons.Filled.Edit, "Rename") {
                promptText = target.name
                promptError = ""
                prompt = Prompt.Rename(target.name)
                menuFor = null
            }
            MenuRow(Icons.Filled.Delete, "Delete", destructive = true) {
                prompt = Prompt.Remove(target.name, target.isDir)
                menuFor = null
            }
        }
    }

    val dCollision = downloadCollision
    if (dCollision != null) {
        ModalBottomSheet(onDismissRequest = { downloadCollision = null }) {
            Text(
                "${dCollision.target.name} already exists",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                "A file with this name is already in your download folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            MenuRow(Icons.Filled.ContentCopy, "Keep both") {
                downloadCollision = null
                scope.launch {
                    val freeName = withContext(Dispatchers.IO) {
                        SftpTransferOps.resolvedTransferName(dCollision.target.name, replace = false) { candidate ->
                            FileModule.findInTree(context, dCollision.folder, candidate) != null
                        }
                    }
                    val dest = withContext(Dispatchers.IO) {
                        runCatching { FileModule.createInTree(context, dCollision.folder, freeName) }.getOrNull()
                    }
                    if (dest == null) {
                        snackbar.showSnackbar("Could not create $freeName in the download folder.")
                        return@launch
                    }
                    runDownload(
                        dest,
                        freeName,
                        dCollision.target.remotePath,
                        dCollision.target.size,
                        freshDestination = true,
                    )
                }
            }
            MenuRow(Icons.Filled.Refresh, "Replace") {
                downloadCollision = null
                runDownload(
                    dCollision.existing,
                    dCollision.target.name,
                    dCollision.target.remotePath,
                    dCollision.target.size,
                    freshDestination = false,
                )
            }
        }
    }

    val uCollision = uploadCollision
    if (uCollision != null) {
        ModalBottomSheet(onDismissRequest = { uploadCollision = null }) {
            Text(
                "${uCollision.name} already exists",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            Text(
                "A file with this name is already in this folder.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
            )
            MenuRow(Icons.Filled.ContentCopy, "Keep both") {
                uploadCollision = null
                val freeName = SftpTransferOps.resolvedTransferName(uCollision.name, replace = false) { candidate ->
                    nameExists(entries.orEmpty(), candidate)
                }
                runUpload(uCollision.uri, freeName, uCollision.size, replace = false)
            }
            MenuRow(Icons.Filled.Refresh, "Replace") {
                uploadCollision = null
                runUpload(uCollision.uri, uCollision.name, uCollision.size, replace = true)
            }
        }
    }

    val open = prompt
    if (open != null) {
        PromptDialog(
            prompt = open,
            text = promptText,
            error = promptError,
            onTextChange = {
                promptText = it
                promptError = ""
            },
            onCancel = {
                prompt = null
                promptError = ""
            },
            onConfirm = {
                // The name is joined onto the current directory, so one
                // carrying a separator or a dot segment would act somewhere
                // the user is not looking.
                if (open !is Prompt.Remove && !isPlainName(promptText)) {
                    promptError = "Use a name without a slash."
                    return@PromptDialog
                }
                val request = open
                prompt = null
                scope.launch {
                    val failure = withContext(Dispatchers.IO) {
                        runCatching {
                            when (request) {
                                is Prompt.Mkdir ->
                                    SftpBridge.mkdir(hostId, joinPath(cwd, promptText.trim()))

                                is Prompt.Rename -> SftpBridge.rename(
                                    hostId,
                                    joinPath(cwd, request.target),
                                    joinPath(cwd, promptText.trim()),
                                )

                                is Prompt.Remove ->
                                    if (request.isDir) {
                                        SftpBridge.removeDir(hostId, joinPath(cwd, request.target))
                                    } else {
                                        SftpBridge.removeFile(hostId, joinPath(cwd, request.target))
                                    }
                            }
                        }.exceptionOrNull()
                    }
                    if (failure != null) {
                        snackbar.showSnackbar(toRemotlyError(failure, ErrorKind.Network).message)
                    }
                    loadDir(cwd, false)
                }
            },
        )
    }
}

@Composable
private fun HostKeyApproval(
    prompt: HostKeyPrompt?,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    if (prompt == null) return
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            if (prompt.changed) "Host key changed" else "New host key",
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            if (prompt.changed) {
                "A different key than the one you approved was presented. This may be a " +
                    "sign of tampering. Only continue if you expect the key to change."
            } else {
                "This server presented a host key you have not seen before. Approving it " +
                    "stores the key for this host."
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(prompt.algorithm, style = MaterialTheme.typography.bodySmall)
        Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onReject) { Text("Reject") }
            Button(onClick = onAccept) { Text("Trust and continue") }
        }
    }
}

@Composable
private fun Breadcrumbs(crumbs: List<Breadcrumb>, onNavigate: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            crumbs.forEachIndexed { i, crumb ->
                if (i > 0) {
                    Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = { onNavigate(crumb.path) }) { Text(crumb.name) }
            }
        }
    }
}

@Composable
private fun SortBar(
    order: FileOrder,
    shown: Int,
    total: Int,
    loading: Boolean,
    onOrderChange: (FileOrder) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (key in SortKey.entries) {
                    val active = key == order.sortKey
                    FilterChip(
                        selected = active,
                        // The direction belongs in the name so it is
                        // announced, not only drawn as an arrow.
                        label = { Text(key.name) },
                        onClick = {
                            onOrderChange(
                                if (active) {
                                    order.copy(
                                        direction = if (order.direction == SortDirection.Asc) {
                                            SortDirection.Desc
                                        } else {
                                            SortDirection.Asc
                                        },
                                    )
                                } else {
                                    order.copy(sortKey = key, direction = SortDirection.Asc)
                                },
                            )
                        },
                        modifier = Modifier.clearAndSetSemantics {
                            contentDescription = if (active) {
                                val dir = if (order.direction == SortDirection.Desc) {
                                    "descending"
                                } else {
                                    "ascending"
                                }
                                "Sort by ${key.name}, $dir"
                            } else {
                                "Sort by ${key.name}"
                            }
                        },
                    )
                }
            }
            IconButton(
                onClick = { onOrderChange(order.copy(showHidden = !order.showHidden)) },
            ) {
                Icon(
                    if (order.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                    contentDescription = if (order.showHidden) {
                        "Hide hidden files"
                    } else {
                        "Show hidden files"
                    },
                )
            }
            Text(
                countLabel(shown, total, loading),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The line beside the sort chips, which is also the answer to "did the search
 * look at the whole folder": the total it reports is the whole directory.
 */
private fun countLabel(shown: Int, total: Int, loading: Boolean): String {
    if (loading) return "Reading..."
    val items = if (total == 1) "1 item" else "$total items"
    return if (shown == total) items else "$shown of $items"
}

private fun iconFor(entry: FileEntry): ImageVector = when {
    entry.isSymlink -> Icons.Filled.LinkOff
    entry.isDir -> Icons.Filled.Folder
    else -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FileRow(entry: FileEntry, onOpen: () -> Unit, onMenu: () -> Unit) {
    ListItem(
        headlineContent = { Text(entry.name, maxLines = 1) },
        supportingContent = {
            val detail = entryDescription(entry)
            if (detail.isNotEmpty()) Text(detail, maxLines = 1)
        },
        leadingContent = {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(entry),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        },
        trailingContent = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Actions for ${entry.name}")
            }
        },
        modifier = Modifier.combinedClickable(
            onClick = onOpen,
            // A long press is a shortcut to the same menu the overflow button
            // opens, never the only way there.
            onLongClick = onMenu,
            onClickLabel = if (entry.isDir) "Open folder" else "Actions",
        ),
    )
}

@Composable
private fun UpRow(onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text("..") },
        leadingContent = {
            Icon(Icons.Filled.ArrowUpward, contentDescription = null)
        },
        modifier = Modifier
            .height(ROW_HEIGHT)
            .clickable(onClickLabel = "Up one level", onClick = onClick),
    )
}

@Composable
private fun MenuRow(
    icon: ImageVector,
    label: String,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val tint = if (destructive) {
        MaterialTheme.colorScheme.error
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    ListItem(
        headlineContent = { Text(label, color = tint) },
        leadingContent = { Icon(icon, contentDescription = null, tint = tint) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun PromptDialog(
    prompt: Prompt,
    text: String,
    error: String,
    onTextChange: (String) -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit,
) {
    val remove = prompt is Prompt.Remove
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Text(
                when (prompt) {
                    is Prompt.Mkdir -> "New folder name"
                    is Prompt.Rename -> "Rename ${prompt.target}"
                    is Prompt.Remove -> "Delete ${prompt.target}?"
                },
            )
        },
        text = {
            if (remove) {
                Text(
                    "This cannot be undone. Directories are removed only when empty; the " +
                        "server reports a non-empty target as an error.",
                )
            } else {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = onTextChange,
                        singleLine = true,
                        isError = error.isNotEmpty(),
                        label = {
                            Text(if (prompt is Prompt.Mkdir) "Folder name" else "New name")
                        },
                    )
                    if (error.isNotEmpty()) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(if (remove) "Delete" else "OK") }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
