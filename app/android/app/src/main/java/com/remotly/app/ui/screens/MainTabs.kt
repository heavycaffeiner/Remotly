package com.remotly.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTabKind
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStoreException
import com.remotly.app.ssh.SshModule
import com.remotly.app.ui.Routes
import com.remotly.app.ui.openRoute
import com.remotly.app.ui.ScreenHorizontalPadding
import com.remotly.app.ui.WideHostListWidth
import com.remotly.app.ui.WideSidebarWidth
import com.remotly.app.ui.components.EmptyState
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.LoadingState
import com.remotly.app.ui.components.RemotlyScreen
import com.remotly.app.ui.components.RemotlyTextField
import com.remotly.app.ui.components.transferBarClearance
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun MainTabs(nav: NavHostController) {
    HostsContent(nav)
}

private enum class HostsPhase { Loading, Ready, Error }

private data class HostRow(
    val host: SshHost,
    val name: String,
    val detail: String,
    /** Open tabs for this host. Zero draws no badge. */
    val sessions: Int = 0,
    val tabs: List<com.remotly.app.session.SshTab> = emptyList(),
)

private fun SshHost.toRow(sessions: Int): HostRow {
    val name = displayName.ifBlank { "$username@$host" }
    val endpoint = "$username@$host${if (port == 22) "" else ":$port"}"
    return HostRow(
        host = this,
        name = name,
        detail = endpoint,
        sessions = sessions,
    )
}

