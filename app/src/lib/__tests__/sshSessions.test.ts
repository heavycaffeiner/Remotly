import {
  attachSshSink,
  closeSshHost,
  closeSshTab,
  openSshTab,
  renameSshTab,
  resizeSshHost,
  selectSshTab,
  sshHostSized,
  sshHostStarted,
  sshHostState,
  subscribeSshHost,
} from '../sshSessions';
import { remotlySsh } from '../ssh';
import terminalStoreModule from '../../specs/NativeRemotlyTerminalStore';
import { decodeBase64 } from '../base64';

const terminalStore = terminalStoreModule as unknown as {
  feed: jest.Mock;
  has: jest.Mock;
};

/** Lets the queued writes settle; the drain is asynchronous. */
async function flushMicrotasks(): Promise<void> {
  for (let i = 0; i < 20; i += 1) await Promise.resolve();
}

// Untyped inside the factory on purpose: jest hoists it above the imports and
// rejects any reference out of its own scope, which includes a type name.
// The typed view of it is `ssh` below.
// The store writes into a session's own terminal when no view is attached.
// The fake records what it was handed so the tests can assert nothing is lost.
jest.mock('../../specs/NativeRemotlyTerminalStore', () => {
  const written = new Map();
  return {
    __esModule: true,
    default: {
      feed: jest.fn(async (sessionId, data) => {
        const prev = written.get(sessionId) ?? [];
        prev.push(data);
        written.set(sessionId, prev);
        return true;
      }),
      has: jest.fn(async () => true),
    },
    __written: written,
  };
});

jest.mock('../ssh', () => {
  // Untyped inside the factory on purpose: jest hoists it above the imports
  // and rejects any name from outside its own scope, which a type annotation
  // in a generic argument also trips. The typed view of it is `ssh` below.
  const handlers = new Map();
  return {
    __esModule: true,
    stageMessage: () => null,
    remotlySsh: {
      connect: jest.fn().mockResolvedValue(undefined),
      write: jest.fn().mockResolvedValue(undefined),
      resize: jest.fn().mockResolvedValue(undefined),
      hostKey: jest.fn().mockResolvedValue(undefined),
      close: jest.fn().mockResolvedValue(undefined),
      closeHost: jest.fn().mockResolvedValue(undefined),
      onState: jest.fn(() => () => undefined),
      onData: jest.fn((h: unknown, s: unknown, handler: unknown) => {
        handlers.set(`${h}:${s}`, handler);
        return () => handlers.delete(`${h}:${s}`);
      }),
      __emit: (h: unknown, s: unknown, payload: unknown, fastPath = false) => {
        handlers.get(`${h}:${s}`)?.(payload, fastPath);
      },
    },
  };
});

type MockSsh = typeof remotlySsh & {
  __emit: (
    hostId: string,
    sessionId: string,
    bytes: Uint8Array,
    fastPath?: boolean,
  ) => void;
};
const ssh = remotlySsh as MockSsh;

let host = 0;
function freshHost(): string {
  host += 1;
  return `h${host}`;
}

beforeEach(() => {
  jest.clearAllMocks();
});

describe('session lifetime', () => {
  // The regression this store exists for: a screen unmounting on back must not
  // take the shell with it.
  it('keeps sessions after the renderer detaches', () => {
    const id = freshHost();
    const detach = attachSshSink(id, () => undefined);
    openSshTab(id);
    expect(sshHostState(id).tabs).toHaveLength(1);

    detach();

    expect(sshHostState(id).tabs).toHaveLength(1);
    expect(ssh.close).not.toHaveBeenCalled();
  });

  it('does not reopen a tab for a host that already has one', () => {
    const id = freshHost();
    openSshTab(id);
    expect(sshHostStarted(id)).toBe(true);
    expect(sshHostState(id).tabs).toHaveLength(1);
  });

  it('closes exactly the tab that was asked for', () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    const [first, second] = sshHostState(id).tabs;

    closeSshTab(id, first.sessionId);

    expect(ssh.close).toHaveBeenCalledWith(id, first.sessionId);
    expect(sshHostState(id).tabs.map(t => t.sessionId)).toEqual([
      second.sessionId,
    ]);
  });

  it('closes every tab when the host disconnects', () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);

    closeSshHost(id);

    expect(ssh.close).toHaveBeenCalledTimes(2);
    expect(sshHostState(id).tabs).toHaveLength(0);
  });
});

