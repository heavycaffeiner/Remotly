import { describe, expect, it } from '@jest/globals';
import {
  applyRefresh,
  beginRefresh,
  buildOverview,
  compareSessions,
  createOverview,
  relativeAge,
  removeHost,
  type OverviewSession,
} from '../overview';

// Session ids are daemon-minted 64-hex-character strings; zero padding keeps
// the lexicographic order aligned with the numeric order used here.
function sid(n: number): string {
  return n.toString(16).padStart(64, '0');
}

function sess(
  hostId: string,
  sessionId: string,
  lastActivity: number,
  extra: Partial<OverviewSession> = {},
): OverviewSession {
  return {
    hostId,
    sessionId,
    title: `s${sessionId}`,
    kind: 'shell',
    lastActivity,
    running: true,
    preview: '',
    ...extra,
  };
}

describe('overview ordering', () => {
  it('orders by last activity descending', () => {
    let state = beginRefresh(createOverview(), 'h').state;
    state = applyRefresh(state, {
      hostId: 'h',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'a',
          kind: 'shell',
          lastActivity: 100,
          running: true,
        },
        {
          sessionId: sid(2),
          title: 'b',
          kind: 'shell',
          lastActivity: 300,
          running: true,
        },
        {
          sessionId: sid(3),
          title: 'c',
          kind: 'shell',
          lastActivity: 200,
          running: true,
        },
      ],
    });
    expect(buildOverview(state).map(s => s.sessionId)).toEqual([
      sid(2),
      sid(3),
      sid(1),
    ]);
  });

  it('breaks timestamp ties by host id then session id', () => {
    const a = sess('host-b', sid(5), 100);
    const b = sess('host-a', sid(9), 100);
    const c = sess('host-a', sid(3), 100);
    const sorted = [a, b, c].sort(compareSessions);
    expect(sorted.map(s => [s.hostId, s.sessionId])).toEqual([
      ['host-a', sid(3)],
      ['host-a', sid(9)],
      ['host-b', sid(5)],
    ]);
  });

  it('aggregates sessions across hosts without duplicates', () => {
    let state = createOverview();
    state = beginRefresh(state, 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'a',
          kind: 'shell',
          lastActivity: 10,
          running: true,
        },
        {
          sessionId: sid(2),
          title: 'b',
          kind: 'shell',
          lastActivity: 20,
          running: true,
        },
      ],
    });
    state = beginRefresh(state, 'h2').state;
    state = applyRefresh(state, {
      hostId: 'h2',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'c',
          kind: 'agent',
          lastActivity: 30,
          running: true,
        },
      ],
    });
    const list = buildOverview(state);
    expect(list).toHaveLength(3);
    expect(list.map(s => [s.hostId, s.sessionId])).toEqual([
      ['h2', sid(1)],
      ['h1', sid(2)],
      ['h1', sid(1)],
    ]);
  });
});

describe('overview stale response rejection', () => {
  it('drops an out-of-order response from an older generation', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    // Generation 1 is in flight. A manual refresh bumps to generation 2.
    state = beginRefresh(state, 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 2,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'new',
          kind: 'shell',
          lastActivity: 20,
          running: true,
        },
      ],
    });
    // The stale generation-1 response arrives late.
    const stale = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(9),
          title: 'stale',
          kind: 'shell',
          lastActivity: 1,
          running: true,
        },
      ],
    });
    expect(stale).toBe(state);
    expect(buildOverview(state).map(s => s.sessionId)).toEqual([sid(1)]);
  });

  it('drops responses for hosts that were removed', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    state = removeHost(state, 'h1');
    const after = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'x',
          kind: 'shell',
          lastActivity: 1,
          running: true,
        },
      ],
    });
    expect(after).toBe(state);
    expect(buildOverview(after)).toEqual([]);
  });

  it('drops responses for unknown hosts', () => {
    const state = createOverview();
    const after = applyRefresh(state, {
      hostId: 'ghost',
      generation: 1,
      ok: true,
      sessions: [],
    });
    expect(after).toBe(state);
  });
});

