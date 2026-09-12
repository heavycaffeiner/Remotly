package com.remotly.app.session

// Tab state for SSH terminals.
//
// SSH tabs are live-only. There is no replay, no cursor, and no persisted
// document: a closed session is gone, and reconnecting starts a new one.
//
// Pure data and pure functions. Every function returns new state, so a
// caller can apply an action and render the result with no round trip. A
// no-op call returns the same instance it was given, so a caller can skip
// work with a reference check instead of a deep comparison.

/** Tabs per host. Bounded so a runaway caller cannot grow the strip forever. */
const val MAX_SSH_TABS = 8

/** Titles are user input; bounded for display and storage. */
const val MAX_SSH_TAB_TITLE = 60

enum class SshTabPhase { Connecting, HostKey, Active, Closed, Failed }

/**
 * What a tab holds.
 *
 * A shell tab owns a live SSH session. A files tab owns none: the SFTP
 * connection is per host and the bridge owns it, so the tab is only a place
 * to render the browser, and it sits in the shell strip beside them. A
 * workspace tab is a shell too, but it is attached to one herdr workspace and
 * is driven by that workspace's own screen, so it is budgeted apart from the
 * shell tabs and never appears in their strip.
 */
enum class SshTabKind { Shell, Files, Workspace }

/** The one multiplexer a tab can be addressed to; a swipe gesture targets it. */
const val MUX_HERDR = "herdr"

data class SshTab(
    /** App-minted, stable for the tab's life, and the terminal's own key. */
    val sessionId: String,
    val title: String,
    val phase: SshTabPhase,
    /** User-facing explanation for a closed or failed tab. Empty otherwise. */
    val detail: String,
    val kind: SshTabKind,
    /**
     * Set once the user renames the tab by hand. The running program's own
     * title is ignored after that, so a shell repainting its title cannot
     * take the name back.
     */
    val titlePinned: Boolean = false,
    /** The multiplexer this tab attached to, when it was opened to attach to one. */
    val mux: String? = null,
    /** Which of the multiplexer's sessions, when it has named ones. */
    val muxSession: String? = null,
    /** The herdr workspace a workspace tab is attached to. */
    val workspaceId: String? = null,
    /**
     * Command that rejoins the workspace manager after a new SSH connection
     * reaches its shell. The manager owns the focused workspace and tab, so
     * rejoining resumes those ids when they still exist and otherwise lands
     * on the manager's current valid focus. Null for ordinary shells.
     */
    val reconnectCommand: String? = null,
)

data class SshTabsState(
    val hostId: String,
    val tabs: List<SshTab> = emptyList(),
    val activeSessionId: String? = null,
)

fun createSshTabs(hostId: String): SshTabsState = SshTabsState(hostId)

/**
 * Mints a session id.
 *
 * Numbered by a running sequence, not by how many tabs are open right now:
 * counting open tabs reuses a number the moment one closes, so two tabs end
 * up sharing a name. The timestamp suffix already makes the id unique, so it
 * carries no host prefix. A composite id is what let a screen bind its view
 * to a different terminal than the one its tab was actually writing to, and
 * what let a close release nothing while the real terminal leaked.
 */
fun mintSessionId(seq: Int): String {
    val n = if (seq > 0) seq else 1
    return "t$n-${System.currentTimeMillis().toString(36)}"
}

fun findSshTab(state: SshTabsState, sessionId: String): SshTab? =
    state.tabs.firstOrNull { it.sessionId == sessionId }

/** How many tabs of a kind's own surface are open. Shell and workspace are budgeted apart. */
fun surfaceCount(state: SshTabsState, kind: SshTabKind): Int {
    val workspace = kind == SshTabKind.Workspace
    return state.tabs.count { (it.kind == SshTabKind.Workspace) == workspace }
}

data class AddSshTabResult(val state: SshTabsState, val tab: SshTab?)

/**
 * Appends a tab and makes it active.
 *
 * Returns the existing tab, state unchanged, for a duplicate id. Returns a
 * null tab past the cap or for a blank id: a blank id could never be
 * selected or closed by name again.
 */
