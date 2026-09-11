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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.remotly.app.files.entryDescription
import com.remotly.app.files.filterEntries
import com.remotly.app.files.isHidden
import com.remotly.app.files.isPlainName
import com.remotly.app.files.joinPath
import com.remotly.app.files.nameExists
import com.remotly.app.files.orderEntries
import com.remotly.app.files.parentPath
import com.remotly.app.files.parseBreadcrumbs
import com.remotly.app.session.FilesTabs
import com.remotly.app.settings.SettingsState
import com.remotly.app.ssh.SftpBridge
import com.remotly.app.ssh.SftpEntry
import com.remotly.app.ui.ScreenHorizontalPadding
import com.remotly.app.ui.components.EmptyState
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.LoadingState
import com.remotly.app.ui.components.RemotlyScreen
import com.remotly.app.ui.components.RemotlyTextField
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.components.tonalChipColors
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
 * The SFTP file browser. A directory is read once and held whole, since SFTP
 * readdir has no resumable cursor; sorting and search run over the full list.
 *
 * The terminal screen owns the app bar and tab strip. This screen remembers
 * its directory under [tabId] and closes the tab through [onClose] when a
 * host key is rejected.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(hostId: String, onClose: () -> Unit, tabId: String) {
    val scope = rememberCoroutineScope()
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }

    var phase by remember { mutableStateOf(Phase.Connecting) }
    var hostKey by remember { mutableStateOf<HostKeyPrompt?>(null) }
    // Bumped once the user answers the host-key prompt, which restarts the
    // poll below. Without it the screen waits for a session that already
    // settled and never reports anything.
    var pollEpoch by remember { mutableIntStateOf(0) }
    // A retry sets this before bumping [pollEpoch]. Accepting a host key does
    // not reconnect: the paused connection continues with the decision.
    var connectRequested by remember { mutableStateOf(true) }
    var keyAnswered by remember { mutableStateOf(false) }
    var cwd by remember { mutableStateOf(FilesTabs.remembered(tabId) ?: "/") }
    var entries by remember { mutableStateOf<List<FileEntry>?>(null) }
    var error by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    // The search is about the folder in front of you, not a preference, so it
    // lives here rather than in settings.
    var query by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var addMenuOpen by remember { mutableStateOf(false) }
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
        FilesTabs.setCwd(tabId, path)
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
    fun reconnect() {
        connectRequested = true
        phase = Phase.Connecting
        hostKey = null
        keyAnswered = false
        error = ""
        pollEpoch++
    }


    LaunchedEffect(hostId, pollEpoch) {
        if (hostId.isEmpty()) {
            phase = Phase.Error
            error = "No host to open. Open the file browser from a host."
            return@LaunchedEffect
        }
        // A host-key decision resumes the paused connection. A retry, on the
        // other hand, explicitly requests a fresh connection here.
        if (connectRequested) {
            connectRequested = false
            withContext(Dispatchers.IO) { runCatching { SftpBridge.connect(hostId) } }
        }
        repeat(SFTP_POLL_MAX) {
            val active = SftpBridge.status(hostId)
            when (active?.state) {
                SftpBridge.State.READY -> {
                    phase = Phase.Ready
                    val home = withContext(Dispatchers.IO) {
                        runCatching { SftpBridge.realPath(hostId, ".") }.getOrDefault("/")
                    }
                    // A tab that has been here before goes back to where it
                    // was, not to the home directory it first landed in.
                    openDir(FilesTabs.remembered(tabId) ?: home.ifEmpty { "/" })
                    return@LaunchedEffect
                }

                SftpBridge.State.FAILED -> {
                    error = active.message.ifEmpty { "SFTP connection failed" }
                    phase = Phase.Error
                    return@LaunchedEffect
                }

                SftpBridge.State.HOST_KEY -> {
                    val info = active.prompt
                    if (info != null && !keyAnswered) {
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
    // Keep this second match set before the hidden-file filter. It lets the
    // empty state explain whether the query found only dotfiles.
    val allMatches = remember(entries, query) { filterEntries(entries.orEmpty(), query) }
    val hiddenOnly = !order.showHidden && allMatches.isNotEmpty() && allMatches.all(::isHidden)

    fun openMkdir() {
        promptText = ""
        promptError = ""
        prompt = Prompt.Mkdir
    }

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
                key = "add",
                icon = Icons.Filled.Add,
                title = "Add",
                onClick = { addMenuOpen = true },
                enabled = phase == Phase.Ready,
            ),
        )
    }

    RemotlyScreen(
        title = "Files",
        onBack = null,
        actions = actions,
        snackbarHostState = snackbar,
        bare = true,
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            when (phase) {
                Phase.Connecting -> LoadingState("Opening files")

                Phase.Error -> ErrorState(
                    title = "Could not open files",
                    message = error.ifEmpty { "The connection could not be opened." },
                    retryLabel = "Reconnect",
                    onRetry = ::reconnect,
                )
                Phase.HostKey -> HostKeyApproval(
                    prompt = hostKey,
                    onReject = onClose,
                    onAccept = {
                        phase = Phase.Connecting
                        hostKey = null
                        keyAnswered = true
                        scope.launch {
                            withContext(Dispatchers.IO) {
                                runCatching { SftpBridge.decideHostKey(hostId, true) }
                            }
                            pollEpoch++
                        }
                    },
                )

                Phase.Ready -> {
                    Breadcrumbs(
                        crumbs = parseBreadcrumbs(cwd, "/"),
                        count = countLabel(shown.size, ordered.size, loading),
                        onNavigate = { openDir(it) },
                    )
                    if (error.isNotEmpty() && entries != null) {
                        ConnectionNotice(error, onReconnect = ::reconnect)
                    }
                    // Search stays inside the sort row so the browser never
                    // grows a third control row when the field is open.
                    SortBar(
                        order = order,
                        searchOpen = searchOpen,
                        query = query,
                        onQueryChange = { query = it },
                        onClearSearch = { query = "" },
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

                            entries == null -> ErrorState(
                                title = "Could not read this folder",
                                message = error.ifEmpty { "The SFTP session is unavailable." },
                                retryLabel = "Reconnect",
                                onRetry = ::reconnect,
                            )

                            entries!!.isEmpty() -> EmptyState(
                                icon = Icons.Filled.FolderOpen,
                                title = "This folder is empty",
                                message = "Upload a file or create a folder to get started.",
                                action = "Upload file" to {
                                    uploadPicker.launch(arrayOf("*/*"))
                                },
                                secondaryAction = "New folder" to ::openMkdir,
                            )

                            hiddenOnly -> EmptyState(
                                icon = Icons.Filled.VisibilityOff,
                                title = if (query.isEmpty()) {
                                    "Only hidden files"
                                } else {
                                    "Only hidden matches"
                                },
                                message = if (query.isEmpty()) {
                                    "This folder contains files hidden by the current setting."
                                } else {
                                    "The search matches hidden files, which are currently hidden."
                                },
                                action = "Show hidden files" to {
                                    SettingsState.update { it.copy(filesShowHidden = true) }
                                },
                                secondaryAction = if (query.isEmpty()) {
                                    null
                                } else {
                                    "Clear search" to { query = "" }
                                },
                            )

                            shown.isEmpty() -> EmptyState(
                                icon = Icons.Filled.Search,
                                title = "No search results",
                                message = "No visible entry in this folder matches \"$query\".",
                                action = "Clear search" to { query = "" },
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

    if (addMenuOpen) {
        ModalBottomSheet(onDismissRequest = { addMenuOpen = false }) {
            Text(
                "Add",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            MenuRow(Icons.Filled.Folder, "New folder") {
                addMenuOpen = false
                openMkdir()
            }
            MenuRow(Icons.Filled.Upload, "Upload file") {
                addMenuOpen = false
                uploadPicker.launch(arrayOf("*/*"))
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
private fun ConnectionNotice(message: String, onReconnect: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
                maxLines = 2,
            )
            TextButton(onClick = onReconnect) { Text("Reconnect") }
        }
    }
}

@Composable
private fun Breadcrumbs(crumbs: List<Breadcrumb>, count: String, onNavigate: (String) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = ScreenHorizontalPadding),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                crumbs.forEachIndexed { i, crumb ->
                    // The root crumb's own name is the separator ("/", or a
                    // drive root ending in one), so another after it draws
                    // "/ / config".
                    val previous = crumbs.getOrNull(i - 1)?.name.orEmpty()
                    if (i > 0 && !previous.endsWith('/') && !previous.endsWith('\\')) {
                        Text("/", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { onNavigate(crumb.path) }) { Text(crumb.name) }
                }
            }
            Text(
                count,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Composable
private fun SortBar(
    order: FileOrder,
    searchOpen: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    onClearSearch: () -> Unit,
    onOrderChange: (FileOrder) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        // Keep every folder control in one horizontally scrollable row. At
        // normal widths this is the second compact row after breadcrumbs,
        // while narrow screens can still reach every control.
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (searchOpen) {
                RemotlyTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    placeholder = { Text("Search") },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.width(220.dp),
                )
                if (query.isNotEmpty()) {
                    TextButton(onClick = onClearSearch) { Text("Clear") }
                }
            }
            for (key in SortKey.entries) {
                val active = key == order.sortKey
                val label = sortLabel(key)
                val direction = if (order.direction == SortDirection.Desc) {
                    "descending"
                } else {
                    "ascending"
                }
                FilterChip(
                    selected = active,
                    label = { Text(label) },
                    border = null,
                    colors = tonalChipColors(),
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
                    // Do not clear semantics here: FilterChip supplies the
                    // role and click action. These additions retain those
                    // semantics while making selection and direction explicit.
                    modifier = Modifier.semantics {
                        role = Role.Checkbox
                        selected = active
                        stateDescription = if (active) {
                            "Selected, $direction"
                        } else {
                            "Not selected"
                        }
                        contentDescription = if (active) {
                            "Sort by $label, $direction"
                        } else {
                            "Sort by $label"
                        }
                    },
                )
            }
            IconButton(
                onClick = { onOrderChange(order.copy(showHidden = !order.showHidden)) },
                modifier = Modifier.semantics {
                    stateDescription = if (order.showHidden) "Showing hidden files" else {
                        "Hiding hidden files"
                    }
                },
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
        }
    }
}

private fun sortLabel(key: SortKey): String = when (key) {
    SortKey.Name -> "Name"
    SortKey.Size -> "Size"
    SortKey.Mtime -> "Modified"
    SortKey.Kind -> "Type"
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
        headlineContent = { Text(entry.name, maxLines = 1, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = {
            val detail = entryDescription(entry)
            if (detail.isNotEmpty()) Text(detail, maxLines = 1, style = MaterialTheme.typography.bodySmall)
        },
        leadingContent = {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    iconFor(entry),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
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
                    RemotlyTextField(
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
