package com.remotly.app.ui.screens

// One host's herdr tree: sessions, their workspaces, and each workspace's tabs.
//
// This is the app's management surface: workspace and tab navigation live
// here, on the row they belong to, so the terminal's own overflow menu carries
// only actions that act on its own content.
//
// State comes from the event-fed HerdrStore, so a change made on the desktop
// or from a gesture in the attached terminal shows up here without this
// composable asking for it.
//
// No edge swipe opens this drawer. A horizontal swipe over an attached
// terminal moves a herdr tab, and a drawer that also claimed the left edge
// would fight it on every attempt; it opens only from the app bar's menu
// button, via [open] and [onClose].

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.remotly.app.ui.components.RemotlyTextField
import com.remotly.app.ui.components.DiscardChangesDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remotly.app.herdr.HerdrClient
import com.remotly.app.herdr.HerdrCreateTab
import com.remotly.app.herdr.HerdrCreateWorkspace
import com.remotly.app.herdr.HerdrError
import com.remotly.app.herdr.HerdrEvent
import com.remotly.app.herdr.HerdrFeed
import com.remotly.app.herdr.HerdrHostState
import com.remotly.app.herdr.HerdrSession
import com.remotly.app.herdr.HerdrStore
import com.remotly.app.herdr.HerdrTab
import com.remotly.app.herdr.HerdrWorkspace
import com.remotly.app.ui.components.LoadingState
import com.remotly.app.ui.components.NoticeBar
import com.remotly.app.ui.components.NoticeTone
import com.remotly.app.ui.WideSidebarWidth
import kotlinx.coroutines.launch

/** Where a workspace's terminal is asked for. */
data class HerdrEnterRequest(val workspaceId: String, val label: String, val session: String?)

/** What a rename or close dialog is acting on. */
private sealed interface Subject {
    data class Workspace(val id: String, val label: String) : Subject
    data class Tab(val id: String, val label: String, val workspaceId: String) : Subject
}

/** A herdr failure, said in the user's terms. */
private fun sidebarFailureMessage(e: Throwable): String =
    if (e is HerdrError) e.detail else "herdr failed."

/**
 * A [ModalNavigationDrawer] on compact widths, a [PermanentNavigationDrawer]
 * on wide ones. [content] is the screen's terminal area, laid out beside or
 * under the drawer depending on [permanent].
 *
 * [session] is only the starting point: a sidebar viewing a host with more
 * than one named herdr session can browse another one without moving the
 * caller's own terminal, which is why entering a workspace reports the
 * session it was found under.
 */
