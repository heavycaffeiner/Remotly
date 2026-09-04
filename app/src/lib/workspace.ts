// Per-host workspace tab state.
//
// A workspace is the app-side view of one paired host's terminal tabs. Each
// tab is an attached daemon session keyed by the stable (hostId, sessionId)
// pair. Closing a tab detaches it: the daemon session keeps running and the
// tab is dropped from the workspace. Killing a session is a separate,
// explicit action and never happens here.
//
// The state is plain data so it can be serialized to the native workspace
// store and reconciled against the daemon's session list after an app
// restart or a reconnect. Reconciliation never kills sessions: a saved tab
// whose session is gone becomes stale, and a duplicate open request returns
// the existing tab.
//
// This module is pure: every function returns a new state, so the UI layer
// can apply actions optimistically and roll back.
import { sanitizePreview } from './preview';

export type TabState = 'attaching' | 'attached' | 'exited' | 'stale';

export interface WorkspaceTab {
  /** The daemon's 64-hex-character session id. Stable for the session's life. */
  sessionId: string;
  title: string;
  kind: string;
  /** Output bytes fully consumed from this session; the replay cursor. */
  cursor: number;
  state: TabState;
  /** Present when the daemon reported an exit for this session. */
  exitCode: number | null;
  /** Bounded plain-text preview of the session's last line. */
  preview: string;
}

export interface WorkspaceState {
  hostId: string;
  tabs: WorkspaceTab[];
  activeSessionId: string | null;
}

// Session ids are daemon-minted hex strings; bound the shape at the boundary
// so a hostile daemon cannot store arbitrary strings as identifiers.
const SESSION_ID = /^[0-9a-f]{16,128}$/;

export function isValidSessionId(id: unknown): id is string {
  return typeof id === 'string' && SESSION_ID.test(id);
}

// Tabs are bounded per host so a hostile daemon cannot grow the workspace
// without limit.
export const MAX_TABS = 16;
// Session titles are daemon metadata; bound them for display and storage.
export const MAX_TITLE_LEN = 120;
// Replay cursors are cumulative byte offsets; keep them JavaScript-safe.
export const MAX_CURSOR = 2 ** 53 - 1;

function clampTitle(title: string): string {
  return title.slice(0, MAX_TITLE_LEN);
}
function clampCursor(cursor: number): number {
  if (!Number.isFinite(cursor) || cursor < 0) return 0;
  return Math.min(Math.floor(cursor), MAX_CURSOR);
}

// Builds an empty workspace for one host.
export function createWorkspace(hostId: string): WorkspaceState {
  return { hostId, tabs: [], activeSessionId: null };
}

// Returns the tab for a session, or null.
export function findTab(
  ws: WorkspaceState,
  sessionId: string,
): WorkspaceTab | null {
  for (const t of ws.tabs) {
    if (t.sessionId === sessionId) return t;
  }
  return null;
}

// Adds a tab for a session. A session that already has a tab is a duplicate
// open request: the existing tab is returned unchanged and the state is not
// modified. A malformed id or a full workspace is rejected: tab is null and
// the state is unchanged, and the caller surfaces the failure.
export function addTab(
  ws: WorkspaceState,
  session: { sessionId: string; title: string; kind: string; running: boolean },
  cursor = 0,
): { state: WorkspaceState; tab: WorkspaceTab | null } {
  const existing = findTab(ws, session.sessionId);
  if (existing !== null) {
    return { state: ws, tab: existing };
  }
  if (!isValidSessionId(session.sessionId) || ws.tabs.length >= MAX_TABS) {
    return { state: ws, tab: null };
  }
  const tab: WorkspaceTab = {
    sessionId: session.sessionId,
    title: clampTitle(session.title),
    kind: typeof session.kind === 'string' ? session.kind.slice(0, 32) : '',
    cursor: clampCursor(cursor),
    state: session.running ? 'attaching' : 'exited',
    exitCode: null,
    preview: '',
  };
  const state: WorkspaceState = {
    ...ws,
    tabs: [...ws.tabs, tab],
    activeSessionId: ws.activeSessionId ?? session.sessionId,
  };
  return { state, tab };
}

// Removes a tab (detach only). The daemon session is untouched. When the
// active tab is removed, the last remaining tab becomes active.
export function closeTab(
  ws: WorkspaceState,
  sessionId: string,
): WorkspaceState {
  const tabs = ws.tabs.filter(t => t.sessionId !== sessionId);
  if (tabs.length === ws.tabs.length) return ws;
  let activeSessionId = ws.activeSessionId;
  if (activeSessionId === sessionId) {
    activeSessionId = tabs.length > 0 ? tabs[tabs.length - 1].sessionId : null;
  }
  return { ...ws, tabs, activeSessionId };
}

