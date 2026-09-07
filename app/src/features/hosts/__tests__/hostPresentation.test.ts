import {
  filterHosts,
  mapWithConcurrency,
  toSshEntry,
  withSessionCount,
  type HostListEntry,
} from '../hostPresentation';
import type { SshHostView } from '../../../lib/sshHosts';

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

describe('entry mapping', () => {
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
  // Only s3 carries example.com, so a query on the endpoint has to pick out
  // one entry rather than matching a host every fixture happens to share.
  const entries: HostListEntry[] = [
    toSshEntry(ssh({ id: 's1', displayName: 'Laptop', host: 'laptop.lan' })),
    toSshEntry(ssh({ id: 's2', displayName: '개발서버', host: 'dev.lan' })),
    toSshEntry(ssh({ id: 's3', displayName: 'Prod', host: 'example.com' })),
  ];

  it('returns everything for an empty query', () => {
    expect(filterHosts(entries, '')).toHaveLength(3);
  });

  it('matches names case-insensitively', () => {
    expect(filterHosts(entries, 'laptop')).toHaveLength(1);
    expect(filterHosts(entries, 'LAPTOP')).toHaveLength(1);
  });

  it('matches a CJK substring', () => {
    const found = filterHosts(entries, '개발');
    expect(found).toHaveLength(1);
    expect(found[0].name).toBe('개발서버');
  });

  it('matches the endpoint text', () => {
    expect(filterHosts(entries, 'example.com')).toHaveLength(1);
  });

  it('ignores surrounding whitespace in the query', () => {
    expect(filterHosts(entries, '  laptop  ')).toHaveLength(1);
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
