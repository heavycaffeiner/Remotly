// Client for a Herdr server reached over SSH.
//
// Herdr is a terminal workspace manager that keeps running on the host. The app
// reaches it by running the `herdr` CLI on the remote over an exec channel; the
// CLI talks to the local socket. This module builds the command strings and
// parses the output; the caller runs each command over the SSH exec channel and
// hands the result back to the matching parser.
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

import { joinShell, shellQuote } from './shell';

// --- typed model -----------------------------------------------------------

export type HerdrAgentStatus =
  | 'idle'
  | 'running'
  | 'waiting'
  | 'unknown'
  | (string & {});

export interface HerdrWorkspace {
  workspaceId: string;
  label: string;
  number: number;
  tabCount: number;
  paneCount: number;
  activeTabId: string | null;
  focused: boolean;
  agentStatus: HerdrAgentStatus;
}

export interface HerdrTab {
  tabId: string;
  workspaceId: string;
  label: string;
  number: number;
  paneCount: number;
  focused: boolean;
  agentStatus: HerdrAgentStatus;
}

export interface HerdrPane {
  paneId: string;
  workspaceId: string;
  tabId: string;
  cwd: string;
  terminalId: string;
  terminalTitle: string | null;
  focused: boolean;
  revision: number;
  agent: string | null;
  agentStatus: HerdrAgentStatus;
}

export interface HerdrSession {
  name: string;
  default: boolean;
  running: boolean;
  sessionDir: string;
  socketPath: string;
}

// The full state of one session in a single call. The snapshot the server
// returns is untrusted: a field the server omits is reported as an empty
// array or null, never as a throw, so a screen degrades to "nothing here"
// rather than crashing on a server it does not fully understand.
export interface HerdrSnapshot {
  workspaces: HerdrWorkspace[];
  tabs: HerdrTab[];
  panes: HerdrPane[];
  focusedWorkspaceId: string | null;
  focusedTabId: string | null;
  focusedPaneId: string | null;
  version: string | null;
  protocol: number | null;
}

// A created workspace carries the new root pane and tab so a caller can point
// at them without a follow-up list.
export interface HerdrCreatedWorkspace {
  workspace: HerdrWorkspace;
  tab: HerdrTab | null;
  rootPane: HerdrPane | null;
}

/**
 * The result of a remote exec as the bridge delivers it. stdout and stderr are
 * separate because herdr writes its typed error document to stdout on failure,
 * while a non-zero exit with empty stdout is a transport-level failure.
 */
export interface HerdrExecResult {
  exitCode: number;
  stdout: string;
  stderr: string;
}

/**
 * A typed failure from the herdr layer. `code` is the stable identifier the UI
 * matches on: `herdr_cli` for a CLI-reported error document,
 * `herdr_unreachable` for a transport failure (no command output at all), and
 * `herdr_bad_json` for output that is not the expected document.
 */
export class HerdrError extends Error {
  readonly code: string;
  readonly detail: string;
  constructor(code: string, detail: string) {
    super(detail);
    this.name = 'HerdrError';
    this.code = code;
    this.detail = detail;
  }
}

// --- command builders ------------------------------------------------------

// The command prefix for a herdr call, with the session target. When a session
// is named it is a global flag placed before the subcommand; the default
// session is the running server and takes no flag.
function herdrPrefix(session: string | null): string[] {
  return session ? ['herdr', '--session', session] : ['herdr'];
}

// The exec channel gets a plain non-interactive shell, so PATH is the system
// default. A user's herdr often lives somewhere only their own shell knows
// about, and with zsh that PATH is usually set in .zshrc, which a login shell
// skips unless it is interactive. The lookup therefore asks one interactive
// login shell where herdr is, with markers around the answer because rc files
// print.
const LOOKUP_OPEN = '__remotly_herdr_path__';
const LOOKUP_CLOSE = '__remotly_herdr_end__';

/** Ask the user's own shell where herdr is. Exits zero even when it knows of
 *  none, so the empty answer is read rather than mapped to a failure. */
export function herdrLookupCommand(): string {
  const script = `echo ${LOOKUP_OPEN}; command -v herdr || true; echo ${LOOKUP_CLOSE}`;
  return `exec "\${SHELL:-/bin/sh}" -ilc ${shellQuote(script)}`;
}

/**
 * The herdr path the shell reported, or null when it knows of none.
 *
 * Only an absolute path counts: `command -v` also answers for an alias or a
 * shell function, and neither is something another shell can run.
 */
