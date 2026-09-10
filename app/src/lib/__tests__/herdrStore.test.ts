/**
 * @format
 */

// The store that keeps a host's herdr state current. What matters here is that
// a pushed event changes the state without another read, that a line the app
// cannot apply exactly asks for a snapshot instead of guessing, and that a
// reader which never acknowledges leaves the host on the fallback re-read.

import { AppState } from 'react-native';
import NativeHerdr from '../../specs/NativeRemotlyHerdr';
import type {
  HerdrLineEvent,
  HerdrStreamEndEvent,
} from '../../specs/NativeRemotlyHerdr';
import { encodeBase64String } from '../base64';
import { eventStreamCommand, parseHerdrEvent } from '../herdr';
import {
  herdrHostState,
  refreshHerdrHost,
  subscribeHerdrHost,
} from '../herdrStore';

const exec = NativeHerdr.exec as jest.MockedFunction<typeof NativeHerdr.exec>;
const subscribe = NativeHerdr.subscribe as jest.MockedFunction<
  typeof NativeHerdr.subscribe
>;
// The emitters return a full EventSubscription, of which the store only calls
// remove, so these two are held as plain mocks rather than restating it.
const onLine = NativeHerdr.onLine as unknown as jest.Mock;
const onStreamEnd = NativeHerdr.onStreamEnd as unknown as jest.Mock;

const SNAPSHOT = JSON.stringify({
  result: {
    snapshot: {
      focused_workspace_id: 'w1',
      focused_tab_id: 'w1:t1',
      workspaces: [
        {
          workspace_id: 'w1',
          label: 'api',
          number: 1,
          tab_count: 1,
          pane_count: 1,
          active_tab_id: 'w1:t1',
          focused: true,
          agent_status: 'unknown',
        },
        {
          workspace_id: 'w2',
          label: 'deploy',
          number: 2,
          tab_count: 0,
          pane_count: 0,
          active_tab_id: null,
          focused: false,
          agent_status: 'unknown',
        },
      ],
      tabs: [
        {
          tab_id: 'w1:t1',
          workspace_id: 'w1',
          label: 'shell',
          number: 1,
          pane_count: 1,
          focused: true,
          agent_status: 'unknown',
        },
      ],
      panes: [],
    },
  },
});

const SESSIONS = JSON.stringify({
  sessions: [
    {
      name: 'default',
      default: true,
      running: true,
      session_dir: '/home/dev/.config/herdr',
      socket_path: '/home/dev/.config/herdr/herdr.sock',
    },
  ],
});

/** The line handler the store installed, so a test can push events at it. */
let pushLine: (event: HerdrLineEvent) => void = () => undefined;
let endStream: (event: HerdrStreamEndEvent) => void = () => undefined;

