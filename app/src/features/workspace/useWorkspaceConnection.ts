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
  MAX_TABS,
  MAX_TITLE_LEN,
  addTab,
  adoptSessions,
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
  killSession,
  listPresets,
  renameSession,
  reportTerminalTitle as reportTerminalTitleTo,
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

type TimerHandle = ReturnType<typeof setTimeout>;

const NOTICE_MS = 5000;
const CURSOR_TICK_MS = 2000;

export interface WorkspaceConnectionOptions {
  hostId: string;
  initialSessionId?: string;
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
  onPtyWrite: (bytes: Uint8Array) => void;
  selectTab: (sessionId: string) => void;
  closeTabById: (sessionId: string) => void;
  createNew: (kind: 'shell' | 'agent', preset?: Preset) => Promise<void>;
  /** Renames a session. The daemon owns the name, so it is sent there. */
  renameTabById: (sessionId: string, title: string) => void;
  /**
   * Records the title the running program set with an escape sequence.
   *
   * Only adopted while the user has not named the session themselves: a name
   * the user typed is theirs, and a shell that repaints its title would
   * otherwise overwrite it on the next prompt.
   */
  reportTerminalTitle: (title: string) => void;
  /** False at the tab cap, so the UI can disable the add action. */
  canAdd: boolean;
  retryNow: () => void;
  disconnect: () => void;
  leave: () => void;
}

