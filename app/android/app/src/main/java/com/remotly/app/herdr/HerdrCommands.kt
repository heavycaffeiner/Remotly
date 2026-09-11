package com.remotly.app.herdr

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

// Client for a Herdr server reached over SSH.
//
// Herdr is a terminal workspace manager that keeps running on the host. The app
// reaches it by running the `herdr` CLI on the remote over an exec channel; the
// CLI talks to the local socket. This file builds the command strings and
// parses the output; HerdrClient runs each command over the SSH exec channel
// and hands the result back to the matching parser here.
//
// The wire format is the one herdr 0.8.2 introduced, verified against 0.9.0:
// most commands are enveloped as `{ id, result: { type, ... } }`, `session
// list` returns a bare `{ sessions: [...] }`, and `pane read` prints raw
// terminal text. `api snapshot` is the single read: one call returns
// workspaces, tabs, panes, and the focused ids for a session. A failure is a
// `{ error: { code, message } }` document, which 0.9.0 writes to stderr for
// the socket-API commands and to stdout for `session list`. A different major
// version changes the shape, so the parsers validate the envelope before
// trusting it.

// --- typed model -------------------------------------------------------

/** One of "idle", "running", "waiting", "unknown", or a value a newer herdr defines. */
typealias HerdrAgentStatus = String

data class HerdrWorkspace(
    val workspaceId: String,
    val label: String,
    val number: Int,
    val tabCount: Int,
    val paneCount: Int,
    val activeTabId: String?,
    val focused: Boolean,
    val agentStatus: HerdrAgentStatus,
)

data class HerdrTab(
    val tabId: String,
    val workspaceId: String,
    val label: String,
    val number: Int,
    val paneCount: Int,
    val focused: Boolean,
    val agentStatus: HerdrAgentStatus,
)

data class HerdrPane(
    val paneId: String,
    val workspaceId: String,
    val tabId: String,
    val cwd: String,
    val terminalId: String,
    val terminalTitle: String?,
    val focused: Boolean,
    val revision: Int,
    val agent: String?,
    val agentStatus: HerdrAgentStatus,
)

data class HerdrSession(
    val name: String,
    val default: Boolean,
    val running: Boolean,
    val sessionDir: String,
    val socketPath: String,
)

/**
 * The full state of one session in a single call. The snapshot the server
 * returns is untrusted: a field the server omits is reported as an empty list
 * or null, never as a throw, so a screen degrades to "nothing here" rather
 * than crashing on a server it does not fully understand.
 */
data class HerdrSnapshot(
    val workspaces: List<HerdrWorkspace>,
    val tabs: List<HerdrTab>,
    val panes: List<HerdrPane>,
    val focusedWorkspaceId: String?,
    val focusedTabId: String?,
    val focusedPaneId: String?,
    val version: String?,
    val protocol: Int?,
)

/** A created workspace carries the new root pane and tab so a caller can point
 *  at them without a follow-up list. */
data class HerdrCreatedWorkspace(
    val workspace: HerdrWorkspace,
    val tab: HerdrTab?,
    val rootPane: HerdrPane?,
)

/**
 * A typed failure from the herdr layer. [code] is the stable identifier a
 * caller matches on: `herdr_cli` for a CLI-reported error document,
 * `herdr_unreachable` for a transport failure (no command output at all), and
 * `herdr_bad_json` for output that is not the expected document.
 */
class HerdrError(val code: String, val detail: String) : Exception(detail)

// --- command builders ----------------------------------------------------

// The command prefix for a herdr call, with the session target. When a session
// is named it is a global flag placed before the subcommand; the default
// session is the running server and takes no flag.
private fun herdrPrefix(session: String?): List<String> =
    if (session != null) listOf("herdr", "--session", session) else listOf("herdr")

/**
 * Quotes one argument for a POSIX shell.
 *
 * Single quotes protect everything except a single quote itself, which is
 * closed, escaped, and reopened: `'a'` + `\'` + `'b'` -> `a'b`. Without this a
 * value could run a command, and the value comes from the user.
 */
internal fun shellQuote(arg: String): String = "'" + arg.replace("'", "'\\''") + "'"

/**
 * Joins argv into one command string, quoting every element.
 *
 * The single quoting seam: nothing else in this file builds a shell string
 * directly. Quoting the program name too is harmless (the shell still
 * resolves it through PATH) and keeps the rule uniform.
 */
internal fun joinShell(argv: List<String>): String = argv.joinToString(" ") { shellQuote(it) }

