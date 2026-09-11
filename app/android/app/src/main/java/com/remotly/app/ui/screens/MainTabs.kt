package com.remotly.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTabKind
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStoreException
import com.remotly.app.ssh.SshModule
import com.remotly.app.ui.EXPANDED_LAYOUT_MIN_WIDTH_DP
import com.remotly.app.ui.Routes
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

private data class TabSpec(
    val route: String,
    val title: String,
    val outlineIcon: ImageVector,
    val filledIcon: ImageVector,
)

private val TABS = listOf(
    TabSpec(Routes.HOSTS, "Hosts", Icons.Outlined.Dns, Icons.Filled.Dns),
    TabSpec(Routes.SETTINGS, "Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * The root shell shared by the Hosts and Settings destinations.
 *
 * The overload without content is the Hosts destination. The content overload
 * lets the NavHost mount Settings as a real destination while keeping the
 * navigation bar or rail in one place. Selection is intentionally read from
 * the NavController rather than mirrored in local state, so back navigation
 * and saved root destinations always agree with what is highlighted.
 */
@Composable
fun MainTabs(nav: NavHostController) {
    MainTabs(nav) { HostsContent(nav) }
}

@Composable
fun MainTabs(nav: NavHostController, content: @Composable () -> Unit) {
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val selectedRoute = TABS.firstOrNull { it.route == currentRoute }?.route ?: Routes.HOSTS
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_LAYOUT_MIN_WIDTH_DP

    fun selectRoot(route: String) {
        // Read live rather than from the composition that built this handler:
        // the bar's click lambda can outlive a recomposition, and comparing
        // against a captured route left the Hosts tab a no-op from Settings.
        if (route == nav.currentBackStackEntry?.destination?.route) return
        nav.navigate(route) {
            // Keep the Hosts entry as the stable root while saving whichever
            // root destination is being covered. This restores search and
            // scroll state without creating duplicate root entries.
            popUpTo(Routes.HOSTS) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Edge to edge is enabled for the window, so this shell consumes system
    // bars before drawing the navigation controls. Every root destination gets
    // the same controls and therefore the same back/restoration behavior.
    val shell = Modifier.fillMaxSize().safeDrawingPadding()
    if (expanded) {
        Row(shell) {
            MainNavigationRail(selectedRoute = selectedRoute, onSelect = ::selectRoot)
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        Column(shell) {
            Box(Modifier.weight(1f)) { content() }
            MainNavigationBar(selectedRoute = selectedRoute, onSelect = ::selectRoot)
        }
    }
}

@Composable
private fun MainNavigationBar(selectedRoute: String, onSelect: (String) -> Unit) {
    NavigationBar {
        TABS.forEach { tab ->
            val isSelected = selectedRoute == tab.route
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(tab.route) },
                icon = {
                    Icon(
                        if (isSelected) tab.filledIcon else tab.outlineIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(tab.title) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(selectedRoute: String, onSelect: (String) -> Unit) {
    NavigationRail {
        TABS.forEach { tab ->
            val isSelected = selectedRoute == tab.route
            NavigationRailItem(
                selected = isSelected,
                onClick = { onSelect(tab.route) },
                icon = {
                    Icon(
                        if (isSelected) tab.filledIcon else tab.outlineIcon,
                        contentDescription = null,
                    )
                },
                label = { Text(tab.title) },
            )
        }
    }
}

// --- Hosts -------------------------------------------------------------

private enum class HostsPhase { Loading, Ready, Error }

private data class HostRow(
    val host: SshHost,
    val name: String,
    val detail: String,
    /** Open tabs for this host. Zero draws no badge. */
    val sessions: Int = 0,
)

private fun SshHost.toRow(sessions: Int): HostRow {
    val name = displayName.ifBlank { "$username@$host" }
    val authLabel = if (authKind == SshHost.AUTH_KEY) "key" else "password"
    val endpoint = "$username@$host:$port"
    return HostRow(
        host = this,
        name = name,
        detail = "$endpoint ($authLabel)",
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
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_LAYOUT_MIN_WIDTH_DP

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

    // This destination is recreated after returning from a pushed screen, so
    // loading on entry ensures a newly added or removed host appears at once.
    LaunchedEffect(Unit) { load() }

    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbarHostState.showSnackbar(notice)
            notice = ""
        }
    }

    val visible = remember(hosts, query) {
        val q = query.trim()
        if (q.isBlank()) {
            hosts
        } else {
            hosts.filter {
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
        nav.navigate(Routes.sshTerminal(row.host.id))
    }

    fun openFiles(row: HostRow) {
        val sessionId = SshSessions.openTab(row.host.id, kind = SshTabKind.Files)
        if (sessionId == null) {
            notice = "This host has no room for another files tab."
        } else {
            // Files is a tab in the terminal destination, not a separate route.
            nav.navigate(Routes.sshTerminal(row.host.id))
        }
    }

    fun openWorkspaces(row: HostRow) {
        nav.navigate(Routes.herdrWorkspace(row.host.id, row.name))
    }

    RemotlyScreen(title = "Hosts", snackbarHostState = snackbarHostState) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
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
                        action = "Add SSH host" to { nav.navigate(Routes.hostEditor()) },
                    )
                } else {
                    Column(Modifier.fillMaxSize()) {
                        val searchModifier = if (expanded) {
                            Modifier.widthIn(max = WideHostListWidth).fillMaxWidth()
                        } else {
                            Modifier.fillMaxWidth()
                        }
                        RemotlyTextField(
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
                        } else if (expanded) {
                            ExpandedHosts(
                                visible = visible,
                                selectedHostId = selectedRow?.host?.id,
                                onSelect = { selectedHostId = it.host.id },
                                onOpenTerminal = ::openTerminal,
                                onOpenFiles = ::openFiles,
                                onOpenWorkspaces = ::openWorkspaces,
                                onEdit = { row -> nav.navigate(Routes.hostEditor(row.host.id)) },
                                onRemove = { removeTarget = it },
                            )
                        } else {
                            CompactHosts(
                                visible = visible,
                                onOpenTerminal = ::openTerminal,
                                onOpenFiles = ::openFiles,
                                onOpenWorkspaces = ::openWorkspaces,
                                onEdit = { row -> nav.navigate(Routes.hostEditor(row.host.id)) },
                                onRemove = { removeTarget = it },
                            )
                        }
                    }
                }
            }

            if (phase == HostsPhase.Ready) {
                FloatingActionButton(
                    onClick = { nav.navigate(Routes.hostEditor()) },
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
                onTerminal = { onOpenTerminal(row) },
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
            if (!expanded) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                ) {
                    FilledTonalButton(
                        onClick = onTerminal,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Icon(Icons.Filled.Terminal, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Open terminal")
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        HostRowAction(label = "Files", icon = Icons.Filled.Folder, onClick = onFiles)
                        HostRowAction(label = "Workspaces", icon = Icons.Filled.Dashboard, onClick = onWorkspaces)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.HostRowAction(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.weight(1f),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, maxLines = 1, style = MaterialTheme.typography.labelLarge)
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
                title = "Open terminal",
                description = "Start or resume an SSH terminal session",
                primary = true,
                onClick = onOpenTerminal,
            )
            HostDetailAction(
                icon = Icons.Filled.Folder,
                title = "Files",
                description = "Browse files in an SSH tab",
                onClick = onOpenFiles,
            )
            HostDetailAction(
                icon = Icons.Filled.Dashboard,
                title = "Workspaces",
                description = "Open Herdr workspaces on this host",
                onClick = onOpenWorkspaces,
            )
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
    description: String,
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
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column {
                Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (primary) foreground else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