@Composable
fun HostSidebar(
    hostId: String,
    hostName: String,
    session: String?,
    open: Boolean,
    onClose: () -> Unit,
    client: HerdrClient,
    store: HerdrStore,
    permanent: Boolean = false,
    currentWorkspaceId: String? = null,
    onEnterWorkspace: (HerdrEnterRequest) -> Unit,
    onOpenShells: () -> Unit,
    onOpenFiles: () -> Unit,
    content: @Composable () -> Unit,
) {
    var viewSession by remember(hostId) { mutableStateOf(session) }
    var sessions by remember { mutableStateOf<List<HerdrSession>>(emptyList()) }
    // The workspace on screen shows its tabs, held as state so a row can be
    // opened or closed by hand and reset when the terminal moves elsewhere.
    var expanded by remember { mutableStateOf(currentWorkspaceId) }
    var notice by remember { mutableStateOf("") }
    var renameFor by remember { mutableStateOf<Subject?>(null) }
    var closeFor by remember { mutableStateOf<Subject?>(null) }
    var createOpen by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var confirmDiscardDraft by remember { mutableStateOf(false) }

    fun dismissDraft() {
        if (draft != renameFor?.label.orEmpty()) {
            confirmDiscardDraft = true
        } else {
            renameFor = null
            createOpen = false
        }
    }

    LaunchedEffect(currentWorkspaceId) {
        if (currentWorkspaceId != null) expanded = currentWorkspaceId
    }

    // Acquired synchronously in `remember`, not `DisposableEffect`: the
    // entry has to exist before `hostState` is read below, on this same
    // composition, or the first frame observes the static empty flow and
    // nothing forces a later recomposition onto the real one.
    val subscription = remember(hostId, viewSession) { store.subscribe(hostId, viewSession) }
    DisposableEffect(subscription) { onDispose { subscription.close() } }
    val state by store.hostState(hostId, viewSession).collectAsStateWithLifecycle()

    // The session list changes rarely and has no event, so it is read when
    // the sidebar comes up rather than kept live.
    LaunchedEffect(hostId, viewSession, open, permanent) {
        if (!open && !permanent) return@LaunchedEffect
        sessions = runCatching { client.listHerdrSessions(hostId) }.getOrDefault(sessions)
    }

    val scope = rememberCoroutineScope()
    fun act(run: suspend () -> Unit) {
        notice = ""
        scope.launch {
            busy = true
            try {
                run()
            } catch (e: Exception) {
                notice = sidebarFailureMessage(e)
                // The optimistic paint has to be undone by the truth, not left.
                store.refresh(hostId, viewSession)
            } finally {
                busy = false
            }
        }
    }

    fun focusTab(tab: HerdrTab) {
        val targetSession = viewSession
        val workspaceLabel = state.workspaces.firstOrNull { it.workspaceId == tab.workspaceId }?.label.orEmpty()
        store.applyLocal(hostId, targetSession, HerdrEvent.TabFocused(tab.tabId, tab.workspaceId))
        act {
            client.focusHerdrTab(hostId, tab.tabId, targetSession)
            if (targetSession != session) {
                onEnterWorkspace(HerdrEnterRequest(tab.workspaceId, workspaceLabel, targetSession))
            }
            if (!permanent) onClose()
        }
    }

    fun commitRename() {
        val subject = renameFor ?: return
        val label = draft.trim()
        if (label.isEmpty()) return
        renameFor = null
        when (subject) {
            is Subject.Workspace -> {
                store.applyLocal(hostId, viewSession, HerdrEvent.WorkspaceRenamed(subject.id, label))
                act { client.renameHerdrWorkspace(hostId, subject.id, label, viewSession) }
            }
            is Subject.Tab -> {
                store.applyLocal(hostId, viewSession, HerdrEvent.TabRenamed(subject.id, subject.workspaceId, label))
                act { client.renameHerdrTab(hostId, subject.id, label, viewSession) }
            }
        }
    }

    fun commitClose() {
        val subject = closeFor ?: return
        closeFor = null
        when (subject) {
            is Subject.Workspace -> act { client.closeHerdrWorkspace(hostId, subject.id, viewSession) }
            is Subject.Tab -> act { client.closeHerdrTab(hostId, subject.id, viewSession) }
        }
    }

    fun commitCreate() {
        val label = draft.trim()
        if (label.isEmpty()) return
        createOpen = false
        act {
            client.createHerdrWorkspace(hostId, HerdrCreateWorkspace(label = label), viewSession)
            expanded = null
        }
    }

    // A new tab in the workspace on screen is what the user is looking at, so
    // it takes focus. A new tab in another workspace must not: herdr focuses
    // a new tab by default, and that would move the session out from under a
    // terminal nobody asked to leave.
    fun newTab(workspaceId: String) {
        act {
            client.createHerdrTab(
                hostId,
                HerdrCreateTab(
                    workspaceId = workspaceId,
                    focus = workspaceId == currentWorkspaceId && viewSession == session,
                ),
                viewSession,
            )
        }
    }

    val drawerContent: @Composable () -> Unit = {
        HostSidebarBody(
            hostName = hostName,
            currentSession = session,
            permanent = permanent,
            onClose = onClose,
            state = state,
            sessions = sessions,
            viewSession = viewSession,
            onSelectSession = { viewSession = it },
            currentWorkspaceId = currentWorkspaceId,
            expanded = expanded,
            busy = busy,
            notice = notice,
            onToggle = { id -> expanded = if (expanded == id) null else id },
            onEnter = { workspace ->
                onEnterWorkspace(HerdrEnterRequest(workspace.workspaceId, workspace.label, viewSession))
                if (!permanent) onClose()
            },
            onRenameWorkspace = { workspace ->
                draft = workspace.label
                renameFor = Subject.Workspace(workspace.workspaceId, workspace.label)
            },
            onCloseWorkspace = { workspace -> closeFor = Subject.Workspace(workspace.workspaceId, workspace.label) },
            onFocusTab = ::focusTab,
            onRenameTab = { tab ->
                draft = tab.label
                renameFor = Subject.Tab(tab.tabId, tab.label, tab.workspaceId)
            },
            onCloseTab = { tab -> closeFor = Subject.Tab(tab.tabId, tab.label, tab.workspaceId) },
            onNewTab = ::newTab,
            onNewWorkspace = { draft = ""; createOpen = true },
            onOpenShells = { onOpenShells(); if (!permanent) onClose() },
            onOpenFiles = { onOpenFiles(); if (!permanent) onClose() },
        )
    }

    if (permanent && open) {
        PermanentNavigationDrawer(
            drawerContent = {
                PermanentDrawerSheet(modifier = Modifier.width(WideSidebarWidth)) { drawerContent() }
            },
            content = content,
        )
    } else if (permanent) {
        // A collapsed permanent drawer leaves the terminal full width. The
        // app bar's menu action is the explicit way to bring it back.
        content()
    } else {
        val currentOnClose by rememberUpdatedState(onClose)
        val drawerState = rememberDrawerState(
            initialValue = if (open) DrawerValue.Open else DrawerValue.Closed,
            confirmStateChange = { value ->
                if (value == DrawerValue.Closed) currentOnClose()
                true
            },
        )
        LaunchedEffect(open) { if (open) drawerState.open() else drawerState.close() }
        ModalNavigationDrawer(
            drawerState = drawerState,
            gesturesEnabled = false,
            drawerContent = { ModalDrawerSheet { drawerContent() } },
            content = content,
        )
    }


    if (renameFor != null) {
        val subject = renameFor
        AlertDialog(
            onDismissRequest = ::dismissDraft,
            title = { Text(if (subject is Subject.Tab) "Rename tab" else "Rename workspace") },
            text = {
                RemotlyTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    label = { Text("Label") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = ::commitRename, enabled = draft.isNotBlank()) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = ::dismissDraft) { Text("Cancel") } },
        )
    }

    if (createOpen) {
        AlertDialog(
            onDismissRequest = ::dismissDraft,
            title = { Text("New workspace") },
            text = {
                Column {
                    RemotlyTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        label = { Text("Label") },
                        singleLine = true,
                    )
                    Text(
                        "Shown in herdr and here.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = ::commitCreate, enabled = draft.isNotBlank()) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = ::dismissDraft) { Text("Cancel") } },
        )
    }

    val closing = closeFor
    if (closing != null) {
        AlertDialog(
            onDismissRequest = { closeFor = null },
            title = { Text("Close ${closing.label}?") },
            text = {
                Text(
                    if (closing is Subject.Tab) {
                        "The tab and its panes are closed on the host. Anything still running in them is ended."
                    } else {
                        "The workspace and its panes are closed on the host. Anything still running in them is ended."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = ::commitClose,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                ) { Text("Close") }
            },
            dismissButton = { TextButton(onClick = { closeFor = null }) { Text("Cancel") } },
        )
    }
    if (confirmDiscardDraft) {
        DiscardChangesDialog(
            onDiscard = {
                confirmDiscardDraft = false
                renameFor = null
                createOpen = false
            },
            onKeepEditing = { confirmDiscardDraft = false },
        )
    }
}

private val Subject.label: String
    get() = when (this) {
        is Subject.Workspace -> label
        is Subject.Tab -> label
    }

@Composable
private fun HostSidebarBody(
    hostName: String,
    permanent: Boolean,
    onClose: () -> Unit,
    currentSession: String?,
    state: HerdrHostState,
    sessions: List<HerdrSession>,
    viewSession: String?,
    onSelectSession: (String?) -> Unit,
    currentWorkspaceId: String?,
    expanded: String?,
    busy: Boolean,
    notice: String,
    onToggle: (String) -> Unit,
    onEnter: (HerdrWorkspace) -> Unit,
    onRenameWorkspace: (HerdrWorkspace) -> Unit,
    onCloseWorkspace: (HerdrWorkspace) -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onRenameTab: (HerdrTab) -> Unit,
    onCloseTab: (HerdrTab) -> Unit,
    onNewTab: (String) -> Unit,
    onNewWorkspace: () -> Unit,
    onOpenShells: () -> Unit,
    onOpenFiles: () -> Unit,
) {
    val currentSessionName = viewSession ?: "default"
    val currentSessionInfo = sessions.firstOrNull {
        (if (it.default) null else it.name) == viewSession
    }
    val otherSessions = sessions.filterNot {
        (if (it.default) null else it.name) == viewSession
    }
    val viewingCurrentSession = viewSession == currentSession

    Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    hostName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    when (state.feed) {
                        HerdrFeed.LIVE -> "Live from herdr"
                        HerdrFeed.POLLING -> "Re-reading on a timer"
                        HerdrFeed.STARTING -> "Connecting"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier.semantics {
                    contentDescription = if (permanent) {
                        "Hide workspaces and tabs"
                    } else {
                        "Close workspaces and tabs"
                    }
                },
            ) {
                Icon(
                    if (permanent) Icons.Filled.ChevronLeft else Icons.Filled.Close,
                    contentDescription = null,
                )
            }
        }

        if (notice.isNotEmpty()) {
            NoticeBar(notice, NoticeTone.Danger, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        SectionHeader("Session")
        Column(Modifier.padding(horizontal = 8.dp)) {
            SessionRow(
                name = currentSessionName,
                running = currentSessionInfo?.running,
                current = true,
                onClick = { onSelectSession(viewSession) },
            )
        }
        if (otherSessions.isNotEmpty()) {
            Text(
                "Other sessions",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            Column(
                modifier = Modifier.padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (s in otherSessions) {
                    val value = if (s.default) null else s.name
                    SessionRow(
                        name = if (s.default) "default" else s.name,
                        running = s.running,
                        current = false,
                        onClick = { onSelectSession(value) },
                    )
                }
            }
        }

        SectionHeader("Workspaces")
        if (!state.loaded && state.error == null) {
            LoadingState(label = "Loading workspaces", modifier = Modifier.padding(16.dp))
        }
        if (state.error != null) {
            NoticeBar(state.error, NoticeTone.Danger, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp))
        }

        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            for (workspace in state.workspaces) {
                WorkspaceRow(
                    workspace = workspace,
                    tabs = state.tabs[workspace.workspaceId].orEmpty(),
                    current = viewingCurrentSession && workspace.workspaceId == currentWorkspaceId,
                    focusedTabId = state.focusedTabId,
                    expanded = expanded == workspace.workspaceId,
                    busy = busy,
                    onToggle = { onToggle(workspace.workspaceId) },
                    onEnter = { onEnter(workspace) },
                    onRename = { onRenameWorkspace(workspace) },
                    onCloseRequest = { onCloseWorkspace(workspace) },
                    onFocusTab = onFocusTab,
                    onRenameTab = onRenameTab,
                    onCloseTab = onCloseTab,
                    onNewTab = { onNewTab(workspace.workspaceId) },
                )
            }
        }

        Row(Modifier.padding(horizontal = 12.dp, vertical = 2.dp)) {
            TextButton(onClick = onNewWorkspace, enabled = !busy) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("New workspace")
            }
        }

        Spacer(Modifier.height(12.dp))
        SectionHeader("This host")
        Column(
            modifier = Modifier.padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            PlainRow(icon = Icons.Filled.Terminal, label = "Shells", hint = "The app's own SSH tabs", onClick = onOpenShells)
            PlainRow(icon = Icons.Filled.Folder, label = "Files", hint = "Browse and transfer over SFTP", onClick = onOpenFiles)
        }
    }
}

