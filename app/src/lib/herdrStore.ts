// What the app knows about a host's herdr session, kept current by events.
//
// One store per (host, session). A screen reads it and subscribes; the store
// holds the event stream, applies what arrives, and re-reads a snapshot when an
// event cannot be applied exactly. A host whose reader never acknowledged falls
// back to re-reading on a timer, which is the only mode the app had before.
//
// State is a value, replaced on change, so a screen renders from
// useSyncExternalStore without a deep compare.

import { AppState } from 'react-native';
import {
  HERDR_EVENT_TYPES,
  eventStreamCommand,
  parseHerdrEvent,
  type HerdrEvent,
  type HerdrSnapshot,
  type HerdrTab,
  type HerdrWorkspace,
} from './herdr';
import { herdrSnapshot, listHerdrSessions } from './herdrClient';
import NativeHerdr from '../specs/NativeRemotlyHerdr';
import { log } from './log';

/** How the store is being kept current. */
export type HerdrFeed = 'starting' | 'live' | 'polling';

export interface HerdrHostState {
  /** Ordered by herdr's own number, which is what a move follows. */
  workspaces: HerdrWorkspace[];
  /** Tabs of every workspace, keyed by workspace id, in place order. */
  tabs: Record<string, HerdrTab[]>;
  focusedWorkspaceId: string | null;
  focusedTabId: string | null;
  feed: HerdrFeed;
  /** The last failure, for a screen that has nothing else to show. */
  error: string | null;
  /** True once a snapshot has been installed. */
  loaded: boolean;
}

const EMPTY: HerdrHostState = {
  workspaces: [],
  tabs: {},
  focusedWorkspaceId: null,
  focusedTabId: null,
  feed: 'starting',
  error: null,
  loaded: false,
};

/** How often a host without a working reader is re-read, in ms. */
const POLL_MS = 4000;

/** How long to wait for the subscription acknowledgement before polling. */
const ACK_TIMEOUT_MS = 4000;

/** Polls between attempts at the stream, so a retry is bounded. */
const STREAM_RETRY_POLLS = 5;

interface Entry {
  key: string;
  hostId: string;
  session: string | null;
  state: HerdrHostState;
  listeners: Set<() => void>;
  /** Lines that arrived before the snapshot was installed. */
  buffered: HerdrEvent[];
  bootstrapped: boolean;
  /** True while the fallback re-read loop is running. */
  polling: boolean;
  /** True from an attempt at the stream until it is known to have failed. */
  streaming: boolean;
  /** Set while a snapshot read is in flight, so events do not stack reads. */
  reading: boolean;
  /** Bumped on every (re)subscribe so a late stream end is ignored. */
  epoch: number;
}

const entries = new Map<string, Entry>();

function keyOf(hostId: string, session: string | null): string {
  return `${hostId}\u0000${session ?? ''}`;
}

function set(entry: Entry, next: Partial<HerdrHostState>): void {
  entry.state = { ...entry.state, ...next };
  entry.listeners.forEach(l => l());
}

/**
 * The state for a host, which is `EMPTY` until something subscribes.
 *
 * Returned by identity so useSyncExternalStore does not see a new object on
 * every render.
 */
export function herdrHostState(
  hostId: string,
  session: string | null = null,
): HerdrHostState {
  return entries.get(keyOf(hostId, session))?.state ?? EMPTY;
}

/**
 * Starts keeping a host current and returns the unsubscribe.
 *
 * The last listener leaving stops the stream and releases the connection: a
 * held connection with no screen behind it is what a phone must not keep.
 */
export function subscribeHerdrHost(
  hostId: string,
  session: string | null,
  onChange: () => void,
): () => void {
  const key = keyOf(hostId, session);
  let entry = entries.get(key);
  if (entry === undefined) {
    entry = {
      key,
      hostId,
      session,
      state: EMPTY,
      listeners: new Set(),
      buffered: [],
      bootstrapped: false,
      polling: false,
      streaming: false,
      reading: false,
      epoch: 0,
    };
    entries.set(key, entry);
  }
  const live = entry;
  live.listeners.add(onChange);
  if (live.listeners.size === 1) start(live);

  return () => {
    live.listeners.delete(onChange);
    if (live.listeners.size === 0) stop(live);
  };
}

/** Re-reads a host now, for a screen that just changed something itself. */
export async function refreshHerdrHost(
  hostId: string,
  session: string | null = null,
): Promise<void> {
  const entry = entries.get(keyOf(hostId, session));
  if (entry !== undefined) await read(entry);
}

/**
 * Applies a change the app just made, before herdr has answered.
 *
 * The event that follows carries the same ids, so applying it again is a
 * no-op: the optimistic paint is a head start, not a second source of truth.
 */