function answer(command: string): string {
  const asked = command.replace(/'/g, '');
  if (asked.includes('api snapshot')) return SNAPSHOT;
  if (asked.includes('session list')) return SESSIONS;
  return '';
}

/** Every argument is shell-quoted, so count against the unquoted form. */
function snapshotReads(): number {
  return exec.mock.calls.filter(c =>
    c[1].replace(/'/g, '').includes('api snapshot'),
  ).length;
}

beforeEach(() => {
  exec.mockReset();
  subscribe.mockReset();
  exec.mockImplementation(async (_hostId: string, command: string) => ({
    ok: true,
    exitCode: 0,
    stdout: encodeBase64String(answer(command)),
    stderr: '',
    code: '',
    message: '',
  }));
  const subscription = { remove: (): void => undefined };
  onLine.mockImplementation(handler => {
    pushLine = handler as (event: HerdrLineEvent) => void;
    return subscription;
  });
  onStreamEnd.mockImplementation(handler => {
    endStream = handler as (event: HerdrStreamEndEvent) => void;
    return subscription;
  });
});

/** Yields to the event loop, which is what the store's reads settle on. */
function tick(): Promise<void> {
  const { promise, resolve } = Promise.withResolvers<void>();
  setImmediate(resolve);
  return promise;
}

/** Subscribes, waits for the bootstrap read and the stream, and returns stop. */
async function start(hostId: string): Promise<() => void> {
  const stop = subscribeHerdrHost(hostId, null, () => undefined);
  for (let i = 0; i < 20; i += 1) {
    if (
      subscribe.mock.calls.length > 0 &&
      herdrHostState(hostId, null).loaded
    ) {
      break;
    }
    await tick();
  }
  return stop;
}

describe('a host kept current by events', () => {
  it('installs the snapshot it bootstrapped from', async () => {
    const stop = await start('h1');
    const state = herdrHostState('h1', null);
    expect(state.workspaces.map(w => w.label)).toEqual(['api', 'deploy']);
    expect(state.tabs.w1?.map(t => t.label)).toEqual(['shell']);
    expect(state.focusedTabId).toBe('w1:t1');
    stop();
  });

  it('follows a pushed focus without reading again', async () => {
    const stop = await start('h2');
    const reads = snapshotReads();

    pushLine({
      hostId: 'h2',
      line: '{"result":{"type":"subscription_started"}}',
    });
    pushLine({
      hostId: 'h2',
      line: '{"event":"workspace_focused","data":{"type":"workspace_focused","workspace_id":"w2"}}',
    });

    expect(herdrHostState('h2', null).focusedWorkspaceId).toBe('w2');
    expect(herdrHostState('h2', null).feed).toBe('live');
    expect(snapshotReads()).toBe(reads);
    stop();
  });

  // herdr publishes nothing for a move its own key bindings made, so a tab
  // switched by typing `prefix+n` reaches no event. Coming back to the app is
  // where that is caught up, rather than on a standing timer.
  it('re-reads every watched host on the way back to the foreground', async () => {
    const stop = await start('hA');
    const reads = snapshotReads();

    const listener = (AppState.addEventListener as jest.Mock).mock.calls.at(-1);
    expect(listener?.[0]).toBe('change');
    (listener?.[1] as (state: string) => void)('active');
    for (let i = 0; i < 5; i += 1) await tick();
    expect(snapshotReads()).toBe(reads + 1);
    stop();
  });

  // A tab switched by typing `prefix+n` inside herdr publishes nothing, so a
  // live host is re-read anyway while the app is in front. Without it the
  // strip keeps showing the tab the user just left.
  it('re-reads a live host while the app is in front', async () => {
    jest.useFakeTimers({ doNotFake: ['setImmediate'] });
    const stop = subscribeHerdrHost('hB', null, () => undefined);
    for (let i = 0; i < 10; i += 1) await tick();
    pushLine({
      hostId: 'hB',
      line: '{"result":{"type":"subscription_started"}}',
    });
    expect(herdrHostState('hB', null).feed).toBe('live');
    const reads = snapshotReads();

    jest.advanceTimersByTime(3000);
    for (let i = 0; i < 5; i += 1) await tick();

    expect(snapshotReads()).toBe(reads + 1);
    stop();
    jest.useRealTimers();
  });

  it('adds a created tab from the record the event carries', async () => {
    const stop = await start('h3');
    pushLine({
      hostId: 'h3',
      line: '{"result":{"type":"subscription_started"}}',
    });
    pushLine({
      hostId: 'h3',
      line: '{"event":"tab_created","data":{"type":"tab_created","tab":{"tab_id":"w2:t1","workspace_id":"w2","label":"fresh","number":1,"pane_count":1,"focused":false,"agent_status":"unknown"}}}',
    });

    expect(herdrHostState('h3', null).tabs.w2?.map(t => t.label)).toEqual([
      'fresh',
    ]);
    stop();
  });

  it('re-reads for a move, whose order it will not guess', async () => {
    const stop = await start('h4');
    pushLine({
      hostId: 'h4',
      line: '{"result":{"type":"subscription_started"}}',
    });
    const before = snapshotReads();

    pushLine({
      hostId: 'h4',
      line: '{"event":"tab_moved","data":{"type":"tab_moved","tab_id":"w1:t1","workspace_id":"w1"}}',
    });

    expect(snapshotReads()).toBe(before + 1);
    stop();
  });

  it('falls back to re-reading when the stream ends', async () => {
    const stop = await start('h5');
    pushLine({
      hostId: 'h5',
      line: '{"result":{"type":"subscription_started"}}',
    });
    expect(herdrHostState('h5', null).feed).toBe('live');

    endStream({ hostId: 'h5', code: 'ssh_remote_closed', message: 'gone' });

    expect(herdrHostState('h5', null).feed).toBe('polling');
    stop();
  });

  // A host answers nothing until its key is accepted, and the fallback has to
  // be the state a screen sees rather than an error.
  it('falls back when the host answers nothing at all', async () => {
    exec.mockImplementation(async () => ({
      ok: false,
      exitCode: 0,
      stdout: '',
      stderr: '',
      code: 'ssh_host_key_rejected',
      message: 'host key rejected',
    }));
    const stop = subscribeHerdrHost('h8', null, () => undefined);
    for (let i = 0; i < 10; i += 1) await tick();

    const state = herdrHostState('h8', null);
    expect(state.feed).toBe('polling');
    expect(state.error).not.toBeNull();
    expect(subscribe).not.toHaveBeenCalledWith('h8', expect.any(String));
    stop();
  });

  // The user accepts the key while the screen is open. Every failed attempt
  // has to leave the next one possible: the flag that says an attempt is in
  // flight was once left set, and the host then stayed on the timer for the
  // life of the screen.
  it('reaches the stream once the host starts answering', async () => {
    // The retry rides the poll loop, so the clock is driven rather than waited
    // on. setImmediate stays real: it is what the store's reads settle on here.
    jest.useFakeTimers({ doNotFake: ['setImmediate'] });
    exec.mockImplementation(async () => ({
      ok: false,
      exitCode: 0,
      stdout: '',
      stderr: '',
      code: 'ssh_host_key_rejected',
      message: 'host key rejected',
    }));
    const stop = subscribeHerdrHost('h9', null, () => undefined);
    for (let i = 0; i < 10; i += 1) await tick();
    expect(herdrHostState('h9', null).feed).toBe('polling');
    expect(subscribe).not.toHaveBeenCalled();

    // A retry made while the fallback loop is already running fails too. This
    // is the case that used to wedge: the attempt was never given back.
    for (let i = 0; i < 6; i += 1) {
      jest.advanceTimersByTime(4000);
      for (let n = 0; n < 5; n += 1) await tick();
    }

    // The key is accepted: the host answers from here on.
    exec.mockImplementation(async (_hostId: string, command: string) => ({
      ok: true,
      exitCode: 0,
      stdout: encodeBase64String(answer(command)),
      stderr: '',
      code: '',
      message: '',
    }));
    // Six polls at four seconds each carries the loop past the retry it makes
    // every fifth poll.
    for (let i = 0; i < 6; i += 1) {
      jest.advanceTimersByTime(4000);
      for (let n = 0; n < 5; n += 1) await tick();
    }

    expect(subscribe).toHaveBeenCalledWith('h9', expect.any(String));
    pushLine({
      hostId: 'h9',
      line: '{"result":{"type":"subscription_started"}}',
    });
    expect(herdrHostState('h9', null).feed).toBe('live');
    stop();
    jest.useRealTimers();
  });

  it('subscribes with the reader command for the session socket', async () => {
    const stop = await start('h6');
    expect(subscribe).toHaveBeenCalledWith(
      'h6',
      eventStreamCommand('/home/dev/.config/herdr/herdr.sock'),
    );
    stop();
  });

  it('releases the host connection when the last screen leaves', async () => {
    const stop = await start('h7');
    stop();
    expect(NativeHerdr.release).toHaveBeenCalledWith('h7');
  });

  it('leaves a refresh for a host nobody is watching alone', async () => {
    exec.mockClear();
    await refreshHerdrHost('nobody', null);
    expect(exec).not.toHaveBeenCalled();
  });
});

describe('the reader command', () => {
  it('asks for the events the app draws from', () => {
    const command = eventStreamCommand('/tmp/herdr.sock');
    expect(command).toContain('events.subscribe');
    expect(command).toContain('workspace.focused');
    expect(command).toContain('tab.created');
    // A per-scroll event would arrive on every wheel turn, so it is not asked
    // for at all rather than filtered later.
    expect(command).not.toContain('pane.scroll_changed');
  });

  it('quotes the socket path it was given', () => {
    expect(eventStreamCommand("/tmp/it's here.sock")).toContain(
      "'/tmp/it'\\''s here.sock'",
    );
  });

  it('tries the direct readers before the interpreters', () => {
    const command = eventStreamCommand('/tmp/s');
    expect(command.indexOf('socat')).toBeLessThan(command.indexOf('python3'));
    expect(command.indexOf('python3')).toBeLessThan(command.indexOf('perl'));
  });
});

describe('one line of the event stream', () => {
  it('reads the acknowledgement that proves the reader works', () => {
    expect(
      parseHerdrEvent('{"result":{"type":"subscription_started"}}'),
    ).toEqual({ kind: 'ack' });
  });

  it('keeps the new label a rename carries', () => {
    expect(
      parseHerdrEvent(
        '{"event":"tab_renamed","data":{"tab_id":"w1:t1","workspace_id":"w1","label":"built"}}',
      ),
    ).toEqual({
      kind: 'tab-renamed',
      tabId: 'w1:t1',
      workspaceId: 'w1',
      label: 'built',
    });
  });

  it('drops an event missing what its own type needs', () => {
    expect(
      parseHerdrEvent('{"event":"tab_focused","data":{"tab_id":"w1:t1"}}'),
    ).toBeNull();
  });

  it('drops a line that is not the stream', () => {
    expect(parseHerdrEvent('herdr: command not found')).toBeNull();
    expect(parseHerdrEvent('')).toBeNull();
  });
});
