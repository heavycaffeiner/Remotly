package com.remotly.app.herdr

import com.remotly.app.ssh.HerdrBridge
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Runs herdr commands on a host and returns typed results.
//
// HerdrCommands builds the command strings and parses the documents; this
// class is the transport half. Each call is one `herdr` invocation over its
// own SSH exec channel: the control commands the app makes all finish and
// disconnect, so nothing here holds a session open.
//
// What belongs here is decided by that shape, not by what the CLI offers: a
// command that prints one document and exits. `session attach`, `agent attach`,
// and the streaming `pane` commands need a PTY and stay attached, so they
// cannot cross this bridge at all; reaching them means opening a terminal on
// the host and running herdr there, which is what the SSH terminal screen
// already does.
//
// Every failure surfaces as a HerdrError. A connect-level failure carries the
// code HerdrBridge reported; a herdr-level failure carries the code and
// message from its error document, whichever stream that document arrived on.

/** The exec-only slice of the transport HerdrClient needs, so a test can fake it. */
fun interface HerdrTransport {
    fun exec(hostId: String, command: String): HerdrBridge.Outcome
}

private object DefaultHerdrTransport : HerdrTransport {
    override fun exec(hostId: String, command: String): HerdrBridge.Outcome =
        HerdrBridge.exec(hostId, command)
}
class HerdrClient(
    private val transport: HerdrTransport = DefaultHerdrTransport,
    /** Where the blocking transport call runs. Overridable so a test can pin
     *  it to the same dispatcher driving its virtual clock. */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    /** Where herdr was found on a host, once a lookup has had to run. */
    private val herdrDir = ConcurrentHashMap<String, String>()

    /**
     * Runs one command and returns its stdout as text.
     *
     * A shell that cannot find herdr is not a failure yet: the lookup runs, and
     * the command is repeated with the directory it named ahead of PATH. What
     * that directory is holds for the host from then on.
     */
    suspend fun execHerdr(hostId: String, command: String): String {
        val dir = herdrDir[hostId]
        if (dir != null) return runHerdr(hostId, withPathPrefix(dir, command))
        return try {
            runHerdr(hostId, command)
        } catch (e: HerdrError) {
            if (!isMissingBinary(e)) throw e
            val found = locateHerdr(hostId)
            herdrDir[hostId] = found
            runHerdr(hostId, withPathPrefix(found, command))
        }
    }

    /**
     * Whether the shell could not find herdr at all.
     *
     * The wording is the shell's, not herdr's: zsh says "command not found: herdr",
     * bash "herdr: command not found", dash "herdr: not found". A shell that says
     * something else is reported as it is rather than guessed at.
     */
    private fun isMissingBinary(e: HerdrError): Boolean =
        e.code == "herdr_cli" && e.detail.contains("not found", ignoreCase = true)

    /**
     * The directory holding herdr, asked of the user's own shell.
     *
     * Reached only after a plain call failed, so the cost is paid on hosts that
     * need it and never on hosts where herdr is already on the default PATH.
     */
    private suspend fun locateHerdr(hostId: String): String {
        val found = parseHerdrLookup(runHerdr(hostId, herdrLookupCommand()))
            ?: throw HerdrError(
                "herdr_missing",
                "herdr was not found on this host, not even by your own shell.",
            )
        val cut = found.lastIndexOf('/')
        return if (cut > 0) found.substring(0, cut) else "/"
    }

    /** Runs one command as given and maps its outcome. */
    private suspend fun runHerdr(hostId: String, command: String): String = withContext(ioDispatcher) {
        val outcome = transport.exec(hostId, command)
        if (!outcome.ok) {
            throw HerdrError(outcome.code, outcome.message.ifEmpty { "The host could not be reached." })
        }
        val stdout = String(outcome.stdout, Charsets.UTF_8)
        val stderr = String(outcome.stderr, Charsets.UTF_8)

        // herdr reports its own failures as a typed error document, and which
        // stream carries it depends on the command: `session list --json`
        // answers on stdout, while `api snapshot` writes the document to
        // stderr and exits one. Both are read before the status is
        // considered, or the reason herdr gave would be replaced by
        // "unreadable output" or by the raw document as text.
        val cli = parseCliError(stdout) ?: parseCliError(stderr)
        if (cli != null) throw HerdrError(cli.code, cli.message)

        if (outcome.exitCode == 0) return@withContext stdout

        // A non-zero exit with no document is the CLI itself failing: a
        // missing binary, or a shell that could not run it.
        val trimmed = stderr.trim()
        throw HerdrError(
            "herdr_cli",
            trimmed.ifEmpty { "herdr exited with status ${outcome.exitCode}." },
        )
    }

    /** The named sessions on the host, whether or not their servers are running. */
    suspend fun listHerdrSessions(hostId: String): List<HerdrSession> =
        parseSessions(execHerdr(hostId, sessionListCommand()))

    /** One session's whole state: workspaces, tabs, panes, and what has focus. */
    suspend fun herdrSnapshot(hostId: String, session: String? = null): HerdrSnapshot =
        parseSnapshot(execHerdr(hostId, apiSnapshotCommand(session)))

    suspend fun createHerdrWorkspace(
        hostId: String,
        opts: HerdrCreateWorkspace,
        session: String? = null,
    ): HerdrCreatedWorkspace =
        parseCreatedWorkspace(execHerdr(hostId, workspaceCreateCommand(opts, session)))

    /**
     * Focuses a workspace.
     *
     * This is what makes the session persistent from the app's side: the
     * workspace keeps running on the host, and focusing it is what a later
     * terminal attach lands on.
     */
    suspend fun focusHerdrWorkspace(hostId: String, workspaceId: String, session: String? = null) {
        execHerdr(hostId, workspaceFocusCommand(workspaceId, session))
    }

    suspend fun closeHerdrWorkspace(hostId: String, workspaceId: String, session: String? = null) {
        execHerdr(hostId, workspaceCloseCommand(workspaceId, session))
    }

    suspend fun renameHerdrWorkspace(
        hostId: String,
        workspaceId: String,
        label: String,
        session: String? = null,
    ) {
        execHerdr(hostId, workspaceRenameCommand(workspaceId, label, session))
    }

    suspend fun createHerdrTab(hostId: String, opts: HerdrCreateTab = HerdrCreateTab(), session: String? = null) {
        execHerdr(hostId, tabCreateCommand(opts, session))
    }

    suspend fun focusHerdrTab(hostId: String, tabId: String, session: String? = null) {
        execHerdr(hostId, tabFocusCommand(tabId, session))
    }

    suspend fun renameHerdrTab(hostId: String, tabId: String, label: String, session: String? = null) {
        execHerdr(hostId, tabRenameCommand(tabId, label, session))
    }

    suspend fun closeHerdrTab(hostId: String, tabId: String, session: String? = null) {
        execHerdr(hostId, tabCloseCommand(tabId, session))
    }

    /** One workspace's tabs. What the app's tab strip is drawn from. */
    suspend fun listHerdrTabs(hostId: String, workspaceId: String, session: String? = null): List<HerdrTab> =
        parseTabs(execHerdr(hostId, tabListCommand(workspaceId, session)))

    /** The plugin actions this host offers, as `plugin.action` ids. */
    suspend fun listHerdrPluginActions(
        hostId: String,
        pluginId: String? = null,
        session: String? = null,
    ): List<String> =
        parsePluginActions(execHerdr(hostId, pluginActionListCommand(pluginId, session)))

    /**
     * Runs a plugin action.
     *
     * See [pluginActionInvokeCommand]: the call returns once herdr has started
     * the action, not once it has finished.
     */
    suspend fun invokeHerdrPluginAction(hostId: String, actionId: String, session: String? = null) {
        execHerdr(hostId, pluginActionInvokeCommand(actionId, session))
    }

    /** Stops a session's server. Its workspaces are gone until it starts again. */
    suspend fun stopHerdrSession(hostId: String, name: String) {
        execHerdr(hostId, sessionStopCommand(name))
    }

    /** Deletes a session: its directory and socket, not only its server. */
    suspend fun deleteHerdrSession(hostId: String, name: String) {
        execHerdr(hostId, sessionDeleteCommand(name))
    }

    /** A pane's contents as plain text, for a preview. */
    suspend fun readHerdrPane(hostId: String, opts: HerdrPaneRead, session: String? = null): String =
        execHerdr(hostId, paneReadCommand(opts, session))

    /**
     * Moves the session to the workspace beside the focused one.
     *
     * herdr ships `next_workspace` and `previous_workspace` unbound, so there
     * is no chord to send an attached terminal: the move is made over the
     * socket instead, following herdr's own workspace order. Wraps at both
     * ends, so repeating the gesture keeps going round. Returns where it
     * landed, or null when the session has nowhere else to go.
     *
     * [from] is the order the caller is already holding, which is the whole
     * cost of the gesture: with it the move is one command, without it a
     * snapshot read comes first.
     */
    suspend fun moveHerdrWorkspace(
        hostId: String,
        direction: Int,
        session: String? = null,
        from: HerdrWorkspaceOrder? = null,
    ): HerdrWorkspace? {
        val known = from ?: herdrSnapshot(hostId, session).let {
            HerdrWorkspaceOrder(it.workspaces, it.focusedWorkspaceId)
        }
        val next = nextHerdrWorkspace(known, direction) ?: return null
        focusHerdrWorkspace(hostId, next.workspaceId, session)
        return next
    }
}

