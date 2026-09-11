package com.remotly.app.ui.screens

// Tabbed shells over one SSH host.
//
// Sessions live in SshSessions, outside this composable entirely, so
// navigating back leaves them running; this screen only renders whatever the
// store currently holds. Several tabs may be open at once, but only the
// active one draws into the terminal; the rest stay connected and keep
// buffering their output in TerminalStore.

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material.icons.filled.PowerOff
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
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
import com.remotly.app.session.HostKeyDecision
import com.remotly.app.session.SshHostKeyPrompt
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTab
import com.remotly.app.session.SshTabKind
import com.remotly.app.session.SshTabPhase
import com.remotly.app.session.neighborTab
import com.remotly.app.session.shouldClaimSwipe
import com.remotly.app.session.shouldShowTabStrip
import com.remotly.app.session.swipeDirection
import com.remotly.app.settings.SettingsState
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshModule
import com.remotly.app.ui.Routes
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.terminal.FocusPolicy
import com.remotly.app.ui.terminal.TerminalPane
import com.remotly.app.ui.terminal.rememberTerminalHandle
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

/**
 * A horizontal swipe that moves to the neighbouring tab.
 *
 * Claimed only once the drag is clearly horizontal, using the same
 * thresholds every tab strip in the app swipes by. A second finger hands the
 * whole gesture back immediately: two fingers on the terminal are its pinch,
 * never a tab switch.
 */
private fun Modifier.terminalSwipeNav(enabled: Boolean, onSwipe: (Int) -> Unit): Modifier {
    if (!enabled) return this
    return pointerInput(onSwipe) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var totalDx = 0f
            var totalDy = 0f
            var claimed = false
            val pointerId = down.id
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.size > 1) return@awaitEachGesture
                val change = event.changes.firstOrNull { it.id == pointerId } ?: return@awaitEachGesture
                if (!change.pressed) {
                    if (claimed) {
                        val velocity = tracker.calculateVelocity()
                        val direction = swipeDirection(totalDx, velocity.x / 1000f)
                        if (direction != 0) onSwipe(direction)
                        change.consume()
                    }
                    return@awaitEachGesture
                }
                tracker.addPosition(change.uptimeMillis, change.position)
                val delta = change.positionChange()
                totalDx += delta.x
                totalDy += delta.y
                if (!claimed) {
                    if (shouldClaimSwipe(totalDx, totalDy)) {
                        claimed = true
                        change.consume()
                    }
                } else {
                    change.consume()
                }
            }
        }
    }
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

    val shellTabs = hostState.tabs.tabs.filter { it.kind == SshTabKind.Shell }
    val activeTab: SshTab? = shellTabs.firstOrNull { it.sessionId == hostState.tabs.activeSessionId }

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
    LaunchedEffect(imeVisible) {
        if (imeVisible) focusPolicy.onKeyboardShown() else focusPolicy.onKeyboardHidden()
    }
    val requestKeyboard = rememberUpdatedState {
        // Through the view, which focuses itself first. Asking the window
        // insets controller instead only worked when the terminal already
        // held focus, and it does not hold focus before its first tap.
        if (focusPolicy.requestFocus()) terminal.openKeyboard()
    }

    fun goBack() {
        nav.popBackStack()
    }

    fun selectSession(sessionId: String) {
        SshSessions.selectTab(hostId, sessionId)
        focusPolicy.onSessionSwitch()
    }

    // The latched Ctrl/Alt modifier for the extra key row. Owned here, not
    // inside the row, so committed IME input can consume it too: a Ctrl+C
    // typed as "hold Ctrl, tap c" latches on the row and applies to the next
    // character the keyboard commits, not just to another row key.
    var latchedModifier by remember { mutableStateOf<ModifierKey?>(null) }

    var renameTargetId by remember(hostId) { mutableStateOf<String?>(null) }

    val title = host?.let(::hostDisplayName) ?: "SSH"
    val subtitle = host?.let { "${it.username}@${it.host}:${it.port}" }

    val banner = when {
        activeTab == null -> null
        activeTab.phase == SshTabPhase.Connecting -> TerminalBanner(TerminalBannerTone.Busy, "Connecting")
        activeTab.phase == SshTabPhase.Closed -> TerminalBanner(
            tone = TerminalBannerTone.Info,
            message = activeTab.detail.ifEmpty { "The session is closed." },
            actionLabel = "Reconnect",
            onAction = { SshSessions.reconnectTab(hostId, activeTab.sessionId) },
        )
        activeTab.phase == SshTabPhase.Failed -> TerminalBanner(
            tone = TerminalBannerTone.Error,
            message = activeTab.detail.ifEmpty { "The connection failed." },
            actionLabel = "Retry",
            onAction = { SshSessions.reconnectTab(hostId, activeTab.sessionId) },
        )
        else -> null
    }

    val failure = when {
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
            onClick = { nav.navigate(Routes.files(hostId)) },
        ),
        ScreenAction(
            key = "new",
            icon = Icons.Filled.Add,
            title = "New session",
            enabled = canAdd,
            onClick = { SshSessions.openTab(hostId, kind = SshTabKind.Shell) },
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
            title = "Close session",
            enabled = activeTab != null,
            onClick = { activeTab?.let { SshSessions.closeTab(hostId, it.sessionId) } },
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
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
                    ?.getItemAt(0)?.coerceToText(context)?.toString()
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
            key = "disconnect",
            icon = Icons.Filled.PowerOff,
            title = "Disconnect",
            destructive = true,
            onClick = {
                SshSessions.closeHost(hostId)
                goBack()
            },
        ),
    )

    Box(Modifier.fillMaxSize()) {
        TerminalScaffold(
            title = title,
            subtitle = subtitle,
            onBack = ::goBack,
            actions = actions,
            tabs = if (shouldShowTabStrip(shellTabs)) {
                shellTabs.map { TerminalTab(it.sessionId, it.title, tabPhase(it.phase)) }
            } else {
                emptyList()
            },
            activeTabId = hostState.tabs.activeSessionId,
            onSelectTab = ::selectSession,
            onCloseTab = { sessionId -> SshSessions.closeTab(hostId, sessionId) },
            onRenameTab = { sessionId -> renameTargetId = sessionId },
            onAddTab = { SshSessions.openTab(hostId, kind = SshTabKind.Shell) },
            canAddTab = canAdd,
            banner = banner,
            failure = failure,
            showKeyRow = settings.showExtraKeyRow,
            activeModifier = latchedModifier,
            onKey = { key ->
                val result = transformKey(key, latchedModifier)
                if (result != null) {
                    if (result.clearModifier) latchedModifier = null
                    if (result.notice != null) notify(result.notice)
                    SshSessions.sendInput(hostId, result.bytes)
                }
            },
            onModifier = { pressed -> latchedModifier = if (latchedModifier == pressed) null else pressed },
            keyRepeatDelayMs = settings.keyRepeatDelayMs,
            haptics = settings.hapticFeedback,
            onKeyboard = { requestKeyboard.value() },
        ) { paneModifier ->
            TerminalPane(
                sessionKey = hostState.tabs.activeSessionId ?: "",
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
                modifier = paneModifier.terminalSwipeNav(enabled = shellTabs.size > 1) { direction ->
                    val next = neighborTab(shellTabs, hostState.tabs.activeSessionId, direction) { it.sessionId }
                    if (next != null) selectSession(next)
                },
                handle = terminal,
                // The setting is honoured here rather than on a timer: this
                // is the first moment the terminal can actually take input.
                onReady = { if (settings.openKeyboardOnTerminal) requestKeyboard.value() },
            )
        }

        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
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
            OutlinedTextField(
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
