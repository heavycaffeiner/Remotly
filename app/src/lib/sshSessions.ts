// Process-wide SSH terminal sessions.
//
// Sessions outlive the screen that opened them. Navigating back leaves them
// running, exactly as the daemon workspace does, and only closing a tab or
// disconnecting the host ends one. That is the whole reason this lives outside
// the React tree: a hook's cleanup runs on unmount, which is precisely the
// moment a live shell must not be killed.
//
// The store owns the native subscriptions and the output buffer. A screen
// attaches to render and detaches when it leaves; nothing about the session
// changes either way.

import { remotlySsh, stageMessage, type SshState } from './ssh';
import {
  MAX_SSH_TABS,
  addSshTab,
  createSshTabs,
  findSshTab,
  mintSessionId,
  removeSshTab,
  setActiveSshTab,
  setSshTabPhase,
  setSshTabTitle,
  type SshTabsState,
} from './sshTabs';
import { forgetFilesTab } from './filesTabs';
import terminalStore from '../specs/NativeRemotlyTerminalStore';
import { encodeBase64 } from './base64';
import { toRemotlyError, userFacingMessage } from './errors';
import { log } from './log';

const DEFAULT_COLS = 80;
const DEFAULT_ROWS = 24;

export interface SshHostKeyPrompt {
  sessionId: string;
  algorithm: string;
  fingerprint: string;
  changed: boolean;
}

interface TabRuntime {
  /**
   * Output waiting for a terminal to exist for this session.
   *
   * Not a display buffer: anything the terminal can take is written to it
   * immediately, so this only holds bytes that arrived before the session had
   * a terminal at all.
   */
  buffered: Uint8Array[];
  bufferedBytes: number;
  /** True while drainToTerminal is running, so chunks stay in order. */
  draining: boolean;
  offData: () => void;
  offState: () => void;
}

interface HostEntry {
  state: SshTabsState;
  hostKey: SshHostKeyPrompt | null;
  runtimes: Map<string, TabRuntime>;
  size: { cols: number; rows: number };
  /**
   * False until the viewport has reported its real grid.
   *
   * A session started before then gets the 80x24 placeholder, and an
   * application that positions with absolute cursor moves draws its overlay
   * against the wrong number of rows. Opening waits for the measurement.
   */
  sized: boolean;
  seq: number;
  /** Set while a screen is mounted and able to accept bytes. */
  sink: ((bytes: Uint8Array) => void) | null;
  listeners: Set<() => void>;
}

const hosts = new Map<string, HostEntry>();

function entry(hostId: string): HostEntry {
  let e = hosts.get(hostId);
  if (e === undefined) {
    e = {
      state: createSshTabs(hostId),
      hostKey: null,
      runtimes: new Map(),
      size: { cols: DEFAULT_COLS, rows: DEFAULT_ROWS },
      sized: false,
      seq: 0,
      sink: null,
      listeners: new Set(),
    };
    hosts.set(hostId, e);
  }
  return e;
}

function notify(e: HostEntry): void {
  for (const listener of e.listeners) listener();
}

/** Subscribes to state changes for one host. Returns an unsubscribe. */
export function subscribeSshHost(
  hostId: string,
  listener: () => void,
): () => void {
  const e = entry(hostId);
  e.listeners.add(listener);
  return () => {
    e.listeners.delete(listener);
  };
}

export function sshHostState(hostId: string): SshTabsState {
  return entry(hostId).state;
}

export function sshHostKeyPrompt(hostId: string): SshHostKeyPrompt | null {
  return entry(hostId).hostKey;
}

export function sshCanAddTab(hostId: string): boolean {
  return entry(hostId).state.tabs.length < MAX_SSH_TABS;
}

/** True once at least one tab has been opened for this host. */
export function sshHostStarted(hostId: string): boolean {
  return entry(hostId).state.tabs.length > 0;
}

/**
 * Open tabs per host, for the hosts list.
 *
 * Reads the live map rather than creating entries, so asking about a host that
 * was never opened does not allocate one.
 */
export function sshSessionCounts(): Map<string, number> {
  const counts = new Map<string, number>();
  for (const [hostId, e] of hosts) {
    if (e.state.tabs.length > 0) counts.set(hostId, e.state.tabs.length);
  }
  return counts;
}

/**
 * Attaches a renderer.
 *
 * Any output buffered while nothing was attached is replayed into the sink, so
 * returning to a screen shows what arrived while it was gone.
 */