@Composable
private fun SessionRow(
    name: String,
    running: Boolean?,
    current: Boolean,
    onClick: () -> Unit,
) {
    val status = when {
        current -> if (running == false) "Current session, stopped" else "Current session"
        running == false -> "Stopped"
        else -> "Running"
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .background(
                if (current) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                MaterialTheme.shapes.small,
            )
            .clickable(role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = (if (current) {
                    "Current session $name"
                } else {
                    "Switch to session $name"
                }) + if (running == false) ", stopped" else ""
                selected = current
            }
            .padding(horizontal = 8.dp),
    ) {
        Icon(
            if (current) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
            contentDescription = null,
            modifier = Modifier.width(20.dp),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1)
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = if (current) {
                    MaterialTheme.colorScheme.onSecondaryContainer
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .semantics { heading() },
    )
}

@Composable
private fun WorkspaceRow(
    workspace: HerdrWorkspace,
    tabs: List<HerdrTab>,
    current: Boolean,
    focusedTabId: String?,
    expanded: Boolean,
    busy: Boolean,
    onToggle: () -> Unit,
    onEnter: () -> Unit,
    onRename: () -> Unit,
    onCloseRequest: () -> Unit,
    onFocusTab: (HerdrTab) -> Unit,
    onRenameTab: (HerdrTab) -> Unit,
    onCloseTab: (HerdrTab) -> Unit,
    onNewTab: () -> Unit,
) {
    val tabWord = if (tabs.size == 1) "1 tab" else "${tabs.size} tabs"
    val herdrFocused = workspace.focused
    val selected = current || herdrFocused
    val rowBackground = when {
        current -> MaterialTheme.colorScheme.secondaryContainer
        herdrFocused -> MaterialTheme.colorScheme.surfaceContainerHigh
        else -> Color.Transparent
    }
    val status = when {
        current && herdrFocused -> "Current workspace, Herdr-focused"
        current -> "Current workspace"
        herdrFocused -> "Herdr-focused workspace"
        else -> null
    }
    var menuOpen by remember(workspace.workspaceId) { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            IconButton(
                onClick = onToggle,
                modifier = Modifier.semantics {
                    contentDescription = "${if (expanded) "Hide" else "Show"} tabs in ${workspace.label}"
                    stateDescription = if (expanded) "Expanded" else "Collapsed"
                },
            ) {
                Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, contentDescription = null)
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(MaterialTheme.shapes.small)
                    .background(rowBackground, MaterialTheme.shapes.small)
                    .clickable(enabled = !busy, role = Role.Button, onClick = onEnter)
                    .semantics {
                        contentDescription = "Open a terminal on ${workspace.label}, $tabWord" +
                            (if (current) ", current workspace" else "") +
                            (if (herdrFocused) ", Herdr-focused workspace" else "")
                        this.selected = selected
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        workspace.label,
                        maxLines = 1,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (current) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                        fontWeight = if (selected) FontWeight.SemiBold else null,
                    )
                    Text(
                        tabWord,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (status != null) {
                        Text(
                            status,
                            style = MaterialTheme.typography.labelSmall,
                            color = if (current) {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }
            Box {
                IconButton(
                    onClick = { menuOpen = true },
                    enabled = !busy,
                    modifier = Modifier.semantics {
                        contentDescription = "Workspace actions for ${workspace.label}"
                    },
                ) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Rename workspace") },
                        leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                        enabled = !busy,
                        onClick = {
                            menuOpen = false
                            onRename()
                        },
                    )
                    HorizontalDivider()
                    DropdownMenuItem(
                        text = { Text("Close workspace") },
                        leadingIcon = {
                            Icon(
                                Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        enabled = !busy,
                        onClick = {
                            menuOpen = false
                            onCloseRequest()
                        },
                    )
                }
            }
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 28.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                for (tab in tabs) {
                    val tabLabel = tab.label.ifEmpty { tab.number.toString() }
                    val focused = tab.tabId == focusedTabId
                    val tabStatus = when {
                        focused && tab.agentStatus == "working" -> "Focused, Working"
                        focused -> "Focused tab"
                        tab.agentStatus == "working" -> "Working"
                        else -> null
                    }
                    var tabMenuOpen by remember(tab.tabId) { mutableStateOf(false) }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clip(MaterialTheme.shapes.small)
                                .background(
                                    if (focused) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                                    MaterialTheme.shapes.small,
                                )
                                .clickable(enabled = !busy, role = Role.Button) { onFocusTab(tab) }
                                .semantics {
                                    contentDescription = "Focus tab $tabLabel" +
                                        (if (focused) ", Focused tab" else "") +
                                        (if (tab.agentStatus == "working") ", working" else "")
                                    this.selected = focused
                                }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(tabLabel, maxLines = 1, style = MaterialTheme.typography.bodyMedium)
                                if (tabStatus != null) {
                                    Text(
                                        tabStatus,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                    )
                                }
                            }
                        }
                        Box {
                            IconButton(
                                onClick = { tabMenuOpen = true },
                                enabled = !busy,
                                modifier = Modifier.semantics {
                                    contentDescription = "Tab actions for $tabLabel"
                                },
                            ) {
                                Icon(Icons.Filled.MoreVert, contentDescription = null)
                            }
                            DropdownMenu(
                                expanded = tabMenuOpen,
                                onDismissRequest = { tabMenuOpen = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename tab") },
                                    leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                    enabled = !busy,
                                    onClick = {
                                        tabMenuOpen = false
                                        onRenameTab(tab)
                                    },
                                )
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text("Close tab") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Close,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    },
                                    enabled = !busy,
                                    onClick = {
                                        tabMenuOpen = false
                                        onCloseTab(tab)
                                    },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = onNewTab, enabled = !busy) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("New tab")
                }
            }
        }
    }
}

@Composable
private fun PlainRow(icon: ImageVector, label: String, hint: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(MaterialTheme.shapes.small)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = "$label. $hint" }
            .padding(horizontal = 8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.width(24.dp))
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
