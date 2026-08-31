// Cross-host sessions overview state.
//
// The overview aggregates live daemon sessions across every paired host and
// presents them as one deterministically ordered list. Refreshes are
// incremental: each host is polled independently, one host's failure must not
// block the others, and out-of-order responses must not overwrite newer
// state.
//
// Stale-response rejection is by generation token: every refresh request for
// a host carries a monotonically increasing generation. A response is applied
// only when its generation matches the newest one issued for that host, so an
// older response that arrives late is dropped. A host that was removed while
// a request was in flight is dropped the same way.
//
// This module is pure data and functions; the transport layer drives it.
import { sanitizePreview } from './preview';

export interface OverviewSession {
  hostId: string;
  /** The daemon's 64-hex-character session id. */
  sessionId: string;
  title: string;
  kind: string;
  /** Unix seconds, from the daemon's clock. */
  lastActivity: number;
  running: boolean;
  preview: string;
}

export interface HostRefresh {
  hostId: string;
  /** The generation of the request this refresh answers. */
  generation: number;
  /** True when the host answered; false on transport failure. */
  ok: boolean;
  sessions?: {
    sessionId: string;
    title: string;
    kind: string;
    lastActivity: number;
    running: boolean;
    preview?: string;
  }[];
}

export interface OverviewState {
  /** Newest generation issued per host. */
  generation: Map<string, number>;
  /** Sessions currently shown, per host. */
  sessions: Map<string, OverviewSession[]>;
  /** True while the host's last request is in flight. */
  loading: Map<string, boolean>;
  /** Last known failure reason code per host, cleared on success. */
  error: Map<string, boolean>;
  /** Hosts known to be removed; their in-flight data is dropped. */
  removed: Set<string>;
}

const MAX_TITLE_LEN = 120;

export function createOverview(): OverviewState {
  return {
    generation: new Map(),
    sessions: new Map(),
    loading: new Map(),
    error: new Map(),
    removed: new Set(),
  };
}

// Starts a refresh for one host and returns the new state plus the
// generation to stamp on the request. Re-issuing for a host that is already
// loading bumps the generation so the earlier in-flight response is rejected
// on arrival.
export function beginRefresh(
  state: OverviewState,
  hostId: string,
): { state: OverviewState; generation: number } {
  const generation = (state.generation.get(hostId) ?? 0) + 1;
  const next: OverviewState = {
    generation: new Map(state.generation).set(hostId, generation),
    sessions: state.sessions,
    loading: new Map(state.loading).set(hostId, true),
    error: new Map(state.error),
    removed: state.removed,
  };
  return { state: next, generation };
}

// Applies one refresh response. The response is dropped (state unchanged)
// when its generation is stale, when the host was removed, or when the state
// does not track the host at all.
export function applyRefresh(
  state: OverviewState,
  refresh: HostRefresh,
): OverviewState {
  if (state.removed.has(refresh.hostId)) return state;
  const newest = state.generation.get(refresh.hostId);
  if (newest === undefined || refresh.generation !== newest) return state;
  const sessions = new Map(state.sessions);
  const loading = new Map(state.loading);
  const error = new Map(state.error);
  loading.set(refresh.hostId, false);
  if (!refresh.ok || !Array.isArray(refresh.sessions)) {
    // A failed refresh keeps whatever the host last reported; the error flag
    // lets the UI show a partial-failure state without blocking others.
    sessions.set(refresh.hostId, state.sessions.get(refresh.hostId) ?? []);
    error.set(refresh.hostId, true);
    return { ...state, sessions, loading, error };
  }
  const rows: OverviewSession[] = [];
  const seen = new Set<string>();
  for (const s of refresh.sessions) {
    if (s === null || typeof s !== 'object') continue;
    const sessionId = s.sessionId;
    if (
      typeof sessionId !== 'string' ||
      sessionId.length < 1 ||
      sessionId.length > 128
    ) {
      continue;
    }
    if (seen.has(sessionId)) continue; // daemon bug: a duplicate row
    seen.add(sessionId);
    rows.push({
      hostId: refresh.hostId,
      sessionId,
      title: typeof s.title === 'string' ? s.title.slice(0, MAX_TITLE_LEN) : '',
      kind: typeof s.kind === 'string' ? s.kind.slice(0, 32) : '',
      lastActivity: Number.isFinite(s.lastActivity)
        ? Math.trunc(s.lastActivity)
        : 0,
      running: s.running === true,
      preview: sanitizePreview(typeof s.preview === 'string' ? s.preview : ''),
    });
  }
  sessions.set(refresh.hostId, rows);
  error.delete(refresh.hostId);
  return { ...state, sessions, loading, error };
}

// Drops one host entirely (host removed). In-flight responses for it are
// rejected afterwards.
export function removeHost(
  state: OverviewState,
  hostId: string,
): OverviewState {
  const sessions = new Map(state.sessions);
  const loading = new Map(state.loading);
  const error = new Map(state.error);
  const generation = new Map(state.generation);
  sessions.delete(hostId);
  loading.delete(hostId);
  error.delete(hostId);
  generation.delete(hostId);
  return {
    ...state,
    sessions,
    loading,
    error,
    generation,
    removed: new Set(state.removed).add(hostId),
  };
}

// Deterministic ordering: last activity descending, then host id ascending,
// then session id ascending. Host clocks can differ, so the tie-break keeps
// the order stable regardless of which daemon reported first.
export function compareSessions(
  a: OverviewSession,
  b: OverviewSession,
): number {
  if (a.lastActivity !== b.lastActivity) return b.lastActivity - a.lastActivity;
  if (a.hostId !== b.hostId) return a.hostId < b.hostId ? -1 : 1;
  return a.sessionId < b.sessionId ? -1 : a.sessionId > b.sessionId ? 1 : 0;
}

// Renders a Unix-seconds timestamp as a short relative age: "just now",
// "5m ago", "2h ago", "3d ago". Values older than 30 days or before the
// reference show as "a while ago" so a wrong daemon clock cannot render a
// negative age.
export function relativeAge(lastActivitySec: number, nowSec: number): string {
  if (!Number.isFinite(lastActivitySec) || !Number.isFinite(nowSec))
    return 'a while ago';
  const delta = nowSec - lastActivitySec;
  if (delta < 30) return 'just now';
  if (delta < 60 * 60) return `${Math.floor(delta / 60)}m ago`;
  if (delta < 24 * 60 * 60) return `${Math.floor(delta / (60 * 60))}h ago`;
  if (delta < 30 * 24 * 60 * 60)
    return `${Math.floor(delta / (24 * 60 * 60))}d ago`;
  return 'a while ago';
}

// The ordered overview list across all hosts.
export function buildOverview(state: OverviewState): OverviewSession[] {
  const all: OverviewSession[] = [];
  for (const rows of state.sessions.values()) {
    all.push(...rows);
  }
  return all.sort(compareSessions);
}