// The exec channel gets a plain non-interactive shell, so PATH is the system
// default. A user's herdr often lives somewhere only their own shell knows
// about, and with zsh that PATH is usually set in .zshrc, which a login shell
// skips unless it is interactive. The lookup therefore asks one interactive
// login shell where herdr is, with markers around the answer because rc files
// print.
private const val LOOKUP_OPEN = "__remotly_herdr_path__"
private const val LOOKUP_CLOSE = "__remotly_herdr_end__"

/** Ask the user's own shell where herdr is. Exits zero even when it knows of
 *  none, so the empty answer is read rather than mapped to a failure. */
fun herdrLookupCommand(): String {
    val script = "echo $LOOKUP_OPEN; command -v herdr || true; echo $LOOKUP_CLOSE"
    return "exec \"\${SHELL:-/bin/sh}\" -ilc ${shellQuote(script)}"
}

/**
 * The herdr path the shell reported, or null when it knows of none.
 *
 * Only an absolute path counts: `command -v` also answers for an alias or a
 * shell function, and neither is something another shell can run.
 */
fun parseHerdrLookup(stdout: String): String? {
    val open = stdout.indexOf(LOOKUP_OPEN)
    if (open == -1) return null
    val close = stdout.indexOf(LOOKUP_CLOSE, open + LOOKUP_OPEN.length)
    if (close == -1) return null
    val body = stdout.substring(open + LOOKUP_OPEN.length, close)
    for (line in body.split("\n")) {
        val path = line.trim()
        if (path.startsWith("/")) return path
    }
    return null
}

/**
 * Runs a command with [dir] ahead of PATH.
 *
 * The directory rather than the binary path, so that herdr resolves whatever
 * it shells out to the way an interactive session would.
 */
fun withPathPrefix(dir: String, command: String): String =
    "PATH=${shellQuote(dir)}:\"\$PATH\" $command"

/** List the named herdr sessions available on the host. */
fun sessionListCommand(session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("session", "list", "--json"))

/** Stop a named session. The default session is addressed by the name
 *  "default". */
fun sessionStopCommand(name: String): String =
    joinShell(listOf("herdr", "session", "stop", name, "--json"))

/** Delete a named session (its directory and socket). */
fun sessionDeleteCommand(name: String): String =
    joinShell(listOf("herdr", "session", "delete", name, "--json"))

data class HerdrCreateWorkspace(
    val label: String,
    val cwd: String? = null,
    val focus: Boolean? = null,
)

/** Create a workspace. The label and cwd are user input and are quoted. */
fun workspaceCreateCommand(opts: HerdrCreateWorkspace, session: String? = null): String {
    val argv =
        (herdrPrefix(session) + listOf("workspace", "create", "--label", opts.label)).toMutableList()
    if (!opts.cwd.isNullOrEmpty()) argv += listOf("--cwd", opts.cwd)
    if (opts.focus == false) argv += "--no-focus"
    return joinShell(argv)
}

/** Focus a workspace by id. */
fun workspaceFocusCommand(workspaceId: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("workspace", "focus", workspaceId))

/** Close a workspace by id. */
fun workspaceCloseCommand(workspaceId: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("workspace", "close", workspaceId))

/**
 * Rename a workspace.
 *
 * herdr takes the label as trailing arguments, so a label with spaces arrives
 * as several. Quoting each one keeps it a single label.
 */
fun workspaceRenameCommand(workspaceId: String, label: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("workspace", "rename", workspaceId, label))

data class HerdrCreateTab(
    /** Omitted, herdr puts the tab in the focused workspace. */
    val workspaceId: String? = null,
    val label: String? = null,
    val cwd: String? = null,
    val focus: Boolean? = null,
)

/** Create a tab. */
fun tabCreateCommand(opts: HerdrCreateTab = HerdrCreateTab(), session: String? = null): String {
    val argv = (herdrPrefix(session) + listOf("tab", "create")).toMutableList()
    if (!opts.workspaceId.isNullOrEmpty()) argv += listOf("--workspace", opts.workspaceId)
    if (!opts.label.isNullOrEmpty()) argv += listOf("--label", opts.label)
    if (!opts.cwd.isNullOrEmpty()) argv += listOf("--cwd", opts.cwd)
    if (opts.focus == false) argv += "--no-focus"
    return joinShell(argv)
}

/** Focus a tab by id. */
fun tabFocusCommand(tabId: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("tab", "focus", tabId))