@Composable
private fun HostsContent(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    // Only the newest load may write state: a slow response from a previous
    // visit to this destination must not overwrite what a later one found.
    val generation = remember { AtomicInteger(0) }

    var phase by remember { mutableStateOf(HostsPhase.Loading) }
    var hosts by remember { mutableStateOf<List<HostRow>>(emptyList()) }
    var errorMessage by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedHostId by rememberSaveable { mutableStateOf<String?>(null) }
    var removeTarget by remember { mutableStateOf<HostRow?>(null) }
    var notice by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }
    val expanded = com.remotly.app.ui.LocalWorkspaceLayout.current.widthDp >= 840
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    val openHosts by SshSessions.openHosts.collectAsStateWithLifecycle()
    BackHandler(enabled = searchOpen || query.isNotEmpty()) { query = ""; searchOpen = false }

    fun load() {
        val gen = generation.incrementAndGet()
        phase = HostsPhase.Loading
        scope.launch {
            val result = runCatching {
                val store = SshModule.store ?: throw SshHostStoreException("SSH storage is not available")
                withContext(Dispatchers.IO) { store.list() }
            }
            if (generation.get() != gen) return@launch
            result.onSuccess { list ->
                val counts = SshSessions.sessionCounts()
                hosts = list.map { it.toRow(counts[it.id] ?: 0) }
                phase = HostsPhase.Ready
            }.onFailure {
                errorMessage = it.message ?: "The host store could not be read."
                phase = HostsPhase.Error
            }
        }
    }

    val countedHosts = hosts.map { row ->
        val tabs = openHosts.firstOrNull { it.tabs.hostId == row.host.id }?.tabs?.tabs.orEmpty()
        row.copy(sessions = tabs.size, tabs = tabs)
    }

    // Refresh saved connections when returning from the editor.
    LaunchedEffect(Unit) { load() }

    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbarHostState.showSnackbar(notice)
            notice = ""
        }
    }

    val visible = remember(countedHosts, query) {
        val q = query.trim()
        if (q.isBlank()) {
            countedHosts
        } else {
            countedHosts.filter {
                it.name.contains(q, ignoreCase = true) || it.detail.contains(q, ignoreCase = true)
            }
        }
    }
    val selectedRow = visible.firstOrNull { it.host.id == selectedHostId }
        ?: visible.firstOrNull()

    // Keep the detail pane useful after a search or deletion changes the list.
    LaunchedEffect(visible, expanded) {
        if (expanded) selectedHostId = selectedRow?.host?.id
    }

    fun openTerminal(row: HostRow) {
        val state = SshSessions.state(row.host.id).value.tabs
        val active = state.tabs.firstOrNull { it.sessionId == state.activeSessionId }
        if (active?.kind == SshTabKind.Workspace) {
            nav.openRoute(Routes.herdrWorkspace(row.host.id, row.name, active.workspaceId, active.title, active.muxSession?.takeIf { it.isNotEmpty() }))
        } else nav.openRoute(Routes.sshTerminal(row.host.id))
    }

    fun openFiles(row: HostRow) {
        val existing = row.tabs.lastOrNull { it.kind == SshTabKind.Files }
        val sessionId = existing?.sessionId ?: SshSessions.openTab(row.host.id, kind = SshTabKind.Files)
        if (sessionId != null) SshSessions.selectTab(row.host.id, sessionId)
        if (sessionId == null) {
            notice = "This host has no room for another files tab."
        } else {
            // Files is a tab in the terminal destination, not a separate route.
            nav.openRoute(Routes.sshTerminal(row.host.id))
        }
    }

    fun openWorkspaces(row: HostRow) {
        nav.openRoute(Routes.herdrWorkspace(row.host.id, row.name))
    }

    fun resumeTab(row: HostRow, tab: com.remotly.app.session.SshTab) {
        SshSessions.selectTab(row.host.id, tab.sessionId)
        openTerminal(row)
    }

    RemotlyScreen(title = "Hosts", snackbarHostState = snackbarHostState,
        actions = listOf(com.remotly.app.ui.components.ScreenAction("search", Icons.Filled.Search, "Search hosts", { searchOpen = !searchOpen }))) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val detailFits = expanded && maxWidth >= 720.dp
            when (phase) {
                HostsPhase.Loading -> LoadingState("Loading hosts")
                HostsPhase.Error -> ErrorState(
                    title = "Could not load hosts",
                    message = errorMessage.ifBlank {
                        "The host store could not be read. Your saved hosts are still on the device."
                    },
                    onRetry = { load() },
                )
                HostsPhase.Ready -> if (hosts.isEmpty()) {
                    EmptyState(
                        icon = Icons.Filled.Dns,
                        title = "No hosts yet",
                        message = "Add an SSH host to connect directly.",
                        action = "Add SSH host" to { nav.openRoute(Routes.hostEditor()) },
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val searchModifier = if (detailFits) {
                            Modifier.widthIn(max = WideHostListWidth).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        }
                        if (searchOpen) RemotlyTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = { Text("Search hosts") },
                            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                            modifier = searchModifier.padding(
                                horizontal = ScreenHorizontalPadding,
                                vertical = 8.dp,
                            ),
                        )
                        if (visible.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "No hosts match this search.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else if (detailFits) {
                            ExpandedHosts(
                                visible = visible,
                                selectedHostId = selectedRow?.host?.id,
                                onSelect = { selectedHostId = it.host.id },
                                onOpenTerminal = ::openTerminal,
                                onOpenFiles = ::openFiles,
                                onOpenWorkspaces = ::openWorkspaces,
                                onEdit = { row -> nav.openRoute(Routes.hostEditor(row.host.id)) },
                                onRemove = { removeTarget = it },
                                onResumeTab = ::resumeTab,
                            )
                        } else {
                            CompactHosts(
                                visible = visible,
                                onOpenTerminal = ::openTerminal,
                                onOpenFiles = ::openFiles,
                                onOpenWorkspaces = ::openWorkspaces,
                                onEdit = { row -> nav.openRoute(Routes.hostEditor(row.host.id)) },
                                onRemove = { removeTarget = it },
                            )
                        }
                    }
                }
            }

            if (phase == HostsPhase.Ready) {
                FloatingActionButton(
                    onClick = { nav.openRoute(Routes.hostEditor()) },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(ScreenHorizontalPadding)
                        .padding(bottom = transferBarClearance()),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add SSH host")
                }
            }
        }
    }

    removeTarget?.let { row ->
        AlertDialog(
            onDismissRequest = { removeTarget = null },
            title = { Text("Remove ${row.name}?") },
            text = {
                Text(
                    "The host, its stored credential, and its pinned host keys are removed from " +
                        "this device. The remote server is not affected.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        val outcome = runCatching {
                            val store = SshModule.store
                                ?: throw SshHostStoreException("SSH storage is not available")
                            withContext(Dispatchers.IO) { store.remove(row.host.id) }
                        }
                        removeTarget = null
                        // A failed or no-op removal leaves the row in place: a
                        // failed delete must never look like a success, or the
                        // user believes their credential is gone when it is not.
                        outcome.onSuccess { removed ->
                            if (removed) load() else notice = "That host was already removed."
                        }.onFailure { e ->
                            notice = e.message ?: "The host could not be removed."
                        }
                    }
                }) {
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun CompactHosts(
    visible: List<HostRow>,
    onOpenTerminal: (HostRow) -> Unit,
    onOpenFiles: (HostRow) -> Unit,
    onOpenWorkspaces: (HostRow) -> Unit,
    onEdit: (HostRow) -> Unit,
    onRemove: (HostRow) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(visible, key = { it.host.id }) { row ->
            HostRowItem(
                row = row,
                expanded = false,
                selected = false,
                onClick = { onOpenTerminal(row) },
                onTerminal = { SshSessions.openTab(row.host.id); onOpenTerminal(row) },
                onFiles = { onOpenFiles(row) },
                onWorkspaces = { onOpenWorkspaces(row) },
                onEdit = { onEdit(row) },
                onRemove = { onRemove(row) },
            )
        }
    }
}

