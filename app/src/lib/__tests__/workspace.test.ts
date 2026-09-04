import { describe, expect, it } from '@jest/globals';
import {
  MAX_TABS,
  advanceCursor,
  addTab,
  adoptSessions,
  applySessionMeta,
  closeTab,
  createWorkspace,
  findTab,
  markAttached,
  markExited,
  parseWorkspace,
  reconcile,
  resumeCursor,
  setActive,
  serializeWorkspace,
  setCursor,
} from '../workspace';

const HOST = 'host-abc';

// Session ids are daemon-minted 64-hex-character strings.
function sid(n: number): string {
  return n.toString(16).padStart(64, '0');
}
const ID1 = sid(1);
const ID2 = sid(2);
const ID3 = sid(3);

function wsWithTabs(n: number) {
  let ws = createWorkspace(HOST);
  for (let i = 1; i <= n; i++) {
    const r = addTab(ws, {
      sessionId: sid(i),
      title: `tab ${i}`,
      kind: 'shell',
      running: true,
    });
    expect(r.tab).not.toBeNull();
    ws = r.state;
  }
  return ws;
}

describe('workspace tab state', () => {
  it('starts empty with no active tab', () => {
    const ws = createWorkspace(HOST);
    expect(ws.tabs).toEqual([]);
    expect(ws.activeSessionId).toBeNull();
  });

  it('adds a tab and makes it active', () => {
    const ws = createWorkspace(HOST);
    const r = addTab(ws, {
      sessionId: ID1,
      title: 'sh',
      kind: 'shell',
      running: true,
    });
    expect(r.state.tabs).toHaveLength(1);
    expect(r.state.activeSessionId).toBe(ID1);
    expect(r.tab!.state).toBe('attaching');
    expect(r.tab!.cursor).toBe(0);
  });

  it('adds an exited session as exited', () => {
    const r = addTab(createWorkspace(HOST), {
      sessionId: ID3,
      title: 'old',
      kind: 'shell',
      running: false,
    });
    expect(r.tab!.state).toBe('exited');
  });

  it('rejects a malformed session id', () => {
    const ws = createWorkspace(HOST);
    const r = addTab(ws, {
      sessionId: 'nope',
      title: 'sh',
      kind: 'shell',
      running: true,
    });
    expect(r.tab).toBeNull();
    expect(r.state).toBe(ws);
  });

  it('dedupes a duplicate open request', () => {
    const ws = createWorkspace(HOST);
    const first = addTab(ws, {
      sessionId: ID2,
      title: 'one',
      kind: 'shell',
      running: true,
    });
    const second = addTab(
      first.state,
      { sessionId: ID2, title: 'two', kind: 'shell', running: true },
      99,
    );
    expect(second.state).toBe(first.state);
    expect(second.tab).toBe(first.tab);
    expect(second.tab!.title).toBe('one');
    expect(second.tab!.cursor).toBe(0);
  });

  it('rejects adding beyond the tab cap', () => {
    let ws = createWorkspace(HOST);
    for (let i = 1; i <= MAX_TABS; i++) {
      ws = addTab(ws, {
        sessionId: sid(i),
        title: `t${i}`,
        kind: 'shell',
        running: true,
      }).state;
    }
    const before = ws;
    const over = addTab(before, {
      sessionId: sid(999),
      title: 'nope',
      kind: 'shell',
      running: true,
    });
    expect(over.state).toBe(before);
    expect(over.tab).toBeNull();
    expect(findTab(ws, sid(999))).toBeNull();
  });

  it('bounds titles and kind', () => {
    const r = addTab(createWorkspace(HOST), {
      sessionId: ID1,
      title: 'x'.repeat(500),
      kind: 'k'.repeat(100),
      running: true,
    });
    expect(r.tab!.title).toHaveLength(120);
    expect(r.tab!.kind).toHaveLength(32);
  });

  it('marks a tab attached', () => {
    const { state } = addTab(createWorkspace(HOST), {
      sessionId: ID1,
      title: 'sh',
      kind: 'shell',
      running: true,
    });
    const attached = markAttached(state, ID1);
    expect(findTab(attached, ID1)?.state).toBe('attached');
    // Idempotent and a no-op for unknown sessions.
    expect(markAttached(attached, ID1)).toBe(attached);
    expect(markAttached(attached, sid(42))).toBe(attached);
  });

  it('closes a tab and moves activity when the active one is removed', () => {
    const ws = wsWithTabs(3);
    expect(ws.activeSessionId).toBe(ID1);
    const closed = closeTab(ws, ID2);
    expect(closed.tabs.map(t => t.sessionId)).toEqual([ID1, ID3]);
    expect(closed.activeSessionId).toBe(ID1);
    // Closing the active tab moves activity to the last remaining tab.
    const closedActive = closeTab(ws, ID1);
    expect(closedActive.tabs.map(t => t.sessionId)).toEqual([ID2, ID3]);
    expect(closedActive.activeSessionId).toBe(ID3);
    // Closing the last tab clears activity.
    let one = closeTab(closedActive, ID2);
    one = closeTab(one, ID3);
    expect(one.activeSessionId).toBeNull();
    // Closing an unknown tab is a no-op.
    expect(closeTab(ws, sid(99))).toBe(ws);
  });

  it('sets the active tab and ignores unknown ids', () => {
    const ws = wsWithTabs(2);
    const next = setActive(ws, ID1);
    expect(next.activeSessionId).toBe(ID1);
    expect(setActive(ws, ID1)).toBe(ws);
    expect(setActive(ws, sid(99))).toBe(ws);
  });

  it('advances the replay cursor and clamps it', () => {
    const { state } = addTab(
      createWorkspace(HOST),
      {
        sessionId: ID1,
        title: 'sh',
        kind: 'shell',
        running: true,
      },
      100,
    );
    const adv = advanceCursor(state, ID1, 50);
    expect(findTab(adv, ID1)?.cursor).toBe(150);
    expect(advanceCursor(adv, ID1, 0)).toBe(adv);
    expect(advanceCursor(adv, ID1, -5)).toBe(adv);
    expect(advanceCursor(adv, sid(99), 5)).toBe(adv);
    // Cursor is clamped to the JS-safe bound.
    const huge = advanceCursor(adv, ID1, 2 ** 60);
    expect(findTab(huge, ID1)?.cursor).toBe(2 ** 53 - 1);
  });

  it('sets an exact cursor, clamped, and bails out when unchanged', () => {
    let ws = createWorkspace(HOST);
    const { state } = addTab(
      ws,
      { sessionId: ID1, title: 'sh', kind: 'shell', running: true },
      0,
    );
    ws = state;
    const set = setCursor(ws, ID1, 4096);
    expect(findTab(set, ID1)?.cursor).toBe(4096);
    expect(setCursor(set, ID1, 4096)).toBe(set);
    expect(setCursor(set, ID1, -1)).toBe(set);
    expect(findTab(setCursor(set, ID1, 2 ** 60), ID1)?.cursor).toBe(
      2 ** 53 - 1,
    );
    expect(setCursor(set, sid(99), 10)).toBe(set);
  });

  it('applies session metadata and sanitizes the preview', () => {
    const { state } = addTab(createWorkspace(HOST), {
      sessionId: ID1,
      title: 'sh',
      kind: 'shell',
      running: true,
    });
    const next = applySessionMeta(state, {
      sessionId: ID1,
      title: 'renamed',
      preview: '\u001b[31mboom\u001b[0m',
    });
    const tab = findTab(next, ID1);
    expect(tab?.title).toBe('renamed');
    expect(tab?.preview).toBe('boom');
    expect(applySessionMeta(state, { sessionId: sid(99), title: 'x' })).toBe(
      state,
    );
  });

  it('reconciles live sessions without touching cursors', () => {
    const { state } = addTab(
      createWorkspace(HOST),
      {
        sessionId: ID1,
        title: 'old',
        kind: 'shell',
        running: true,
      },
      42,
    );
    const next = markAttached(state, ID1);
    const reconciled = reconcile(next, [
      { sessionId: ID1, title: 'new', running: true, preview: 'last line' },
    ]);
    const tab = findTab(reconciled, ID1);
    expect(tab?.title).toBe('new');
    expect(tab?.cursor).toBe(42);
    expect(tab?.state).toBe('attached');
    expect(tab?.preview).toBe('last line');
  });

  it('marks missing sessions stale and exited sessions exited', () => {
    let ws = createWorkspace(HOST);
    ws = addTab(ws, {
      sessionId: ID1,
      title: 'a',
      kind: 'shell',
      running: true,
    }).state;
    ws = addTab(ws, {
      sessionId: ID2,
      title: 'b',
      kind: 'shell',
      running: true,
    }).state;
    const reconciled = reconcile(ws, [
      { sessionId: ID2, title: 'b', running: false },
    ]);
    expect(findTab(reconciled, ID1)?.state).toBe('stale');
    expect(findTab(reconciled, ID2)?.state).toBe('exited');
    // Reconciling again is stable.
    expect(
      reconcile(reconciled, [{ sessionId: ID2, title: 'b', running: false }]),
    ).toBe(reconciled);
  });

  it('reconcile does not add daemon sessions the user never opened', () => {
    const ws = createWorkspace(HOST);
    const reconciled = reconcile(ws, [
      { sessionId: sid(9), title: 'x', running: true },
    ]);
    expect(reconciled.tabs).toEqual([]);
  });

  it('marks a session exited with its code', () => {
    const { state } = addTab(createWorkspace(HOST), {
      sessionId: ID1,
      title: 'sh',
      kind: 'shell',
      running: true,
    });
    const exited = markExited(state, ID1, 2);
    expect(findTab(exited, ID1)?.state).toBe('exited');
    expect(findTab(exited, ID1)?.exitCode).toBe(2);
    expect(markExited(state, sid(99), 1)).toBe(state);
  });

  it('round-trips through serialize/parse', () => {
    let ws = createWorkspace(HOST);
    ws = addTab(
      ws,
      { sessionId: ID1, title: 'a', kind: 'shell', running: true },
      10,
    ).state;
    ws = addTab(ws, {
      sessionId: ID2,
      title: 'b',
      kind: 'agent',
      running: true,
    }).state;
    ws = markAttached(ws, ID1);
    ws = markExited(ws, ID2, 1);
    ws = setActive(ws, ID2);

    const parsed = parseWorkspace(serializeWorkspace(ws), HOST);
    expect(parsed).not.toBeNull();
    expect(parsed!.tabs.map(t => t.sessionId)).toEqual([ID1, ID2]);
    expect(parsed!.activeSessionId).toBe(ID2);
    expect(findTab(parsed!, ID1)?.cursor).toBe(10);
    expect(findTab(parsed!, ID1)?.state).toBe('attached');
    expect(findTab(parsed!, ID2)?.state).toBe('exited');
    expect(findTab(parsed!, ID2)?.exitCode).toBe(1);
  });

  it('parse rejects malformed records', () => {
    expect(parseWorkspace('not json', HOST)).toBeNull();
    expect(
      parseWorkspace('{"v":2,"hostId":"' + HOST + '","tabs":[]}', HOST),
    ).toBeNull();
    expect(
      parseWorkspace('{"v":1,"hostId":"other","tabs":[]}', HOST),
    ).toBeNull();
    expect(
      parseWorkspace('{"v":1,"hostId":"' + HOST + '","tabs":"nope"}', HOST),
    ).toBeNull();
    expect(
      parseWorkspace(
        '{"v":1,"hostId":"' + HOST + '","tabs":[{"sessionId":"nope"}]}',
        HOST,
      ),
    ).toBeNull();
    expect(
      parseWorkspace(
        '{"v":1,"hostId":"' + HOST + '","tabs":[{"sessionId":7}]}',
        HOST,
      ),
    ).toBeNull();
    expect(
      parseWorkspace(
        '{"v":1,"hostId":"' +
          HOST +
          '","tabs":[{"sessionId":"' +
          ID1 +
          '"},{"sessionId":"' +
          ID1 +
          '"}]}',
        HOST,
      ),
    ).toBeNull();
    expect(
      parseWorkspace(
        '{"v":1,"hostId":"' +
          HOST +
          '","tabs":[{"sessionId":"' +
          ID1 +
          '","state":"bogus"}]}',
        HOST,
      ),
    ).toBeNull();
  });

  it('parse sanitizes stored previews and clamps cursors', () => {
    const json =
      '{"v":1,"hostId":"' +
      HOST +
      '","tabs":[{"sessionId":"' +
      ID1 +
      '","title":"t","kind":"shell","cursor":1e20,"state":"attached","preview":"\\u001b[31mred\\u001b[0m"}]}';
    const parsed = parseWorkspace(json, HOST)!;
    expect(parsed).not.toBeNull();
    expect(findTab(parsed!, ID1)?.cursor).toBe(2 ** 53 - 1);
    expect(findTab(parsed!, ID1)?.preview).toBe('red');
  });

  it('parse falls back to the first tab when the active id is unknown', () => {
    const json =
      '{"v":1,"hostId":"' +
      HOST +
      '","activeSessionId":"' +
      sid(99) +
      '","tabs":[{"sessionId":"' +
      ID1 +
      '","title":"t","kind":"shell","cursor":0,"state":"attached"}]}';
    const parsed = parseWorkspace(json, HOST)!;
    expect(parsed.activeSessionId).toBe(ID1);
  });
});

