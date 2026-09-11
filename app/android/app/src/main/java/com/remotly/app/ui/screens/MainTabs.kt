package com.remotly.app.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.remotly.app.session.SshSessions
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshHostStoreException
import com.remotly.app.ssh.SshModule
import com.remotly.app.ui.Routes
import com.remotly.app.ui.components.EmptyState
import com.remotly.app.ui.components.ErrorState
import com.remotly.app.ui.components.LoadingState
import com.remotly.app.ui.components.RemotlyScreen
import com.remotly.app.ui.components.ScreenAction
import com.remotly.app.ui.components.transferBarClearance
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Width, in dp, at or above which the tab bar becomes a side rail. */
private const val EXPANDED_WIDTH_DP = 840

/** Above this many hosts, search earns its own field instead of a toggle. */
private const val SEARCH_THRESHOLD = 8

private data class TabSpec(val title: String, val outlineIcon: ImageVector, val filledIcon: ImageVector)

private val TABS = listOf(
    TabSpec("Hosts", Icons.Outlined.Dns, Icons.Filled.Dns),
    TabSpec("Settings", Icons.Outlined.Settings, Icons.Filled.Settings),
)

/**
 * The two-destination shell: Hosts and Settings.
 *
 * Both destinations stay composed at all times. Each owns its own loading
 * state, and tearing one down on every tab switch would refetch the host
 * list and re-run the settings load whenever the user glances at the other
 * tab.
 */
@Composable
fun MainTabs(nav: NavHostController) {
    var selected by rememberSaveable { mutableStateOf(0) }
    val expanded = LocalConfiguration.current.screenWidthDp >= EXPANDED_WIDTH_DP

    val scenes: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            Scene(active = selected == 0) { HostsContent(nav) }
            Scene(active = selected == 1) { SettingsContent() }
        }
    }

    // Edge to edge is on for the window, so the shell consumes the system
    // bars here. Left unconsumed, the navigation bar draws under the
    // system's own and the system takes its touches, which shows up as a
    // destination that cannot be selected rather than as a visual defect.
    val shell = Modifier.fillMaxSize().safeDrawingPadding()
    if (expanded) {
        Row(shell) {
            MainNavigationRail(selected = selected, onSelect = { selected = it })
            Box(Modifier.weight(1f)) { scenes() }
        }
    } else {
        Column(shell) {
            Box(Modifier.weight(1f)) { scenes() }
            MainNavigationBar(selected = selected, onSelect = { selected = it })
        }
    }
}

/**
 * Hides an inactive destination instead of disposing it.
 *
 * Shrinking it to zero size takes it out of both the layout and the hit
 * test area, and [hideFromAccessibility] takes it out of the accessibility
 * tree, so a screen reader can never land on a tab the user cannot see.
 */
@Composable
private fun Scene(active: Boolean, content: @Composable () -> Unit) {
    Box(
        // fillMaxSize belongs to the active branch alone. Applying it first
        // and shrinking afterwards left the inactive pane at full size, and
        // being second in the stack it painted over the active one.
        modifier = if (active) {
            Modifier.fillMaxSize()
        } else {
            Modifier.size(0.dp).semantics { hideFromAccessibility() }
        },
    ) {
        content()
    }
}

@Composable
private fun MainNavigationBar(selected: Int, onSelect: (Int) -> Unit) {
    NavigationBar {
        TABS.forEachIndexed { index, tab ->
            val isSelected = selected == index
            NavigationBarItem(
                selected = isSelected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(if (isSelected) tab.filledIcon else tab.outlineIcon, contentDescription = null)
                },
                label = { Text(tab.title) },
            )
        }
    }
}