export function applyHerdrLocal(
  hostId: string,
  session: string | null,
  event: HerdrEvent,
): void {
  const entry = entries.get(keyOf(hostId, session));
  if (entry === undefined) return;
  apply(entry, event);
}

function start(entry: Entry): void {
  entry.epoch += 1;
  entry.bootstrapped = false;
  entry.buffered = [];
  set(entry, { feed: 'starting' });

  // The stream opens first and its lines are buffered, so nothing that
  // happens during the snapshot read is lost.
  void openStream(entry);
  void read(entry);
  watchForeground();
}

/**
 * Re-reads every watched host when the app comes back to the foreground.
 *
 * herdr publishes nothing for a move its own key bindings made, so a tab
 * switched by typing `prefix+n` reaches no event. Rather than a standing
 * timer, which is what this whole change removed, the catch-up happens when
 * the user returns: one read per host, at the moment they are looking.
 * Screens do the same on focus.
 */
let foregroundWatched = false;
function watchForeground(): void {
  if (foregroundWatched) return;
  foregroundWatched = true;
  AppState.addEventListener('change', state => {
    if (state !== 'active') return;
    entries.forEach(entry => void read(entry));
  });
}

function stop(entry: Entry): void {
  // Every timer and callback checks the epoch, so bumping it is what stops
  // them: nothing here holds a handle to cancel.
  entry.epoch += 1;
  entry.polling = false;
  entry.streaming = false;
  entries.delete(entry.key);
  detachLines(entry);
  // The native connection is per host, and two sessions on one host share it,
  // so it is only dropped once nothing is watching that host.
  const stillWatched = [...entries.values()].some(
    other => other.hostId === entry.hostId,
  );
  if (!stillWatched) NativeHerdr.release(entry.hostId);
}

/**
 * Falls back to re-reading, and keeps trying for the stream.
 *
 * A host reached before its key was accepted, or before its herdr was up,
 * cannot open a stream on the first try. Without a retry that host stays on a
 * timer for as long as the screen is open, which is the behaviour this whole
 * change exists to remove.
 */
function startPolling(entry: Entry): void {
  // The attempt is given back first: a host that fails twice reaches here with
  // the loop already running, and leaving the flag set would stop every later
  // retry, which is how a host stayed on the timer after its key was accepted.
  entry.streaming = false;
  if (entry.polling) return;
  entry.polling = true;
  set(entry, { feed: 'polling' });
  const epoch = entry.epoch;
  let ticks = 0;
  const tick = (): void => {
    setTimeout(() => {
      if (entry.epoch !== epoch || !entry.polling) return;
      void read(entry);
      ticks += 1;
      if (!entry.streaming && ticks % STREAM_RETRY_POLLS === 0) {
        void openStream(entry);
      }
      tick();
    }, POLL_MS);
  };
  tick();
}

function stopPolling(entry: Entry): void {
  entry.polling = false;
}

/** Line and end subscriptions, per entry, so one host's end never ends another. */
const attached = new Map<string, { lines: () => void; end: () => void }>();

function detachLines(entry: Entry): void {
  const handles = attached.get(entry.key);
  if (handles === undefined) return;
  handles.lines();
  handles.end();
  attached.delete(entry.key);
}

async function openStream(entry: Entry): Promise<void> {
  const epoch = entry.epoch;
  if (entry.streaming) return;
  entry.streaming = true;
  let socketPath: string | null = null;
  let failure: unknown = null;
  try {
    const sessions = await listHerdrSessions(entry.hostId);
    const wanted =
      entry.session === null
        ? sessions.find(s => s.default) ?? sessions[0]
        : sessions.find(s => s.name === entry.session);
    socketPath = wanted?.socketPath ?? null;
  } catch (e) {
    failure = e;
  }
  // An entry that has since stopped or restarted has nobody to tell, and its
  // attempt has to be given back or no retry will ever run again.
  if (entry.epoch !== epoch) {
    entry.streaming = false;
    return;
  }
  if (failure !== null) {
    // A host whose key is not accepted yet answers nothing. The poll loop
    // retries this, so it is a warning rather than a failure.
    log.warn('herdr: session list failed', { error: String(failure) });
  }
  if (socketPath === null || socketPath === '') {
    startPolling(entry);
    return;
  }

  // A retry replaces the previous handlers rather than stacking a second set.
  detachLines(entry);
  const lines = NativeHerdr.onLine(event => {
    if (event.hostId !== entry.hostId || entry.epoch !== epoch) return;
    const parsed = parseHerdrEvent(event.line);
    if (parsed === null) return;
    if (parsed.kind === 'ack') {
      stopPolling(entry);
      set(entry, { feed: 'live' });
      return;
    }
    if (!entry.bootstrapped) {
      entry.buffered.push(parsed);
      return;
    }
    apply(entry, parsed);
  });
  const end = NativeHerdr.onStreamEnd(event => {
    if (event.hostId !== entry.hostId || entry.epoch !== epoch) return;
    // The stream is what kept the state current; without it the screen falls
    // back to re-reading rather than showing a frozen list.
    startPolling(entry);
  });
  attached.set(entry.key, {
    lines: () => lines.remove(),
    end: () => end.remove(),
  });

  NativeHerdr.subscribe(entry.hostId, eventStreamCommand(socketPath));

  // A reader that never acknowledges is a host with none of the interpreters,
  // or a herdr too old for the subscription. Either way, polling.
  setTimeout(() => {
    if (entry.epoch !== epoch) {
      entry.streaming = false;
      return;
    }
    if (entry.state.feed !== 'live') startPolling(entry);
  }, ACK_TIMEOUT_MS);
}