/** Rename a tab. The label is quoted as one argument, as for a workspace. */
fun tabRenameCommand(tabId: String, label: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("tab", "rename", tabId, label))

/** Close a tab by id. */
fun tabCloseCommand(tabId: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("tab", "close", tabId))

/** List one workspace's tabs. Small enough to poll while a screen is open. */
fun tabListCommand(workspaceId: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("tab", "list", "--workspace", workspaceId))

/** The actions a plugin declares, or every plugin's when none is named. */
fun pluginActionListCommand(pluginId: String? = null, session: String? = null): String {
    val argv = (herdrPrefix(session) + listOf("plugin", "action", "list")).toMutableList()
    if (pluginId != null) argv += listOf("--plugin", pluginId)
    return joinShell(argv)
}

/**
 * Invoke a plugin action.
 *
 * The response says the action started, not what it printed: its stdout is
 * kept in the plugin log. Callers that need a result read the state the action
 * changed instead of waiting for output.
 */
fun pluginActionInvokeCommand(actionId: String, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("plugin", "action", "invoke", actionId))

data class HerdrPaneRead(
    val paneId: String,
    /** "visible", "recent", or "recent-unwrapped". */
    val source: String? = null,
    val lines: Int? = null,
)

/** Read a pane's contents as plain text. The caller gets raw text, not JSON. */
fun paneReadCommand(opts: HerdrPaneRead, session: String? = null): String {
    val argv = (herdrPrefix(session) + listOf("pane", "read", opts.paneId)).toMutableList()
    if (!opts.source.isNullOrEmpty()) argv += listOf("--source", opts.source)
    if (opts.lines != null) argv += listOf("--lines", opts.lines.toString())
    return joinShell(argv)
}

/**
 * Send key presses to an agent pane. Herdr only drives panes that host a
 * recognized agent, so the target must be a pane id (or unique agent name)
 * that currently hosts one; a bare shell pane is rejected by the server with
 * `agent_not_found`.
 */
fun agentSendKeysCommand(target: String, keys: List<String>, session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("agent", "send-keys", target) + keys)

/** Submit a prompt to an agent pane and, when [wait], block until the agent
 *  reaches a terminal state. */
fun agentPromptCommand(
    target: String,
    text: String,
    wait: Boolean = false,
    session: String? = null,
): String {
    val argv = (herdrPrefix(session) + listOf("agent", "prompt", target, text)).toMutableList()
    if (wait) argv += "--wait"
    return joinShell(argv)
}

/** Build the `herdr api snapshot` command, the single read for a session's
 *  workspaces, tabs, panes, and focused ids. */
fun apiSnapshotCommand(session: String? = null): String =
    joinShell(herdrPrefix(session) + listOf("api", "snapshot"))

/** The event types the app's view of a host is drawn from. */
val HERDR_EVENT_TYPES: List<String> = listOf(
    "workspace.created",
    "workspace.closed",
    "workspace.renamed",
    "workspace.moved",
    "workspace.focused",
    "tab.created",
    "tab.closed",
    "tab.renamed",
    "tab.moved",
    "tab.focused",
)

/**
 * Build the command that streams a session's events.
 *
 * herdr publishes events on its control socket and its CLI has no streaming
 * command, so this is a reader run on the host: one request line, then a line
 * per event. Whatever the host has speaks it, tried in order of directness;
 * the app requires the subscription acknowledgement before it trusts the
 * stream, so a host with none of them fails as a stream that never
 * acknowledged rather than as a wrong answer.
 *
 * [socketPath] comes from herdr's own session document. It is quoted here,
 * like every other argument the app sends.
 */