export function parseHerdrLookup(stdout: string): string | null {
  const open = stdout.indexOf(LOOKUP_OPEN);
  const close = stdout.indexOf(LOOKUP_CLOSE, open + LOOKUP_OPEN.length);
  if (open === -1 || close === -1) return null;
  const body = stdout.slice(open + LOOKUP_OPEN.length, close);
  for (const line of body.split('\n')) {
    const path = line.trim();
    if (path.startsWith('/')) return path;
  }
  return null;
}

/**
 * Runs a command with `dir` ahead of PATH.
 *
 * The directory rather than the binary path, so that herdr resolves whatever
 * it shells out to the way an interactive session would.
 */
export function withPathPrefix(dir: string, command: string): string {
  return `PATH=${shellQuote(dir)}:"$PATH" ${command}`;
}

/** List the named herdr sessions available on the host. */
export function sessionListCommand(session: string | null = null): string {
  return joinShell([...herdrPrefix(session), 'session', 'list', '--json']);
}

/** Stop a named session. The default session is addressed by the name
 *  "default". */
export function sessionStopCommand(name: string): string {
  return joinShell(['herdr', 'session', 'stop', name, '--json']);
}

/** Delete a named session (its directory and socket). */
export function sessionDeleteCommand(name: string): string {
  return joinShell(['herdr', 'session', 'delete', name, '--json']);
}

export interface HerdrCreateWorkspace {
  cwd?: string;
  label: string;
  focus?: boolean;
}

/** Create a workspace. The label and cwd are user input and are quoted. */
export function workspaceCreateCommand(
  opts: HerdrCreateWorkspace,
  session: string | null = null,
): string {
  const argv = [
    ...herdrPrefix(session),
    'workspace',
    'create',
    '--label',
    opts.label,
  ];
  if (opts.cwd) argv.push('--cwd', opts.cwd);
  if (opts.focus === false) argv.push('--no-focus');
  return joinShell(argv);
}

/** Focus a workspace by id. */
export function workspaceFocusCommand(
  workspaceId: string,
  session: string | null = null,
): string {
  return joinShell([
    ...herdrPrefix(session),
    'workspace',
    'focus',
    workspaceId,
  ]);
}

/** Close a workspace by id. */
export function workspaceCloseCommand(
  workspaceId: string,
  session: string | null = null,
): string {
  return joinShell([
    ...herdrPrefix(session),
    'workspace',
    'close',
    workspaceId,
  ]);
}

/**
 * Rename a workspace.
 *
 * herdr takes the label as trailing arguments, so a label with spaces arrives
 * as several. Quoting each one keeps it a single label.
 */
export function workspaceRenameCommand(
  workspaceId: string,
  label: string,
  session: string | null = null,
): string {
  return joinShell([
    ...herdrPrefix(session),
    'workspace',
    'rename',
    workspaceId,
    label,
  ]);
}

export interface HerdrCreateTab {
  /** Omitted, herdr puts the tab in the focused workspace. */
  workspaceId?: string;
  label?: string;
  cwd?: string;
  focus?: boolean;
}

/** Create a tab. */
export function tabCreateCommand(
  opts: HerdrCreateTab = {},
  session: string | null = null,
): string {
  const argv = [...herdrPrefix(session), 'tab', 'create'];
  if (opts.workspaceId) argv.push('--workspace', opts.workspaceId);
  if (opts.label) argv.push('--label', opts.label);
  if (opts.cwd) argv.push('--cwd', opts.cwd);
  if (opts.focus === false) argv.push('--no-focus');
  return joinShell(argv);
}

/** Focus a tab by id. */
export function tabFocusCommand(
  tabId: string,
  session: string | null = null,
): string {
  return joinShell([...herdrPrefix(session), 'tab', 'focus', tabId]);
}

/** Rename a tab. The label is quoted as one argument, as for a workspace. */
export function tabRenameCommand(
  tabId: string,
  label: string,
  session: string | null = null,
): string {
  return joinShell([...herdrPrefix(session), 'tab', 'rename', tabId, label]);
}

/** Close a tab by id. */
export function tabCloseCommand(
  tabId: string,
  session: string | null = null,
): string {
  return joinShell([...herdrPrefix(session), 'tab', 'close', tabId]);
}

/** List one workspace's tabs. Small enough to poll while a screen is open. */
export function tabListCommand(
  workspaceId: string,
  session: string | null = null,
): string {
  return joinShell([
    ...herdrPrefix(session),
    'tab',
    'list',
    '--workspace',
    workspaceId,
  ]);
}

/** The actions a plugin declares, or every plugin's when none is named. */
export function pluginActionListCommand(
  pluginId: string | null = null,
  session: string | null = null,
): string {
  const argv = [...herdrPrefix(session), 'plugin', 'action', 'list'];
  if (pluginId !== null) argv.push('--plugin', pluginId);
  return joinShell(argv);
}