// Adds a tab for every running daemon session that does not have one yet,
// oldest first, up to the tab cap.
//
// reconcile deliberately never creates tabs, so a device that has no stored
// workspace (a fresh pairing, a cleared app, or a stored document the parser
// rejected) showed no tabs at all while the daemon was running several
// sessions: the sessions were unreachable from the app and only a brand new
// one could be opened. Sessions that already exited are left alone; adopting
// them would fill the strip with dead tabs on every connect.
export function adoptSessions(
  ws: WorkspaceState,
  sessions: readonly {
    sessionId: string;
    title: string;
    kind: string;
    running: boolean;
  }[],
): WorkspaceState {
  let state = ws;
  for (const s of sessions) {
    if (!s.running) continue;
    if (findTab(state, s.sessionId) !== null) continue;
    const { state: next, tab } = addTab(state, s);
    if (tab === null) break;
    state = next;
  }
  return state;
}

// Sets the active tab. Unknown session ids are ignored.
export function setActive(
  ws: WorkspaceState,
  sessionId: string,
): WorkspaceState {
  if (findTab(ws, sessionId) === null) return ws;
  if (ws.activeSessionId === sessionId) return ws;
  return { ...ws, activeSessionId: sessionId };
}

// Marks a tab's attach as complete.
export function markAttached(
  ws: WorkspaceState,
  sessionId: string,
): WorkspaceState {
  const tab = findTab(ws, sessionId);
  if (tab === null || tab.state !== 'attaching') return ws;
  return {
    ...ws,
    tabs: ws.tabs.map(t =>
      t.sessionId === sessionId ? { ...t, state: 'attached' as const } : t,
    ),
  };
}

// Marks a tab stale: its session is gone from the daemon (for example an
// attach answered unknown_session). The tab is kept so the user sees what
// happened, but it is no longer attachable.
export function markStale(
  ws: WorkspaceState,
  sessionId: string,
): WorkspaceState {
  const tab = findTab(ws, sessionId);
  if (tab === null || tab.state === 'stale') return ws;
  return {
    ...ws,
    tabs: ws.tabs.map(t =>
      t.sessionId === sessionId ? { ...t, state: 'stale' as const } : t,
    ),
  };
}

// Records that the session exited (or that its final state was fetched).
export function markExited(
  ws: WorkspaceState,
  sessionId: string,
  exitCode: number | null,
): WorkspaceState {
  const tab = findTab(ws, sessionId);
  if (tab === null) return ws;
  return {
    ...ws,
    tabs: ws.tabs.map(t =>
      t.sessionId === sessionId
        ? {
            ...t,
            state: 'exited' as const,
            exitCode: exitCode === null ? null : Math.trunc(exitCode),
          }
        : t,
    ),
  };
}

// Advances the replay cursor by the number of bytes fully consumed.
export function advanceCursor(
  ws: WorkspaceState,
  sessionId: string,
  bytes: number,
): WorkspaceState {
  const tab = findTab(ws, sessionId);
  if (tab === null) return ws;
  if (!Number.isFinite(bytes) || bytes < 0) return ws;
  const next = clampCursor(tab.cursor + bytes);
  if (next === tab.cursor) return ws;
  return {
    ...ws,
    tabs: ws.tabs.map(t =>
      t.sessionId === sessionId ? { ...t, cursor: next } : t,
    ),
  };
}

// Sets a tab's cursor to an exact value (clamped to the JS-safe bound). Used
// when an attach reports the replay start offset, which may differ from the
// saved cursor when the session's output grew in between. Negative values
// are ignored: resetting a cursor to zero would replay the whole session.
// Returns the same state when the cursor does not move.
export function setCursor(
  ws: WorkspaceState,
  sessionId: string,
  cursor: number,
): WorkspaceState {
  const tab = findTab(ws, sessionId);
  if (tab === null) return ws;
  if (!Number.isFinite(cursor) || cursor < 0) return ws;
  const next = clampCursor(cursor);
  if (next === tab.cursor) return ws;
  return {
    ...ws,
    tabs: ws.tabs.map(t =>
      t.sessionId === sessionId ? { ...t, cursor: next } : t,
    ),
  };
}

/**
 * The cursor to resume an attach from, or undefined to replay everything.
 *
 * A cursor only means something while a terminal still holds the output it
 * counts. The cursor is persisted; the terminal is native memory that dies
 * with the process. After a force quit or a cold start the saved cursor
 * therefore describes scrollback nothing has, and resuming from it makes the
 * daemon report "gapless" and replay nothing into an empty terminal: the
 * history is silently lost. Asking without one replays the whole retained
 * ring, which is how a session's scrollback is fetched on connect.
 */
export function resumeCursor(
  cursor: number,
  hasTerminal: boolean,
): number | undefined {
  if (!hasTerminal) return undefined;
  if (!Number.isFinite(cursor) || cursor <= 0) return undefined;
  return cursor;
}

