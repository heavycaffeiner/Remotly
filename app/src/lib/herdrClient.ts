// Runs herdr commands on a host and returns typed results.
//
// `herdr.ts` builds the command strings and parses the documents; this module
// is the transport half. Each call is one `herdr` invocation over its own SSH
// exec channel: the control commands the app makes all finish and disconnect,
// so nothing here holds a session open.
//
// What belongs here is decided by that shape, not by what the CLI offers: a
// command that prints one document and exits. `session attach`, `agent attach`,
// and the streaming `pane` commands need a PTY and stay attached, so they
// cannot cross this bridge at all; reaching them means opening a terminal on
// the host and running herdr there, which is what SshTerminal already does.
//
// Every failure surfaces as a HerdrError. A connect-level failure carries the
// SshCode the bridge reported; a herdr-level failure carries the code and
// message from its error document, whichever stream that document arrived on.

import NativeHerdr from '../specs/NativeRemotlyHerdr';
import { decodeBase64, decodeUtf8 } from './base64';
import {
  HerdrError,
  apiSnapshotCommand,
  herdrLookupCommand,
  paneReadCommand,
  parseCliError,
  parseCreatedWorkspace,
  parseHerdrLookup,
  parsePluginActions,
  parseSessions,
  parseSnapshot,
  parseTabs,
  pluginActionInvokeCommand,
  pluginActionListCommand,
  sessionDeleteCommand,
  sessionListCommand,
  sessionStopCommand,
  tabCloseCommand,
  tabCreateCommand,
  tabFocusCommand,
  tabListCommand,
  tabRenameCommand,
  withPathPrefix,
  workspaceCloseCommand,
  workspaceCreateCommand,
  workspaceFocusCommand,
  workspaceRenameCommand,
  type HerdrCreateTab,
  type HerdrCreateWorkspace,
  type HerdrCreatedWorkspace,
  type HerdrPaneRead,
  type HerdrSession,
  type HerdrSnapshot,
  type HerdrTab,
  type HerdrWorkspace,
} from './herdr';

/** The bridge carries bytes as standard base64; empty means no output. */
function textOf(encoded: string): string {
  return encoded === '' ? '' : decodeUtf8(decodeBase64(encoded));
}

/**
 * Runs one command and returns its stdout as text.
 *
 * Exported so a caller can run a command this module does not wrap yet without
 * reimplementing the failure mapping.
 *
 * A shell that cannot find herdr is not a failure yet: the lookup runs, and
 * the command is repeated with the directory it named ahead of PATH. What that
 * directory is holds for the host from then on.
 */
export async function execHerdr(
  hostId: string,
  command: string,
): Promise<string> {
  const dir = herdrDir.get(hostId);
  if (dir !== undefined) return runHerdr(hostId, withPathPrefix(dir, command));
  try {
    return await runHerdr(hostId, command);
  } catch (e) {
    if (!(e instanceof HerdrError) || !isMissingBinary(e)) throw e;
    const found = await locateHerdr(hostId);
    herdrDir.set(hostId, found);
    return runHerdr(hostId, withPathPrefix(found, command));
  }
}

/** Where herdr was found on a host, once a lookup has had to run. */
const herdrDir = new Map<string, string>();

/**
 * Whether the shell could not find herdr at all.
 *
 * The wording is the shell's, not herdr's: zsh says "command not found: herdr",
 * bash "herdr: command not found", dash "herdr: not found". A shell that says
 * something else is reported as it is rather than guessed at.
 */
function isMissingBinary(e: HerdrError): boolean {
  return e.code === 'herdr_cli' && /not found/i.test(e.detail);
}

/**
 * The directory holding herdr, asked of the user's own shell.
 *
 * Reached only after a plain call failed, so the cost is paid on hosts that
 * need it and never on hosts where herdr is already on the default PATH.
 */
async function locateHerdr(hostId: string): Promise<string> {
  const found = parseHerdrLookup(await runHerdr(hostId, herdrLookupCommand()));
  if (found === null) {
    throw new HerdrError(
      'herdr_missing',
      'herdr was not found on this host, not even by your own shell.',
    );
  }
  const cut = found.lastIndexOf('/');
  return cut > 0 ? found.slice(0, cut) : '/';
}

/** Runs one command as given and maps its outcome. */
async function runHerdr(hostId: string, command: string): Promise<string> {
  const res = await NativeHerdr.exec(hostId, command);
  if (!res.ok) {
    throw new HerdrError(
      res.code === '' ? 'herdr_unreachable' : res.code,
      res.message === '' ? 'The host could not be reached.' : res.message,
    );
  }
  const stdout = textOf(res.stdout);
  const stderr = textOf(res.stderr);

  // herdr reports its own failures as a typed error document, and which stream
  // carries it depends on the command: `session list --json` answers on
  // stdout, while `api snapshot` writes the document to stderr and exits one.
  // Both are read before the status is considered, or the reason herdr gave
  // would be replaced by "unreadable output" or by the raw document as text.
  const cli = parseCliError(stdout) ?? parseCliError(stderr);
  if (cli !== null) throw new HerdrError(cli.code, cli.message);

  if (res.exitCode === 0) return stdout;

  // A non-zero exit with no document is the CLI itself failing: a missing
  // binary, or a shell that could not run it.
  const trimmed = stderr.trim();
  throw new HerdrError(
    'herdr_cli',
    trimmed !== ''
      ? trimmed
      : `herdr exited with status ${String(res.exitCode)}.`,
  );
}