async function read(entry: Entry): Promise<void> {
  if (entry.reading) return;
  entry.reading = true;
  const epoch = entry.epoch;
  try {
    const snap = await herdrSnapshot(entry.hostId, entry.session);
    if (entry.epoch !== epoch) return;
    install(entry, snap);
  } catch (e) {
    if (entry.epoch !== epoch) return;
    set(entry, { error: String(e), loaded: entry.state.loaded });
  } finally {
    entry.reading = false;
  }
}

function install(entry: Entry, snap: HerdrSnapshot): void {
  const tabs: Record<string, HerdrTab[]> = {};
  snap.tabs.forEach(tab => {
    const list = tabs[tab.workspaceId];
    if (list === undefined) tabs[tab.workspaceId] = [tab];
    else list.push(tab);
  });
  set(entry, {
    workspaces: [...snap.workspaces].sort((a, b) => a.number - b.number),
    tabs,
    focusedWorkspaceId: snap.focusedWorkspaceId,
    focusedTabId: snap.focusedTabId,
    error: null,
    loaded: true,
  });

  // Anything that happened while the snapshot was being read is newer than
  // the snapshot, so it goes on top in the order it arrived.
  const pending = entry.buffered;
  entry.buffered = [];
  entry.bootstrapped = true;
  pending.forEach(event => apply(entry, event));
}

function apply(entry: Entry, event: HerdrEvent): void {
  const s = entry.state;
  switch (event.kind) {
    case 'ack':
      return;
    case 'resync':
      void read(entry);
      return;
    case 'workspace-created': {
      const without = s.workspaces.filter(
        w => w.workspaceId !== event.workspace.workspaceId,
      );
      set(entry, {
        workspaces: [...without, event.workspace].sort(
          (a, b) => a.number - b.number,
        ),
      });
      return;
    }
    case 'workspace-closed': {
      const tabs = { ...s.tabs };
      delete tabs[event.workspaceId];
      set(entry, {
        workspaces: s.workspaces.filter(
          w => w.workspaceId !== event.workspaceId,
        ),
        tabs,
      });
      return;
    }
    case 'workspace-renamed':
      set(entry, {
        workspaces: s.workspaces.map(w =>
          w.workspaceId === event.workspaceId
            ? { ...w, label: event.label }
            : w,
        ),
      });
      return;
    case 'workspace-focused':
      set(entry, {
        focusedWorkspaceId: event.workspaceId,
        workspaces: s.workspaces.map(w => ({
          ...w,
          focused: w.workspaceId === event.workspaceId,
        })),
      });
      return;
    case 'tab-created': {
      const list = s.tabs[event.tab.workspaceId] ?? [];
      const without = list.filter(t => t.tabId !== event.tab.tabId);
      set(entry, {
        tabs: {
          ...s.tabs,
          [event.tab.workspaceId]: [...without, event.tab].sort(
            (a, b) => a.number - b.number,
          ),
        },
      });
      return;
    }
    case 'tab-closed':
      set(entry, {
        tabs: {
          ...s.tabs,
          [event.workspaceId]: (s.tabs[event.workspaceId] ?? []).filter(
            t => t.tabId !== event.tabId,
          ),
        },
      });
      return;
    case 'tab-renamed':
      set(entry, {
        tabs: {
          ...s.tabs,
          [event.workspaceId]: (s.tabs[event.workspaceId] ?? []).map(t =>
            t.tabId === event.tabId ? { ...t, label: event.label } : t,
          ),
        },
      });
      return;
    case 'tab-focused':
      set(entry, {
        focusedTabId: event.tabId,
        tabs: {
          ...s.tabs,
          [event.workspaceId]: (s.tabs[event.workspaceId] ?? []).map(t => ({
            ...t,
            focused: t.tabId === event.tabId,
          })),
        },
      });
      return;
  }
}

/** The event types the reader asks for, for anyone reporting what is covered. */
export const HERDR_SUBSCRIBED = HERDR_EVENT_TYPES;