describe('output routing', () => {
  it('delivers the active tab output to the attached renderer', () => {
    const id = freshHost();
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    openSshTab(id);
    const [tab] = sshHostState(id).tabs;

    ssh.__emit(id, tab.sessionId, Uint8Array.from([1, 2]));

    expect(seen).toEqual([1, 2]);
  });

  /**
   * The native side writes the bytes into the terminal itself and reports the
   * event with no payload. Writing again from here would double every
   * character on screen.
   */
  it('does not write fast-path output a second time', () => {
    const id = freshHost();
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    openSshTab(id);
    const [tab] = sshHostState(id).tabs;

    ssh.__emit(id, tab.sessionId, new Uint8Array(0), true);

    expect(seen).toEqual([]);
  });

  /**
   * A fast-path event must not queue either. Queuing an empty chunk would
   * hand the next slow-path block to the drain instead of the sink, which is
   * the round trip the fast path exists to avoid.
   */
  it('keeps delivering to the renderer after a fast-path event', () => {
    const id = freshHost();
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    openSshTab(id);
    const [tab] = sshHostState(id).tabs;

    ssh.__emit(id, tab.sessionId, new Uint8Array(0), true);
    ssh.__emit(id, tab.sessionId, Uint8Array.from([7]));

    expect(seen).toEqual([7]);
    expect(terminalStore.feed).not.toHaveBeenCalled();
  });

  // Output that arrives while the user is elsewhere has to survive, or coming
  // back shows a terminal missing everything that happened.
  // Output that arrives with no renderer attached goes into the session's own
  // terminal, which owns the scrollback. Nothing is dropped and nothing waits
  // for a view to come back.
  it('writes detached output into the session terminal', async () => {
    const id = freshHost();
    const detach = attachSshSink(id, () => undefined);
    openSshTab(id);
    const [tab] = sshHostState(id).tabs;
    detach();

    ssh.__emit(id, tab.sessionId, Uint8Array.from([7]));
    await flushMicrotasks();

    expect(terminalStore.feed).toHaveBeenCalledWith(
      tab.sessionId,
      expect.any(String),
      expect.any(Number),
      expect.any(Number),
    );
  });

  /**
   * The guarantee this app is built on: output is never discarded, however
   * much of it arrives while the tab is off screen.
   */
  it('keeps every byte of a large detached burst', async () => {
    const id = freshHost();
    const detach = attachSshSink(id, () => undefined);
    openSshTab(id);
    const [tab] = sshHostState(id).tabs;
    detach();

    // Comfortably past the old 256KB cap, which discarded the oldest output.
    const chunk = new Uint8Array(64 * 1024).fill(9);
    for (let i = 0; i < 8; i += 1) ssh.__emit(id, tab.sessionId, chunk);
    await flushMicrotasks();

    const sent = terminalStore.feed.mock.calls
      .filter(c => c[0] === tab.sessionId)
      .reduce((n, c) => n + decodeBase64(c[1] as string).length, 0);
    expect(sent).toBe(8 * chunk.length);
  });

  it('does not send a background tab output to the renderer', () => {
    const id = freshHost();
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    openSshTab(id);
    openSshTab(id);
    const [background, active] = sshHostState(id).tabs;
    expect(sshHostState(id).activeSessionId).toBe(active.sessionId);

    ssh.__emit(id, background.sessionId, Uint8Array.from([9]));

    expect(seen).toEqual([]);
  });

  // Selecting must not write through the sink that is still attached: it
  // belongs to the tab being left, and its terminal is about to be replaced.
  // Writing there put the new tab's output into the old tab's screen.
  it('does not write into the outgoing tab on select', () => {
    const id = freshHost();
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    openSshTab(id);
    openSshTab(id);
    const [first] = sshHostState(id).tabs;

    ssh.__emit(id, first.sessionId, Uint8Array.from([5]));
    selectSshTab(id, first.sessionId);

    expect(seen).toEqual([]);
  });

  it('writes a background tab output into its own terminal', async () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    const [first] = sshHostState(id).tabs;

    ssh.__emit(id, first.sessionId, Uint8Array.from([5]));
    await flushMicrotasks();

    // It went to the hidden tab's terminal, not to whatever is on screen.
    expect(terminalStore.feed).toHaveBeenCalledWith(
      first.sessionId,
      expect.any(String),
      expect.any(Number),
      expect.any(Number),
    );
  });

  /**
   * Order is the whole point of the single queue. Output that arrives while a
   * tab is being switched must not overtake output already queued for it:
   * both end up in the same terminal, and a later chunk landing first paints
   * the newest text and then buries it under the older text.
   */
  it('keeps chunks in order across a tab switch', async () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    const [first] = sshHostState(id).tabs;

    // Hold the first write open, so it is still in flight when the tab is
    // switched and the next chunk arrives. Without this the race cannot
    // happen and the test proves nothing.
    let releaseWrite = (): void => {};
    terminalStore.feed.mockImplementationOnce(
      () =>
        new Promise(resolve => {
          releaseWrite = () => resolve(true);
        }),
    );

    ssh.__emit(id, first.sessionId, Uint8Array.from([1]));
    await flushMicrotasks();

    // The tab becomes active and a view attaches while chunk 1 is unfinished.
    selectSshTab(id, first.sessionId);
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    ssh.__emit(id, first.sessionId, Uint8Array.from([2]));
    await flushMicrotasks();

    // Chunk 2 must not reach the screen before chunk 1 has been written.
    expect(seen).toEqual([]);

    releaseWrite();
    await flushMicrotasks();
    expect(seen).toEqual([2]);
  });

  /**
   * Numbering by how many tabs are open reuses a number the moment one is
   * closed, so two tabs share a name and the strip looks like it skipped one.
   */
  it('does not reuse a shell number after a tab is closed', () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    openSshTab(id);
    const titles = () => sshHostState(id).tabs.map(t => t.title);
    expect(titles()).toEqual(['Shell 1', 'Shell 2', 'Shell 3']);

    const [, second] = sshHostState(id).tabs;
    closeSshTab(id, second.sessionId);
    openSshTab(id);

    const now = titles();
    expect(new Set(now).size).toBe(now.length);
    expect(now).toContain('Shell 2');
  });

  /**
   * A write that did not happen must still reach the tab. Leaving it queued
   * until the next output arrives is what made a tab render late by exactly
   * the amount of output it missed while it was hidden.
   */
  it('delivers a block through the view when its write failed', async () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    const [first] = sshHostState(id).tabs;

    // The hidden tab's write fails: nothing reached any terminal.
    let releaseWrite: (ok: boolean) => void = () => {};
    terminalStore.feed.mockImplementationOnce(
      () =>
        new Promise<boolean>(resolve => {
          releaseWrite = resolve;
        }),
    );

    ssh.__emit(id, first.sessionId, Uint8Array.from([1]));
    await flushMicrotasks();

    // The tab becomes active while the write is still unresolved.
    selectSshTab(id, first.sessionId);
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));

    // The write reports failure; the block must go to the view, not wait.
    releaseWrite(false);
    await flushMicrotasks();

    expect(seen).toEqual([1]);
  });

  /** Switching tabs must not replay what the terminal already holds. */
  it('does not resend terminal output when a tab is selected', async () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    const [first] = sshHostState(id).tabs;

    ssh.__emit(id, first.sessionId, Uint8Array.from([5]));
    await flushMicrotasks();

    selectSshTab(id, first.sessionId);
    const seen: number[] = [];
    attachSshSink(id, b => seen.push(...b));
    expect(seen).toEqual([]);
  });
});

