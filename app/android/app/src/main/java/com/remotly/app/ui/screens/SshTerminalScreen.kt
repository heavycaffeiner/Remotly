package com.remotly.app.ui.screens

// Tabbed shells over one SSH host.
//
// Sessions live in SshSessions, outside this composable entirely, so
// navigating back leaves them running; this screen only renders whatever the
// store currently holds. Several tabs may be open at once, but only the
// active one draws into the terminal; the rest stay connected and keep
// buffering their output in TerminalStore.

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import com.remotly.app.ui.components.RemotlyTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.remotly.app.core.ErrorKind
import com.remotly.app.core.RemotlyError
import com.remotly.app.core.RemotlyException
import com.remotly.app.core.toRemotlyError
import com.remotly.app.files.ImagePaste
import com.remotly.app.notify.TerminalNotifications
import com.remotly.app.platform.readClipboardText
import com.remotly.app.session.HostKeyDecision
import com.remotly.app.session.SshHostKeyPrompt
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTab
import com.remotly.app.session.SshTabKind
import com.remotly.app.session.SshTabPhase
import com.remotly.app.session.neighborTab
import com.remotly.app.session.shouldShowTabStrip
import com.remotly.app.settings.SettingsState
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshModule
import com.remotly.app.ui.Routes
import com.remotly.app.ui.openRoute
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.components.DiscardChangesDialog
import com.remotly.app.ui.terminal.FocusPolicy
import com.remotly.app.ui.terminal.TerminalPane
import com.remotly.app.ui.terminal.rememberTerminalHandle
import com.remotly.app.ui.terminal.terminalTabSwipe
import com.remotly.app.ui.terminal.ModifierKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class HostLookup(val host: SshHost?, val error: RemotlyError?)

private fun hostDisplayName(host: SshHost): String =
    host.displayName.ifBlank { "${host.username}@${host.host}" }