/**
 * A device with no stored workspace has to find the daemon's live sessions.
 *
 * reconcile only ever updates tabs that already exist, so without adoption a
 * fresh pairing (or a stored document the parser rejected) showed an empty
 * strip while the daemon held several running sessions: they were unreachable
 * from the app, and the only way to get a tab was to create another session.
 */
describe('adoptSessions', () => {
  function remote(n: number, running = true) {
    return { sessionId: sid(n), title: `tab ${n}`, kind: 'shell', running };
  }

  it('creates a tab for every running session', () => {
    const ws = adoptSessions(createWorkspace(HOST), [
      remote(1),
      remote(2),
      remote(3),
    ]);

    expect(ws.tabs.map(t => t.sessionId)).toEqual([ID1, ID2, ID3]);
    // The first adopted session is what the screen attaches to.
    expect(ws.activeSessionId).toBe(ID1);
  });

  it('leaves exited sessions alone', () => {
    const ws = adoptSessions(createWorkspace(HOST), [
      remote(1),
      remote(2, false),
    ]);

    expect(ws.tabs.map(t => t.sessionId)).toEqual([ID1]);
  });

  // Reconnects run this every time, so it must not duplicate what is open.
  it('keeps existing tabs and their cursors', () => {
    const open = setCursor(wsWithTabs(1), ID1, 4096);
    const ws = adoptSessions(open, [remote(1), remote(2)]);

    expect(ws.tabs).toHaveLength(2);
    expect(findTab(ws, ID1)?.cursor).toBe(4096);
  });

  it('stops at the tab cap', () => {
    const many = Array.from({ length: MAX_TABS + 5 }, (_, i) => remote(i + 1));
    const ws = adoptSessions(createWorkspace(HOST), many);

    expect(ws.tabs).toHaveLength(MAX_TABS);
  });
});

/**
 * The cursor counts output held in a terminal that does not survive the
 * process, while the cursor itself is written to disk. Resuming from a cursor
 * with no terminal behind it makes the daemon replay nothing into an empty
 * screen, which is scrollback silently lost on every cold start.
 */
describe('resumeCursor', () => {
  it('resumes where a terminal still holds the output', () => {
    expect(resumeCursor(4096, true)).toBe(4096);
  });

  // The case that loses history: a saved cursor after the terminal is gone.
  it('replays everything when no terminal holds the history', () => {
    expect(resumeCursor(4096, false)).toBeUndefined();
  });

  // Nothing has been consumed, so there is nothing to resume from.
  it('replays everything from a zero cursor', () => {
    expect(resumeCursor(0, true)).toBeUndefined();
  });

  it('rejects a cursor that is not a usable number', () => {
    expect(resumeCursor(Number.NaN, true)).toBeUndefined();
    expect(resumeCursor(-1, true)).toBeUndefined();
  });
});
