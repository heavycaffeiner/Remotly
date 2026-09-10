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
//
// What the strip and the sidebar draw comes from the event-fed store, so a
// change made here, from a gesture, or on the desktop lands without a timer.

import React, { useCallback, useMemo, useRef, useState } from 'react';
import {
  useFocusEffect,
  useNavigation,
  useRoute,
} from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';
import { useSyncExternalStore } from 'react';
import { View } from 'react-native';

import {
  TerminalScreen,
  type TerminalScreenHandle,
} from '../terminal/TerminalScreen';
import type { TerminalMenuAction } from '../terminal/TerminalToolbar';
import { SessionTabs, type SessionTabView } from '../terminal/SessionTabs';
import { HostSidebar } from './HostSidebar';
import { useDrawerPermanent } from '../../components/ui/drawer';
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
  nextHerdrWorkspace,
  renameHerdrTab,
} from '../../lib/herdrClient';
import { HerdrError } from '../../lib/herdr';
import {
  applyHerdrLocal,
  herdrHostState,
  refreshHerdrHost,
  subscribeHerdrHost,
  type HerdrHostState,
} from '../../lib/herdrStore';
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

/** A herdr failure, said in the user's terms. */
function message(e: unknown): string {
  if (e instanceof HerdrError) return e.message;
  return e instanceof Error && e.message !== '' ? e.message : 'herdr failed.';
}

export function HerdrWorkspaceScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const params = useRoute<Route>().params;
  const { hostId, hostName, session } = params;
  const { settings, update } = useSettings();
  const terminal = useRef<TerminalScreenHandle>(null);

  // Which workspace this terminal is on. It starts as the one entered, or as
  // whichever herdr has focused when the caller named none, and changes when a
  // gesture or the sidebar moves the session: the terminal follows the
  // session's focus, so the strip and the title follow it too.
  const [here, setHere] = useState<{
    workspaceId: string | null;
    label: string;
  }>({
    workspaceId: params.workspaceId ?? null,
    label: params.label ?? '',
  });

  const [notice, setNotice] = useState('');
  const [sessionId, setSessionId] = useState<string | null>(null);
  const [renameRequest, setRenameRequest] = useState(0);
  const [sidebar, setSidebar] = useState(false);
  const permanent = useDrawerPermanent();

  const herdr: HerdrHostState = useSyncExternalStore(
    useCallback(
      cb => subscribeHerdrHost(hostId, session, cb),
      [hostId, session],
    ),
    useCallback(() => herdrHostState(hostId, session), [hostId, session]),
  );

  // A screen entered without a workspace lands on the focused one, which is
  // what "open herdr on this host" means.
  const workspaceId = here.workspaceId ?? herdr.focusedWorkspaceId;
  const workspace =
    herdr.workspaces.find(w => w.workspaceId === workspaceId) ?? null;
  const label = workspace?.label ?? here.label;
  const tabs = useMemo(
    () => (workspaceId === null ? [] : herdr.tabs[workspaceId] ?? []),
    [herdr.tabs, workspaceId],
  );

  const hostState = useSyncExternalStore(
    useCallback(cb => subscribeSshHost(hostId, cb), [hostId]),
    useCallback(() => sshHostState(hostId), [hostId]),
  );
  const tab = sessionId === null ? null : findSshTab(hostState, sessionId);

  /** Runs one herdr call. herdr owns the tabs, and its events report them. */
  const act = useCallback(
    async (run: () => Promise<void>) => {
      try {
        await run();
      } catch (e) {
        setNotice(message(e));
        await refreshHerdrHost(hostId, session);
      }
    },
    [hostId, session],
  );

  // Focus the workspace, then attach. Focusing first is what makes the
  // terminal come up on this workspace rather than on whichever one the
  // session was last left on.
  useFocusEffect(
    useCallback(() => {
      if (workspaceId === null) return;
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
      })();
      return () => {
        cancelled = true;
      };
    }, [hostId, workspaceId, label, session]),
  );

  /**
   * Finishes a gesture.
   *
   * A tab move is a chord the terminal already sent and herdr's own event
   * reports where it landed, so there is nothing to do here. A workspace move
   * has no chord: herdr ships those unbound, so the move is made over the
   * socket, from the order this screen is already holding.
   */
  const onGesture = useCallback(
    (action: MuxAction) => {
      if (action !== 'workspace-next' && action !== 'workspace-previous') {
        return;
      }
      const direction = action === 'workspace-next' ? 1 : -1;
      const next = nextHerdrWorkspace(
        { workspaces: herdr.workspaces, focusedWorkspaceId: workspaceId },
        direction,
      );
      if (next === null) {
        setNotice('This session has only one workspace.');
        return;
      }
      // Painted before the command is sent. The gesture has to answer in the
      // frame it was made; herdr's own event confirms the same ids after.
      setHere({ workspaceId: next.workspaceId, label: next.label });
      applyHerdrLocal(hostId, session, {
        kind: 'workspace-focused',
        workspaceId: next.workspaceId,
      });
      void act(() => focusHerdrWorkspace(hostId, next.workspaceId, session));
    },
    [act, hostId, session, herdr.workspaces, workspaceId],
  );

  const selectTab = useCallback(
    (tabId: string) => {
      // Painted before the round trip; the event that follows carries the same
      // ids, so it lands as a no-op.
      if (workspaceId !== null) {
        applyHerdrLocal(hostId, session, {
          kind: 'tab-focused',
          tabId,
          workspaceId,
        });
      }
      void act(() => focusHerdrTab(hostId, tabId, session));
    },
    [act, hostId, session, workspaceId],
  );

  const newTab = useCallback(() => {
    if (workspaceId === null) return;
    void act(() =>
      createHerdrTab(hostId, { workspaceId, focus: true }, session),
    );
  }, [act, hostId, workspaceId, session]);

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

  const focusedTabId =
    tabs.find(t => t.tabId === herdr.focusedTabId)?.tabId ??
    tabs.find(t => t.focused)?.tabId ??
    null;

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
      activeSessionId={focusedTabId}
      onSelect={selectTab}
      onClose={closeTab}
      onNew={newTab}
      onRename={renameTab}
      renameRequest={renameRequest}
    />
  );

  // Terminal actions only. Everything that manages a workspace or a tab lives
  // on its own row in the sidebar, which is also the path for anyone who
  // cannot make the gestures.
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
        disabled: focusedTabId === null,
        onPress: () => setRenameRequest(n => n + 1),
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
    [newTab, runPlugin, focusedTabId, sessionId, hostId, navigation],
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
    <View style={{ flex: 1, flexDirection: 'row' }}>
      <HostSidebar
        hostId={hostId}
        hostName={hostName}
        session={session}
        open={sidebar}
        onClose={() => setSidebar(false)}
        permanent={permanent}
        currentWorkspaceId={workspaceId}
        onEnterWorkspace={request => {
          // The terminal follows the session's focus, so entering another
          // workspace moves this one rather than stacking a second.
          setHere({ workspaceId: request.workspaceId, label: request.label });
        }}
        onOpenShells={() => navigation.navigate('SshTerminal', { hostId })}
        onOpenFiles={() => navigation.navigate('Files', { hostId })}
      />
      <View style={{ flex: 1 }}>
        <TerminalScreen
          ref={terminal}
          title={label}
          subtitle={`${hostName}, ${session ?? 'default'}`}
          onBack={() => navigation.goBack()}
          {...(permanent ? {} : { onSidebar: () => setSidebar(true) })}
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
      </View>
      <Toast message={notice} onDismiss={() => setNotice('')} />
    </View>
  );
}