fun eventStreamCommand(socketPath: String): String {
    val request = JsonObject().apply {
        addProperty("id", "remotly")
        addProperty("method", "events.subscribe")
        add(
            "params",
            JsonObject().apply {
                add(
                    "subscriptions",
                    JsonArray().apply {
                        HERDR_EVENT_TYPES.forEach { type ->
                            add(JsonObject().apply { addProperty("type", type) })
                        }
                    },
                )
            },
        )
    }.toString()
    // Written as one shell word per candidate so the remote shell picks the
    // reader; the request and the path are the only interpolations, both quoted.
    val perl = listOf(
        "use IO::Socket::UNIX;",
        "\$|=1;",
        "my \$s=IO::Socket::UNIX->new(Peer=>\$ARGV[0]) or exit 1;",
        "print \$s \$ARGV[1],\"\\n\";",
        "while(my \$l=<\$s>){print \$l}",
    ).joinToString("")
    val python = listOf(
        "import socket,sys",
        "s=socket.socket(socket.AF_UNIX);s.connect(sys.argv[1])",
        "s.sendall((sys.argv[2]+\"\\n\").encode())",
        "f=s.makefile()",
        "for line in f: sys.stdout.write(line);sys.stdout.flush()",
    ).joinToString("\n")
    return listOf(
        "S=${shellQuote(socketPath)};",
        "R=${shellQuote(request)};",
        "if command -v socat >/dev/null 2>&1; then",
        "printf \"%s\\n\" \"\$R\" | socat -t 3600 - UNIX-CONNECT:\"\$S\";",
        "elif command -v python3 >/dev/null 2>&1; then",
        "python3 -c ${shellQuote(python)} \"\$S\" \"\$R\";",
        "elif command -v perl >/dev/null 2>&1; then",
        "perl -e ${shellQuote(perl)} \"\$S\" \"\$R\";",
        "fi",
    ).joinToString(" ")
}

// --- parsing -------------------------------------------------------------

// Untrusted input: coerce a value, or return a default when the field is
// absent or the wrong type.
private fun asString(e: JsonElement?): String? =
    if (e != null && e.isJsonPrimitive && e.asJsonPrimitive.isString) e.asString else null

private fun asBool(e: JsonElement?, fallback: Boolean = false): Boolean =
    if (e != null && e.isJsonPrimitive && e.asJsonPrimitive.isBoolean) e.asBoolean else fallback

