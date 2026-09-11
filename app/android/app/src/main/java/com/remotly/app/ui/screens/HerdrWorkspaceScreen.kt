package com.remotly.app.ui.screens

// A terminal attached to one herdr workspace, with that workspace's tabs.
//
// One terminal per herdr session. Which workspace has focus is session state,
// so a second terminal on the same session could only mirror this one;
// entering another workspace moves this terminal instead. Workspace and tab
// navigation live in HostSidebar; this screen's own overflow menu carries
// only actions that act on the attached terminal's content.
//
// What the tab strip and the sidebar draw comes from HerdrStore, fed by
// herdr's own event stream, so a change made here, from a gesture, or on the
// desktop lands without a timer.

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material3.AlertDialog
import com.remotly.app.ui.components.RemotlyTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.remotly.app.ui.EXPANDED_LAYOUT_MIN_WIDTH_DP
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.remotly.app.herdr.HerdrClient
import com.remotly.app.herdr.HerdrCreateTab
import com.remotly.app.herdr.HerdrError
import com.remotly.app.herdr.HerdrEvent
import com.remotly.app.herdr.HerdrStore
import com.remotly.app.herdr.HerdrWorkspaceOrder
import com.remotly.app.herdr.joinShell
import com.remotly.app.herdr.nextHerdrTab
import com.remotly.app.herdr.nextHerdrWorkspace
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTabPhase
import com.remotly.app.session.findSshTab
import com.remotly.app.settings.SettingsState
import com.remotly.app.ui.Routes
import com.remotly.app.session.SshTabKind
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.terminal.ModifierKey
import com.remotly.app.ui.terminal.TerminalBackground
import com.remotly.app.ui.terminal.TerminalPane
import com.remotly.app.ui.terminal.transformKey
import androidx.compose.ui.platform.LocalContext
import com.remotly.app.notify.TerminalNotifications
import com.remotly.app.ui.terminal.rememberTerminalHandle
import com.remotly.app.ui.terminal.terminalTabSwipe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/** The plugin that carries the actions herdr's own CLI cannot express. */
private const val PLUGIN = "remotly.bridge"

/** How long a transient notice stays on the banner before it clears itself. */
private const val NOTICE_MS = 4_000L

/** A herdr failure, said in the user's terms. */
private fun herdrFailureMessage(e: Throwable): String {
    if (e is HerdrError) {
        // The pane actions in this menu are the plugin's, so a host without
        // it has to be told how to get it rather than shown herdr's error code.
        if (e.code == "plugin_action_not_found") {
            return "This host has no Remotly plugin. Install it with: " +
                "herdr plugin install heavycaffeiner/Remotly/plugin"
        }
        return e.detail
    }
    return "The herdr command failed."
}

/** What a mux gesture over the terminal asks for. */
private enum class MuxAction { TabNext, TabPrevious, WorkspaceNext }