/**
 * Invoke a plugin action.
 *
 * The response says the action started, not what it printed: its stdout is
 * kept in the plugin log. Callers that need a result read the state the action
 * changed instead of waiting for output.
 */
export function pluginActionInvokeCommand(
  actionId: string,
  session: string | null = null,
): string {
  return joinShell([
    ...herdrPrefix(session),
    'plugin',
    'action',
    'invoke',
    actionId,
  ]);
}

export interface HerdrPaneRead {
  paneId: string;
  source?: 'visible' | 'recent' | 'recent-unwrapped';
  lines?: number;
}

/** Read a pane's contents as plain text. The caller gets raw text, not JSON. */
export function paneReadCommand(
  opts: HerdrPaneRead,
  session: string | null = null,
): string {
  const argv = [...herdrPrefix(session), 'pane', 'read', opts.paneId];
  if (opts.source) argv.push('--source', opts.source);
  if (opts.lines !== undefined) argv.push('--lines', String(opts.lines));
  return joinShell(argv);
}

/**
 * Send key presses to an agent pane. Herdr only drives panes that host a
 * recognized agent, so the target must be a pane id (or unique agent name)
 * that currently hosts one; a bare shell pane is rejected by the server with
 * `agent_not_found`.
 */
export function agentSendKeysCommand(
  target: string,
  keys: readonly string[],
  session: string | null = null,
): string {
  return joinShell([
    ...herdrPrefix(session),
    'agent',
    'send-keys',
    target,
    ...keys,
  ]);
}

/** Submit a prompt to an agent pane and, when `wait`, block until the agent
 *  reaches a terminal state. */
export function agentPromptCommand(
  target: string,
  text: string,
  wait = false,
  session: string | null = null,
): string {
  const argv = [...herdrPrefix(session), 'agent', 'prompt', target, text];
  if (wait) argv.push('--wait');
  return joinShell(argv);
}

/** Build the `herdr api snapshot` command, the single read for a session's
 *  workspaces, tabs, panes, and focused ids. */
export function apiSnapshotCommand(session: string | null = null): string {
  return joinShell([...herdrPrefix(session), 'api', 'snapshot']);
}

// --- parsing ---------------------------------------------------------------

type Raw = Record<string, unknown>;

// Untrusted input: coerce a value, or return a default when the field is
// absent or the wrong type.
function asString(v: unknown): string | null {
  return typeof v === 'string' ? v : null;
}
function asBool(v: unknown, fallback = false): boolean {
  return typeof v === 'boolean' ? v : fallback;
}
function asNumber(v: unknown, fallback = 0): number {
  return typeof v === 'number' ? v : fallback;
}

// A missing or malformed id is a null so a caller can guard.
function nonEmpty(v: unknown): string | null {
  const s = asString(v);
  return s && s.length > 0 ? s : null;
}

function parseWorkspace(o: Raw): HerdrWorkspace {
  const id = nonEmpty(o.workspace_id);
  if (!id) throw new Error('workspace missing workspace_id');
  return {
    workspaceId: id,
    label: asString(o.label) ?? '',
    number: asNumber(o.number),
    tabCount: asNumber(o.tab_count),
    paneCount: asNumber(o.pane_count),
    activeTabId: nonEmpty(o.active_tab_id),
    focused: asBool(o.focused),
    agentStatus: (asString(o.agent_status) ?? 'unknown') as HerdrAgentStatus,
  };
}

function parseTab(o: Raw): HerdrTab {
  const id = nonEmpty(o.tab_id);
  if (!id) throw new Error('tab missing tab_id');
  return {
    tabId: id,
    workspaceId: asString(o.workspace_id) ?? '',
    label: asString(o.label) ?? '',
    number: asNumber(o.number),
    paneCount: asNumber(o.pane_count),
    focused: asBool(o.focused),
    agentStatus: (asString(o.agent_status) ?? 'unknown') as HerdrAgentStatus,
  };
}

function parsePane(o: Raw): HerdrPane {
  const id = nonEmpty(o.pane_id);
  if (!id) throw new Error('pane missing pane_id');
  return {
    paneId: id,
    workspaceId: asString(o.workspace_id) ?? '',
    tabId: asString(o.tab_id) ?? '',
    cwd: asString(o.cwd) ?? '',
    terminalId: asString(o.terminal_id) ?? '',
    terminalTitle: asString(o.terminal_title),
    focused: asBool(o.focused),
    revision: asNumber(o.revision),
    agent: asString(o.agent),
    agentStatus: (asString(o.agent_status) ?? 'unknown') as HerdrAgentStatus,
  };
}

function parseSession(o: Raw): HerdrSession {
  const name = asString(o.name);
  if (!name) throw new Error('session missing name');
  return {
    name,
    default: asBool(o.default),
    running: asBool(o.running),
    sessionDir: asString(o.session_dir) ?? '',
    socketPath: asString(o.socket_path) ?? '',
  };
}