export function attachSshSink(
  hostId: string,
  sink: (bytes: Uint8Array) => void,
): () => void {
  const e = entry(hostId);
  e.sink = sink;
  flushActive(hostId);
  return () => {
    if (e.sink === sink) e.sink = null;
  };
}

// Output still waiting for a terminal is written as one block. A write per
// chunk crosses the bridge that many times before anything is drawn, which is
// visible as the screen filling in line by line when a tab is reattached.
//
// Skipped while a drain is running: both write the same queue, and taking it
// from under the drain would deliver those bytes twice.
function flushActive(hostId: string): void {
  const e = entry(hostId);
  const sessionId = e.state.activeSessionId;
  if (sessionId === null || e.sink === null) return;
  const rt = e.runtimes.get(sessionId);
  if (rt === undefined || rt.buffered.length === 0 || rt.draining) return;
  const queued = rt.buffered;
  rt.buffered = [];
  rt.bufferedBytes = 0;
  let total = 0;
  for (const c of queued) total += c.length;
  const block = new Uint8Array(total);
  let at = 0;
  for (const c of queued) {
    block.set(c, at);
    at += c.length;
  }
  e.sink(block);
}

/**
 * Takes output for a tab and queues it.
 *
 * Everything goes through one queue per tab, on screen or not. Both
 * destinations are the same terminal, so delivering some chunks through the
 * attached view while others are still being written would let a later chunk
 * land before an earlier one. The screen then shows the newest output with
 * older output painted over it, which reads as truncated or stuck.
 *
 * Nothing is discarded.
 */
function receive(hostId: string, sessionId: string, bytes: Uint8Array): void {
  const e = entry(hostId);
  const rt = e.runtimes.get(sessionId);
  if (rt === undefined) return;

  // The tab being watched, with nothing queued ahead of it, goes straight to
  // the renderer. This is the common case and the one latency is felt in, so
  // it takes no queue, no await, and no bridge call.
  if (
    rt.buffered.length === 0 &&
    !rt.draining &&
    e.state.activeSessionId === sessionId &&
    e.sink !== null
  ) {
    e.sink(bytes);
    return;
  }

  rt.buffered.push(bytes);
  rt.bufferedBytes += bytes.length;
  void drainToTerminal(hostId, sessionId);
}

/**
 * Writes a tab's queued output, in order.
 *
 * One drain runs per tab, so chunks cannot overtake each other. The rendered
 * tab goes through its attached view, which draws as it writes; any other tab
 * is written into its own terminal.
 */
async function drainToTerminal(
  hostId: string,
  sessionId: string,
): Promise<void> {
  const e = entry(hostId);
  const rt = e.runtimes.get(sessionId);
  if (rt === undefined || rt.draining) return;
  rt.draining = true;
  try {
    while (rt.buffered.length > 0) {
      // Everything queued goes in one write. A shell delivers output in many
      // small chunks, and paying a bridge round trip for each one is what
      // made the terminal feel heavy and stall under a burst.
      const block = joinChunks(rt.buffered);
      rt.buffered = [];
      rt.bufferedBytes = 0;

      // Re-read each pass: the active tab and its sink can both change while
      // this is awaiting, and the next block must follow the new destination.
      if (e.state.activeSessionId === sessionId && e.sink !== null) {
        // Synchronous, so the tab being watched costs nothing extra.
        e.sink(block);
        continue;
      }
      const written = await terminalStore
        .feed(sessionId, encodeBase64(block), e.size.cols, e.size.rows)
        .catch(() => false);
      if (!written) {
        // The write did not happen, so the block can still go anywhere. A tab
        // that became active while this was in flight takes it through the
        // view; leaving it queued instead is what made a tab render late by
        // exactly the amount of output it missed, until the keyboard forced a
        // redraw.
        if (e.state.activeSessionId === sessionId && e.sink !== null) {
          e.sink(block);
          continue;
        }
        rt.buffered.unshift(block);
        rt.bufferedBytes += block.length;
        return;
      }
    }
  } finally {
    rt.draining = false;
  }
}

/** Concatenates queued chunks into one buffer, in order. */
function joinChunks(chunks: readonly Uint8Array[]): Uint8Array {
  if (chunks.length === 1) return chunks[0] as Uint8Array;
  let total = 0;
  for (const c of chunks) total += c.length;
  const block = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) {
    block.set(c, at);
    at += c.length;
  }
  return block;
}