/** The named sessions on the host, whether or not their servers are running. */
export async function listHerdrSessions(
  hostId: string,
): Promise<HerdrSession[]> {
  return parseSessions(await execHerdr(hostId, sessionListCommand()));
}

/** One session's whole state: workspaces, tabs, panes, and what has focus. */
export async function herdrSnapshot(
  hostId: string,
  session: string | null = null,
): Promise<HerdrSnapshot> {
  return parseSnapshot(await execHerdr(hostId, apiSnapshotCommand(session)));
}

export async function createHerdrWorkspace(
  hostId: string,
  opts: HerdrCreateWorkspace,
  session: string | null = null,
): Promise<HerdrCreatedWorkspace> {
  return parseCreatedWorkspace(
    await execHerdr(hostId, workspaceCreateCommand(opts, session)),
  );
}

/**
 * Focuses a workspace.
 *
 * This is what makes the session persistent from the app's side: the workspace
 * keeps running on the host, and focusing it is what a later terminal attach
 * lands on.
 */
export async function focusHerdrWorkspace(
  hostId: string,
  workspaceId: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, workspaceFocusCommand(workspaceId, session));
}

/**
 * Moves the session to the workspace beside the focused one.
 *
 * herdr ships `next_workspace` and `previous_workspace` unbound, so there is
 * no chord to send an attached terminal: the move is made over the socket
 * instead, from the order the snapshot reports. Wraps at both ends, so
 * repeating the gesture keeps going round. Returns where it landed, or null
 * when the session has nowhere else to go.
 */
export async function moveHerdrWorkspace(
  hostId: string,
  direction: 1 | -1,
  session: string | null = null,
): Promise<HerdrWorkspace | null> {
  const snap = await herdrSnapshot(hostId, session);
  const ordered = [...snap.workspaces].sort((a, b) => a.number - b.number);
  if (ordered.length < 2) return null;
  const at = ordered.findIndex(w => w.workspaceId === snap.focusedWorkspaceId);
  const from = at < 0 ? 0 : at;
  const next = ordered[(from + direction + ordered.length) % ordered.length];
  if (next === undefined) return null;
  await focusHerdrWorkspace(hostId, next.workspaceId, session);
  return next;
}

export async function closeHerdrWorkspace(
  hostId: string,
  workspaceId: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, workspaceCloseCommand(workspaceId, session));
}

export async function renameHerdrWorkspace(
  hostId: string,
  workspaceId: string,
  label: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, workspaceRenameCommand(workspaceId, label, session));
}

export async function createHerdrTab(
  hostId: string,
  opts: HerdrCreateTab = {},
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, tabCreateCommand(opts, session));
}

export async function focusHerdrTab(
  hostId: string,
  tabId: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, tabFocusCommand(tabId, session));
}

export async function renameHerdrTab(
  hostId: string,
  tabId: string,
  label: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, tabRenameCommand(tabId, label, session));
}

export async function closeHerdrTab(
  hostId: string,
  tabId: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, tabCloseCommand(tabId, session));
}

/** One workspace's tabs. What the app's tab strip is drawn from. */
export async function listHerdrTabs(
  hostId: string,
  workspaceId: string,
  session: string | null = null,
): Promise<HerdrTab[]> {
  return parseTabs(
    await execHerdr(hostId, tabListCommand(workspaceId, session)),
  );
}

/** The plugin actions this host offers, as `plugin.action` ids. */
export async function listHerdrPluginActions(
  hostId: string,
  pluginId: string | null = null,
  session: string | null = null,
): Promise<string[]> {
  return parsePluginActions(
    await execHerdr(hostId, pluginActionListCommand(pluginId, session)),
  );
}

/**
 * Runs a plugin action.
 *
 * Returns once herdr has started it. What it did shows up in the state the
 * caller reads next, which is why nothing here waits for its output.
 */
export async function invokeHerdrPluginAction(
  hostId: string,
  actionId: string,
  session: string | null = null,
): Promise<void> {
  await execHerdr(hostId, pluginActionInvokeCommand(actionId, session));
}

/** Stops a session's server. Its workspaces are gone until it starts again. */
export async function stopHerdrSession(
  hostId: string,
  name: string,
): Promise<void> {
  await execHerdr(hostId, sessionStopCommand(name));
}

/** Deletes a session: its directory and socket, not only its server. */
export async function deleteHerdrSession(
  hostId: string,
  name: string,
): Promise<void> {
  await execHerdr(hostId, sessionDeleteCommand(name));
}

/** A pane's contents as plain text, for a preview. */
export async function readHerdrPane(
  hostId: string,
  opts: HerdrPaneRead,
  session: string | null = null,
): Promise<string> {
  return execHerdr(hostId, paneReadCommand(opts, session));
}