@Composable
fun HerdrWorkspaceScreen(
    hostId: String,
    hostName: String,
    workspaceId: String?,
    label: String?,
    session: String?,
    nav: NavHostController,
) {
    val settings by SettingsState.settings.collectAsStateWithLifecycle()

    // ProcessLifecycleOwner is not on this module's classpath (only
    // lifecycle-runtime-compose and lifecycle-viewmodel-compose are
    // declared), so this is built from the composition's own LocalLifecycleOwner
    // instead, per HerdrStore's documented fallback. Within a NavHost
    // destination that lifecycle is the back stack entry's own: it stops
    // when this screen is covered by a pushed destination and starts again
    // when the user comes back to it, not only when the whole app
    // backgrounds. HerdrStore already re-reads every watched host on a
    // false-to-true transition of this flow, so that one signal is both the
    // app-foreground input HerdrStore asks for and the unconditional
    // re-read this screen needs on every return, including one herdr's own
    // key bindings made while this screen was away.
    val lifecycleOwner = LocalLifecycleOwner.current
    val foregrounded: Flow<Boolean> = remember(lifecycleOwner) {
        callbackFlow {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> trySend(true)
                    Lifecycle.Event.ON_STOP -> trySend(false)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            awaitClose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }
            // The store collects on Dispatchers.Default, and a callbackFlow
            // body runs in the collector's context. Registering a lifecycle
            // observer off the main thread throws.
            .flowOn(Dispatchers.Main.immediate)
    }

    val client = remember { HerdrClient() }
    val store = remember(client, foregrounded) { HerdrStore(client = client, foregrounded = foregrounded) }
    DisposableEffect(store) { onDispose { store.close() } }

    // Acquired synchronously in `remember`, not `DisposableEffect`: the entry
    // has to exist before `hostState` is read below, on this same
    // composition, or the first frame observes the static empty flow and
    // nothing forces a later recomposition onto the real one.
    val subscription = remember(store, hostId, session) { store.subscribe(hostId, session) }
    DisposableEffect(subscription) { onDispose { subscription.close() } }
    val herdr by store.hostState(hostId, session).collectAsStateWithLifecycle()

    var sessionId by remember(hostId) { mutableStateOf<String?>(null) }
    // Null lets wide screens start with their permanent sidebar visible while
    // keeping compact screens closed until the user explicitly opens them.
    var sidebarOpen by remember { mutableStateOf<Boolean?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var renameTabId by remember { mutableStateOf<String?>(null) }
    var renameDraft by remember { mutableStateOf("") }
    var activeModifier by remember { mutableStateOf<ModifierKey?>(null) }
    var hasSelection by remember { mutableStateOf(false) }

    val hostState by SshSessions.state(hostId).collectAsStateWithLifecycle()
    val terminal = rememberTerminalHandle()
    val tab = sessionId?.let { findSshTab(hostState.tabs, it) }

    // Which workspace this terminal is on is herdr's own focus, not a copy of
    // it: the terminal is a herdr client, so it shows whatever the session
    // has focused, whether this screen moved it, the sidebar did, or the
    // desktop did. The route's workspace id is the placeholder for the
    // frames before the first snapshot arrives, and nothing after it.
    val workspaceIdCurrent = if (herdr.loaded) herdr.focusedWorkspaceId else workspaceId
    val workspace = herdr.workspaces.firstOrNull { it.workspaceId == workspaceIdCurrent }
    val effectiveLabel = workspace?.label ?: label.orEmpty()
    val tabs = if (workspaceIdCurrent == null) emptyList() else herdr.tabs[workspaceIdCurrent].orEmpty()
    val focusedTabId = tabs.firstOrNull { it.tabId == herdr.focusedTabId }?.tabId
        ?: tabs.firstOrNull { it.focused }?.tabId

    val scope = rememberCoroutineScope()
    fun act(run: suspend () -> Unit) {
        scope.launch {
            try {
                run()
            } catch (e: Exception) {
                notice = herdrFailureMessage(e)
                // The optimistic paint has to be undone by the truth, not left.
                store.refresh(hostId, session)
            }
        }
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            delay(NOTICE_MS)
            notice = null
        }
    }

    // A workspace named in the route was chosen somewhere else, so it is the
    // one case where this screen has to focus it: once, on the way in.
    // Painted first, or the first loaded frame would show whichever
    // workspace herdr still had focused.
    LaunchedEffect(Unit) {
        if (workspaceId == null) return@LaunchedEffect
        store.applyLocal(hostId, session, HerdrEvent.WorkspaceFocused(workspaceId))
        act { client.focusHerdrWorkspace(hostId, workspaceId, session) }
    }

    // The terminal is attached once per workspace and retagged when it
    // changes. A workspace rename retags the same tab's title too, which is
    // why it is part of this key rather than guarded separately.
    LaunchedEffect(hostId, workspaceIdCurrent, effectiveLabel, session) {
        val wsId = workspaceIdCurrent ?: return@LaunchedEffect
        val runs = if (session == null) "herdr" else joinShell(listOf("herdr", "--session", session))
        val opened = SshSessions.openWorkspaceTab(hostId, wsId, effectiveLabel, runs, session)
        if (opened == null) {
            notice = "This host has no room for another terminal."
            return@LaunchedEffect
        }
        sessionId = opened
    }

    fun selectTab(tabId: String) {
        if (workspaceIdCurrent != null) {
            store.applyLocal(hostId, session, HerdrEvent.TabFocused(tabId, workspaceIdCurrent))
        }
        act { client.focusHerdrTab(hostId, tabId, session) }
    }

    fun newTab() {
        val wsId = workspaceIdCurrent ?: return
        act { client.createHerdrTab(hostId, HerdrCreateTab(workspaceId = wsId, focus = true), session) }
    }

    fun runPlugin(action: String) {
        act { client.invokeHerdrPluginAction(hostId, "$PLUGIN.$action", session) }
    }

    fun commitRenameTab() {
        val tabId = renameTabId ?: return
        val newLabel = renameDraft.trim()
        if (newLabel.isEmpty()) return
        renameTabId = null
        if (workspaceIdCurrent != null) {
            store.applyLocal(hostId, session, HerdrEvent.TabRenamed(tabId, workspaceIdCurrent, newLabel))
        }
        act { client.renameHerdrTab(hostId, tabId, newLabel, session) }
    }

    fun startRenameTab(tabId: String) {
        renameDraft = tabs.firstOrNull { it.tabId == tabId }?.label.orEmpty()
        renameTabId = tabId
    }

    /**
     * Answers a mux gesture.
     *
     * Both moves go over the socket rather than as the chord herdr binds
     * (`prefix+n`, `prefix+p`). herdr emits no event for a move made by its
     * own key binding, so a chord leaves the strip and the title on the tab
     * the session has just left, which an event-fed screen cannot see. A
     * focus command emits the event, and the target is already known here,
     * so it stays one command either way.
     */
    fun onMuxGesture(action: MuxAction) {
        when (action) {
            MuxAction.TabNext, MuxAction.TabPrevious -> {
                val step = if (action == MuxAction.TabNext) 1 else -1
                val inWorkspace = herdr.tabs[workspaceIdCurrent].orEmpty()
                val next = nextHerdrTab(inWorkspace, herdr.focusedTabId, step)
                if (next == null) {
                    notice = "This workspace has only one tab."
                    return
                }
                store.applyLocal(hostId, session, HerdrEvent.TabFocused(next.tabId, next.workspaceId))
                act { client.focusHerdrTab(hostId, next.tabId, session) }
            }

            MuxAction.WorkspaceNext -> {
                val next = nextHerdrWorkspace(HerdrWorkspaceOrder(herdr.workspaces, workspaceIdCurrent), 1)
                if (next == null) {
                    notice = "This session has only one workspace."
                    return
                }
                store.applyLocal(hostId, session, HerdrEvent.WorkspaceFocused(next.workspaceId))
                act { client.focusHerdrWorkspace(hostId, next.workspaceId, session) }
            }
        }
    }

    fun onKeyPress(key: String) {
        terminal.clearComposition()
        val result = transformKey(key, activeModifier) ?: return
        SshSessions.sendInput(hostId, result.bytes)
        if (result.clearModifier) activeModifier = null
        result.notice?.let { notice = it }
    }

    // Terminal content actions only. Everything that manages a workspace or a
    // tab lives on its own row in the sidebar, which is also the path for
    // anyone who cannot make the gestures.
    val actions = listOf(
        ScreenAction(key = "new-tab", icon = Icons.Filled.Add, title = "New tab", onClick = ::newTab, enabled = workspaceIdCurrent != null),
        ScreenAction(key = "tab-here", icon = Icons.Filled.Folder, title = "New tab here", onClick = { runPlugin("tab-here") }),
        ScreenAction(key = "panes-to-tabs", icon = Icons.Filled.Dashboard, title = "Panes to tabs", onClick = { runPlugin("panes-to-tabs") }),
        ScreenAction(key = "zoom", icon = Icons.Filled.Fullscreen, title = "Toggle pane zoom", onClick = { runPlugin("zoom") }),
        ScreenAction(
            key = "rename",
            icon = Icons.Filled.Edit,
            title = "Rename tab",
            enabled = focusedTabId != null,
            onClick = { focusedTabId?.let(::startRenameTab) },
        ),
        ScreenAction(
            key = "detach",
            icon = Icons.Filled.LinkOff,
            title = "Close this terminal",
            destructive = true,
            enabled = sessionId != null,
            onClick = {
                sessionId?.let { SshSessions.closeTab(hostId, it) }
                nav.popBackStack()
            },
        ),
    )

    val phaseBanner = when {
        sessionId == null -> TerminalBanner(TerminalBannerTone.Busy, if (effectiveLabel.isBlank()) "Attaching" else "Attaching to $effectiveLabel")
        tab == null -> null
        tab.phase == SshTabPhase.Connecting -> TerminalBanner(TerminalBannerTone.Busy, "Attaching")
        tab.phase == SshTabPhase.HostKey -> TerminalBanner(TerminalBannerTone.Error, "This host key has not been accepted yet.")
        tab.phase == SshTabPhase.Closed || tab.phase == SshTabPhase.Failed -> TerminalBanner(
            tone = TerminalBannerTone.Error,
            message = tab.detail.ifEmpty { "The terminal is closed." },
            actionLabel = "Reconnect",
            onAction = { sessionId?.let { SshSessions.reconnectTab(hostId, it) } },
        )
        else -> null
    }
    val banner = notice?.let { TerminalBanner(TerminalBannerTone.Error, it) } ?: phaseBanner

    val tabViews = tabs.map { herdrTab ->
        TerminalTab(
            id = herdrTab.tabId,
            label = herdrTab.label.ifEmpty { herdrTab.number.toString() },
            phase = TerminalTabPhase.Active,
        )
    }

    BoxWithConstraints {
        val permanent = maxWidth >= EXPANDED_LAYOUT_MIN_WIDTH_DP.dp
        val drawerOpen = sidebarOpen ?: permanent
        HostSidebar(
            hostId = hostId,
            hostName = hostName,
            session = session,
            open = drawerOpen,
            onClose = { sidebarOpen = false },
            client = client,
            store = store,
            permanent = permanent,
            currentWorkspaceId = workspaceIdCurrent,
            onEnterWorkspace = { request ->
                store.applyLocal(hostId, request.session, HerdrEvent.WorkspaceFocused(request.workspaceId))
                act { client.focusHerdrWorkspace(hostId, request.workspaceId, request.session) }
            },
            onOpenShells = { nav.navigate(Routes.sshTerminal(hostId)) },
            onOpenFiles = {
                SshSessions.openTab(hostId, kind = SshTabKind.Files)
                nav.navigate(Routes.sshTerminal(hostId))
            },
        ) {
            TerminalScaffold(
                title = effectiveLabel,
                subtitle = "$hostName, ${session ?: "default"}",
                onBack = { nav.popBackStack() },
                onMenu = { sidebarOpen = true },
                tabs = tabViews,
                activeTabId = focusedTabId,
                onSelectTab = ::selectTab,
                onCloseTab = { tabId -> act { client.closeHerdrTab(hostId, tabId, session) } },
                onRenameTab = ::startRenameTab,
                onAddTab = if (workspaceIdCurrent != null) ::newTab else null,
                banner = banner,
                showKeyRow = settings.showExtraKeyRow,
                activeModifier = activeModifier,
                onKey = ::onKeyPress,
                onModifier = { m ->
                    terminal.clearComposition()
                    activeModifier = if (activeModifier == m) null else m
                },
                keyRepeatDelayMs = settings.keyRepeatDelayMs,
                haptics = settings.hapticFeedback,
                onKeyboard = { terminal.openKeyboard() },
                content = { modifier ->
                    val activeSessionId = sessionId
                    val context = LocalContext.current
                    if (activeSessionId == null) {
                        Box(modifier.background(TerminalBackground))
                    } else {
                        TerminalPane(
                            sessionKey = activeSessionId,
                            fontSizeSp = settings.terminalFontSize,
                            cursorStyle = settings.cursorStyle,
                            onInput = { bytes -> SshSessions.sendInput(hostId, bytes) },
                            onPtyWrite = { bytes -> SshSessions.sendInput(hostId, bytes) },
                            onResize = { cols, rows -> SshSessions.resizeHost(hostId, cols, rows) },
                            // The native selection toolbar and the system's own
                            // clipboard confirmation already cover this; no
                            // separate affordance is needed at the screen level.
                            onLinkCopied = {},
                            // The same shared behaviour as the shell terminal
                            // screen's onNotify, but without proactively
                            // requesting POST_NOTIFICATIONS from here: quiet
                            // when the permission has not been granted yet.
                            onNotify = { title, body ->
                                if (TerminalNotifications.canPost(context)) {
                                    TerminalNotifications.show(context, title, body)
                                }
                            },
                            onFontSizeChanged = { size -> SettingsState.update { it.copy(terminalFontSize = size) } },
                            // Double taps are recognized by the native view:
                            // it receives both taps, while a Compose modifier
                            // around it is not shown an unclaimed first tap.
                            modifier = modifier.terminalTabSwipe(enabled = !hasSelection) { direction ->
                                onMuxGesture(if (direction > 0) MuxAction.TabNext else MuxAction.TabPrevious)
                            },
                            handle = terminal,
                            // The native handle is ready exactly when this
                            // session has a measured terminal; opening here
                            // works on first attach and after a workspace tab
                            // is replaced.
                            onReady = { if (settings.openKeyboardOnTerminal) terminal.openKeyboard() },
                            onDoubleTap = { onMuxGesture(MuxAction.WorkspaceNext) },
                            onSelectionChanged = { hasSelection = it },
                        )
                    }
                },
            )
        }
    }

    if (renameTabId != null) {
        AlertDialog(
            onDismissRequest = { renameTabId = null },
            title = { Text("Rename tab") },
            text = {
                RemotlyTextField(
                    value = renameDraft,
                    onValueChange = { renameDraft = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = ::commitRenameTab, enabled = renameDraft.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTabId = null }) { Text("Cancel") } },
        )
    }
}
