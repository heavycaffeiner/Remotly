package com.remotly.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.remotly.app.session.SshSessions
import com.remotly.app.session.SshTab
import com.remotly.app.session.SshTabKind
import com.remotly.app.ssh.SshHost
import com.remotly.app.ssh.SshModule
import com.remotly.app.ui.components.TransferHistory
import com.remotly.app.ui.components.TransferIndicator
import com.remotly.app.transfers.TransferRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceShell(nav: NavHostController, content: @Composable () -> Unit) {
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.HOSTS
    val hostId = entry?.arguments?.getString(Routes.ARG_HOST_ID)
    val working = route == Routes.SSH_TERMINAL || route == Routes.HERDR_WORKSPACE
    val hostListing = rememberSavedHosts(route)
    val hosts = hostListing.hosts
    val sessions by SshSessions.openHosts.collectAsStateWithLifecycle()
    var selector by rememberSaveable { mutableStateOf(false) }
    var transfers by rememberSaveable { mutableStateOf(false) }
    var sidebarExpanded by rememberSaveable { mutableStateOf(true) }
    var sidebarWidth by rememberSaveable { mutableFloatStateOf(288f) }
    var resizeFocused by remember { mutableStateOf(false) }
    val companionHosts = rememberSaveable { mutableStateOf(emptyList<String>()) }
    val companion = working && hostId != null && hostId in companionHosts.value
    val density = LocalDensity.current

    fun root(destination: String) {
        selector = false
        if (nav.currentDestination?.route == destination) return
        nav.navigate(destination) {
            popUpTo(Routes.HOSTS) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }
    fun open(host: SshHost, tab: SshTab? = null) {
        selector = false
        if (tab != null) SshSessions.selectTab(host.id, tab.sessionId)
        val selected = tab ?: SshSessions.state(host.id).value.tabs.let { state ->
            state.tabs.firstOrNull { it.sessionId == state.activeSessionId }
        }
        val destination = if (selected?.kind == SshTabKind.Workspace) {
            Routes.herdrWorkspace(host.id, host.displayName.ifBlank { host.host },
                selected.workspaceId, selected.title, selected.muxSession?.takeIf { it.isNotEmpty() })
        } else Routes.sshTerminal(host.id)
        nav.openRoute(destination)
    }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val layout = WorkspaceLayout(maxWidth.value.toInt(), maxHeight.value.toInt())
        val shellNavigation = route != Routes.HOST_EDITOR && route != Routes.HERDR_WORKSPACE
        val rail = layout.rail
        val sidebar = shellNavigation && layout.sidebar && sidebarExpanded && !companion
        val actions = WorkspaceActions(
            selectWork = { selector = true },
            toggleSidebar = { sidebarExpanded = !sidebarExpanded },
            sidebarVisible = sidebar,
            modalOpen = selector || transfers,
            openTransfers = { transfers = true },
            companionRequested = companion,
            toggleCompanion = {
                hostId?.let { id ->
                    companionHosts.value = if (id in companionHosts.value) companionHosts.value - id else companionHosts.value + id
                }
            },
        )
        CompositionLocalProvider(LocalWorkspaceLayout provides layout, LocalWorkspaceActions provides actions) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.weight(1f).fillMaxWidth()) {
                    if (sidebar) {
                        Column(Modifier.width(sidebarWidth.dp).fillMaxHeight()) {
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { root(Routes.HOSTS) }, modifier = Modifier.weight(1f)) {
                                    Text("Remotly", style = MaterialTheme.typography.titleMedium)
                                }
                                IconButton(onClick = { sidebarExpanded = false }) {
                                    Icon(Icons.Default.ChevronLeft, contentDescription = "Hide navigation")
                                }
                            }
                            SidebarNavigation(route, ::root)
                            hostListing.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(12.dp)) }
                            WorkspaceList(hosts, sessions, hostId, onOpen = ::open, modifier = Modifier.weight(1f))
                            TextButton(onClick = { transfers = true }, modifier = Modifier.fillMaxWidth()) { Text("Transfers") }
                        }
                        VerticalDivider(Modifier.fillMaxHeight().width(8.dp)
                            .onFocusChanged { resizeFocused = it.isFocused }
                            .onKeyEvent { event ->
                                if (event.type != KeyEventType.KeyDown) false else when (event.key) {
                                    Key.DirectionLeft -> { sidebarWidth = (sidebarWidth - 24f).coerceAtLeast(240f); true }
                                    Key.DirectionRight -> { sidebarWidth = (sidebarWidth + 24f).coerceAtMost(360f); true }
                                    else -> false
                                }
                            }.focusable().pointerInput(Unit) {
                            detectHorizontalDragGestures { change, amount ->
                                change.consume()
                                sidebarWidth = (sidebarWidth + with(density) { amount.toDp().value }).coerceIn(240f, 360f)
                            }
                        }.semantics {
                            contentDescription = "Resize navigation sidebar"
                            stateDescription = "${sidebarWidth.toInt()} dp"
                            customActions = listOf(
                                CustomAccessibilityAction("Narrower navigation sidebar") { sidebarWidth = (sidebarWidth - 24f).coerceAtLeast(240f); true },
                                CustomAccessibilityAction("Wider navigation sidebar") { sidebarWidth = (sidebarWidth + 24f).coerceAtMost(360f); true },
                            )
                        }, color = if (resizeFocused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant)
                    } else if (shellNavigation && rail && !layout.sidebar) {
                        NavigationRail {
                            IconButton(onClick = { selector = true }) {
                                Icon(Icons.Default.Menu, contentDescription = "Open workspace selector")
                            }
                            RootNavigation(route, ::root, vertical = true)
                            Spacer(Modifier.weight(1f))
                            IconButton(onClick = { transfers = true }) { Icon(Icons.Default.SwapVert, "Transfers") }
                        }
                    }
                    Box(Modifier.weight(1f).fillMaxHeight()) { content() }
                }
                if (!working && route != Routes.HOST_EDITOR) TransferIndicator(onOpen = { transfers = true })
                if (!rail && !working && route != Routes.HOST_EDITOR) RootNavigation(route, ::root, vertical = false)
            }
            if (selector) {
                ModalBottomSheet(onDismissRequest = { selector = false }) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("Switch workspace", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                        IconButton(onClick = { selector = false }) { Icon(Icons.Default.Close, "Close workspace selector") }
                    }
                    RootNavigation(route, ::root, vertical = false)
                    hostListing.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
                    WorkspaceList(hosts, sessions, hostId, onOpen = ::open, modifier = Modifier.fillMaxWidth().heightIn(max = 640.dp))
                }
            }
            TransferHistory(transfers, onDismiss = { transfers = false })
        }
    }
}

