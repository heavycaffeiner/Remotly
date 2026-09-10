// A terminal attached to one herdr workspace, with that workspace's tabs.
//
// The strip here is herdr's, not the app's: every chip is a herdr tab, and
// selecting, adding, renaming, or closing one goes to the host rather than to
// local state. The app's own SSH tabs live in SshTerminal and are untouched by
// this screen, so a workspace and a plain shell never share a strip.
//
// One terminal per herdr session. Which workspace has focus is session state,
// so a second terminal on the same session could only mirror this one;
// entering another workspace moves this terminal instead.

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import {
  useFocusEffect,
  useNavigation,
  useRoute,
} from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSyncExternalStore } from 'react';

import {
  TerminalScreen,
  type TerminalScreenHandle,
} from '../terminal/TerminalScreen';
import type { TerminalMenuAction } from '../terminal/TerminalToolbar';
import { SessionTabs, type SessionTabView } from '../terminal/SessionTabs';
import { Toast } from '../../components/Toast';
import { Text } from '../../components/ui/text';
import { TERMINAL_FOREGROUND } from '../../theme/terminalChrome';
import { useSettings } from '../../theme/SettingsProvider';
import { joinShell } from '../../lib/shell';
import {
  closeHerdrTab,
  createHerdrTab,
  focusHerdrTab,
  focusHerdrWorkspace,
  invokeHerdrPluginAction,
  listHerdrTabs,
  moveHerdrWorkspace,
  renameHerdrTab,
} from '../../lib/herdrClient';
import { HerdrError, type HerdrTab } from '../../lib/herdr';
import {
  closeSshTab,
  openSshWorkspaceTab,
  reconnectSshTab,
  reportSshTerminalTitle,
  resizeSshHost,
  sendSshInput,
  sshHostState,
  subscribeSshHost,
} from '../../lib/sshSessions';
import { findSshTab } from '../../lib/sshTabs';
import { postTerminalNotification } from '../../lib/terminalNotify';
import type { MuxAction } from '../terminal/muxKeys';
import type { RootStackParamList } from '../../navigation/types';

type Nav = NativeStackNavigationProp<RootStackParamList, 'HerdrWorkspace'>;
type Route = RouteProp<RootStackParamList, 'HerdrWorkspace'>;

/** The plugin that carries the actions herdr's own CLI cannot express. */
const PLUGIN = 'remotly.bridge';

/** How often the strip is re-read while the screen is up, in ms. */
const POLL_MS = 4000;

/** How long a gesture's chord needs before herdr has acted on it, in ms. */
const GESTURE_SETTLE_MS = 450;

/** A herdr failure, said in the user's terms. */
function message(e: unknown): string {
  if (e instanceof HerdrError) {
    if (e.code === 'plugin_action_not_found') {
      return 'This host has no Remotly plugin. Install it with: herdr plugin install heavycaffeiner/Remotly/plugin';
    }
    return e.detail;
  }
  return 'The herdr command failed.';
}

