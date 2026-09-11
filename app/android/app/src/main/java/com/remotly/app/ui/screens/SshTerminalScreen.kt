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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
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
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.terminal.FocusPolicy
import com.remotly.app.ui.terminal.TerminalPane
import com.remotly.app.ui.terminal.rememberTerminalHandle
import com.remotly.app.ui.terminal.terminalTabSwipe
import com.remotly.app.ui.terminal.ModifierKey
import com.remotly.app.ui.terminal.applyModifier
import com.remotly.app.ui.terminal.transformKey
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

    // The shell the terminal keeps showing while a files tab is in front.
    var lastShellId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(activeTab?.sessionId, activeTab?.kind) {
        val shell = activeTab?.takeIf { it.kind == SshTabKind.Shell } ?: return@LaunchedEffect
        lastShellId = shell.sessionId
    }

    // A files tab owns no session, so the terminal underneath stays on the
    // shell that was last in front instead of being rekeyed to an id with
    // nothing behind it.
    val paneSessionKey = (if (filesTab == null) hostState.tabs.activeSessionId else lastShellId) ?: ""

    // Coming back from a workspace tab leaves that terminal active, which
    // this screen does not show. Land on the shell tab that was last open.
    LaunchedEffect(hostState.tabs.activeSessionId, shellTabs) {
        if (activeTab != null || shellTabs.isEmpty()) return@LaunchedEffect
        val last = shellTabs.lastOrNull() ?: return@LaunchedEffect
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
        if (filesTab != null) terminal.hideKeyboard()
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

    fun goBack() {
        val files = filesTab
        if (files != null) {
            SshSessions.closeTab(hostId, files.sessionId)
        } else {
            nav.popBackStack()
        }
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

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri: Uri? ->
        if (uri != null && hostId.isNotEmpty()) {
            scope.launch {
                notify("Uploading the image")
                when (val result = ImagePaste.paste(context, hostId, uri)) {
                    is ImagePaste.Result.Ok -> {
                        ImagePaste.typeResult(hostId, result.path)
                        notify("Pasted the image path")
                    }
                    is ImagePaste.Result.Failed -> notify(result.message)
                }
            }
        }
    }

    // The latched Ctrl/Alt modifier for the extra key row. Owned here, not
    // inside the row, so committed IME input can consume it too.
    var latchedModifier by remember { mutableStateOf<ModifierKey?>(null) }
    var hasSelection by remember { mutableStateOf(false) }
    var renameTargetId by remember(hostId) { mutableStateOf<String?>(null) }
    var confirmDisconnect by remember { mutableStateOf(false) }
    var closeTargetId by remember { mutableStateOf<String?>(null) }

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
            onSecondaryAction = { nav.navigate(Routes.hostEditor(hostId)) },
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
    val actions = listOf(
        ScreenAction(
            key = "files",
            icon = Icons.Filled.Folder,
            title = "Files",
            enabled = hostId.isNotEmpty(),
            onClick = {
                if (SshSessions.openTab(hostId, kind = SshTabKind.Files) == null) {
                    notify("This host has no room for another files tab")
                }
            },
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
            title = "Rename session",
            enabled = activeTab != null,
            onClick = { renameTargetId = activeTab?.sessionId },
        ),
        ScreenAction(
            key = "close",
            icon = Icons.Filled.Close,
            title = if (activeTab?.kind == SshTabKind.Files) "Close files tab" else "Close session",
            enabled = activeTab != null,
            onClick = {
                activeTab?.let { active ->
                    if (active.kind == SshTabKind.Files) {
                        SshSessions.closeTab(hostId, active.sessionId)
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
            enabled = activeTab != null,
            onClick = { terminal.selectAll() },
        ),
        ScreenAction(
            key = "copy",
            icon = Icons.Filled.ContentCopy,
            title = "Copy",
            enabled = activeTab != null,
            onClick = {
                val text = terminal.copySelection()
                if (text.isNullOrEmpty()) notify("Nothing is selected") else notify("Copied")
            },
        ),
        ScreenAction(
            key = "paste",
            icon = Icons.Filled.ContentPaste,
            title = "Paste",
            enabled = activeTab != null,
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
            enabled = activeTab != null,
            onClick = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        ),
        ScreenAction(
            key = "disconnect",
            icon = Icons.Filled.PowerOff,
            title = "Disconnect",
            destructive = true,
            onClick = { confirmDisconnect = true },
        ),
    )

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
                TerminalActionGroup(
                    title = "Connection",
                    actionKeys = setOf("disconnect"),
                ),
            ),
            onActionsMenuOpen = { terminal.hideKeyboard() },
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
                    SshSessions.closeTab(hostId, closedId)
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
            showKeyRow = settings.showExtraKeyRow,
            activeModifier = latchedModifier,
            onKey = { key ->
                terminal.clearComposition()
                val result = transformKey(key, latchedModifier)
                if (result != null) {
                    if (result.clearModifier) latchedModifier = null
                    if (result.notice != null) notify(result.notice)
                    SshSessions.sendInput(hostId, result.bytes)
                }
            },
            onModifier = { pressed ->
                terminal.clearComposition()
                latchedModifier = if (latchedModifier == pressed) null else pressed
            },
            keyRepeatDelayMs = settings.keyRepeatDelayMs,
            haptics = settings.hapticFeedback,
            onKeyboard = { requestKeyboard.value() },
            pane = if (filesTab == null) null else {
                {
                    FilesScreen(
                        hostId = hostId,
                        onClose = { SshSessions.closeTab(hostId, filesTab.sessionId) },
                        tabId = filesTab.sessionId,
                    )
                }
            },
        ) { paneModifier ->
            TerminalPane(
                sessionKey = paneSessionKey,
                fontSizeSp = settings.terminalFontSize,
                cursorStyle = settings.cursorStyle,
                onInput = { bytes ->
                    val result = applyModifier(bytes, latchedModifier)
                    if (result.clearModifier) latchedModifier = null
                    if (result.notice != null) notify(result.notice)
                    SshSessions.sendInput(hostId, result.bytes)
                },
                onPtyWrite = { bytes -> SshSessions.sendInput(hostId, bytes) },
                onResize = { cols, rows -> SshSessions.resizeHost(hostId, cols, rows) },
                onLinkCopied = { link -> notify("Copied $link") },
                onFontSizeChanged = { sp ->
                    SettingsState.update(onFailure = { notify("The font size could not be saved.") }) {
                        it.copy(terminalFontSize = sp)
                    }
                    notify("Terminal text: $sp sp")
                },
                modifier = paneModifier.terminalTabSwipe(
                    enabled = shellTabs.size > 1 && !hasSelection,
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
                    if (open) terminal.openKeyboard()
                },
                onNotify = ::notifyFromTerminal,
                onFocusChanged = focusPolicy::onFocusChange,
                onSelectionChanged = { hasSelection = it },
            )
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
                        goBack()
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename session") },
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
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
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