private fun asNumber(e: JsonElement?, fallback: Int = 0): Int =
    if (e != null && e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else fallback

private fun asIntOrNull(e: JsonElement?): Int? =
    if (e != null && e.isJsonPrimitive && e.asJsonPrimitive.isNumber) e.asInt else null

// A missing or malformed id is a null so a caller can guard.
private fun nonEmpty(e: JsonElement?): String? {
    val s = asString(e)
    return if (!s.isNullOrEmpty()) s else null
}

// An element that is not the object shape a parser needs degrades to an empty
// object rather than throwing, so a wrong-shaped list entry is reported through
// the same missing-field path as a field the server left out.
private fun JsonElement?.asObjectOrEmpty(): JsonObject =
    if (this != null && isJsonObject) asJsonObject else JsonObject()

private fun parseWorkspace(o: JsonObject): HerdrWorkspace {
    val id = nonEmpty(o.get("workspace_id")) ?: throw IllegalStateException("workspace missing workspace_id")
    return HerdrWorkspace(
        workspaceId = id,
        label = asString(o.get("label")) ?: "",
        number = asNumber(o.get("number")),
        tabCount = asNumber(o.get("tab_count")),
        paneCount = asNumber(o.get("pane_count")),
        activeTabId = nonEmpty(o.get("active_tab_id")),
        focused = asBool(o.get("focused")),
        agentStatus = asString(o.get("agent_status")) ?: "unknown",
    )
}

private fun parseTab(o: JsonObject): HerdrTab {
    val id = nonEmpty(o.get("tab_id")) ?: throw IllegalStateException("tab missing tab_id")
    return HerdrTab(
        tabId = id,
        workspaceId = asString(o.get("workspace_id")) ?: "",
        label = asString(o.get("label")) ?: "",
        number = asNumber(o.get("number")),
        paneCount = asNumber(o.get("pane_count")),
        focused = asBool(o.get("focused")),
        agentStatus = asString(o.get("agent_status")) ?: "unknown",
    )
}

private fun parsePane(o: JsonObject): HerdrPane {
    val id = nonEmpty(o.get("pane_id")) ?: throw IllegalStateException("pane missing pane_id")
    return HerdrPane(
        paneId = id,
        workspaceId = asString(o.get("workspace_id")) ?: "",
        tabId = asString(o.get("tab_id")) ?: "",
        cwd = asString(o.get("cwd")) ?: "",
        terminalId = asString(o.get("terminal_id")) ?: "",
        terminalTitle = asString(o.get("terminal_title")),
        focused = asBool(o.get("focused")),
        revision = asNumber(o.get("revision")),
        agent = asString(o.get("agent")),
        agentStatus = asString(o.get("agent_status")) ?: "unknown",
    )
}

private fun parseSession(o: JsonObject): HerdrSession {
    val name = nonEmpty(o.get("name")) ?: throw IllegalStateException("session missing name")
    return HerdrSession(
        name = name,
        default = asBool(o.get("default")),
        running = asBool(o.get("running")),
        sessionDir = asString(o.get("session_dir")) ?: "",
        socketPath = asString(o.get("socket_path")) ?: "",
    )
}

// Every parser goes through this: a document that is not a JSON object is a
// transport problem (wrong command, server banner, truncation), reported as a
// typed HerdrError so the UI can say what failed.
private fun parseDoc(stdout: String): JsonObject {
    val doc = try {
        JsonParser.parseString(stdout)
    } catch (e: Exception) {
        throw HerdrError("herdr_bad_json", "herdr output is not JSON")
    }
    if (!doc.isJsonObject) {
        throw HerdrError("herdr_bad_json", "herdr output is not an object")
    }
    return doc.asJsonObject
}

/** Parse a bare `session list --json` document: `{ sessions: [...] }`. */
fun parseSessions(stdout: String): List<HerdrSession> {
    val doc = parseDoc(stdout)
    val arr = doc.get("sessions")
    if (arr == null || !arr.isJsonArray) {
        throw HerdrError("herdr_bad_json", "session list: missing sessions")
    }
    return arr.asJsonArray.map { parseSession(it.asObjectOrEmpty()) }
}

// Extract the `result` object from an enveloped `{ id, result }` document.
private fun resultOf(doc: JsonObject): JsonObject {
    val r = doc.get("result")
    if (r == null || !r.isJsonObject) {
        throw HerdrError("herdr_bad_json", "herdr output has no result")
    }
    return r.asJsonObject
}

/** Parse a `tab list` document into the workspace's tabs, in place order. */
fun parseTabs(stdout: String): List<HerdrTab> {
    val r = resultOf(parseDoc(stdout))
    val tabs = r.get("tabs")
    if (tabs == null || !tabs.isJsonArray) {
        throw HerdrError("herdr_bad_json", "tab list: missing tabs")
    }
    return tabs.asJsonArray.map { parseTab(it.asObjectOrEmpty()) }
}

/** Parse a `plugin action list` document into qualified action ids. */
fun parsePluginActions(stdout: String): List<String> {
    val r = resultOf(parseDoc(stdout))
    val actions = r.get("actions")
    if (actions == null || !actions.isJsonArray) {
        throw HerdrError("herdr_bad_json", "plugin action list: no actions")
    }
    val out = mutableListOf<String>()
    for (a in actions.asJsonArray) {
        val o = a.asObjectOrEmpty()
        val plugin = nonEmpty(o.get("plugin_id"))
        val action = nonEmpty(o.get("action_id"))
        if (plugin != null && action != null) out += "$plugin.$action"
    }
    return out
}

/** Parse a `workspace create` document, keeping the new workspace, its root
 *  pane, and its first tab. */
fun parseCreatedWorkspace(stdout: String): HerdrCreatedWorkspace {
    val r = resultOf(parseDoc(stdout))
    val w = r.get("workspace")
    if (w == null || !w.isJsonObject) {
        throw HerdrError("herdr_bad_json", "workspace create: missing workspace")
    }
    val tab = r.get("tab")
    val rootPane = r.get("root_pane")
    return HerdrCreatedWorkspace(
        workspace = parseWorkspace(w.asJsonObject),
        tab = if (tab != null && tab.isJsonObject) parseTab(tab.asJsonObject) else null,
        rootPane = if (rootPane != null && rootPane.isJsonObject) parsePane(rootPane.asJsonObject) else null,
    )
}

/** Parse a `herdr api snapshot` document into the full session state. One
 *  call replaces separate workspace, tab, and pane list reads, so a screen
 *  never races between them. */
fun parseSnapshot(stdout: String): HerdrSnapshot {
    val r = resultOf(parseDoc(stdout))
    val snap = r.get("snapshot")
    if (snap == null || !snap.isJsonObject) {
        throw HerdrError("herdr_bad_json", "snapshot: missing snapshot")
    }
    val s = snap.asJsonObject
    fun arrOf(key: String): List<JsonObject> {
        val a = s.get(key)
        return if (a != null && a.isJsonArray) a.asJsonArray.map { it.asObjectOrEmpty() } else emptyList()
    }
    return HerdrSnapshot(
        workspaces = arrOf("workspaces").map(::parseWorkspace),
        tabs = arrOf("tabs").map(::parseTab),
        panes = arrOf("panes").map(::parsePane),
        focusedWorkspaceId = nonEmpty(s.get("focused_workspace_id")),
        focusedTabId = nonEmpty(s.get("focused_tab_id")),
        focusedPaneId = nonEmpty(s.get("focused_pane_id")),
        version = asString(s.get("version")),
        protocol = asIntOrNull(s.get("protocol")),
    )
}

/**
 * What one line of the event stream says.
 *
 * [Ack] is the subscription acknowledgement, which is what proves the reader
 * on the host works at all. [Resync] is an event the app subscribes to but
 * cannot apply from its payload alone, so the caller re-reads a snapshot: one
 * command on a rare event, rather than a guess at a shape.
 */
sealed class HerdrEvent {
    object Ack : HerdrEvent()
    object Resync : HerdrEvent()
    data class WorkspaceCreated(val workspace: HerdrWorkspace) : HerdrEvent()
    data class WorkspaceClosed(val workspaceId: String) : HerdrEvent()
    data class WorkspaceRenamed(val workspaceId: String, val label: String) : HerdrEvent()
    data class WorkspaceFocused(val workspaceId: String) : HerdrEvent()
    data class TabCreated(val tab: HerdrTab) : HerdrEvent()
    data class TabClosed(val tabId: String, val workspaceId: String) : HerdrEvent()
    data class TabRenamed(val tabId: String, val workspaceId: String, val label: String) : HerdrEvent()
    data class TabFocused(val tabId: String, val workspaceId: String) : HerdrEvent()
}

/**
 * Parse one line of the event stream, or null for a line that says nothing
 * the app uses.
 *
 * The stream is a host's output: every field is checked, and a line that does
 * not carry what its own type needs is dropped rather than half-applied.
 */
fun parseHerdrEvent(line: String): HerdrEvent? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val doc = try {
        val parsed = JsonParser.parseString(trimmed)
        if (!parsed.isJsonObject) return null
        parsed.asJsonObject
    } catch (e: Exception) {
        return null
    }

    val result = doc.get("result")
    if (result != null && result.isJsonObject) {
        val type = asString(result.asJsonObject.get("type"))
        return if (type == "subscription_started") HerdrEvent.Ack else null
    }

    val event = asString(doc.get("event"))
    val dataEl = doc.get("data")
    val data = if (dataEl != null && dataEl.isJsonObject) dataEl.asJsonObject else JsonObject()
    val workspaceId = nonEmpty(data.get("workspace_id"))
    val tabId = nonEmpty(data.get("tab_id"))
    val label = asString(data.get("label"))
    fun record(key: String): JsonObject? {
        val v = data.get(key)
        return if (v != null && v.isJsonObject) v.asJsonObject else null
    }

    return try {
        when (event) {
            "workspace_created" -> record("workspace")?.let { HerdrEvent.WorkspaceCreated(parseWorkspace(it)) }
            "workspace_closed" -> workspaceId?.let { HerdrEvent.WorkspaceClosed(it) }
            "workspace_renamed" ->
                if (workspaceId != null && label != null) HerdrEvent.WorkspaceRenamed(workspaceId, label) else null
            "workspace_focused" -> workspaceId?.let { HerdrEvent.WorkspaceFocused(it) }
            "tab_created" -> record("tab")?.let { HerdrEvent.TabCreated(parseTab(it)) }
            "tab_closed" ->
                if (tabId != null && workspaceId != null) HerdrEvent.TabClosed(tabId, workspaceId) else null
            "tab_renamed" ->
                if (tabId != null && workspaceId != null && label != null) {
                    HerdrEvent.TabRenamed(tabId, workspaceId, label)
                } else {
                    null
                }
            "tab_focused" ->
                if (tabId != null && workspaceId != null) HerdrEvent.TabFocused(tabId, workspaceId) else null
            // A move carries its new order as a list the app would have to trust
            // field by field. Reading the snapshot is one command and is exact.
            "workspace_moved", "tab_moved" -> HerdrEvent.Resync
            else -> null
        }
    } catch (e: Exception) {
        null
    }
}

/** A parsed `{ error: { code, message } }` CLI error document. */
data class HerdrCliError(val code: String, val message: String)

// The CLI error document is `{ error: { code, message } }`. Extract it so a
// caller gets a typed HerdrError.
fun parseCliError(stdout: String): HerdrCliError? {
    return try {
        val doc = parseDoc(stdout)
        val e = doc.get("error")
        if (e != null && e.isJsonObject) {
            val eo = e.asJsonObject
            val code = asString(eo.get("code")) ?: "herdr_cli"
            val message = asString(eo.get("message")) ?: "herdr command failed"
            HerdrCliError(code, message)
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }
}
