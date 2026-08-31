// The daemon workspace controller: connection, reconnect, session
// reconciliation, attach and replay, tab operations, and session events.
//
// Invariants preserved from the original screen:
//   - the replay cursor never moves backwards
//   - a stale response never overwrites current host or session state
//   - buffered terminal output has a hard memory cap
//   - a replay gap is reported explicitly
//   - terminal content is never logged

import { useCallback, useEffect, useRef, useState } from 'react';
import { AppState } from 'react-native';

import { getHosts, type HostRecord } from '../../lib/hosts';
import { getTransport, type SessionEvent } from '../../lib/transport';
import {
  HINT_RELAY,
  hintTarget,
  relayIdFromDaemonPub,
} from '../../lib/pairing';
import {
  MAX_CURSOR,
  addTab,
  applySessionMeta,
  closeTab,
  createWorkspace,
  findTab,
  markAttached,
  markExited,
  markStale,
  parseWorkspace,
  reconcile,
  serializeWorkspace,
  setActive,
  setCursor,
  type WorkspaceState,
} from '../../lib/workspace';
import {
  loadWorkspaceDocument,
  releaseTerminal,
  saveWorkspaceDocument,
} from '../../lib/workspaceStore';
import { postOSNotification } from '../../lib/notify';
import {
  DaemonError,
  attachSession,
  createSession,
  detachChannel,
  listPresets,
  listSessions,
  resizeSession,
  type Preset,
  type RawSession,
} from '../../lib/sessions';
import { Backoff } from '../../lib/backoff';
import { EventDeduper } from '../../lib/events';
import { sanitizePreview } from '../../lib/preview';
import { toRemotlyError, userFacingMessage } from '../../lib/errors';
import { log } from '../../lib/log';

export type WorkspacePhase =
  | 'init'
  | 'loading'
  | 'connecting'
  | 'connected'
  | 'disconnected'
  | 'error';

/** A term channel's replay progress. */
export interface ChannelTrack {
  channelId: number;
  replayedFrom: number;
  receivedBytes: number;
  continuity: 'full' | 'gapless' | 'gap';
  replayDone: boolean;
}

export interface ActiveAttachment {
  sessionId: string;
  track: ChannelTrack;
}

export interface WorkspaceNotice {
  id: number;
  title: string;
  text: string;
}

/** Buffered output cap: a stuck mount cannot grow memory without bound. */
const PENDING_CAP = 1024 * 1024;

/** Joins buffered chunks into one block, in arrival order. */
function concatChunks(chunks: readonly Uint8Array[]): Uint8Array {
  if (chunks.length === 1) return chunks[0];
  let total = 0;
  for (const c of chunks) total += c.length;
  const out = new Uint8Array(total);
  let at = 0;
  for (const c of chunks) {
    out.set(c, at);
    at += c.length;
  }
  return out;
}
const NOTICE_MS = 5000;
const CURSOR_TICK_MS = 2000;

export interface WorkspaceConnectionOptions {
  hostId: string;
  /** Writes output into the terminal. */
  write: (bytes: Uint8Array) => void;
  /** True once the terminal can accept bytes. */
  notifyEnabled: boolean;
}

export interface WorkspaceController {
  phase: WorkspacePhase;
  host: HostRecord | null;
  workspace: WorkspaceState | null;
  active: ActiveAttachment | null;
  presets: Preset[];
  via: 'direct' | 'relay' | null;
  reconnecting: boolean;
  errorText: string;
  notice: WorkspaceNotice | null;
  dismissNotice: () => void;
  onViewportReady: (size: { cols: number; rows: number }) => void;
  resize: (size: { cols: number; rows: number }) => void;
  send: (bytes: Uint8Array) => void;
  selectTab: (sessionId: string) => void;
  closeTabById: (sessionId: string) => void;
  createNew: (kind: 'shell' | 'agent', preset?: Preset) => Promise<void>;
  retryNow: () => void;
  disconnect: () => void;
  leave: () => void;
}