describe('overview partial failure', () => {
  it('keeps last-known sessions when a host fails and flags the error', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'a',
          kind: 'shell',
          lastActivity: 10,
          running: true,
        },
      ],
    });
    expect(state.error.get('h1')).toBeUndefined();
    state = beginRefresh(state, 'h1').state;
    state = applyRefresh(state, { hostId: 'h1', generation: 2, ok: false });
    expect(state.error.get('h1')).toBe(true);
    // Last-known data is retained.
    expect(buildOverview(state).map(s => s.sessionId)).toEqual([sid(1)]);
    // A later success clears the error flag.
    state = beginRefresh(state, 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 3,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'a',
          kind: 'shell',
          lastActivity: 11,
          running: true,
        },
      ],
    });
    expect(state.error.has('h1')).toBe(false);
  });

  it('one failing host does not block another', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    state = beginRefresh(state, 'h2').state;
    state = applyRefresh(state, { hostId: 'h1', generation: 1, ok: false });
    state = applyRefresh(state, {
      hostId: 'h2',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(7),
          title: 'b',
          kind: 'shell',
          lastActivity: 5,
          running: true,
        },
      ],
    });
    expect(buildOverview(state).map(s => [s.hostId, s.sessionId])).toEqual([
      ['h2', sid(7)],
    ]);
  });

  it('tracks loading state per host', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    expect(state.loading.get('h1')).toBe(true);
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [],
    });
    expect(state.loading.get('h1')).toBe(false);
  });
});

describe('overview untrusted input', () => {
  it('sanitizes previews and bounds titles', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'x'.repeat(500),
          kind: 'k'.repeat(100),
          lastActivity: 1,
          running: true,
          preview: '\u001b]0;spoof\u0007\u001b[31mred\u001b[0m',
        },
      ],
    });
    const row = buildOverview(state)[0];
    expect(row.title).toHaveLength(120);
    expect(row.kind).toHaveLength(32);
    expect(row.preview).toBe('red');
  });

  it('dedupes duplicate session rows from a hostile daemon', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: sid(1),
          title: 'a',
          kind: 'shell',
          lastActivity: 1,
          running: true,
        },
        {
          sessionId: sid(1),
          title: 'a',
          kind: 'shell',
          lastActivity: 2,
          running: true,
        },
      ],
    });
    expect(buildOverview(state)).toHaveLength(1);
  });

  it('skips malformed rows without dropping the rest', () => {
    let state = beginRefresh(createOverview(), 'h1').state;
    state = applyRefresh(state, {
      hostId: 'h1',
      generation: 1,
      ok: true,
      sessions: [
        {
          sessionId: '',
          title: 'bad',
          kind: 'shell',
          lastActivity: 1,
          running: true,
        },
        {
          sessionId: sid(1),
          title: 'good',
          kind: 'shell',
          lastActivity: 2,
          running: true,
        },
      ],
    });
    expect(buildOverview(state).map(s => s.sessionId)).toEqual([sid(1)]);
  });
});

describe('relativeAge', () => {
  const now = 1_700_000_000;
  it('renders short ages in minutes, hours, and days', () => {
    expect(relativeAge(now - 10, now)).toBe('just now');
    expect(relativeAge(now - 5 * 60, now)).toBe('5m ago');
    expect(relativeAge(now - 2 * 60 * 60, now)).toBe('2h ago');
    expect(relativeAge(now - 3 * 24 * 60 * 60, now)).toBe('3d ago');
  });

  it('shows a future or ancient timestamp as vague', () => {
    // A daemon clock slightly ahead of the app renders as current; a wrong
    // clock far in the future or an ancient timestamp reads as vague.
    expect(relativeAge(now + 60, now)).toBe('just now');
    expect(relativeAge(now - 40 * 24 * 60 * 60, now)).toBe('a while ago');
    expect(relativeAge(NaN, now)).toBe('a while ago');
  });
});