// Updates a tab's title and preview from a daemon sessionUpdate.
export function applySessionMeta(
  ws: WorkspaceState,
  session: { sessionId: string; title: string; preview?: string },
): WorkspaceState {
  const tab = findTab(ws, session.sessionId);
  if (tab === null) return ws;
  return {
    ...ws,
    tabs: ws.tabs.map(t =>
      t.sessionId === session.sessionId
        ? {
            ...t,
            title: clampTitle(session.title),
            preview: sanitizePreview(
              typeof session.preview === 'string' ? session.preview : '',
            ),
          }
        : t,
    ),
  };
}

// Reconciles the workspace against the daemon's current session list.
//
// - A tab whose session still exists keeps its cursor and state, gains a
//   refreshed title/preview, and an exited session is marked exited.
// - A tab whose session is gone becomes stale (its output is no longer
//   resumable). Nothing is killed.
// - Sessions on the daemon without a tab are not added here: tabs are only
//   created by explicit user action or navigation from the overview.
export function reconcile(
  ws: WorkspaceState,
  sessions: {
    sessionId: string;
    title: string;
    running: boolean;
    preview?: string;
  }[],
): WorkspaceState {
  const byId = new Map<
    string,
    { title: string; running: boolean; preview?: string }
  >();
  for (const s of sessions) {
    byId.set(s.sessionId, s);
  }
  let changed = false;
  const tabs = ws.tabs.map(t => {
    const s = byId.get(t.sessionId);
    if (s === undefined) {
      if (t.state === 'stale') return t;
      changed = true;
      return { ...t, state: 'stale' as const };
    }
    const exitCode = t.state === 'exited' ? t.exitCode : null;
    const next: WorkspaceTab = {
      ...t,
      title: clampTitle(s.title),
      preview: sanitizePreview(typeof s.preview === 'string' ? s.preview : ''),
      state: s.running
        ? t.state === 'stale'
          ? t.state
          : 'attached'
        : 'exited',
      exitCode,
    };
    if (
      next.title !== t.title ||
      next.preview !== t.preview ||
      next.state !== t.state
    ) {
      changed = true;
    }
    return next;
  });
  if (!changed) return ws;
  return { ...ws, tabs };
}

// --- persistence ---

// Serializes a workspace for the native store.
export function serializeWorkspace(ws: WorkspaceState): string {
  return JSON.stringify({
    v: 1,
    hostId: ws.hostId,
    activeSessionId: ws.activeSessionId,
    tabs: ws.tabs.map(t => ({
      sessionId: t.sessionId,
      title: t.title,
      kind: t.kind,
      cursor: t.cursor,
      state: t.state,
      exitCode: t.exitCode,
      preview: t.preview,
    })),
  });
}

// Parses and validates a stored workspace. Malformed input yields null so the
// caller can quarantine the record, matching the host store's behavior.
export function parseWorkspace(
  json: string,
  hostId: string,
): WorkspaceState | null {
  let raw: unknown;
  try {
    raw = JSON.parse(json);
  } catch {
    return null;
  }
  if (raw === null || typeof raw !== 'object') return null;
  const o = raw as Record<string, unknown>;
  if (o.v !== 1) return null;
  if (typeof o.hostId !== 'string' || o.hostId !== hostId) return null;
  const active =
    o.activeSessionId === null || isValidSessionId(o.activeSessionId)
      ? (o.activeSessionId as string | null)
      : null;
  if (!Array.isArray(o.tabs)) return null;
  if (o.tabs.length > MAX_TABS) return null;
  const seen = new Set<string>();
  const tabs: WorkspaceTab[] = [];
  for (const el of o.tabs) {
    if (el === null || typeof el !== 'object') return null;
    const t = el as Record<string, unknown>;
    if (!isValidSessionId(t.sessionId)) return null;
    const sid = t.sessionId;
    if (seen.has(sid)) return null;
    seen.add(sid);
    const state = t.state;
    if (
      state !== 'attaching' &&
      state !== 'attached' &&
      state !== 'exited' &&
      state !== 'stale'
    ) {
      return null;
    }
    tabs.push({
      sessionId: sid,
      title: typeof t.title === 'string' ? clampTitle(t.title) : '',
      kind: typeof t.kind === 'string' ? t.kind.slice(0, 32) : '',
      cursor: clampCursor(typeof t.cursor === 'number' ? t.cursor : 0),
      state: state,
      exitCode:
        t.exitCode === null || t.exitCode === undefined
          ? null
          : Number.isInteger(t.exitCode)
          ? (t.exitCode as number)
          : null,
      preview: sanitizePreview(typeof t.preview === 'string' ? t.preview : ''),
    });
  }
  const activeSessionId =
    active !== null && seen.has(active)
      ? active
      : tabs.length > 0
      ? tabs[0].sessionId
      : null;
  return { hostId, tabs, activeSessionId };
}
