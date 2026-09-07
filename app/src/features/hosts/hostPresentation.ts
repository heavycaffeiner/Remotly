// Pure presentation logic for the hosts list: row mapping, search, and
// bounded-concurrency loading.
//
// Nothing here touches navigation or the native bridge, so every rule below is
// unit-testable.

import type { SshHostView } from '../../lib/sshHosts';

/** One row in the hosts list. */
export interface HostListEntry {
  id: string;
  name: string;
  /** The endpoint. */
  detail: string;
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

export function sshName(host: SshHostView): string {
  return host.displayName || `${host.username}@${host.host}`;
}

export function sshEndpoint(host: SshHostView): string {
  return `${host.username}@${host.host}:${host.port}`;
}

export function toSshEntry(host: SshHostView): HostListEntry {
  const name = sshName(host);
  const endpoint = sshEndpoint(host);
  const auth = host.authKind === 1 ? 'key' : 'password';
  return {
    id: host.id,
    name,
    detail: `${endpoint} (${auth})`,
    statusLabel: 'SSH host',
    accessibilityLabel: `${name}, SSH host ${endpoint}, ${auth} authentication`,
  };
}

/**
 * Filters hosts by a case-insensitive substring query.
 *
 * The query is folded for comparison only; stored names keep their exact
 * original text, which matters for CJK and for names that differ only in case.
 */
export function filterHosts(
  entries: readonly HostListEntry[],
  query: string,
): HostListEntry[] {
  const q = query.trim().toLowerCase();
  if (q === '') return entries as HostListEntry[];
  return entries.filter(
    e => e.name.toLowerCase().includes(q) || e.detail.toLowerCase().includes(q),
  );
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