export function HerdrWorkspaceScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const params = useRoute<Route>().params;
  const { hostId, hostName, session } = params;
  const { settings, update } = useSettings();
  const terminal = useRef<TerminalScreenHandle>(null);

  // Which workspace this terminal is on. It starts as the one entered and
  // changes when a gesture moves the session to another: the terminal follows
  // the session's focus, so the strip and the title have to follow it too.
  const [here, setHere] = useState({
    workspaceId: params.workspaceId,
    label: params.label,
  });
  const { workspaceId, label } = here;

  const [tabs, setTabs] = useState<HerdrTab[]>([]);
  const [notice, setNotice] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [renameRequest, setRenameRequest] = useState(0);

  const hostState = useSyncExternalStore(
    useCallback(cb => subscribeSshHost(hostId, cb), [hostId]),
    useCallback(() => sshHostState(hostId), [hostId]),
  );
  const tab = sessionId === null ? null : findSshTab(hostState, sessionId);

  /** Re-reads the workspace's tabs. Quiet on failure: the strip keeps what it
   *  has rather than emptying under a transient error. */
  const load = useCallback(async () => {
    try {
      setTabs(await listHerdrTabs(hostId, workspaceId, session));
    } catch {
      // Left as it was.
    }
  }, [hostId, workspaceId, session]);

  /** Runs one herdr call, then re-reads: herdr owns the tabs, not this screen. */
  const act = useCallback(
    async (run: () => Promise<void>) => {
      try {
        await run();
      } catch (e) {
        setNotice(message(e));
      }
      await load();
    },
    [load],
  );

  // Focus the workspace, then attach. Focusing first is what makes the
  // terminal come up on this workspace rather than on whichever one the
  // session was last left on.
  useFocusEffect(
    useCallback(() => {
      let cancelled = false;
      void (async () => {
        try {
          await focusHerdrWorkspace(hostId, workspaceId, session);
        } catch (e) {
          setNotice(message(e));
        }
        if (cancelled) return;
        const runs =
          session === null
            ? 'herdr'
            : joinShell(['herdr', '--session', session]);
        const opened = openSshWorkspaceTab(hostId, {
          workspaceId,
          label,
          runs,
          ...(session === null ? {} : { session }),
        });
        if (opened === null) {
          setNotice('This host has no room for another terminal.');
          return;
        }
        setSessionId(opened);
        await load();
      })();
      return () => {
        cancelled = true;
      };
    }, [hostId, workspaceId, label, session, load]),
  );

  // Tabs also change from the desktop and from the terminal's own gestures,
  // so the strip is re-read on a timer rather than only after the app acts.
  useEffect(() => {
    const timer = setInterval(() => void load(), POLL_MS);
    return () => clearInterval(timer);
  }, [load]);

  /**
   * Finishes a gesture.
   *
   * A tab move is a chord the terminal already sent, so this only catches the
   * strip up. A workspace move has no chord: herdr ships those unbound, so
   * the move itself is made here, and the title and strip follow it.
   */
  const onGesture = useCallback(
    (action: MuxAction) => {
      if (action === 'tab-next' || action === 'tab-previous') {
        // The chord lands in the terminal before herdr has answered it.
        setTimeout(() => void load(), GESTURE_SETTLE_MS);
        return;
      }
      const direction = action === 'workspace-next' ? 1 : -1;
      void (async () => {
        try {
          const next = await moveHerdrWorkspace(hostId, direction, session);
          if (next === null) {
            setNotice('This session has only one workspace.');
            return;
          }
          setHere({ workspaceId: next.workspaceId, label: next.label });
        } catch (e) {
          setNotice(message(e));
        }
      })();
    },
    [hostId, session, load],
  );

  const selectTab = useCallback(
    (tabId: string) => void act(() => focusHerdrTab(hostId, tabId, session)),
    [act, hostId, session],
  );

  const newTab = useCallback(
    () =>
      void act(() =>
        createHerdrTab(hostId, { workspaceId, focus: true }, session),
      ),
    [act, hostId, workspaceId, session],
  );

  const closeTab = useCallback(
    (tabId: string) => void act(() => closeHerdrTab(hostId, tabId, session)),
    [act, hostId, session],
  );

  const renameTab = useCallback(
    (tabId: string, title: string) =>
      void act(() => renameHerdrTab(hostId, tabId, title, session)),
    [act, hostId, session],
  );

  const runPlugin = useCallback(
    (action: string) =>
      void act(() =>
        invokeHerdrPluginAction(hostId, `${PLUGIN}.${action}`, session),
      ),
    [act, hostId, session],
  );

  const send = useCallback(
    (bytes: Uint8Array) => sendSshInput(hostId, bytes),
    [hostId],
  );

  const resize = useCallback(
    (size: { cols: number; rows: number }) => resizeSshHost(hostId, size),
    [hostId],
  );

  const focused = tabs.find(t => t.focused) ?? null;

  const tabViews = useMemo<SessionTabView[]>(
    () =>
      tabs.map(t => ({
        sessionId: t.tabId,
        label: t.label === '' ? String(t.number) : t.label,
        status: t.agentStatus === 'working' ? 'busy' : 'live',
      })),
    [tabs],
  );

  const strip = (
    <SessionTabs
      tabs={tabViews}
      activeSessionId={focused?.tabId ?? null}
      onSelect={selectTab}
      onClose={closeTab}
      onNew={newTab}
      onRename={renameTab}
      renameRequest={renameRequest}
    />
  );

  const actions = useMemo<TerminalMenuAction[]>(
    () => [
      { key: 'new-tab', title: 'New tab', icon: 'plus', onPress: newTab },
      {
        key: 'tab-here',
        title: 'New tab here',
        icon: 'folder',
        onPress: () => runPlugin('tab-here'),
      },
      {
        key: 'panes-to-tabs',
        title: 'Panes to tabs',
        icon: 'view-dashboard',
        onPress: () => runPlugin('panes-to-tabs'),
      },
      {
        key: 'zoom',
        title: 'Toggle pane zoom',
        icon: 'arrow-up',
        onPress: () => runPlugin('zoom'),
      },
      {
        key: 'rename',
        title: 'Rename tab',
        icon: 'pencil',
        disabled: focused === null,
        onPress: () => setRenameRequest(n => n + 1),
      },
      {
        key: 'close',
        title: 'Close tab',
        icon: 'close',
        disabled: focused === null,
        onPress: () => {
          if (focused !== null) closeTab(focused.tabId);
        },
      },
      {
        key: 'detach',
        title: 'Close this terminal',
        icon: 'link-off',
        destructive: true,
        disabled: sessionId === null,
        onPress: () => {
          if (sessionId !== null) closeSshTab(hostId, sessionId);
          navigation.goBack();
        },
      },
    ],
    [newTab, runPlugin, focused, closeTab, sessionId, hostId, navigation],
  );

  const banner = useMemo(() => {
    if (tab === null) return null;
    if (tab.phase === 'connecting') {
      return { tone: 'busy' as const, message: 'Attaching' };
    }
    if (tab.phase === 'hostKey') {
      return {
        tone: 'error' as const,
        message: 'This host key has not been accepted yet.',
      };
    }
    if (tab.phase === 'closed' || tab.phase === 'failed') {
      return {
        tone: 'error' as const,
        message: tab.detail || 'The terminal is closed.',
        action: {
          label: 'Reconnect',
          onPress: () => {
            if (sessionId !== null) reconnectSshTab(hostId, sessionId);
          },
        },
      };
    }
    return null;
  }, [tab, hostId, sessionId]);

  const overlay =
    sessionId === null ? (
      <Text
        variant="title"
        style={{ textAlign: 'center', color: TERMINAL_FOREGROUND }}
      >
        Attaching to {label}
      </Text>
    ) : null;

  const keyboardPrimary = settings.showExtraKeyRow
    ? {}
    : {
        toolbarPrimary: {
          icon: 'keyboard' as const,
          label: 'Show the keyboard',
          onPress: () => terminal.current?.focus(),
        },
      };

  return (
    <>
      <TerminalScreen
        ref={terminal}
        title={label}
        subtitle={`${hostName} · ${session ?? 'default'}`}
        onBack={() => navigation.goBack()}
        onSend={send}
        onResize={resize}
        sessionKey={sessionId ?? ''}
        {...(sessionId === null ? {} : { sessionId })}
        fontSize={settings.terminalFontSize}
        cursorStyle={settings.cursorStyle}
        autoOpenKeyboard={settings.openKeyboardOnTerminal}
        showKeyRow={settings.showExtraKeyRow}
        keyRepeatDelayMs={settings.keyRepeatDelayMs}
        haptics={settings.hapticFeedback}
        onFontSizeChange={fontSize => {
          void update({ terminalFontSize: fontSize });
        }}
        banner={banner}
        toolbarActions={actions}
        {...keyboardPrimary}
        {...(overlay === null ? {} : { overlay })}
        tabStrip={strip}
        mux="herdr"
        onMuxAction={onGesture}
        onTitle={title => reportSshTerminalTitle(hostId, title)}
        onNotify={postTerminalNotification}
      />
      <Toast message={notice} onDismiss={() => setNotice('')} />
    </>
  );
}