private fun tabPhase(phase: SshTabPhase): TerminalTabPhase = when (phase) {
    SshTabPhase.Connecting, SshTabPhase.HostKey -> TerminalTabPhase.Connecting
    SshTabPhase.Active -> TerminalTabPhase.Active
    SshTabPhase.Closed -> TerminalTabPhase.Ended
    SshTabPhase.Failed -> TerminalTabPhase.Gone
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SshTerminalScreen(hostId: String, nav: NavHostController) {
    val hostState by SshSessions.state(hostId).collectAsStateWithLifecycle()
    val settings by SettingsState.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val workspace = com.remotly.app.ui.LocalWorkspaceActions.current
    val layout = com.remotly.app.ui.LocalWorkspaceLayout.current
    val hardwareKeyboard = com.remotly.app.ui.hardwareKeyboardAttached()
    var temporaryKeys by rememberSaveable(hostId) { mutableStateOf(false) }
    var contextMenuOpen by remember { mutableStateOf(false) }
    var terminalFocused by remember { mutableStateOf(false) }

    fun notify(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    var lookup by remember(hostId) { mutableStateOf<HostLookup?>(null) }
    LaunchedEffect(hostId) {
        if (hostId.isEmpty()) {
            lookup = HostLookup(null, RemotlyError(ErrorKind.Unknown, "No host was specified for this terminal."))
            return@LaunchedEffect
        }
        lookup = withContext(Dispatchers.IO) {
            runCatching {
                val store = SshModule.store ?: throw RemotlyException(ErrorKind.Storage)
                store.get(hostId)
            }.fold(
                onSuccess = { found ->
                    if (found == null) {
                        HostLookup(null, RemotlyError(ErrorKind.Unknown, "This host is no longer saved on this device."))
                    } else {
                        HostLookup(found, null)
                    }
                },
                onFailure = { e -> HostLookup(null, toRemotlyError(e, ErrorKind.Storage)) },
            )
        }
    }
    val host = lookup?.host

    // A files tab sits in this strip beside the shells: the browser is a
    // place on the host, not a screen to leave the terminal for.
    val shellTabs = hostState.tabs.tabs.filter { it.kind != SshTabKind.Workspace }
    val activeTab: SshTab? = shellTabs.firstOrNull { it.sessionId == hostState.tabs.activeSessionId }
    val filesTab = activeTab?.takeIf { it.kind == SshTabKind.Files }
    val companionFiles = if (workspace.companionRequested) shellTabs.lastOrNull { it.kind == SshTabKind.Files } else null
    val shownFiles = filesTab ?: companionFiles
    val filesNavigation = remember(shownFiles?.sessionId) { FilesNavigation() }
    val browserStates = remember(hostId) { mutableMapOf<String, com.remotly.app.ui.files.FilesBrowserState>() }
    val shellSelected = activeTab?.kind == SshTabKind.Shell || (workspace.companionRequested && terminalFocused)

    // The shell the terminal keeps showing while a files tab is in front.
    var lastShellId by rememberSaveable(hostId) { mutableStateOf<String?>(null) }
    LaunchedEffect(activeTab?.sessionId, activeTab?.kind) {
        val shell = activeTab?.takeIf { it.kind == SshTabKind.Shell } ?: return@LaunchedEffect
        lastShellId = shell.sessionId
    }

    // A files tab owns no session, so the terminal underneath stays on the
    // shell that was last in front instead of being rekeyed to an id with
    // nothing behind it.
    val paneSessionKey = if (filesTab == null && activeTab?.kind == SshTabKind.Shell) activeTab.sessionId else
        shellTabs.firstOrNull { it.sessionId == lastShellId && it.kind == SshTabKind.Shell }?.sessionId
            ?: shellTabs.lastOrNull { it.kind == SshTabKind.Shell }?.sessionId.orEmpty()

    // Coming back from a workspace tab leaves that terminal active, which
    // this screen does not show. Land on the shell tab that was last open.
    LaunchedEffect(hostState.tabs.activeSessionId, shellTabs) {
        if (activeTab != null || shellTabs.isEmpty()) return@LaunchedEffect
        val last = shellTabs.firstOrNull { it.sessionId == lastShellId } ?: shellTabs.lastOrNull() ?: return@LaunchedEffect
        SshSessions.selectTab(hostId, last.sessionId)
    }

    // The first tab opens only once the always-mounted terminal below has
    // measured a real grid. A session started against the 80x24 placeholder
    // gets a pty whose row count does not match the screen.
    LaunchedEffect(hostId, host, hostState.sized) {
        if (host == null) return@LaunchedEffect
        if (!SshSessions.hostStarted(hostId) && hostState.sized) {
            SshSessions.openTab(hostId, kind = SshTabKind.Shell)
        }
    }

    val terminal = rememberTerminalHandle()
    val focusPolicy = remember { FocusPolicy(autoOpen = settings.openKeyboardOnTerminal) }
    val imeVisible = WindowInsets.isImeVisible
    var imeStateObserved by remember { mutableStateOf(false) }
    LaunchedEffect(imeVisible) {
        if (imeVisible) {
            focusPolicy.onKeyboardShown()
        } else if (imeStateObserved) {
            focusPolicy.onKeyboardHidden()
        }
        imeStateObserved = true
    }
    // The browser is not a place to type into the shell, so the keyboard
    // comes down with the files tab and stays down until asked for again.
    LaunchedEffect(filesTab?.sessionId) {
        if (filesTab != null && !workspace.companionRequested) terminal.hideKeyboard()
    }
    // Session of the last terminal that reported ready; a ready for another
    // session is a tab switch. Decided in the callback, because an effect
    // keyed on the active tab runs only after the new view has reported ready.
    var readySessionKey by remember { mutableStateOf<String?>(null) }
    val requestKeyboard = rememberUpdatedState {
        // Through the view, which focuses itself first. Asking the window
        // insets controller instead only worked when the terminal already
        // held focus, and it does not hold focus before its first tap.
        if (focusPolicy.requestFocus()) terminal.openKeyboard()
    }


    fun selectSession(sessionId: String) {
        SshSessions.selectTab(hostId, sessionId)
    }

    var pendingNotify by remember { mutableStateOf<Pair<String, String>?>(null) }
    val notifyPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        val pending = pendingNotify
        pendingNotify = null
        if (granted && pending != null) TerminalNotifications.show(context, pending.first, pending.second)
    }

    // Requested the moment a remote program actually asks for a notification,
    // not proactively on opening the screen. A refusal here is quiet: the
    // terminal session must not be affected either way.
    fun notifyFromTerminal(notifyTitle: String, notifyBody: String) {
        if (TerminalNotifications.canPost(context)) {
            TerminalNotifications.show(context, notifyTitle, notifyBody)
        } else if (TerminalNotifications.needsRuntimePermission()) {
            pendingNotify = notifyTitle to notifyBody
            notifyPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    var imageTarget by remember { mutableStateOf<String?>(null) }
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        val target = imageTarget
        if (uri != null && hostId.isNotEmpty() && target != null) {
            scope.launch {
                notify("Uploading the image")
                when (val result = ImagePaste.paste(context, hostId, uri)) {
                    is ImagePaste.Result.Ok -> {
                        SshSessions.sendInput(hostId, target, "${result.path} ".toByteArray(Charsets.UTF_8))
                        notify("Pasted the image path")
                    }
                    is ImagePaste.Result.Failed -> notify(result.message)
                }
            }
        }
    }

    // The latched Ctrl, Alt, or Shift modifier for the extra key row. Owned
    // here, not inside the row, so committed IME input can consume it too.
    var latchedModifier by remember { mutableStateOf<ModifierKey?>(null) }
    var hasSelection by remember { mutableStateOf(false) }
    var renameTargetId by remember(hostId) { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var closeTargetId by remember { mutableStateOf<String?>(null) }

    fun goBack() {
        when {
            filesTab == null && hasSelection -> terminal.clearSelection()
            imeVisible -> terminal.hideKeyboard()
            filesTab != null -> {
                if (filesNavigation.goBack()) return
                val shell = shellTabs.firstOrNull { it.sessionId == lastShellId && it.kind == SshTabKind.Shell }
                    ?: shellTabs.lastOrNull { it.kind == SshTabKind.Shell }
                if (shell != null) selectSession(shell.sessionId) else nav.popBackStack()
            }
            else -> nav.popBackStack()
        }
    }

    fun closeFilesTab(sessionId: String) {
        browserStates.remove(sessionId)
        SshSessions.closeTab(hostId, sessionId)
        if (workspace.companionRequested && shellTabs.count { it.kind == SshTabKind.Files } <= 1) workspace.toggleCompanion()
    }

    val title = host?.let(::hostDisplayName) ?: "SSH"
    // The header already names the host. The second line identifies the
    // current surface instead of repeating user@host:port.
    val subtitle = activeTab?.title

    val banner = when {
        activeTab == null -> null
        activeTab.phase == SshTabPhase.Connecting -> TerminalBanner(TerminalBannerTone.Busy, "Connecting")
        activeTab.phase == SshTabPhase.Closed -> TerminalBanner(
            tone = TerminalBannerTone.Info,
            message = activeTab.detail.ifEmpty { "The session is closed." },
            actionLabel = "Reconnect",
            onAction = { SshSessions.reconnectTab(hostId, activeTab.sessionId) },
        )
        // A failed connection gets a persistent card below, not a two-line
        // overlay that can disappear behind terminal output.
        else -> null
    }

    val failedTab = activeTab?.takeIf { it.phase == SshTabPhase.Failed }
    val failure = when {
        failedTab != null -> TerminalFailure(
            title = "Connection failed",
            message = "Remotly could not establish this SSH connection.",
            actionLabel = "Retry",
            onAction = { SshSessions.reconnectTab(hostId, failedTab.sessionId) },
            secondaryActionLabel = "Edit connection",
            onSecondaryAction = { nav.openRoute(Routes.hostEditor(hostId)) },
            details = failedTab.detail.ifBlank { "No additional connection details were provided." },
        )
        lookup?.error != null -> TerminalFailure(
            title = lookup?.error?.message.orEmpty(),
            actionLabel = "Go back",
            onAction = ::goBack,
        )
        host != null && hostState.sized && shellTabs.isEmpty() -> TerminalFailure(
            icon = Icons.Filled.Terminal,
            title = "No open sessions",
            actionLabel = "New session",
            onAction = { SshSessions.openTab(hostId, kind = SshTabKind.Shell) },
        )
        else -> null
    }

    val canAdd = SshSessions.canAddTab(hostId)
    fun hideFiles() {
        if (workspace.companionRequested) workspace.toggleCompanion()
        if (paneSessionKey.isNotEmpty()) {
            SshSessions.selectTab(hostId, paneSessionKey)
            terminal.focus()
        } else shownFiles?.let { closeFilesTab(it.sessionId) }
    }
    fun dockFiles() {
        val id = shownFiles?.sessionId ?: return
        if (workspace.companionRequested) workspace.toggleCompanion()
        SshSessions.selectTab(hostId, id)
    }
    fun openFiles() {
        if (workspace.companionRequested || filesTab != null) {
            hideFiles()
            return
        }
        val existing = shellTabs.lastOrNull { it.kind == SshTabKind.Files }
        val id = existing?.sessionId ?: SshSessions.openTab(hostId, kind = SshTabKind.Files)
        if (id == null) notify("This host has no room for another files tab")
        else if (layout.companion && !workspace.companionRequested && paneSessionKey.isNotEmpty()) {
            SshSessions.selectTab(hostId, paneSessionKey)
            workspace.toggleCompanion()
        }
        else SshSessions.selectTab(hostId, id)
    }
    val actions = listOf(
        ScreenAction("switch", Icons.Filled.SwapHoriz, "Switch workspace", workspace.selectWork),
        ScreenAction("settings", Icons.Filled.Palette, "Terminal appearance", { nav.openRoute(Routes.TERMINAL_APPEARANCE) }),
        ScreenAction("keyboard", if (imeVisible) Icons.Filled.KeyboardHide else Icons.Filled.Keyboard, if (imeVisible) "Hide keyboard" else "Show keyboard", { if (imeVisible) terminal.hideKeyboard() else requestKeyboard.value() }),
        ScreenAction("extra-keys", Icons.Filled.Keyboard, if (temporaryKeys) "Hide temporary keys" else "Show extra keys", { temporaryKeys = !temporaryKeys }),
        ScreenAction("companion", Icons.Filled.ViewColumn, if (workspace.companionRequested) "Use Files as a tab" else "Files beside terminal", { if (workspace.companionRequested) dockFiles() else openFiles() }, enabled = layout.companion || workspace.companionRequested),
        ScreenAction(
            key = "files",
            icon = Icons.Filled.Folder,
            title = "Files",
            enabled = hostId.isNotEmpty(),
            onClick = ::openFiles,
        ),
        ScreenAction(
            key = "new",
            icon = Icons.Filled.Add,
            title = "New session",
            enabled = canAdd,
            onClick = {
                if (SshSessions.openTab(hostId, kind = SshTabKind.Shell) == null) {
                    notify("This host has no room for another session")
                }
            },
        ),
        ScreenAction(
            key = "rename",
            icon = Icons.Filled.Edit,
            title = if (filesTab != null) "Rename files tab" else "Rename session",
            enabled = activeTab != null,
            onClick = { renameTargetId = activeTab?.sessionId },
        ),
        ScreenAction(
            key = "close",
            icon = Icons.Filled.Close,
            title = if (activeTab?.kind == SshTabKind.Files) "Close files tab" else "Close session",
            enabled = activeTab != null,
            destructive = activeTab?.kind == SshTabKind.Shell,
            onClick = {
                activeTab?.let { active ->
                    if (active.kind == SshTabKind.Files) {
                        closeFilesTab(active.sessionId)
                    } else {
                        closeTargetId = active.sessionId
                    }
                }
            },
        ),
        ScreenAction(
            key = "selectAll",
            icon = Icons.Filled.SelectAll,
            title = "Select all",
            enabled = shellSelected,
            onClick = { terminal.selectAll() },
        ),
        ScreenAction(
            key = "copy",
            icon = Icons.Filled.ContentCopy,
            title = "Copy",
            enabled = shellSelected && hasSelection,
            onClick = {
                val text = terminal.copySelection()
                if (text.isNullOrEmpty()) notify("Nothing is selected") else notify("Copied")
            },
        ),
        ScreenAction(
            key = "paste",
            icon = Icons.Filled.ContentPaste,
            title = "Paste",
            enabled = shellSelected,
            onClick = {
                val text = readClipboardText(context)
                if (text.isNullOrEmpty()) {
                    notify("The clipboard is empty")
                } else {
                    // Through the view, not the session's input: a program
                    // that asked for bracketed paste has to receive the whole
                    // block as one, not as a run of Enter keys.
                    terminal.paste(text)
                }
            },
        ),
        ScreenAction(
            key = "paste-image",
            icon = Icons.Filled.Image,
            title = "Paste an image",
            enabled = shellSelected,
            onClick = { imageTarget = paneSessionKey; imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ),
        ScreenAction(
            key = "disconnect",
            icon = Icons.Filled.PowerOff,
            title = "Disconnect",
            destructive = true,
            onClick = { confirmDisconnect = true },
        ),
    ).filter { it.key != "companion" || layout.companion || workspace.companionRequested }

    Box(Modifier.fillMaxSize()) {
        TerminalScaffold(
            title = title,
            subtitle = subtitle,
            onBack = ::goBack,
            actions = actions,
            actionGroups = listOf(
                TerminalActionGroup(
                    title = "Sessions and files",
                    actionKeys = setOf("files", "new", "rename", "close"),
                ),
                TerminalActionGroup(
                    title = "Clipboard",
                    actionKeys = setOf("selectAll", "copy", "paste", "paste-image"),
                ),
                TerminalActionGroup("Appearance and input", setOf("settings", "keyboard", "extra-keys")),
                TerminalActionGroup("Navigation", setOf("switch", "companion")),
                TerminalActionGroup(
                    title = "Connection",
                    actionKeys = setOf("disconnect"),
                ),
            ),
            onFiles = ::openFiles,
            filesOpen = workspace.companionRequested || filesTab != null,
            contextMenuOpen = contextMenuOpen,
            onContextMenuDismiss = { contextMenuOpen = false },
            tabs = if (shouldShowTabStrip(shellTabs)) {
                shellTabs.map { TerminalTab(it.sessionId, it.title, tabPhase(it.phase)) }
            } else {
                emptyList()
            },
            activeTabId = hostState.tabs.activeSessionId,
            onSelectTab = ::selectSession,
            onCloseTab = { closedId ->
                val closed = shellTabs.firstOrNull { it.sessionId == closedId }
                if (closed?.kind == SshTabKind.Files) {
                    closeFilesTab(closedId)
                } else {
                    closeTargetId = closedId
                }
            },
            onRenameTab = { sessionId -> renameTargetId = sessionId },
            onAddTab = {
                if (SshSessions.openTab(hostId, kind = SshTabKind.Shell) == null) {
                    notify("This host has no room for another session")
                }
            },
            canAddTab = canAdd,
            banner = banner,
            failure = failure,
            showKeyRow = com.remotly.app.ui.terminal.extraKeysVisible(settings.extraKeyVisibility, terminalFocused,
                imeVisible, hardwareKeyboard, temporarilyRequested = temporaryKeys) &&
                (filesTab == null || workspace.companionRequested),
            activeModifier = latchedModifier,
            onKey = { key -> terminal.sendKey(key) },
            onModifier = { pressed ->
                terminal.clearComposition()
                latchedModifier = if (latchedModifier == pressed) null else pressed
            },
            keyRepeatDelayMs = settings.keyRepeatDelayMs,
            haptics = settings.hapticFeedback,
            onKeyboard = { requestKeyboard.value() },
        ) { paneModifier ->
            FilesCompanion(filesSelected = filesTab != null, requested = workspace.companionRequested,
                onFilesFocus = { shownFiles?.let { SshSessions.selectTab(hostId, it.sessionId) } }, modifier = paneModifier,
                onClose = ::hideFiles, onDock = ::dockFiles,
                files = shownFiles?.let { shown ->
                    { filesModifier ->
                        val browser = browserStates.getOrPut(shown.sessionId) {
                            val stored = com.remotly.app.session.FilesTabs.state(shown.sessionId)
                            com.remotly.app.ui.files.FilesBrowserState(stored.cwd, stored.history, stored.query, stored.searchOpen)
                        }
                        FilesScreen(hostId, { closeFilesTab(shown.sessionId) }, shown.sessionId, filesNavigation, filesModifier, browser)
                    }
                }, terminal = { terminalModifier ->
            TerminalPane(
                sessionKey = paneSessionKey,
                fontSizeSp = settings.terminalFontSize,
                cursorStyle = settings.cursorStyle,
                onInput = { bytes -> SshSessions.sendInput(hostId, paneSessionKey, bytes) },
                inputModifier = latchedModifier,
                onModifierConsumed = { latchedModifier = null },
                onInputNotice = ::notify,
                onPtyWrite = { bytes -> SshSessions.sendInput(hostId, paneSessionKey, bytes) },
                onResize = { cols, rows ->
                    if (paneSessionKey.isEmpty()) SshSessions.measureHost(hostId, cols, rows)
                    else SshSessions.resizeSession(hostId, paneSessionKey, cols, rows)
                },
                onLinkCopied = { link -> notify("Copied $link") },
                onFontSizeChanged = { sp ->
                    SettingsState.update(onFailure = { notify("The font size could not be saved.") }) {
                        it.copy(terminalFontSize = sp)
                    }
                },
                theme = settings.terminalTheme.resolve(),
                touchpadGesturesEnabled = !workspace.modalOpen && !contextMenuOpen &&
                    (filesTab == null || workspace.companionRequested),
                legacyTouchNavigationEnabled = settings.navigationGesturesEnabled,
                onContextMenuRequested = { _, _ -> contextMenuOpen = true },
                modifier = terminalModifier.terminalTabSwipe(
                    enabled = settings.navigationGesturesEnabled && shellTabs.size > 1 && !hasSelection,
                ) { direction ->
                    val next = neighborTab(shellTabs, hostState.tabs.activeSessionId, direction) { it.sessionId }
                    if (next != null) selectSession(next)
                },
                handle = terminal,
                // The setting is honoured here rather than on a timer: this
                // is the first moment the terminal can actually take input.
                onReady = {
                    val previous = readySessionKey
                    readySessionKey = paneSessionKey
                    val open = if (previous != null && previous != paneSessionKey) {
                        focusPolicy.onSessionSwitch()
                    } else {
                        focusPolicy.onReady()
                    }
                    if (open && !hardwareKeyboard) terminal.openKeyboard() else if (filesTab == null) terminal.focus()
                },
                onNotify = ::notifyFromTerminal,
                onFocusChanged = { focused ->
                    terminalFocused = focused
                    focusPolicy.onFocusChange(focused)
                    if (focused && paneSessionKey.isNotEmpty()) SshSessions.selectTab(hostId, paneSessionKey)
                },
                onSelectionChanged = { hasSelection = it },
            )
            })
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }

    val closeTarget = shellTabs.firstOrNull { it.sessionId == closeTargetId }
    if (closeTarget != null) {
        AlertDialog(
            onDismissRequest = { closeTargetId = null },
            title = { Text("Close ${closeTarget.title}?") },
            text = { Text("The remote shell and anything still running in it will end.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        SshSessions.closeTab(hostId, closeTarget.sessionId)
                        closeTargetId = null
                    },
                ) { Text("Close", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { closeTargetId = null }) { Text("Keep open") } },
        )
    }

    if (confirmDisconnect) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            icon = { Icon(Icons.Filled.Warning, contentDescription = null) },
            title = { Text("Disconnect this host?") },
            text = { Text("All open terminal sessions for this host will be closed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        SshSessions.closeHost(hostId)
                        nav.popBackStack()
                    },
                ) { Text("Disconnect", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }) { Text("Keep connected") }
            },
        )
    }

    val activeHostKeyPrompt = hostState.hostKeyPrompt?.takeIf { it.sessionId == hostState.tabs.activeSessionId }
    if (activeHostKeyPrompt != null) {
        HostKeyDialog(
            prompt = activeHostKeyPrompt,
            hostLabel = subtitle ?: title,
            onAccept = {
                val decision = if (activeHostKeyPrompt.changed) HostKeyDecision.Replace else HostKeyDecision.Accept
                SshSessions.answerHostKey(hostId, decision)
            },
            onReject = { SshSessions.answerHostKey(hostId, HostKeyDecision.Reject) },
        )
    }

    val renameTarget = shellTabs.firstOrNull { it.sessionId == renameTargetId }
    if (renameTarget != null) {
        RenameTabDialog(
            currentLabel = renameTarget.title,
            onDismiss = { renameTargetId = null },
            onConfirm = { name ->
                SshSessions.renameTab(hostId, renameTarget.sessionId, name)
                renameTargetId = null
            },
        )
    }
}

@Composable
private fun RenameTabDialog(currentLabel: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var draft by rememberSaveable(currentLabel) { mutableStateOf(currentLabel) }
    var confirmDiscard by remember(currentLabel) { mutableStateOf(false) }
    fun requestDismiss() {
        if (draft != currentLabel) confirmDiscard = true else onDismiss()
    }
    AlertDialog(
        onDismissRequest = ::requestDismiss,
        title = { Text("Rename tab") },
        text = {
            RemotlyTextField(
                value = draft,
                onValueChange = { draft = it },
                label = { Text("Session name") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(draft) }, enabled = draft.isNotBlank()) { Text("Rename") }
        },
        dismissButton = {
            TextButton(onClick = ::requestDismiss) { Text("Cancel") }
        },
    )
    if (confirmDiscard) {
        DiscardChangesDialog(
            onDiscard = {
                confirmDiscard = false
                onDismiss()
            },
            onKeepEditing = { confirmDiscard = false },
        )
    }
}

/**
 * Not dismissable in effect: every way of leaving without a decision
 * (the system back gesture, a tap outside) answers reject rather than
 * leaving the connection in limbo or defaulting to trust.
 */
@Composable
private fun HostKeyDialog(
    prompt: SshHostKeyPrompt,
    hostLabel: String,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onReject,
        title = { Text(if (prompt.changed) "Host key changed" else "Verify host key") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (prompt.changed) {
                        "The key $hostLabel presents no longer matches the one saved for it. " +
                            "This can mean the server was reinstalled, or that something is " +
                            "intercepting the connection."
                    } else {
                        "$hostLabel presented a new host key. Verify the fingerprint before trusting it."
                    },
                )
                Text(prompt.algorithm, style = MaterialTheme.typography.labelMedium)
                Text(prompt.fingerprint, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = onAccept) {
                Text(if (prompt.changed) "Replace and connect" else "Trust and connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onReject) { Text("Reject") }
        },
    )
}