@Composable
fun WorkspaceNavigationButton() {
    val actions = LocalWorkspaceActions.current
    val layout = LocalWorkspaceLayout.current
    IconButton(onClick = if (layout.sidebar && !actions.companionRequested) actions.toggleSidebar else actions.selectWork) {
        Icon(Icons.Default.Menu, if (layout.sidebar && !actions.companionRequested) {
            if (actions.sidebarVisible) "Hide navigation" else "Show navigation"
        } else "Open workspace selector")
    }
}

@Composable
private fun SidebarNavigation(route: String, onSelect: (String) -> Unit) {
    val selected = when (route) {
        Routes.SSH_TERMINAL, Routes.HERDR_WORKSPACE -> Routes.SESSIONS
        Routes.TERMINAL_APPEARANCE -> Routes.SETTINGS
        else -> route
    }
    Column(Modifier.padding(horizontal = 8.dp).fillMaxWidth()) {
        listOf(Triple(Routes.HOSTS, "Hosts", Icons.Default.Dns),
            Triple(Routes.SESSIONS, "Sessions", Icons.Default.Terminal),
            Triple(Routes.SETTINGS, "Settings", Icons.Default.Settings)).forEach { (id, label, icon) ->
            NavigationDrawerItem(label = { Text(label) }, selected = selected == id,
                onClick = { onSelect(id) }, icon = { Icon(icon, null) })
        }
    }
}

@Composable
private fun RootNavigation(route: String, onSelect: (String) -> Unit, vertical: Boolean) {
    val destinations = listOf(Triple(Routes.HOSTS, "Hosts", Icons.Default.Dns),
        Triple(Routes.SESSIONS, "Sessions", Icons.Default.Terminal),
        Triple(Routes.SETTINGS, "Settings", Icons.Default.Settings))
    val selected = when (route) {
        Routes.SSH_TERMINAL, Routes.HERDR_WORKSPACE -> Routes.SESSIONS
        Routes.TERMINAL_APPEARANCE -> Routes.SETTINGS
        else -> route
    }
    if (vertical) {
        destinations.forEach { (id, label, icon) ->
            NavigationRailItem(selected == id, { onSelect(id) }, icon = { Icon(icon, null) }, label = { Text(label) })
        }
    } else {
        NavigationBar {
            destinations.forEach { (id, label, icon) ->
                NavigationBarItem(selected == id, { onSelect(id) }, icon = { Icon(icon, null) }, label = { Text(label) })
            }
        }
    }
}

