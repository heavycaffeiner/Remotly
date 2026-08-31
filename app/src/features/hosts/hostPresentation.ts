// Pure presentation logic for the hosts list: status derivation, filtering,
// search, and bounded-concurrency status loading.
//
// Nothing here touches navigation or the native bridge, so every rule below is
// unit-testable.

import type { HostRecord } from '../../lib/hosts';
import type { SshHostView } from '../../lib/sshHosts';
import type { TransportStatus } from '../../lib/transport';

export type HostKind = 'daemon' | 'ssh';

/**
 * A host's connection state.
 *
 * `unavailable` is distinct from `offline`: it means the status query itself
 * failed, so the app does not know. Reporting that as offline hides a local
 * bridge or storage fault behind a normal-looking label.
 */
export type HostStatus =
  | 'connected-direct'
  | 'connected-relay'
  | 'connecting'
  | 'offline'
  | 'unavailable';

export const STATUS_LABEL: Record<HostStatus, string> = {
  'connected-direct': 'Connected direct',
  'connected-relay': 'Connected via relay',
  connecting: 'Connecting',
  offline: 'Offline',
  unavailable: 'Status unavailable',
};

export type StatusTone = 'ok' | 'busy' | 'idle' | 'danger';

export const STATUS_TONE: Record<HostStatus, StatusTone> = {
  'connected-direct': 'ok',
  'connected-relay': 'ok',
  connecting: 'busy',
  offline: 'idle',
  unavailable: 'danger',
};

/** One row in the unified hosts list. */
export interface HostListEntry {
  kind: HostKind;
  id: string;
  name: string;
  /** The endpoint, or the host type when there is no address to show. */
  detail: string;
  status: HostStatus;
  statusLabel: string;
  /** Full name and endpoint, for a screen reader, even when truncated. */
  accessibilityLabel: string;
  /** Open terminal sessions for this host. Zero renders no badge. */
  sessions?: number;
}

/** Adds the open session count to an entry, for the hosts list badge. */
export function withSessionCount(
  entry: HostListEntry,
  sessions: number,
): HostListEntry {
  if (!Number.isFinite(sessions) || sessions <= 0) return entry;
  const plural = sessions === 1 ? 'session' : 'sessions';
  return {
    ...entry,
    sessions,
    accessibilityLabel: `${entry.accessibilityLabel}, ${sessions} open ${plural}`,
  };
}

export function daemonStatus(
  entry: TransportStatus | undefined,
  failed: boolean,
): HostStatus {
  if (failed) return 'unavailable';
  if (entry === undefined) return 'offline';
  if (entry.connected) {
    return entry.via === 'relay' ? 'connected-relay' : 'connected-direct';
  }
  if (entry.state === 'connecting') return 'connecting';
  return 'offline';
}

/** Relative time for "last seen". */
export function relTime(
  unixSeconds: number,
  nowMs: number = Date.now(),
): string {
  if (!unixSeconds) return 'never';
  const s = Math.max(0, Math.floor(nowMs / 1000) - unixSeconds);
  if (s < 60) return 'just now';
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  return `${Math.floor(s / 86400)}d ago`;
}

export function daemonName(host: HostRecord): string {
  return host.daemonName || host.id.slice(0, 12);
}

export function sshName(host: SshHostView): string {
  return host.displayName || `${host.username}@${host.host}`;
}

export function sshEndpoint(host: SshHostView): string {
  return `${host.username}@${host.host}:${host.port}`;
}

export function toDaemonEntry(
  host: HostRecord,
  status: HostStatus,
  nowMs: number = Date.now(),
): HostListEntry {
  const name = daemonName(host);
  const label = STATUS_LABEL[status];
  const detail =
    status === 'offline'
      ? `Remotly host, last seen ${relTime(host.lastConnectedAt, nowMs)}`
      : 'Remotly host';
  return {
    kind: 'daemon',
    id: host.id,
    name,
    detail,
    status,
    statusLabel: label,
    accessibilityLabel: `${name}, Remotly host, ${label}`,
  };
}

export function toSshEntry(host: SshHostView): HostListEntry {
  const name = sshName(host);
  const endpoint = sshEndpoint(host);
  const auth = host.authKind === 1 ? 'key' : 'password';
  return {
    kind: 'ssh',
    id: host.id,
    name,
    detail: `${endpoint} (${auth})`,
    status: 'offline',
    statusLabel: 'SSH host',
    accessibilityLabel: `${name}, SSH host ${endpoint}, ${auth} authentication`,
  };
}

export type HostFilter = 'all' | 'daemon' | 'ssh';

/**
 * Filters by kind and a case-insensitive substring query.
 *
 * The query is folded for comparison only; stored names keep their exact
 * original text, which matters for CJK and for names that differ only in case.
 */
export function filterHosts(
  entries: readonly HostListEntry[],
  filter: HostFilter,
  query: string,
): HostListEntry[] {
  const q = query.trim().toLowerCase();
  return entries.filter(e => {
    if (filter !== 'all' && e.kind !== filter) return false;
    if (q === '') return true;
    return (
      e.name.toLowerCase().includes(q) || e.detail.toLowerCase().includes(q)
    );
  });
}

/**
 * Runs `task` over `items` with at most `limit` in flight.
 *
 * Fanning a status query out across every host at once floods the bridge on a
 * large host list; this keeps the request count bounded without adding a
 * dependency. Results keep input order, and a rejected task yields undefined
 * rather than failing the batch.
 */
export async function mapWithConcurrency<T, R>(
  items: readonly T[],
  limit: number,
  task: (item: T, index: number) => Promise<R>,
): Promise<(R | undefined)[]> {
  const results = new Array<R | undefined>(items.length);
  const width = Math.max(1, Math.min(limit, items.length));
  let next = 0;

  async function worker(): Promise<void> {
    for (;;) {
      const index = next;
      next += 1;
      if (index >= items.length) return;
      try {
        results[index] = await task(items[index], index);
      } catch {
        results[index] = undefined;
      }
    }
  }

  await Promise.all(Array.from({ length: width }, () => worker()));
  return results;
}