/** Where a move starts from, when the caller already knows it. */
data class HerdrWorkspaceOrder(
    val workspaces: List<HerdrWorkspace>,
    val focusedWorkspaceId: String?,
)

/**
 * Where a move would land, without making it.
 *
 * Separate so a screen can paint the move before the command it sends has
 * been answered, and so both agree on the order they follow.
 */
fun nextHerdrWorkspace(from: HerdrWorkspaceOrder, direction: Int): HerdrWorkspace? {
    val ordered = from.workspaces.sortedBy { it.number }
    if (ordered.size < 2) return null
    val at = ordered.indexOfFirst { it.workspaceId == from.focusedWorkspaceId }
    val start = if (at < 0) 0 else at
    val index = ((start + direction) % ordered.size + ordered.size) % ordered.size
    return ordered[index]
}

/**
 * The tab beside the focused one, in herdr's own order, wrapping at both ends.
 *
 * A sideways gesture goes over the socket rather than as the chord herdr binds
 * for it, because herdr emits no event for a move its own binding made and the
 * strip would be left showing the tab the session had left. Naming the target
 * here is what makes that one command.
 */
fun nextHerdrTab(tabs: List<HerdrTab>, focusedTabId: String?, direction: Int): HerdrTab? {
    val ordered = tabs.sortedBy { it.number }
    if (ordered.size < 2) return null
    val at = ordered.indexOfFirst { it.tabId == focusedTabId }
    val start = if (at < 0) 0 else at
    val index = ((start + direction) % ordered.size + ordered.size) % ordered.size
    return ordered[index]
}