@Composable
private fun ExpandedHosts(
    visible: List<HostRow>,
    selectedHostId: String?,
    onSelect: (HostRow) -> Unit,
    onOpenTerminal: (HostRow) -> Unit,
    onOpenFiles: (HostRow) -> Unit,
    onOpenWorkspaces: (HostRow) -> Unit,
    onEdit: (HostRow) -> Unit,
    onRemove: (HostRow) -> Unit,
    onResumeTab: (HostRow, com.remotly.app.session.SshTab) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = ScreenHorizontalPadding, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .widthIn(max = WideHostListWidth)
                .fillMaxHeight(),
        ) {
            Text(
                "Saved hosts",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(visible, key = { it.host.id }) { row ->
                    HostRowItem(
                        row = row,
                        expanded = true,
                        selected = row.host.id == selectedHostId,
                        onClick = { onSelect(row) },
                        onTerminal = { onOpenTerminal(row) },
                        onFiles = { onOpenFiles(row) },
                        onWorkspaces = { onOpenWorkspaces(row) },
                        onEdit = { onEdit(row) },
                        onRemove = { onRemove(row) },
                    )
                }
            }
        }
        visible.firstOrNull { it.host.id == selectedHostId }?.let { row ->
            HostDetailPane(
                row = row,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(max = WideSidebarWidth)
                    .fillMaxHeight(),
                onOpenTerminal = { onOpenTerminal(row) },
                onOpenFiles = { onOpenFiles(row) },
                onOpenWorkspaces = { onOpenWorkspaces(row) },
                onEdit = { onEdit(row) },
                onRemove = { onRemove(row) },
                onResumeTab = { onResumeTab(row, it) },
            )
        }
    }
}

@Composable
private fun HostRowItem(
    row: HostRow,
    expanded: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onTerminal: () -> Unit,
    onFiles: () -> Unit,
    onWorkspaces: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    var menuOpen by remember(row.host.id) { mutableStateOf(false) }
    val activation = if (expanded) {
        Modifier.selectable(
            selected = selected,
            role = Role.Button,
            onClick = onClick,
        )
    } else {
        Modifier.clickable(
            role = Role.Button,
            onClickLabel = "Open terminal for ${row.name}",
            onClick = onClick,
        )
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .then(activation),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainer
        },
        shape = MaterialTheme.shapes.large,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = if (expanded) 12.dp else 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Dns,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f).padding(vertical = 10.dp)) {
                    Text(
                        row.name,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(
                        row.detail,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (row.sessions > 0) {
                    Badge(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ) {
                        Text("${row.sessions}")
                    }
                }
                if (!expanded) {
                    Box {
                        IconButton(
                            onClick = { menuOpen = true },
                            modifier = Modifier.semantics {
                                contentDescription = "Edit or remove ${row.name}"
                            },
                        ) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                        ) {
                            DropdownMenuItem(text = { Text("New terminal") }, onClick = { menuOpen = false; onTerminal() })
                            DropdownMenuItem(text = { Text("Files") }, onClick = { menuOpen = false; onFiles() })
                            DropdownMenuItem(text = { Text("Workspaces") }, onClick = { menuOpen = false; onWorkspaces() })
                            DropdownMenuItem(
                                text = { Text("Edit") },
                                leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEdit()
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Remove") },
                                leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onRemove()
                                },
                            )
                        }
                    }
                }
            }

        }
    }
}

@Composable
private fun HostDetailPane(
    row: HostRow,
    modifier: Modifier,
    onOpenTerminal: () -> Unit,
    onOpenFiles: () -> Unit,
    onOpenWorkspaces: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
    onResumeTab: (com.remotly.app.session.SshTab) -> Unit,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.large,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                row.name,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                row.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (row.sessions > 0) {
                Text(
                    "${row.sessions} open ${if (row.sessions == 1) "session" else "sessions"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(8.dp))
            HostDetailAction(
                icon = Icons.Filled.Terminal,
                title = if (row.sessions > 0) "Resume session" else "Open terminal",
                primary = true,
                onClick = onOpenTerminal,
            )
            HostDetailAction(
                icon = Icons.Filled.Folder,
                title = "Files",
                onClick = onOpenFiles,
            )
            HostDetailAction(
                icon = Icons.Filled.Dashboard,
                title = "Workspaces",
                onClick = onOpenWorkspaces,
            )
            if (row.tabs.isNotEmpty()) {
                Text("Open sessions", style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() })
                row.tabs.forEach { tab ->
                    HostDetailAction(
                        icon = if (tab.kind == SshTabKind.Files) Icons.Default.Folder else Icons.Default.Terminal,
                        title = "${tab.title}, ${tab.phase.name.lowercase()}",
                        onClick = { onResumeTab(tab) },
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Edit")
                }
                TextButton(onClick = onRemove, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Remove", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun HostDetailAction(
    icon: ImageVector,
    title: String,
    primary: Boolean = false,
    onClick: () -> Unit,
) {
    val container = if (primary) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val foreground = if (primary) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Surface(
        onClick = onClick,
        color = container,
        contentColor = foreground,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
        }
    }
}
