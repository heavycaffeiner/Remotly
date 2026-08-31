// Tab state for SSH terminals.
//
// SSH tabs are live-only. There is no replay, no cursor, and no persisted
// document: a closed session is gone, and reconnecting starts a new one. That
// is the whole difference from the daemon workspace in lib/workspace.ts, which
// keeps a cursor and reconciles against sessions the daemon still holds.
//
// Pure data. Every function returns new state, so the screen can apply an
// action and render the result without a round trip.

/** Tabs per host. Bounded so a runaway caller cannot grow the strip forever. */
export const MAX_SSH_TABS = 8;

export type SshTabPhase =
  | 'connecting'
  | 'hostKey'
  | 'active'
  | 'closed'
  | 'failed';

/**
 * What a tab holds.
 *
 * A shell tab owns a live SSH session; a files tab browses the same host over
 * SFTP. They share the strip because they are both views onto one host, and a
 * files tab that lived in its own screen could not be left running while the
 * user worked in a shell.
 */
export type SshTabKind = 'shell' | 'files';

export interface SshTab {
  /** App-minted, stable for the tab's life, and the native session key. */
  sessionId: string;
  title: string;
  phase: SshTabPhase;
  /** User-facing explanation for a closed or failed tab. Empty otherwise. */
  detail: string;
  kind: SshTabKind;
}

export interface SshTabsState {
  hostId: string;
  tabs: SshTab[];
  activeSessionId: string | null;
}

export function createSshTabs(hostId: string): SshTabsState {
  return { hostId, tabs: [], activeSessionId: null };
}

/**
 * Mints a session id.
 *
 * Only has to be unique within one host's open tabs, and it becomes a native
 * map key, so it is kept to characters the bridge accepts.
 */
export function mintSessionId(seq: number): string {
  const n = Number.isFinite(seq) && seq > 0 ? Math.floor(seq) : 1;
  return `t${n}-${Date.now().toString(36)}`;
}

export function findSshTab(
  state: SshTabsState,
  sessionId: string,
): SshTab | null {
  return state.tabs.find(t => t.sessionId === sessionId) ?? null;
}

/**
 * Appends a tab and makes it active.
 *
 * Returns the state unchanged with a null tab when the cap is reached, so the
 * caller can say so rather than silently dropping the request.
 */
export function addSshTab(
  state: SshTabsState,
  sessionId: string,
  title: string,
  kind: SshTabKind = 'shell',
): { state: SshTabsState; tab: SshTab | null } {
  const existing = findSshTab(state, sessionId);
  if (existing !== null) return { state, tab: existing };
  if (sessionId === '' || state.tabs.length >= MAX_SSH_TABS) {
    return { state, tab: null };
  }
  const tab: SshTab = {
    sessionId,
    title,
    phase: 'connecting',
    detail: '',
    kind,
  };
  return {
    state: {
      ...state,
      tabs: [...state.tabs, tab],
      activeSessionId: sessionId,
    },
    tab,
  };
}

/**
 * Drops a tab.
 *
 * When the closed tab was active, focus moves to the neighbour on the left,
 * which is where the eye already is.
 */
export function removeSshTab(
  state: SshTabsState,
  sessionId: string,
): SshTabsState {
  const index = state.tabs.findIndex(t => t.sessionId === sessionId);
  if (index < 0) return state;
  const tabs = state.tabs.filter(t => t.sessionId !== sessionId);
  if (state.activeSessionId !== sessionId) return { ...state, tabs };
  const neighbour = tabs[Math.max(0, index - 1)];
  return {
    ...state,
    tabs,
    activeSessionId: neighbour?.sessionId ?? null,
  };
}

export function setActiveSshTab(
  state: SshTabsState,
  sessionId: string,
): SshTabsState {
  if (findSshTab(state, sessionId) === null) return state;
  return { ...state, activeSessionId: sessionId };
}

/** Updates one tab's phase. Unknown ids are ignored. */
export function setSshTabPhase(
  state: SshTabsState,
  sessionId: string,
  phase: SshTabPhase,
  detail = '',
): SshTabsState {
  const tab = findSshTab(state, sessionId);
  if (tab === null) return state;
  if (tab.phase === phase && tab.detail === detail) return state;
  return {
    ...state,
    tabs: state.tabs.map(t =>
      t.sessionId === sessionId ? { ...t, phase, detail } : t,
    ),
  };
}

/** Titles are user input; bound them for display and storage. */
export const MAX_SSH_TAB_TITLE = 60;

/**
 * Renames a tab.
 *
 * The title is trimmed and bounded. An empty result is rejected rather than
 * leaving a tab with no label to select or close by.
 */
export function setSshTabTitle(
  state: SshTabsState,
  sessionId: string,
  title: string,
): SshTabsState {
  const tab = findSshTab(state, sessionId);
  if (tab === null) return state;
  const next = title.trim().slice(0, MAX_SSH_TAB_TITLE);
  if (next === '' || tab.title === next) return state;
  return {
    ...state,
    tabs: state.tabs.map(t =>
      t.sessionId === sessionId ? { ...t, title: next } : t,
    ),
  };
}
