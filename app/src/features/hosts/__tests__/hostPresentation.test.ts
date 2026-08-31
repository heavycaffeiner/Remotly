import {
  daemonStatus,
  filterHosts,
  mapWithConcurrency,
  relTime,
  toDaemonEntry,
  toSshEntry,
  withSessionCount,
  type HostListEntry,
} from '../hostPresentation';
import type { HostRecord } from '../../../lib/hosts';
import type { SshHostView } from '../../../lib/sshHosts';

const host = (over: Partial<HostRecord> = {}): HostRecord => ({
  id: 'abcdef0123456789',
  daemonName: 'dev-box',
  daemonPub: 'pub',
  hints: [],
  pairedAt: 0,
  lastConnectedAt: 0,
  ...over,
});

const ssh = (over: Partial<SshHostView> = {}): SshHostView => ({
  id: 'ssh-1',
  displayName: 'Prod',
  host: 'example.com',
  port: 22,
  username: 'deploy',
  authKind: 1,
  hasCredential: true,
  knownKeys: [],
  createdAt: 0,
  updatedAt: 0,
  ...over,
});

describe('daemonStatus', () => {
  it('reports a direct connection', () => {
    expect(
      daemonStatus(
        { connected: true, state: 'connected', via: 'direct' },
        false,
      ),
    ).toBe('connected-direct');
  });

  it('reports a relay connection', () => {
    expect(
      daemonStatus(
        { connected: true, state: 'connected', via: 'relay' },
        false,
      ),
    ).toBe('connected-relay');
  });

  it('reports connecting', () => {
    expect(daemonStatus({ connected: false, state: 'connecting' }, false)).toBe(
      'connecting',
    );
  });

  it('reports offline for a disconnected host', () => {
    expect(
      daemonStatus({ connected: false, state: 'disconnected' }, false),
    ).toBe('offline');
  });

  it('separates a failed status query from an offline host', () => {
    expect(daemonStatus(undefined, true)).toBe('unavailable');
    expect(daemonStatus(undefined, false)).toBe('offline');
  });
});

describe('relTime', () => {
  const now = 1_000_000_000_000;

  it('reports never for an unset timestamp', () => {
    expect(relTime(0, now)).toBe('never');
  });

  it('reports coarse relative times', () => {
    const nowSec = Math.floor(now / 1000);
    expect(relTime(nowSec - 10, now)).toBe('just now');
    expect(relTime(nowSec - 120, now)).toBe('2m ago');
    expect(relTime(nowSec - 7200, now)).toBe('2h ago');
    expect(relTime(nowSec - 172800, now)).toBe('2d ago');
  });

  it('never reports a negative age for a clock skewed into the future', () => {
    const nowSec = Math.floor(now / 1000);
    expect(relTime(nowSec + 500, now)).toBe('just now');
  });
});

describe('entry mapping', () => {
  it('falls back to a shortened id when the daemon has no name', () => {
    const entry = toDaemonEntry(host({ daemonName: '' }), 'offline');
    expect(entry.name).toBe('abcdef012345');
  });

  it('keeps a CJK name exactly as stored', () => {
    const entry = toDaemonEntry(host({ daemonName: '개발서버' }), 'offline');
    expect(entry.name).toBe('개발서버');
    expect(entry.accessibilityLabel).toContain('개발서버');
  });

  it('reads the full endpoint in the accessible label', () => {
    const entry = toSshEntry(ssh());
    expect(entry.accessibilityLabel).toContain('deploy@example.com:22');
    expect(entry.accessibilityLabel).toContain('key authentication');
  });

  it('names the password auth kind', () => {
    expect(toSshEntry(ssh({ authKind: 0 })).detail).toContain('password');
  });

  it('falls back to user@host when the ssh host has no display name', () => {
    expect(toSshEntry(ssh({ displayName: '' })).name).toBe(
      'deploy@example.com',
    );
  });
});

describe('withSessionCount', () => {
  it('adds the count and announces it', () => {
    const entry = withSessionCount(toSshEntry(ssh()), 2);
    expect(entry.sessions).toBe(2);
    expect(entry.accessibilityLabel).toContain('2 open sessions');
  });

  it('uses the singular for one session', () => {
    expect(withSessionCount(toSshEntry(ssh()), 1).accessibilityLabel).toContain(
      '1 open session',
    );
  });

  // No badge for a host with nothing open, rather than a zero.
  it('leaves an entry untouched at zero', () => {
    const base = toSshEntry(ssh());
    expect(withSessionCount(base, 0)).toBe(base);
  });

  it('ignores a nonsense count', () => {
    const base = toSshEntry(ssh());
    expect(withSessionCount(base, -1)).toBe(base);
    expect(withSessionCount(base, NaN)).toBe(base);
  });
});

describe('filterHosts', () => {
  const entries: HostListEntry[] = [
    toDaemonEntry(host({ id: 'd1', daemonName: '개발서버' }), 'offline'),
    toDaemonEntry(host({ id: 'd2', daemonName: 'Laptop' }), 'connected-direct'),
    toSshEntry(ssh({ id: 's1', displayName: 'Prod' })),
  ];

  it('returns everything for the all filter and an empty query', () => {
    expect(filterHosts(entries, 'all', '')).toHaveLength(3);
  });

  it('filters by kind', () => {
    expect(filterHosts(entries, 'daemon', '')).toHaveLength(2);
    expect(filterHosts(entries, 'ssh', '')).toHaveLength(1);
  });

  it('matches names case-insensitively', () => {
    expect(filterHosts(entries, 'all', 'laptop')).toHaveLength(1);
    expect(filterHosts(entries, 'all', 'LAPTOP')).toHaveLength(1);
  });

  it('matches a CJK substring', () => {
    const found = filterHosts(entries, 'all', '개발');
    expect(found).toHaveLength(1);
    expect(found[0].name).toBe('개발서버');
  });

  it('matches the endpoint text', () => {
    expect(filterHosts(entries, 'all', 'example.com')).toHaveLength(1);
  });

  it('ignores surrounding whitespace in the query', () => {
    expect(filterHosts(entries, 'all', '  laptop  ')).toHaveLength(1);
  });
});

describe('mapWithConcurrency', () => {
  it('keeps input order', async () => {
    const out = await mapWithConcurrency([1, 2, 3, 4, 5], 2, async n => n * 2);
    expect(out).toEqual([2, 4, 6, 8, 10]);
  });

  it('never exceeds the concurrency limit', async () => {
    let inFlight = 0;
    let peak = 0;
    await mapWithConcurrency(
      Array.from({ length: 10 }, (_, i) => i),
      3,
      async () => {
        inFlight += 1;
        peak = Math.max(peak, inFlight);
        await new Promise(r => setTimeout(r, 1));
        inFlight -= 1;
        return true;
      },
    );
    expect(peak).toBeLessThanOrEqual(3);
  });

  it('yields undefined for a rejected task without failing the batch', async () => {
    const out = await mapWithConcurrency([1, 2, 3], 2, async n => {
      if (n === 2) throw new Error('nope');
      return n;
    });
    expect(out).toEqual([1, undefined, 3]);
  });

  it('handles an empty input', async () => {
    expect(await mapWithConcurrency([], 4, async () => 1)).toEqual([]);
  });
});