function applyState(hostId: string, sessionId: string, s: SshState): void {
  const e = entry(hostId);
  const clearPrompt = () => {
    if (e.hostKey?.sessionId === sessionId) e.hostKey = null;
  };
  switch (s.state) {
    case 'hostKey':
      e.state = setSshTabPhase(e.state, sessionId, 'hostKey');
      e.hostKey = {
        sessionId,
        algorithm: s.algorithm ?? '',
        fingerprint: s.fingerprint ?? '',
        changed: s.changed === true,
      };
      break;
    case 'active':
      clearPrompt();
      e.state = setSshTabPhase(e.state, sessionId, 'active');
      break;
    case 'closed':
      clearPrompt();
      e.state = setSshTabPhase(
        e.state,
        sessionId,
        'closed',
        s.userInitiated === true
          ? 'The session was closed.'
          : 'The remote host closed the session.',
      );
      break;
    case 'failed':
      clearPrompt();
      e.state = setSshTabPhase(
        e.state,
        sessionId,
        'failed',
        stageMessage(s.stage) ?? s.reason ?? 'The connection failed.',
      );
      break;
    default:
      return;
  }
  notify(e);
}

// Subscribes a tab and starts its native session. Listeners are registered
// before connect, so a failure arriving immediately is not missed.
function startSession(hostId: string, sessionId: string): void {
  if (hostId === '') return;
  const e = entry(hostId);
  const existing = e.runtimes.get(sessionId);
  if (existing !== undefined) {
    existing.offData();
    existing.offState();
  }
  const offData = remotlySsh.onData(hostId, sessionId, bytes =>
    receive(hostId, sessionId, bytes),
  );
  const offState = remotlySsh.onState(hostId, sessionId, s =>
    applyState(hostId, sessionId, s),
  );
  e.runtimes.set(sessionId, {
    buffered: [],
    bufferedBytes: 0,
    draining: false,
    offData,
    offState,
  });
  void remotlySsh
    .connect(hostId, sessionId, e.size.cols, e.size.rows)
    .catch(err => {
      e.state = setSshTabPhase(
        e.state,
        sessionId,
        'failed',
        userFacingMessage(toRemotlyError(err, 'network')),
      );
      notify(e);
    });
}

function stopSession(hostId: string, sessionId: string): void {
  const e = entry(hostId);
  const rt = e.runtimes.get(sessionId);
  if (rt !== undefined) {
    rt.offData();
    rt.offState();
    e.runtimes.delete(sessionId);
  }
  if (hostId === '') return;
  void remotlySsh.close(hostId, sessionId).catch(() => undefined);
}

/** Opens a tab. Does nothing at the cap. */
export function openSshTab(hostId: string): void {
  const e = entry(hostId);
  if (e.state.tabs.length >= MAX_SSH_TABS) return;
  e.seq += 1;
  const sessionId = mintSessionId(e.seq);
  // Numbered past the highest in use, not by how many are open. Counting open
  // tabs reuses a number as soon as one is closed, so two tabs end up with the
  // same name and the strip appears to skip one.
  const { state: next, tab } = addSshTab(
    e.state,
    sessionId,
    `Shell ${nextShellNumber(e.state.tabs)}`,
  );
  if (tab === null) return;
  e.state = next;
  notify(e);
  startSession(hostId, sessionId);
}

/** The lowest shell number not already taken by an open tab. */
function nextShellNumber(tabs: readonly { title: string }[]): number {
  const used = new Set<number>();
  for (const t of tabs) {
    const m = /^Shell (\d+)$/.exec(t.title);
    if (m !== null) used.add(Number(m[1]));
  }
  let n = 1;
  while (used.has(n)) n += 1;
  return n;
}

/**
 * Opens a file browser tab for the host.
 *
 * It carries no SSH session: the SFTP connection is per host and owned by the
 * bridge, so the tab is only a place to render the browser. Several can be
 * open at once, each remembering its own directory, which is what makes
 * copying between two places on one host workable.
 */
export function openSshFilesTab(hostId: string): void {
  const e = entry(hostId);
  if (e.state.tabs.length >= MAX_SSH_TABS) return;
  e.seq += 1;
  const sessionId = mintSessionId(e.seq);
  const count = e.state.tabs.filter(t => t.kind === 'files').length;
  const title = count === 0 ? 'Files' : `Files ${count + 1}`;
  const { state: next, tab } = addSshTab(e.state, sessionId, title, 'files');
  if (tab === null) return;
  // A files tab has nothing to connect, so it is usable immediately rather
  // than sitting in the connecting phase a shell starts in.
  e.state = setSshTabPhase(next, sessionId, 'active', '');
  notify(e);
}

