// The SSH multi-tab controller.
//
// The sessions themselves live in lib/sshSessions, outside the React tree, so
// navigating back does not kill a running shell. This hook subscribes to that
// store, attaches the terminal as its output sink while mounted, and opens the
// first tab the first time a host is visited.

import {
  useCallback,
  useEffect,
  useRef,
  useState,
  useSyncExternalStore,
} from 'react';
import { sshHosts, type SshHostView } from '../../lib/sshHosts';
import type { SshTabsState } from '../../lib/sshTabs';
import {
  answerSshHostKey,
  attachSshSink,
  closeSshHost,
  closeSshTab,
  openSshTab,
  openSshFilesTab,
  reconnectSshTab,
  renameSshTab,
  reportSshTerminalTitle,
  resizeSshHost,
  selectSshTab,
  sendSshInput,
  sshCanAddTab,
  sshHostKeyPrompt,
  sshHostSized,
  sshHostStarted,
  sshHostState,
  subscribeSshHost,
  type SshHostKeyPrompt,
} from '../../lib/sshSessions';
import { toRemotlyError, userFacingMessage } from '../../lib/errors';

export type { SshHostKeyPrompt } from '../../lib/sshSessions';

export interface SshTabsController {
  host: SshHostView | null;
  state: SshTabsState;
  /** The prompt for the active tab, or null. */
  hostKey: SshHostKeyPrompt | null;
  /** True once the host record has been read. */
  loaded: boolean;
  fatal: string;
  canAdd: boolean;
  onViewportReady: (size: { cols: number; rows: number }) => void;
  resize: (size: { cols: number; rows: number }) => void;
  send: (bytes: Uint8Array) => void;
  newTab: () => void;
  /** Opens (or reveals) the host's file browser tab. */
  openFiles: () => void;
  selectTab: (sessionId: string) => void;
  closeTab: (sessionId: string) => void;
  /** Renames a tab. A blank name is ignored. */
  renameTab: (sessionId: string, title: string) => void;
  /**
   * Records the title the running program set. Adopted only while the tab
   * still carries its generated name.
   */
  reportTitle: (title: string) => void;
  reconnectActive: () => void;
  answerHostKey: (decision: 'accept' | 'replace' | 'reject') => void;
  /** Ends every session for this host. Not called on unmount. */
  disconnect: () => void;
}

export function useSshTabs(
  hostId: string,
  write: (bytes: Uint8Array) => void,
): SshTabsController {
  const [host, setHost] = useState<SshHostView | null>(null);
  const [loaded, setLoaded] = useState(false);
  const [fatal, setFatal] = useState('');

  const state = useSyncExternalStore(
    useCallback(listener => subscribeSshHost(hostId, listener), [hostId]),
    useCallback(() => sshHostState(hostId), [hostId]),
  );
  const hostKey = useSyncExternalStore(
    useCallback(listener => subscribeSshHost(hostId, listener), [hostId]),
    useCallback(() => sshHostKeyPrompt(hostId), [hostId]),
  );
  // Re-runs the open effect once the viewport reports its real grid.
  const sized = useSyncExternalStore(
    useCallback(listener => subscribeSshHost(hostId, listener), [hostId]),
    useCallback(() => sshHostSized(hostId), [hostId]),
  );

  const writeRef = useRef(write);
  writeRef.current = write;

  // The terminal renders this host's output only while the screen is mounted.
  // Detaching leaves the sessions running and buffers what they produce.
  //
  // Re-attached on every tab change: the viewport is remounted for the new
  // session, so the sink has to point at the new terminal. Attaching also
  // replays what the tab buffered while it was hidden, which is what redraws
  // its screen.
  useEffect(() => {
    if (hostId === '') return undefined;
    return attachSshSink(hostId, bytes => writeRef.current(bytes));
  }, [hostId, state.activeSessionId]);

  // Reads the host record, and opens the first tab the first time this host is
  // visited. Returning to a host that already has tabs does not open another.
  useEffect(() => {
    if (hostId === '') {
      setFatal('No host was specified for this terminal.');
      setLoaded(true);
      return undefined;
    }
    let cancelled = false;
    void sshHosts
      .list()
      .then(list => {
        if (cancelled) return;
        const found = list.find(h => h.id === hostId) ?? null;
        setHost(found);
        setLoaded(true);
        if (found === null) {
          setFatal('This host is no longer saved on this device.');
          return;
        }
        // Waits for the viewport's first measurement. A session started
        // against the 80x24 placeholder has a PTY whose row count does not
        // match the screen, and an application that draws its overlay with
        // absolute cursor moves puts it in the wrong place.
        if (!sshHostStarted(hostId) && sshHostSized(hostId)) openSshTab(hostId);
      })
      .catch(e => {
        if (cancelled) return;
        setLoaded(true);
        setFatal(userFacingMessage(toRemotlyError(e, 'storage')));
      });
    return () => {
      cancelled = true;
    };
  }, [hostId, sized]);

  const onViewportReady = useCallback(
    (size: { cols: number; rows: number }) => resizeSshHost(hostId, size),
    [hostId],
  );

  const resize = useCallback(
    (size: { cols: number; rows: number }) => resizeSshHost(hostId, size),
    [hostId],
  );

  const send = useCallback(
    (bytes: Uint8Array) => sendSshInput(hostId, bytes),
    [hostId],
  );

  const newTab = useCallback(() => openSshTab(hostId), [hostId]);
  const openFiles = useCallback(() => openSshFilesTab(hostId), [hostId]);

  const selectTab = useCallback(
    (sessionId: string) => selectSshTab(hostId, sessionId),
    [hostId],
  );

  const closeTab = useCallback(
    (sessionId: string) => closeSshTab(hostId, sessionId),
    [hostId],
  );

  const renameTab = useCallback(
    (sessionId: string, title: string) =>
      renameSshTab(hostId, sessionId, title),
    [hostId],
  );

  const reportTitle = useCallback(
    (title: string) => reportSshTerminalTitle(hostId, title),
    [hostId],
  );

  const reconnectActive = useCallback(() => {
    const sessionId = sshHostState(hostId).activeSessionId;
    if (sessionId !== null) reconnectSshTab(hostId, sessionId);
  }, [hostId]);

  const answerHostKey = useCallback(
    (decision: 'accept' | 'replace' | 'reject') =>
      answerSshHostKey(hostId, decision),
    [hostId],
  );

  const disconnect = useCallback(() => closeSshHost(hostId), [hostId]);

  return {
    host,
    state,
    hostKey:
      hostKey !== null && hostKey.sessionId === state.activeSessionId
        ? hostKey
        : null,
    loaded,
    fatal,
    canAdd: sshCanAddTab(hostId),
    onViewportReady,
    resize,
    send,
    newTab,
    openFiles,
    selectTab,
    closeTab,
    renameTab,
    reportTitle,
    reconnectActive,
    answerHostKey,
    disconnect,
  };
}