@Composable
private fun MainNavigationRail(selected: Int, onSelect: (Int) -> Unit) {
    NavigationRail {
        TABS.forEachIndexed { index, tab ->
            val isSelected = selected == index
            NavigationRailItem(
                selected = isSelected,
                onClick = { onSelect(index) },
                icon = {
                    Icon(if (isSelected) tab.filledIcon else tab.outlineIcon, contentDescription = null)
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
    val accessibilityLabel: String,
    /** Open tabs for this host. Zero draws no badge. */
    val sessions: Int = 0,
)

private fun SshHost.toRow(sessions: Int): HostRow {
    val name = displayName.ifBlank { "$username@$host" }
    val authLabel = if (authKind == SshHost.AUTH_KEY) "key" else "password"
    val endpoint = "$username@$host:$port"
    val base = "$name, SSH host $endpoint, $authLabel authentication"
    val open = if (sessions <= 0) "" else ", $sessions open ${if (sessions == 1) "session" else "sessions"}"
    return HostRow(
        host = this,
        name = name,
        detail = "$endpoint ($authLabel)",
        accessibilityLabel = base + open,
        sessions = sessions,
    )
}

@Composable
private fun HostsContent(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    // Only the newest load may write state: a slow response from a previous
    // visit to this tab must not overwrite what a later one found.
    val generation = remember { AtomicInteger(0) }

    var phase by remember { mutableStateOf(HostsPhase.Loading) }
    var hosts by remember { mutableStateOf<List<HostRow>>(emptyList()) }
    var errorMessage by remember { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var addOpen by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<HostRow?>(null) }
    var removeTarget by remember { mutableStateOf<HostRow?>(null) }
    var notice by remember { mutableStateOf("") }
    val snackbarHostState = remember { SnackbarHostState() }

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

    // This composable is recreated from scratch every time the user returns
    // to this tab from another screen, so a plain launch-on-entry reloads
    // every visit rather than only the first one.
    LaunchedEffect(Unit) { load() }

    LaunchedEffect(notice) {
        if (notice.isNotBlank()) {
            snackbarHostState.showSnackbar(notice)
            notice = ""
        }
    }

    val showSearch = searchOpen || hosts.size > SEARCH_THRESHOLD
    val visible = remember(hosts, query) {
        val q = query.trim()
        if (q.isBlank()) hosts
        else hosts.filter { it.name.contains(q, ignoreCase = true) || it.detail.contains(q, ignoreCase = true) }
    }

    val actions = remember(phase, hosts.size, searchOpen) {
        if (phase != HostsPhase.Ready || hosts.isEmpty() || hosts.size > SEARCH_THRESHOLD) {
            emptyList()
        } else {
            listOf(
                ScreenAction(
                    key = "search",
                    icon = Icons.Filled.Search,
                    title = if (searchOpen) "Hide search" else "Search hosts",
                    onClick = { searchOpen = !searchOpen; query = "" },
                ),
            )
        }
    }

    RemotlyScreen(title = "Hosts", actions = actions, snackbarHostState = snackbarHostState) { padding ->
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
                        if (showSearch) {
                            OutlinedTextField(
                                value = query,
                                onValueChange = { query = it },
                                placeholder = { Text("Search hosts") },
                                singleLine = true,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                            )
                        }
                        if (visible.isEmpty()) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(
                                    "No hosts match this search.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        } else {
                            LazyColumn(
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                items(visible, key = { it.host.id }) { row ->
                                    HostRowCard(
                                        row = row,
                                        onOpen = { nav.navigate(Routes.sshTerminal(it.host.id)) },
                                        onMenu = { menuFor = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            if (phase == HostsPhase.Ready) {
                FloatingActionButton(
                    onClick = { addOpen = true },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(20.dp)
                        .padding(bottom = transferBarClearance()),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Add a host")
                }
            }
        }
    }

    if (addOpen) {
        AddHostSheet(
            onDismiss = { addOpen = false },
            onAddSsh = {
                addOpen = false
                nav.navigate(Routes.hostEditor())
            },
        )
    }

    menuFor?.let { row ->
        HostMenuSheet(
            row = row,
            onDismiss = { menuFor = null },
            onOpen = {
                menuFor = null
                nav.navigate(Routes.sshTerminal(row.host.id))
            },
            onFiles = {
                menuFor = null
                nav.navigate(Routes.files(row.host.id))
            },
            onWorkspaces = {
                menuFor = null
                nav.navigate(Routes.herdrWorkspace(row.host.id, row.name))
            },
            onEdit = {
                menuFor = null
                nav.navigate(Routes.hostEditor(row.host.id))
            },
            onRemove = {
                menuFor = null
                removeTarget = row
            },
        )
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HostRowCard(row: HostRow, onOpen: (HostRow) -> Unit, onMenu: (HostRow) -> Unit) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onOpen(row) },
                onLongClick = { onMenu(row) },
                onClickLabel = "Open terminal",
                onLongClickLabel = "Host actions",
            )
            .semantics { contentDescription = row.accessibilityLabel },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Dns,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(row.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                Text(
                    row.detail,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (row.sessions > 0) {
                // A count, not a coloured dot: the number is the information,
                // and a mark alone would carry it for nobody who cannot see it.
                Badge(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ) {
                    Text("${row.sessions}")
                }
                Spacer(Modifier.width(4.dp))
            }
            // The visible way into the same menu long press opens: a hidden
            // long press is undiscoverable and unusable with a screen reader.
            IconButton(onClick = { onMenu(row) }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Host actions for ${row.name}")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddHostSheet(onDismiss: () -> Unit, onAddSsh: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            "Add a host",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SheetActionRow(
            icon = Icons.Filled.Terminal,
            title = "Add SSH host",
            description = "Connect to any remote server with standard SSH",
            onClick = onAddSsh,
        )
        Spacer(Modifier.height(24.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostMenuSheet(
    row: HostRow,
    onDismiss: () -> Unit,
    onOpen: () -> Unit,
    onFiles: () -> Unit,
    onWorkspaces: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Text(
            row.name,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        SheetActionRow(icon = Icons.Filled.Terminal, title = "Open terminal", onClick = onOpen)
        SheetActionRow(icon = Icons.Filled.Folder, title = "Files", onClick = onFiles)
        SheetActionRow(
            icon = Icons.Filled.Dashboard,
            title = "Workspaces",
            description = "Herdr workspaces running on this host",
            onClick = onWorkspaces,
        )
        SheetActionRow(icon = Icons.Filled.Edit, title = "Edit", onClick = onEdit)
        SheetActionRow(icon = Icons.Filled.Delete, title = "Remove", destructive = true, onClick = onRemove)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SheetActionRow(
    icon: ImageVector,
    title: String,
    description: String? = null,
    destructive: Boolean = false,
    onClick: () -> Unit,
) {
    val containerColor =
        if (destructive) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer
    val contentColor =
        if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = title, role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(36.dp).clip(CircleShape).background(containerColor),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = contentColor)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                fontWeight = FontWeight.Medium,
                color = if (destructive) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
            if (description != null) {
                Text(
                    description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