data class SavedHostListing(val hosts: List<SshHost> = emptyList(), val error: String? = null)

@Composable
fun rememberSavedHosts(refreshKey: Any?): SavedHostListing {
    var listing by remember { mutableStateOf(SavedHostListing()) }
    LaunchedEffect(refreshKey) {
        val result = withContext(Dispatchers.IO) {
            runCatching { requireNotNull(SshModule.store) { "Host storage is unavailable" }.list() }
        }
        result.onSuccess { listing = SavedHostListing(it) }
            .onFailure { listing = listing.copy(error = "Saved hosts could not be read. Your open sessions are unchanged.") }
    }
    return listing
}

@Composable
private fun WorkspaceList(
    hosts: List<SshHost>,
    sessions: List<com.remotly.app.session.SshHostSessionState>,
    activeHostId: String?,
    onOpen: (SshHost, SshTab?) -> Unit,
    modifier: Modifier = Modifier,
    sessionsOnly: Boolean = false,
) {
    LazyColumn(modifier) {
        hosts.forEach { host ->
            val tabs = sessions.firstOrNull { it.tabs.hostId == host.id }?.tabs?.tabs.orEmpty()
            if (!sessionsOnly || tabs.isNotEmpty()) {
                item(key = "host-${host.id}") {
                    ListItem(headlineContent = { Text(host.displayName.ifBlank { host.host }) },
                        supportingContent = { Text("${host.username}@${host.host}") },
                        leadingContent = { Icon(Icons.Default.Dns, null) },
                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .workspaceClick("Resume ${host.displayName.ifBlank { host.host }}") { onOpen(host, null) })
                }
                items(tabs, key = { it.sessionId }) { tab ->
                    val kind = when (tab.kind) { SshTabKind.Shell -> "SSH"; SshTabKind.Files -> "Files"; SshTabKind.Workspace -> "Herdr" }
                    ListItem(headlineContent = { Text(tab.title) }, supportingContent = { Text("$kind, ${tab.phase.name.lowercase()}") },
                        leadingContent = { Icon(if (tab.kind == SshTabKind.Files) Icons.Default.Folder else Icons.Default.Terminal, null) },
                        colors = ListItemDefaults.colors(containerColor = if (activeHostId == host.id && sessions.firstOrNull { it.tabs.hostId == host.id }?.tabs?.activeSessionId == tab.sessionId) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface),
                        modifier = Modifier.padding(start = 12.dp).heightIn(min = 48.dp).workspaceClick("Open ${tab.title}") { onOpen(host, tab) })
                }
            }
        }
        if (sessionsOnly && sessions.none { it.tabs.tabs.isNotEmpty() }) {
            item { Text("No open sessions", modifier = Modifier.padding(24.dp)) }
        }
    }
}

private fun Modifier.workspaceClick(label: String, action: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClickLabel = label, onClick = action))

@Composable
fun SessionsContent(nav: NavHostController) {
    val listing = rememberSavedHosts(Unit)
    val hosts = listing.hosts
    val sessions by SshSessions.openHosts.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkspaceNavigationButton()
            Text("Sessions", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp).semantics { heading() })
        }
        listing.error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp)) }
        WorkspaceList(hosts, sessions, null, onOpen = { host, tab ->
            if (tab != null) SshSessions.selectTab(host.id, tab.sessionId)
            if (tab?.kind == SshTabKind.Workspace) {
                nav.openRoute(Routes.herdrWorkspace(host.id, host.displayName.ifBlank { host.host }, tab.workspaceId, tab.title, tab.muxSession?.takeIf(String::isNotEmpty)))
            } else nav.openRoute(Routes.sshTerminal(host.id))
        }, modifier = Modifier.weight(1f), sessionsOnly = true)
    }
}

@Composable
fun WorkspaceTransferButton() {
    val transfers by TransferRegistry.transfers.collectAsStateWithLifecycle()
    val count = transfers.count(TransferRegistry::raisesBar)
    if (count > 0) {
        val actions = LocalWorkspaceActions.current
        IconButton(onClick = actions.openTransfers) {
            BadgedBox(badge = { Badge { Text(count.toString()) } }) { Icon(Icons.Default.SwapVert, "Show transfers") }
        }
    }
}