export function useWorkspaceConnection({
  hostId,
  write,
  notifyEnabled,
}: WorkspaceConnectionOptions): WorkspaceController {
  const [phase, setPhase] = useState<WorkspacePhase>('init');
  const [host, setHost] = useState<HostRecord | null>(null);
  const [workspace, setWorkspace] = useState<WorkspaceState | null>(null);
  const [active, setActiveState] = useState<ActiveAttachment | null>(null);
  const [presets, setPresets] = useState<Preset[]>([]);
  const [via, setVia] = useState<'direct' | 'relay' | null>(null);
  const [reconnecting, setReconnecting] = useState(false);
  const [errorText, setErrorText] = useState('');
  const [notice, setNotice] = useState<WorkspaceNotice | null>(null);

  const wsRef = useRef<WorkspaceState | null>(null);
  const hostRef = useRef<HostRecord | null>(null);
  const activeRef = useRef<ActiveAttachment | null>(null);
  const phaseRef = useRef<WorkspacePhase>('init');
  const notifyRef = useRef(notifyEnabled);
  const readyRef = useRef(false);
  const pending = useRef<Uint8Array[]>([]);
  const pendingBytes = useRef(0);
  const size = useRef<{ cols: number; rows: number } | null>(null);
  const deduper = useRef(new EventDeduper());
  const backoff = useRef(new Backoff());
  const noticeTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const reconnectTimer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const disposed = useRef(false);
  const noticeSeq = useRef(0);
  const writeRef = useRef(write);
  writeRef.current = write;

  notifyRef.current = notifyEnabled;
  phaseRef.current = phase;

  const commitWs = useCallback((next: WorkspaceState): WorkspaceState => {
    wsRef.current = next;
    if (!disposed.current) setWorkspace(next);
    return next;
  }, []);

  const setActiveAttachment = useCallback((next: ActiveAttachment | null) => {
    activeRef.current = next;
    if (!disposed.current) setActiveState(next);
  }, []);

  const cursorOf = (track: ChannelTrack): number =>
    Math.min(MAX_CURSOR, track.replayedFrom + track.receivedBytes);

  // Persists the workspace document on every change; the save is local and
  // small, so it runs eagerly and failures only log.
  useEffect(() => {
    if (workspace === null || hostId === '') return;
    void saveWorkspaceDocument(hostId, serializeWorkspace(workspace)).catch(
      e => {
        log.error('failed to save workspace', {
          message: userFacingMessage(toRemotlyError(e, 'storage')),
        });
      },
    );
  }, [workspace, hostId]);

  // Keeps the saved cursor fresh while attached. The byte counter lives in a
  // ref, so this tick writes it into the persisted state at a slow rate.
  useEffect(() => {
    if (phase !== 'connected' || active === null) return;
    const t = setInterval(() => {
      const cur = activeRef.current;
      if (cur === null) return;
      commitWs(
        setCursor(
          wsRef.current ?? createWorkspace(cur.sessionId),
          cur.sessionId,
          cursorOf(cur.track),
        ),
      );
    }, CURSOR_TICK_MS);
    return () => clearInterval(t);
  }, [phase, active, commitWs]);

  // Buffered output is written as one block. Attach replays the scrollback in
  // many small chunks, and a write per chunk crosses the bridge that many
  // times before anything is drawn.
  //
  // Nothing is written before the viewport reports ready: the native view is
  // not attached yet, so the write is rejected and those bytes are gone. The
  // queue is kept instead and onViewportReady flushes it.
  const flushPending = useCallback(() => {
    if (!readyRef.current) return;
    const queued = pending.current;
    if (queued.length === 0) return;
    pending.current = [];
    pendingBytes.current = 0;
    writeRef.current(concatChunks(queued));
  }, []);

  const scheduleResizeFor = useCallback(
    (hid: string, sessionId: string, cols: number, rows: number) => {
      void resizeSession(hid, sessionId, cols, rows).catch(() => undefined);
    },
    [],
  );

  // Detaches the current channel, if any. The daemon session keeps running.
  //
  // readyRef is deliberately untouched: it describes whether the viewport is
  // mounted and can take bytes, which a channel change does not alter. The
  // native view only reports ready once per mount, so clearing it here left
  // it false for the life of the screen and every later write was buffered
  // instead of drawn.
  const detachActive = useCallback(
    async (h: HostRecord | null) => {
      const cur = activeRef.current;
      if (cur === null) return;
      setActiveAttachment(null);
      if (h !== null) {
        await detachChannel(h.id, cur.track.channelId).catch(() => undefined);
      }
    },
    [setActiveAttachment],
  );

  // Attaches with a cursor, retrying once without it when the cursor fell out
  // of the daemon's retained window.
  const doAttach = useCallback(
    async (
      hid: string,
      sessionId: string,
      cursor: number,
    ): Promise<ChannelTrack> => {
      const build = (r: {
        channelId: number;
        replayedFrom: number;
        continuity: 'full' | 'gapless' | 'gap';
      }): ChannelTrack => ({
        channelId: r.channelId,
        replayedFrom: r.replayedFrom,
        receivedBytes: 0,
        continuity: r.continuity,
        replayDone: false,
      });
      try {
        return build(
          await attachSession(hid, sessionId, cursor > 0 ? cursor : undefined),
        );
      } catch (e) {
        if (e instanceof DaemonError && e.code === 'cursor_out_of_range') {
          return build(await attachSession(hid, sessionId));
        }
        throw e;
      }
    },
    [],
  );

  // Attaches the workspace's active tab (or the last attachable one). A gone
  // session is marked stale and the next tab is tried.
  const attachActiveTab = useCallback(
    async (h: HostRecord, initial: WorkspaceState) => {
      await detachActive(h);
      let current = initial;
      let guard = 0;
      while (guard++ <= current.tabs.length) {
        let target =
          current.activeSessionId !== null
            ? findTab(current, current.activeSessionId)
            : null;
        if (target === null || target.state === 'stale') {
          const usable = [...current.tabs]
            .reverse()
            .find(t => t.state !== 'stale');
          if (usable === undefined) return;
          current = commitWs(setActive(current, usable.sessionId));
          target = usable;
        }
        const sessionId = target.sessionId;
        try {
          const track = await doAttach(h.id, sessionId, target.cursor);
          current = commitWs(
            markAttached(
              setCursor(current, sessionId, track.replayedFrom),
              sessionId,
            ),
          );
          setActiveAttachment({ sessionId, track });
          // A previous attach failure left its message behind, and the screen
          // renders that as a full overlay over the terminal. Attaching again
          // is what makes it stale, so it is cleared here rather than only on
          // a fresh connect: after a reconnect the terminal was live and
          // usable underneath an error saying the connection had closed.
          setErrorText('');
          const s = size.current;
          if (s !== null) scheduleResizeFor(h.id, sessionId, s.cols, s.rows);
          return;
        } catch (e) {
          if (e instanceof DaemonError && e.code === 'unknown_session') {
            current = commitWs(markStale(current, sessionId));
            continue;
          }
          log.error('attach failed', {
            message: userFacingMessage(toRemotlyError(e, 'terminal')),
          });
          setErrorText(userFacingMessage(toRemotlyError(e, 'terminal')));
          return;
        }
      }
    },
    [commitWs, detachActive, doAttach, setActiveAttachment, scheduleResizeFor],
  );

  const afterConnect = useCallback(
    async (h: HostRecord) => {
      if (disposed.current) return;
      let sessions: RawSession[];
      try {
        sessions = await listSessions(h.id);
      } catch (e) {
        // The list is advisory; the attach surfaces a hard failure.
        log.error('session.list failed', {
          message: userFacingMessage(toRemotlyError(e, 'unknown')),
        });
        sessions = [];
      }
      const reconciled = reconcile(
        wsRef.current ?? createWorkspace(h.id),
        sessions.map(s => ({
          sessionId: s.id,
          title: s.title,
          running: s.running,
          preview: s.preview,
        })),
      );
      commitWs(reconciled);
      try {
        setPresets(await listPresets(h.id));
      } catch {
        setPresets([]);
      }
      await attachActiveTab(h, reconciled);
    },
    [commitWs, attachActiveTab],
  );

  const scheduleReconnect = useCallback(() => {
    if (disposed.current) return;
    setReconnecting(true);
    // A pending attempt is dropped first. Two disconnects in a row each armed
    // a timer and only the last handle was kept, so the earlier one still
    // fired and ran a second connect concurrently with the first; the cleanup
    // could not cancel it either.
    clearTimeout(reconnectTimer.current ?? undefined);
    const delay = backoff.current.next();
    reconnectTimer.current = setTimeout(() => {
      reconnectTimer.current = null;
      if (disposed.current) return;
      const h = hostRef.current;
      if (h !== null && phaseRef.current === 'disconnected')
        void connectRef.current(h);
    }, delay);
  }, []);

  // Tries the saved direct hints in order, then the relay as a last resort.
  // Network failures move on; a pinned-key mismatch is fatal and never retried.
  const connect = useCallback(
    async (h: HostRecord) => {
      setPhase('connecting');
      setReconnecting(false);
      setErrorText('');
      const directHints = h.hints.filter(x => x.kind !== HINT_RELAY);
      const relayHint = h.hints.find(x => x.kind === HINT_RELAY) ?? null;
      const relayId = relayIdFromDaemonPub(h.daemonPub);
      let lastError: unknown = null;

      for (const hint of directHints) {
        try {
          const conn = await getTransport().connect(h.id, hintTarget(hint), {
            daemonPub: h.daemonPub,
          });
          if (disposed.current) return;
          setVia(conn.via ?? 'direct');
          backoff.current.reset();
          setPhase('connected');
          await afterConnect(h);
          return;
        } catch (e) {
          lastError = e;
          // Only a rejected identity ends the attempt. Every other failure
          // means this hint did not work, not that the host is unusable: a
          // daemon advertises its hostname alongside its addresses, and a
          // client that cannot resolve that name would otherwise stop before
          // trying the IPv4 and IPv6 hints that do reach it.
          const kind = toRemotlyError(e, 'network').kind;
          if (kind === 'auth' || kind === 'handshake') {
            setErrorText(userFacingMessage(toRemotlyError(e, 'network')));
            setPhase('error');
            return;
          }
        }
      }

      if (relayHint !== null && relayId !== null) {
        const relayTarget = hintTarget(relayHint);
        try {
          const conn = await getTransport().connect(h.id, relayTarget, {
            daemonPub: h.daemonPub,
            relayTarget,
            relayId,
            relayOnly: true,
          });
          if (disposed.current) return;
          setVia(conn.via ?? 'relay');
          backoff.current.reset();
          setPhase('connected');
          await afterConnect(h);
          return;
        } catch (e) {
          lastError = e;
        }
      }

      if (disposed.current) return;
      setVia(null);
      log.warn('workspace connect unavailable', {
        message: userFacingMessage(toRemotlyError(lastError, 'network')),
      });
      setErrorText(userFacingMessage(toRemotlyError(lastError, 'network')));
      setPhase('disconnected');
      scheduleReconnect();
    },
    [afterConnect, scheduleReconnect],
  );

  const connectRef = useRef(connect);
  connectRef.current = connect;

  const retryNow = useCallback(() => {
    const h = hostRef.current;
    if (h === null) return;
    clearTimeout(reconnectTimer.current ?? undefined);
    reconnectTimer.current = null;
    backoff.current.reset();
    setReconnecting(false);
    void connectRef.current(h);
  }, []);

  const onViewportReady = useCallback(
    (next: { cols: number; rows: number }) => {
      size.current = next;
      readyRef.current = true;
      flushPending();
      const cur = activeRef.current;
      const h = hostRef.current;
      if (cur !== null && h !== null) {
        scheduleResizeFor(h.id, cur.sessionId, next.cols, next.rows);
      }
    },
    [flushPending, scheduleResizeFor],
  );

  const resize = useCallback(
    (next: { cols: number; rows: number }) => {
      size.current = next;
      const cur = activeRef.current;
      const h = hostRef.current;
      if (cur === null || h === null) return;
      scheduleResizeFor(h.id, cur.sessionId, next.cols, next.rows);
    },
    [scheduleResizeFor],
  );

  const send = useCallback((bytes: Uint8Array) => {
    const cur = activeRef.current;
    const h = hostRef.current;
    if (cur === null || h === null || phaseRef.current !== 'connected') return;
    void getTransport()
      .writeTerm(h.id, cur.track.channelId, bytes)
      .catch(e => {
        log.error('terminal input failed', {
          message: userFacingMessage(toRemotlyError(e, 'terminal')),
        });
      });
  }, []);

  const selectTab = useCallback(
    (sessionId: string) => {
      const w = wsRef.current;
      const h = hostRef.current;
      if (w === null || h === null || w.activeSessionId === sessionId) return;
      const tab = findTab(w, sessionId);
      if (tab === null) return;
      const next = commitWs(setActive(w, sessionId));
      void (async () => {
        await detachActive(h);
        if (tab.state !== 'stale') await attachActiveTab(h, next);
      })();
    },
    [commitWs, detachActive, attachActiveTab],
  );

  const closeTabById = useCallback(
    (sessionId: string) => {
      const w = wsRef.current;
      if (w === null) return;
      const h = hostRef.current;
      const wasActive = activeRef.current?.sessionId === sessionId;
      void (async () => {
        if (wasActive) await detachActive(h);
        const next = commitWs(closeTab(w, sessionId));
        // The terminal is retained across screens so its scrollback survives
        // navigation; closing the tab is what frees it.
        void releaseTerminal(sessionId).catch(() => undefined);
        if (h !== null && wasActive && next.tabs.length > 0) {
          await attachActiveTab(h, next);
        }
      })();
    },
    [commitWs, detachActive, attachActiveTab],
  );

  const createNew = useCallback(
    async (kind: 'shell' | 'agent', preset?: Preset) => {
      const h = hostRef.current;
      const w = wsRef.current;
      if (h === null || w === null) return;
      try {
        const s = size.current;
        const created = await createSession(h.id, {
          kind,
          ...(preset !== undefined
            ? { command: preset.command, title: preset.name }
            : {}),
          ...(s !== null ? { cols: s.cols, rows: s.rows } : {}),
        });
        const { state, tab } = addTab(w, {
          sessionId: created.id,
          title: created.title,
          kind: created.kind,
          running: created.running,
        });
        if (tab === null) {
          setErrorText('Too many open tabs. Close one first.');
          return;
        }
        const next = commitWs(setActive(state, tab.sessionId));
        await detachActive(h);
        await attachActiveTab(h, next);
      } catch (e) {
        log.error('session create failed', {
          message: userFacingMessage(toRemotlyError(e, 'unknown')),
        });
        setErrorText(userFacingMessage(toRemotlyError(e, 'unknown')));
      }
    },
    [commitWs, detachActive, attachActiveTab],
  );

  const disconnect = useCallback(() => {
    const h = hostRef.current;
    if (h === null) return;
    void getTransport()
      .close(h.id)
      .catch(() => undefined);
  }, []);

  const leave = useCallback(() => {
    disconnect();
  }, [disconnect]);

  const dismissNotice = useCallback(() => setNotice(null), []);

  // Terminal content never enters logs: event text reaches the banner and the
  // OS notification only, and log lines carry ids and counts at most.
  const onSessionEvent = useCallback((e: SessionEvent) => {
    const h = hostRef.current;
    if (h === null || e.hostId !== h.id) return;
    if (!deduper.current.accept(e.sessionId, e.seq)) return;
    const w = wsRef.current;
    const tab = w !== null ? findTab(w, e.sessionId) : null;
    const ctx = tab !== null && tab.title !== '' ? tab.title : h.daemonName;
    const title = e.kind === 'bell' ? `Bell in ${ctx}` : `Match in ${ctx}`;
    const text = sanitizePreview(e.text ?? '');
    noticeSeq.current += 1;
    setNotice({ id: noticeSeq.current, title, text });
    clearTimeout(noticeTimer.current ?? undefined);
    noticeTimer.current = setTimeout(() => setNotice(null), NOTICE_MS);
    if (notifyRef.current) {
      void postOSNotification({
        hostId: h.id,
        sessionId: e.sessionId,
        hostName: h.daemonName,
        title,
        text,
      }).catch(err => {
        log.error('notification post failed', { message: String(err) });
      });
    }
  }, []);

  const initHost = useCallback(
    async (id: string) => {
      setPhase('loading');
      try {
        const list = await getHosts().list();
        if (disposed.current) return;
        const h = list.find(x => x.id === id) ?? null;
        if (h === null) {
          setErrorText('This host is no longer paired.');
          setPhase('error');
          return;
        }
        setHost(h);
        hostRef.current = h;
        let doc = '';
        try {
          doc = await loadWorkspaceDocument(id);
        } catch (e) {
          log.warn('failed to load workspace document', {
            message: userFacingMessage(toRemotlyError(e, 'storage')),
          });
        }
        const parsed = doc === '' ? null : parseWorkspace(doc, id);
        commitWs(parsed ?? createWorkspace(id));
        await connectRef.current(h);
      } catch (e) {
        if (disposed.current) return;
        log.error('failed to load workspace', {
          message: userFacingMessage(toRemotlyError(e, 'storage')),
        });
        setErrorText(userFacingMessage(toRemotlyError(e, 'storage')));
        setPhase('error');
      }
    },
    [commitWs],
  );

  // Registered once; every handler reads refs, so the first-render closures
  // stay valid for the life of the screen.
  useEffect(() => {
    disposed.current = false;
    log.info('workspace screen mounted');

    if (hostId === '') {
      setErrorText('No host was specified for this workspace.');
      setPhase('error');
      return;
    }
    void initHost(hostId);

    const transport = getTransport();
    const unsubs = [
      transport.onEvent('termData', e => {
        const cur = activeRef.current;
        const h = hostRef.current;
        if (h === null || cur === null || e.hostId !== h.id) return;
        if (e.channelId !== cur.track.channelId) return;
        cur.track.receivedBytes += e.data.length;
        // Replay arrives as a long run of small chunks. Writing each one as it
        // lands makes the user watch the history scroll past, so it is held
        // until replayComplete and written as a single block. Live output,
        // after replay, goes straight through.
        if (readyRef.current && cur.track.replayDone) {
          writeRef.current(e.data);
          return;
        }
        pending.current.push(e.data);
        pendingBytes.current += e.data.length;
        // Over the cap the oldest chunks go, not the newest. Clearing the
        // whole queue threw away the screen the user is about to see and left
        // the terminal blank until the next write; dropping from the front
        // costs only history that no longer fits.
        let dropped = false;
        while (
          pendingBytes.current > PENDING_CAP &&
          pending.current.length > 1
        ) {
          const gone = pending.current.shift();
          if (gone === undefined) break;
          pendingBytes.current -= gone.length;
          dropped = true;
        }
        // Output discarded here leaves the same hole in the history as the
        // daemon's own dropped window, so it is reported the same way. Without
        // this the gap was silent: the banner only ever saw what attach
        // reported.
        if (dropped && cur.track.continuity !== 'gap') {
          cur.track.continuity = 'gap';
          setActiveAttachment({
            sessionId: cur.sessionId,
            track: { ...cur.track },
          });
        }
      }),
      transport.onEvent('replayComplete', e => {
        const cur = activeRef.current;
        const h = hostRef.current;
        if (h === null || cur === null || e.hostId !== h.id) return;
        if (e.channelId !== cur.track.channelId) return;
        cur.track.replayDone = true;
        // The whole replayed screen is written at once, so the terminal draws
        // the settled state rather than every intermediate line.
        flushPending();
        setActiveAttachment({
          sessionId: cur.sessionId,
          track: { ...cur.track },
        });
      }),
      transport.onEvent('channelClose', e => {
        const cur = activeRef.current;
        const h = hostRef.current;
        if (h === null || cur === null || e.hostId !== h.id) return;
        if (e.channelId !== cur.track.channelId) return;
        const sessionId = cur.sessionId;
        setActiveAttachment(null);
        if (e.reason === 'session_exited') {
          commitWs(
            markExited(wsRef.current ?? createWorkspace(h.id), sessionId, null),
          );
        }
      }),
      transport.onEvent('sessionUpdate', e => {
        const h = hostRef.current;
        if (h === null || e.hostId !== h.id) return;
        const w = wsRef.current;
        if (w === null) return;
        let next = applySessionMeta(w, {
          sessionId: e.session.id,
          title: e.session.title,
          preview: e.session.preview,
        });
        if (e.session.running === false) {
          next = markExited(next, e.session.id, e.session.exit?.code ?? null);
        }
        if (next !== w) commitWs(next);
      }),
      transport.onEvent('sessionEvent', onSessionEvent),
      transport.onEvent('disconnected', e => {
        const h = hostRef.current;
        if (h === null || e.hostId !== h.id) return;
        setActiveAttachment(null);
        setVia(null);
        setPhase('disconnected');
        scheduleReconnect();
      }),
    ];

    // Timers do not run while the app is backgrounded, so returning to the
    // foreground retries a dropped connection immediately.
    const appState = AppState.addEventListener('change', s => {
      if (disposed.current) return;
      if (s === 'active' && phaseRef.current === 'disconnected') retryNow();
    });

    return () => {
      disposed.current = true;
      clearTimeout(noticeTimer.current ?? undefined);
      clearTimeout(reconnectTimer.current ?? undefined);
      appState.remove();
      for (const off of unsubs) off();
      const h = hostRef.current;
      if (h !== null) {
        void getTransport()
          .close(h.id)
          .catch(() => undefined);
      }
    };
  }, [
    hostId,
    initHost,
    commitWs,
    setActiveAttachment,
    onSessionEvent,
    scheduleReconnect,
    retryNow,
    flushPending,
  ]);

  return {
    phase,
    host,
    workspace,
    active,
    presets,
    via,
    reconnecting,
    errorText,
    notice,
    dismissNotice,
    onViewportReady,
    resize,
    send,
    selectTab,
    closeTabById,
    createNew,
    retryNow,
    disconnect,
    leave,
  };
}