/** Renames a tab. Ignores a blank name. */
export function renameSshTab(
  hostId: string,
  sessionId: string,
  title: string,
): void {
  const e = entry(hostId);
  const next = setSshTabTitle(e.state, sessionId, title);
  if (next === e.state) return;
  e.state = next;
  notify(e);
}

export function selectSshTab(hostId: string, sessionId: string): void {
  const e = entry(hostId);
  if (e.state.activeSessionId === sessionId) return;
  if (findSshTab(e.state, sessionId) === null) return;
  e.state = setActiveSshTab(e.state, sessionId);
  // The renderer is torn down and rebuilt for the new session, so the sink
  // that is attached right now belongs to the tab being left. Writing here
  // would put this tab's output into the previous tab's terminal; the new
  // renderer flushes for itself once it attaches.
  e.sink = null;
  notify(e);
}

/** Closes one tab. This is the only thing that ends a session. */
export function closeSshTab(hostId: string, sessionId: string): void {
  const e = entry(hostId);
  // A files tab owns no SSH session; closing one must not tear down a shell
  // channel that was never opened for it.
  if (findSshTab(e.state, sessionId)?.kind === 'files') {
    forgetFilesTab(sessionId);
  } else {
    stopSession(hostId, sessionId);
  }
  e.state = removeSshTab(e.state, sessionId);
  if (e.hostKey?.sessionId === sessionId) e.hostKey = null;
  notify(e);
}

/** Closes every tab for a host and forgets it. */
export function closeSshHost(hostId: string): void {
  const e = entry(hostId);
  for (const sessionId of [...e.runtimes.keys()]) {
    stopSession(hostId, sessionId);
  }
  hosts.delete(hostId);
  notify(e);
}

export function reconnectSshTab(hostId: string, sessionId: string): void {
  const e = entry(hostId);
  if (findSshTab(e.state, sessionId) === null) return;
  e.state = setSshTabPhase(e.state, sessionId, 'connecting');
  notify(e);
  startSession(hostId, sessionId);
}

export function sendSshInput(hostId: string, bytes: Uint8Array): void {
  const e = entry(hostId);
  const sessionId = e.state.activeSessionId;
  if (hostId === '' || sessionId === null || bytes.length === 0) return;
  void remotlySsh.write(hostId, sessionId, bytes).catch(err => {
    log.warn('ssh terminal write failed', {
      message: userFacingMessage(toRemotlyError(err, 'terminal')),
    });
  });
}

/** True once the viewport has reported a real grid for this host. */
export function sshHostSized(hostId: string): boolean {
  return entry(hostId).sized;
}

/** Every tab shares one viewport, so a resize applies to all of them. */
export function resizeSshHost(
  hostId: string,
  size: { cols: number; rows: number },
): void {
  const e = entry(hostId);
  if (size.cols <= 0 || size.rows <= 0) return;
  const first = !e.sized;
  e.size = size;
  e.sized = true;
  // The sized flag is read through useSyncExternalStore, so the first real
  // measurement has to wake its subscribers.
  if (first) notify(e);
  if (hostId === '') return;
  // Every open tab is corrected, including on the host's first measurement.
  //
  // The viewport only mounts once a tab exists, so the first real grid always
  // arrives after that tab has already connected at the 80x24 placeholder.
  // Skipping the resize here left the remote pty at 80 columns for the life of
  // the session: zsh then sized its partial-line marker to a width the screen
  // does not have, wrapping it and stranding a `%` on the first prompt.
  //
  // Tabs with no runtime yet are not connected, so resizing them is a no-op on
  // the native side rather than an error.
  for (const tab of e.state.tabs) {
    void remotlySsh
      .resize(hostId, tab.sessionId, size.cols, size.rows)
      .catch(() => undefined);
  }
}

export function answerSshHostKey(
  hostId: string,
  decision: 'accept' | 'replace' | 'reject',
): void {
  const e = entry(hostId);
  const prompt = e.hostKey;
  if (prompt === null || hostId === '') return;
  e.hostKey = null;
  if (decision === 'reject') {
    stopSession(hostId, prompt.sessionId);
    e.state = setSshTabPhase(
      e.state,
      prompt.sessionId,
      'closed',
      'The host key was rejected.',
    );
    notify(e);
    return;
  }
  notify(e);
  void remotlySsh.hostKey(hostId, prompt.sessionId, decision).catch(err => {
    log.error('host key answer failed', {
      message: userFacingMessage(toRemotlyError(err, 'unknown')),
    });
  });
}