fun addSshTab(
    state: SshTabsState,
    sessionId: String,
    title: String,
    kind: SshTabKind = SshTabKind.Shell,
): AddSshTabResult {
    findSshTab(state, sessionId)?.let { return AddSshTabResult(state, it) }
    if (sessionId.isEmpty() || surfaceCount(state, kind) >= MAX_SSH_TABS) {
        return AddSshTabResult(state, null)
    }
    val tab = SshTab(sessionId = sessionId, title = title, phase = SshTabPhase.Connecting, detail = "", kind = kind)
    return AddSshTabResult(state.copy(tabs = state.tabs + tab, activeSessionId = sessionId), tab)
}

/** Drops a tab. Focus moves to its left neighbour when it was the active one. */
fun removeSshTab(state: SshTabsState, sessionId: String): SshTabsState {
    val index = state.tabs.indexOfFirst { it.sessionId == sessionId }
    if (index < 0) return state
    val tabs = state.tabs.filter { it.sessionId != sessionId }
    if (state.activeSessionId != sessionId) return state.copy(tabs = tabs)
    val neighbour = tabs.getOrNull(maxOf(0, index - 1))
    return state.copy(tabs = tabs, activeSessionId = neighbour?.sessionId)
}

fun setActiveSshTab(state: SshTabsState, sessionId: String): SshTabsState {
    if (findSshTab(state, sessionId) == null) return state
    return state.copy(activeSessionId = sessionId)
}

/** Updates one tab's phase. Unknown ids are ignored. */
fun setSshTabPhase(state: SshTabsState, sessionId: String, phase: SshTabPhase, detail: String = ""): SshTabsState {
    val tab = findSshTab(state, sessionId) ?: return state
    if (tab.phase == phase && tab.detail == detail) return state
    return state.copy(
        tabs = state.tabs.map { if (it.sessionId == sessionId) it.copy(phase = phase, detail = detail) else it },
    )
}

/**
 * Renames a tab.
 *
 * A tab the user named keeps that name against a later untitled call; only a
 * pinning call may change it again. A blank name is ignored rather than
 * clearing the label, and a long one is bounded for display and storage.
 */
fun setSshTabTitle(state: SshTabsState, sessionId: String, title: String, pin: Boolean = false): SshTabsState {
    val tab = findSshTab(state, sessionId) ?: return state
    if (!pin && tab.titlePinned) return state
    val next = title.trim().take(MAX_SSH_TAB_TITLE)
    if (next.isEmpty()) return state
    val pinning = pin && !tab.titlePinned
    if (tab.title == next && !pinning) return state
    return state.copy(
        tabs = state.tabs.map {
            if (it.sessionId == sessionId) it.copy(title = next, titlePinned = it.titlePinned || pin) else it
        },
    )
}

private val SHELL_TITLE = Regex("^Shell (\\d+)$")

/** The lowest shell number not already taken by an open tab. */
fun nextShellNumber(tabs: List<SshTab>): Int {
    val used = tabs.mapNotNull { SHELL_TITLE.matchEntire(it.title)?.groupValues?.get(1)?.toIntOrNull() }.toHashSet()
    var n = 1
    while (used.contains(n)) n += 1
    return n
}

private val FILES_TITLE = Regex("^Files(?: (\\d+))?\$")

/** The next free browser title: "Files", then "Files 2", as tabs come and go. */
fun nextFilesTitle(tabs: List<SshTab>): String {
    val used = tabs
        .mapNotNull { FILES_TITLE.matchEntire(it.title)?.groupValues?.get(1) }
        .map { if (it.isEmpty()) 1 else it.toIntOrNull() ?: 1 }
        .toHashSet()
    var n = 1
    while (used.contains(n)) n += 1
    return if (n == 1) "Files" else "Files $n"
}

private val GENERIC_SHELL_TITLE = Regex("^Shell \\d+$")

/** True for a name the app minted itself rather than one a user or a program chose. */
fun isGenericShellTitle(title: String): Boolean = GENERIC_SHELL_TITLE.matches(title)

/**
 * Whether a tab strip is worth showing.
 *
 * A lone tab called "Shell 1" needs no strip: it repeats what the screen's
 * own title already says. A lone tab with any other name is the opposite,
 * since the name is the only thing saying what it is attached to.
 */
fun shouldShowTabStrip(tabs: List<SshTab>): Boolean =
    tabs.size > 1 || (tabs.size == 1 && !isGenericShellTitle(tabs.first().title))