// Every parser goes through this: a document that is not a JSON object is a
// transport problem (wrong command, server banner, truncation), reported as a
// typed HerdrError so the UI can say what failed.
function parseDoc(stdout: string): Raw {
  let doc: unknown;
  try {
    doc = JSON.parse(stdout);
  } catch {
    throw new HerdrError('herdr_bad_json', 'herdr output is not JSON');
  }
  if (!doc || typeof doc !== 'object' || Array.isArray(doc)) {
    throw new HerdrError('herdr_bad_json', 'herdr output is not an object');
  }
  return doc as Raw;
}

// Parse a bare `session list --json` document: { sessions: [...] }.
export function parseSessions(stdout: string): HerdrSession[] {
  const doc = parseDoc(stdout);
  const arr = doc.sessions;
  if (!Array.isArray(arr)) {
    throw new HerdrError('herdr_bad_json', 'session list: missing sessions');
  }
  return arr.map(s => parseSession(s as Raw));
}

// Extract the `result` object from an enveloped `{ id, result }` document.
function resultOf(doc: Raw): Raw {
  const r = doc.result;
  if (!r || typeof r !== 'object') {
    throw new HerdrError('herdr_bad_json', 'herdr output has no result');
  }
  return r as Raw;
}

/** Parse a `tab list` document into the workspace's tabs, in place order. */
export function parseTabs(stdout: string): HerdrTab[] {
  const r = resultOf(parseDoc(stdout));
  if (!Array.isArray(r.tabs)) {
    throw new HerdrError('herdr_bad_json', 'tab list: missing tabs');
  }
  return (r.tabs as Raw[]).map(parseTab);
}

/** Parse a `plugin action list` document into qualified action ids. */
export function parsePluginActions(stdout: string): string[] {
  const r = resultOf(parseDoc(stdout));
  if (!Array.isArray(r.actions)) {
    throw new HerdrError('herdr_bad_json', 'plugin action list: no actions');
  }
  const out: string[] = [];
  for (const a of r.actions as Raw[]) {
    const plugin = nonEmpty(a.plugin_id);
    const action = nonEmpty(a.action_id);
    if (plugin !== null && action !== null) out.push(`${plugin}.${action}`);
  }
  return out;
}

/** Parse a `workspace create` document, keeping the new workspace, its root
 *  pane, and its first tab. */
export function parseCreatedWorkspace(stdout: string): HerdrCreatedWorkspace {
  const r = resultOf(parseDoc(stdout));
  const w = r.workspace;
  if (!w || typeof w !== 'object') {
    throw new HerdrError(
      'herdr_bad_json',
      'workspace create: missing workspace',
    );
  }
  return {
    workspace: parseWorkspace(w as Raw),
    tab: r.tab ? parseTab(r.tab as Raw) : null,
    rootPane: r.root_pane ? parsePane(r.root_pane as Raw) : null,
  };
}

/** Parse a `herdr api snapshot` document into the full session state. One
 *  call replaces separate workspace, tab, and pane list reads, so a screen
 *  never races between them. */
export function parseSnapshot(stdout: string): HerdrSnapshot {
  const r = resultOf(parseDoc(stdout));
  const snap = r.snapshot;
  if (!snap || typeof snap !== 'object') {
    throw new HerdrError('herdr_bad_json', 'snapshot: missing snapshot');
  }
  const s = snap as Raw;
  const arrOf = (k: string): Raw[] =>
    Array.isArray(s[k]) ? (s[k] as Raw[]) : [];
  return {
    workspaces: arrOf('workspaces').map(parseWorkspace),
    tabs: arrOf('tabs').map(parseTab),
    panes: arrOf('panes').map(parsePane),
    focusedWorkspaceId: nonEmpty(s.focused_workspace_id),
    focusedTabId: nonEmpty(s.focused_tab_id),
    focusedPaneId: nonEmpty(s.focused_pane_id),
    version: asString(s.version),
    protocol: typeof s.protocol === 'number' ? s.protocol : null,
  };
}

// The CLI error document is `{ error: { code, message } }`, printed to stdout
// with a non-zero exit. Extract it so a caller gets a typed HerdrError.
export function parseCliError(
  stdout: string,
): { code: string; message: string } | null {
  try {
    const doc = parseDoc(stdout);
    const e = doc.error;
    if (e && typeof e === 'object') {
      const code = asString((e as Raw).code) ?? 'herdr_cli';
      const message = asString((e as Raw).message) ?? 'herdr command failed';
      return { code, message };
    }
  } catch {
    return null;
  }
  return null;
}