describe('sizing', () => {
  it('is unsized until the viewport reports a grid', () => {
    const id = freshHost();
    expect(sshHostSized(id)).toBe(false);
    resizeSshHost(id, { cols: 100, rows: 40 });
    expect(sshHostSized(id)).toBe(true);
  });

  it('ignores a zero-sized report', () => {
    const id = freshHost();
    resizeSshHost(id, { cols: 0, rows: 0 });
    expect(sshHostSized(id)).toBe(false);
  });

  // The first measurement only records the size: nothing is connected yet, so
  // sending a resize would be a call against a session that does not exist.
  it('does not resize on the first measurement', () => {
    const id = freshHost();
    resizeSshHost(id, { cols: 100, rows: 40 });
    expect(ssh.resize).not.toHaveBeenCalled();
  });

  // The reason this exists: a session opened against the 80x24 placeholder has
  // a PTY whose row count does not match the screen, and an application that
  // draws with absolute cursor moves puts its overlay in the wrong place.
  it('connects with the measured grid, not the placeholder', () => {
    const id = freshHost();
    resizeSshHost(id, { cols: 100, rows: 40 });
    openSshTab(id);

    expect(ssh.connect).toHaveBeenCalledWith(
      id,
      sshHostState(id).tabs[0].sessionId,
      100,
      40,
    );
  });

  it('resizes every open tab once sized', () => {
    const id = freshHost();
    resizeSshHost(id, { cols: 100, rows: 40 });
    openSshTab(id);
    openSshTab(id);
    (ssh.resize as jest.Mock).mockClear();

    resizeSshHost(id, { cols: 90, rows: 30 });

    expect(ssh.resize).toHaveBeenCalledTimes(2);
  });
});

describe('renaming', () => {
  it('renames the requested tab only', () => {
    const id = freshHost();
    openSshTab(id);
    openSshTab(id);
    const [first, second] = sshHostState(id).tabs;

    renameSshTab(id, first.sessionId, 'build');

    const tabs = sshHostState(id).tabs;
    expect(tabs[0].title).toBe('build');
    expect(tabs[1].title).toBe(second.title);
  });

  it('ignores a blank name', () => {
    const id = freshHost();
    openSshTab(id);
    const before = sshHostState(id).tabs[0].title;

    renameSshTab(id, sshHostState(id).tabs[0].sessionId, '  ');

    expect(sshHostState(id).tabs[0].title).toBe(before);
  });

  it('notifies subscribers', () => {
    const id = freshHost();
    openSshTab(id);
    const listener = jest.fn();
    subscribeSshHost(id, listener);

    renameSshTab(id, sshHostState(id).tabs[0].sessionId, 'renamed');

    expect(listener).toHaveBeenCalled();
  });
});

describe('subscription', () => {
  it('notifies a listener when tabs change', () => {
    const id = freshHost();
    const listener = jest.fn();
    const off = subscribeSshHost(id, listener);

    openSshTab(id);
    expect(listener).toHaveBeenCalled();

    off();
    listener.mockClear();
    openSshTab(id);
    expect(listener).not.toHaveBeenCalled();
  });
});