export function useWorkspaceConnection({
  hostId,
  initialSessionId,
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

  const initialSessionIdRef = useRef(initialSessionId);
  initialSessionIdRef.current = initialSessionId;

  const wsRef = useRef<WorkspaceState | null>(null);
  const hostRef = useRef<HostRecord | null>(null);
  const activeRef = useRef<ActiveAttachment | null>(null);
  const phaseRef = useRef<WorkspacePhase>('init');
  const notifyRef = useRef(notifyEnabled);
  const readyRef = useRef(false);
  const size = useRef<{ cols: number; rows: number } | null>(null);
  const deduper = useRef(new EventDeduper());
  const backoff = useRef(new Backoff());
  const noticeTimer = useRef<TimerHandle | null>(null);
  const reconnectTimer = useRef<TimerHandle | null>(null);
  const overflowTimer = useRef<TimerHandle | null>(null);
  const overflowRetries = useRef(new Map<string, number>());
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
      const w = wsRef.current;
      if (w !== null) {
        commitWs(setCursor(w, cur.sessionId, cursorOf(cur.track)));
      }
      setActiveAttachment(null);
      if (h !== null) {
        await detachChannel(h.id, cur.track.channelId).catch(() => undefined);
      }
    },
    [commitWs, setActiveAttachment],
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
      // Sessions the daemon is already running are adopted into tabs before
      // reconcile, which only ever updates tabs that exist. Without this a
      // device with no stored workspace saw an empty strip while the daemon
      // held live sessions, so only one tab could ever be opened: a new one.
      const adopted = adoptSessions(
        wsRef.current ?? createWorkspace(h.id),
        sessions.map(s => ({
          sessionId: s.id,
          title: s.title,
          kind: s.kind,
          running: s.running,
        })),
      );
      let reconciled = reconcile(
        adopted,
        sessions.map(s => ({
          sessionId: s.id,
          title: s.title,
          running: s.running,
          preview: s.preview,
        })),
      );
      const initSid = initialSessionIdRef.current;
      if (initSid !== undefined && findTab(reconciled, initSid) !== null) {
        reconciled = setActive(reconciled, initSid);
      }
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
    clearTimeout(overflowTimer.current ?? undefined);
    overflowTimer.current = null;
    overflowRetries.current.clear();
    backoff.current.reset();
    setReconnecting(false);
    void connectRef.current(h);
  }, []);

  const onViewportReady = useCallback(
    (next: { cols: number; rows: number }) => {
      size.current = next;
      readyRef.current = true;
      const cur = activeRef.current;
      const h = hostRef.current;
      if (cur !== null && h !== null) {
        scheduleResizeFor(h.id, cur.sessionId, next.cols, next.rows);
      }
    },
    [scheduleResizeFor],
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

  const onPtyWrite = useCallback(
    (bytes: Uint8Array) => {
      const cur = activeRef.current;
      // Automated queries (such as CPR) during history replay must not be
      // sent to the live session PTY.
      if (cur === null || !cur.track.replayDone) return;
      send(bytes);
    },
    [send],
  );

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
  useEffect(() => {
    if (initialSessionId === undefined || initialSessionId === '') return;
    const w = wsRef.current;
    if (
      w !== null &&
      w.activeSessionId !== initialSessionId &&
      findTab(w, initialSessionId) !== null
    ) {
      selectTab(initialSessionId);
    }
  }, [initialSessionId, selectTab]);

  const renameTabById = useCallback(
    (sessionId: string, title: string) => {
      const h = hostRef.current;
      const w = wsRef.current;
      if (h === null || w === null) return;
      const name = title.trim().slice(0, MAX_TITLE_LEN);
      if (name === '') return;
      // Shown immediately; the daemon's session.update confirms it. Waiting on
      // the round trip made renaming feel like it had not registered.
      commitWs(applySessionMeta(w, { sessionId, title: name }));
      void renameSession(h.id, sessionId, name).catch(e => {
        log.error('session rename failed', {
          message: userFacingMessage(toRemotlyError(e, 'unknown')),
        });
      });
    },
    [commitWs],
  );

  // A shell repaints its title on every prompt, so this keeps following it.
  // The daemon decides whether to take it: once the session has been renamed
  // by hand it keeps that name and answers this as a no-op. Deciding here
  // instead would freeze the tab on the first title it ever saw.
  const reportTerminalTitle = useCallback((title: string) => {
    const h = hostRef.current;
    const sessionId = activeRef.current?.sessionId ?? null;
    if (h === null || sessionId === null) return;
    const name = title.trim().slice(0, MAX_TITLE_LEN);
    if (name === '') return;
    void reportTerminalTitleTo(h.id, sessionId, name).catch(() => undefined);
  }, []);

  const closeTabById = useCallback(
    (sessionId: string) => {
      const w = wsRef.current;
      if (w === null) return;
      const h = hostRef.current;
      const wasActive = activeRef.current?.sessionId === sessionId;
      // The tab goes now, before any round trip. Closing is a local decision
      // and the daemon has no say in it, so waiting on the network first left
      // the tab sitting there looking stuck.
      const next = commitWs(closeTab(w, sessionId));
      // The terminal is retained across screens so its scrollback survives
      // navigation; closing the tab is what frees it.
      void releaseTerminal(sessionId).catch(() => undefined);
      // Ending the session is what stops it running on the daemon. Without it
      // every closed shell stayed alive there, invisible to the app and
      // impossible to close from it. Nothing waits on it: the next tab has to
      // come up now, and a kill that fails leaves a session the list still
      // shows.
      if (h !== null) {
        void killSession(h.id, sessionId).catch(() => undefined);
      }
      if (h === null || !wasActive) return;
      void (async () => {
        // attachActiveTab detaches the old channel before taking the next
        // tab, so the detach is not repeated here.
        const current = wsRef.current ?? next;
        if (current.tabs.length > 0) await attachActiveTab(h, current);
        else await detachActive(h);
      })();
    },
    [commitWs, detachActive, attachActiveTab],
  );

  const createNew = useCallback(
    async (kind: 'shell' | 'agent', preset?: Preset) => {
      const h = hostRef.current;
      const w = wsRef.current;
      if (h === null || w === null) return;
      // Checked before the daemon call, not after. Creating first and then
      // finding no room left the session running on the daemon with no tab
      // referring to it: invisible in the app and impossible to close from it.
      if (w.tabs.length >= MAX_TABS) {
        setErrorText('Too many open tabs. Close one first.');
        return;
      }
      try {
        const s = size.current;
        const created = await createSession(h.id, {
          kind,
          ...(preset !== undefined
            ? { command: preset.command, title: preset.name }
            : {}),
          ...(s !== null ? { cols: s.cols, rows: s.rows } : {}),
        });
        // Re-read: the await above is a daemon round trip, and a tab may have
        // been added or closed while it was in flight. Committing from the
        // pre-await snapshot silently discarded that change.
        const current = wsRef.current ?? w;
        const { state, tab } = addTab(current, {
          sessionId: created.id,
          title: created.title,
          kind: created.kind,
          running: created.running,
        });
        if (tab === null) {
          setErrorText('Too many open tabs. Close one first.');
          return;
        }
        // attachActiveTab detaches first, so a separate detach here only
        // added a second round trip before the new shell could appear.
        const next = commitWs(setActive(state, tab.sessionId));
        await attachActiveTab(h, next);
      } catch (e) {
        log.error('session create failed', {
          message: userFacingMessage(toRemotlyError(e, 'unknown')),
        });
        setErrorText(userFacingMessage(toRemotlyError(e, 'unknown')));
      }
    },
    [commitWs, attachActiveTab],
  );

  const disconnect = useCallback(() => {
    const h = hostRef.current;
    if (h === null) return;
    void getTransport()
      .close(h.id)
      .catch(() => undefined);
  }, []);

  const leave = useCallback(() => {
    clearTimeout(overflowTimer.current ?? undefined);
    overflowTimer.current = null;
    overflowRetries.current.clear();
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
        const chunkLen = e.length ?? e.data?.length ?? 0;
        cur.track.receivedBytes += chunkLen;
      }),
      transport.onEvent('replayComplete', e => {
        const cur = activeRef.current;
        const h = hostRef.current;
        if (h === null || cur === null || e.hostId !== h.id) return;
        if (e.channelId !== cur.track.channelId) return;
        if (e.gap === true && cur.track.continuity !== 'gap') {
          cur.track.continuity = 'gap';
        }
        cur.track.replayDone = true;
        overflowRetries.current.delete(cur.sessionId);
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
        const resumeCursor = cursorOf(cur.track);
        setActiveAttachment(null);
        if (e.reason === 'session_exited') {
          overflowRetries.current.delete(sessionId);
          commitWs(
            markExited(wsRef.current ?? createWorkspace(h.id), sessionId, null),
          );
          return;
        }
        if (e.reason === 'overflow') {
          const retries = overflowRetries.current.get(sessionId) ?? 0;
          if (retries < 3) {
            overflowRetries.current.set(sessionId, retries + 1);
            const delay = 150 * Math.pow(2, retries);
            const w = commitWs(
              setCursor(
                wsRef.current ?? createWorkspace(h.id),
                sessionId,
                resumeCursor,
              ),
            );
            clearTimeout(overflowTimer.current ?? undefined);
            overflowTimer.current = setTimeout(() => {
              void attachActiveTab(h, w);
            }, delay);
            return;
          }
          setErrorText('Output throughput limit reached. Tap to retry.');
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
      const cur = activeRef.current;
      const w = wsRef.current;
      if (cur !== null && w !== null && hostId !== '') {
        const next = setCursor(w, cur.sessionId, cursorOf(cur.track));
        wsRef.current = next;
        void saveWorkspaceDocument(hostId, serializeWorkspace(next)).catch(
          e => {
            log.error('failed to save workspace on unmount', {
              message: userFacingMessage(toRemotlyError(e, 'storage')),
            });
          },
        );
      }
      clearTimeout(noticeTimer.current ?? undefined);
      clearTimeout(reconnectTimer.current ?? undefined);
      clearTimeout(overflowTimer.current ?? undefined);
      overflowTimer.current = null;
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
    attachActiveTab,
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
    onPtyWrite,
    selectTab,
    closeTabById,
    createNew,
    renameTabById,
    reportTerminalTitle,
    canAdd: (workspace?.tabs.length ?? 0) < MAX_TABS,
    retryNow,
    disconnect,
    leave,
  };
}
