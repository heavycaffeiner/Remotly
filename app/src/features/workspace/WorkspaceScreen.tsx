// A daemon host's workspace: tabbed PTY sessions over the encrypted transport.
//
// Connection, reconnect, attach, and replay live in useWorkspaceConnection;
// the terminal itself is the shared scaffold. This file is composition and
// presentation.

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { ActivityIndicator, View } from 'react-native';
import {
  useNavigation,
  useRoute,
  type RouteProp,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import {
  TerminalScreen,
  type TerminalScreenHandle,
} from '../terminal/TerminalScreen';
import type { TerminalMenuAction } from '../terminal/TerminalToolbar';
import {
  SessionTabs,
  type SessionTabStatus,
  type SessionTabView,
} from '../terminal/SessionTabs';
import NativeCamera from '../../specs/NativeRemotlyCamera';
import { DaemonFilesBackend } from '../../lib/files';
import { DaemonTransferBackend } from '../../lib/daemonTransfer';
import { getTransport } from '../../lib/transport';
import { pasteImage } from '../../lib/imagePaste';
import { NewSessionSheet } from './NewSessionSheet';
import { useWorkspaceConnection } from './useWorkspaceConnection';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toast } from '../../components/Toast';
import { Button } from '../../components/ui/button';
import { Text } from '../../components/ui/text';
import { useSettings } from '../../theme/SettingsProvider';
import { log } from '../../lib/log';
import type { Preset } from '../../lib/sessions';
import type { RootStackParamList } from '../../navigation/types';

type Nav = NativeStackNavigationProp<RootStackParamList>;

// A daemon tab's state, as the shared strip shows it. Attaching reads as busy;
// a session the daemon no longer has is gone.
const TAB_STATUS: Record<string, SessionTabStatus> = {
  attaching: 'busy',
  attached: 'live',
  exited: 'ended',
  stale: 'gone',
};

export function WorkspaceScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProp<RootStackParamList, 'Workspace'>>();
  const hostId = route.params?.hostId ?? '';
  const initialSessionId = route.params?.sessionId;
  const { settings, update } = useSettings();

  const terminal = useRef<TerminalScreenHandle | null>(null);
  const [pickerOpen, setPickerOpen] = useState(false);
  const [creating, setCreating] = useState(false);
  const [closeTarget, setCloseTarget] = useState<string | null>(null);
  const [copyNotice, setCopyNotice] = useState('');

  const write = useCallback((bytes: Uint8Array) => {
    void terminal.current?.write(bytes).catch(err => {
      log.error('terminal write failed', { message: String(err) });
    });
  }, []);

  const ws = useWorkspaceConnection({
    hostId,
    initialSessionId,
    write,
    notifyEnabled: settings.notifyEnabled,
  });

  const goBack = useCallback(() => {
    ws.leave();
    navigation.goBack();
  }, [ws, navigation]);

  // An image cannot be typed into a terminal, so it is written to the remote
  // host and its path is typed instead. That works for anything that takes a
  // filename, not only for one particular agent.
  const pasteClipboardImage = useCallback(async () => {
    try {
      const clip = await NativeCamera.readClipboardImage();
      if (clip.data === '') {
        setCopyNotice('The clipboard holds no image');
        return;
      }
      const roots = await new DaemonFilesBackend(req =>
        getTransport().control(hostId, req),
      ).roots();
      const home = roots[0] ?? '/tmp';
      setCopyNotice('Uploading the image');
      const result = await pasteImage(
        clip,
        new DaemonTransferBackend(getTransport(), hostId),
        home,
      );
      ws.send(new TextEncoder().encode(result.text));
      setCopyNotice('Pasted the image path');
    } catch (e) {
      setCopyNotice(
        e instanceof Error ? e.message : 'Could not paste that image',
      );
    }
  }, [hostId, ws]);

  const openFiles = useCallback(() => {
    navigation.navigate('Files', { hostId, kind: 'daemon' });
  }, [navigation, hostId]);

  const handleSelectTab = useCallback(
    (sessionId: string) => {
      ws.selectTab(sessionId);
      // Keeps focus only when the keyboard is already up; never reopens one
      // the user dismissed.
      terminal.current?.handleSessionSwitch();
    },
    [ws],
  );

  // A swipe moves to the neighbouring tab. It stops at the ends rather than
  // wrapping, so the gesture cannot silently jump across the whole strip.
  const switchSession = useCallback(
    (direction: -1 | 1) => {
      const list = ws.workspace?.tabs ?? [];
      const index = list.findIndex(
        t => t.sessionId === ws.workspace?.activeSessionId,
      );
      if (index < 0) return;
      const next = list[index + direction];
      if (next === undefined) return;
      handleSelectTab(next.sessionId);
    },
    [ws.workspace, handleSelectTab],
  );

  const handleCreate = useCallback(
    async (kind: 'shell' | 'agent', preset?: Preset) => {
      setCreating(true);
      try {
        await ws.createNew(kind, preset);
        setPickerOpen(false);
      } finally {
        setCreating(false);
      }
    },
    [ws],
  );

  const actions = useMemo<TerminalMenuAction[]>(
    () => [
      { key: 'files', title: 'Files', icon: 'folder', onPress: openFiles },
      {
        key: 'new',
        title: 'New session',
        icon: 'plus',
        onPress: () => setPickerOpen(true),
      },
      {
        key: 'select-all',
        title: 'Select all',
        icon: 'select',
        onPress: () => void terminal.current?.selectAll(),
      },
      {
        key: 'copy',
        title: 'Copy selection',
        icon: 'copy',
        onPress: () => {
          void terminal.current?.copy().then(text => {
            setCopyNotice(text === null ? 'Nothing selected' : 'Copied');
          });
        },
      },
      {
        key: 'paste',
        title: 'Paste',
        icon: 'clipboard',
        onPress: () => terminal.current?.paste(),
      },
      {
        key: 'paste-image',
        title: 'Paste an image',
        icon: 'image',
        onPress: () => void pasteClipboardImage(),
      },
      {
        key: 'disconnect',
        title: 'Disconnect',
        icon: 'unplug',
        destructive: true,
        onPress: ws.disconnect,
      },
    ],
    [openFiles, ws.disconnect, pasteClipboardImage],
  );

  const tabs = useMemo(() => ws.workspace?.tabs ?? [], [ws.workspace]);
  const activeTab = tabs.find(
    t => t.sessionId === ws.workspace?.activeSessionId,
  );
  const tabViews = useMemo<SessionTabView[]>(
    () =>
      tabs.map(t => ({
        sessionId: t.sessionId,
        label: t.title !== '' ? t.title : t.kind,
        status: TAB_STATUS[t.state] ?? 'live',
      })),
    [tabs],
  );

  const title = ws.host?.daemonName || 'Workspace';
  const subtitle =
    ws.via === null
      ? undefined
      : ws.via === 'relay'
      ? 'Connected via relay'
      : 'Connected direct';

  const banner = useMemo(() => {
    if (ws.phase === 'connecting') {
      return { tone: 'busy' as const, message: 'Connecting' };
    }
    if (ws.phase === 'disconnected') {
      return {
        tone: 'error' as const,
        message: ws.reconnecting
          ? 'Disconnected. Reconnecting.'
          : ws.errorText || 'Disconnected.',
        action: { label: 'Retry', onPress: ws.retryNow },
      };
    }
    if (ws.active?.track.continuity === 'gap') {
      return {
        tone: 'info' as const,
        message: 'Some earlier output was not kept and is missing.',
      };
    }
    if (ws.notice !== null) {
      return {
        tone: 'info' as const,
        message: `${ws.notice.title}: ${ws.notice.text}`,
        action: { label: 'Dismiss', onPress: ws.dismissNotice },
      };
    }
    if (ws.errorText !== '' && ws.phase === 'connected') {
      return { tone: 'error' as const, message: ws.errorText };
    }
    return null;
  }, [
    ws.phase,
    ws.reconnecting,
    ws.errorText,
    ws.active,
    ws.notice,
    ws.retryNow,
    ws.dismissNotice,
  ]);

  const overlay =
    ws.phase === 'error' ? (
      <View className="items-center gap-4">
        <Text variant="title" className="text-center text-terminal-foreground">
          {ws.errorText}
        </Text>
        <Button onPress={goBack}>
          <Text>Go back</Text>
        </Button>
      </View>
    ) : ws.phase === 'loading' || ws.phase === 'init' ? (
      <ActivityIndicator accessibilityLabel="Loading the workspace" />
    ) : tabs.length === 0 && ws.phase === 'connected' ? (
      <View className="items-center gap-4">
        <Text variant="title" className="text-center text-terminal-foreground">
          No sessions yet
        </Text>
        <Button onPress={() => setPickerOpen(true)}>
          <Text>New session</Text>
        </Button>
      </View>
    ) : null;

  return (
    <>
      <TerminalScreen
        ref={terminal}
        title={title}
        {...(subtitle ? { subtitle } : {})}
        onBack={goBack}
        onSend={ws.send}
        onPtyWrite={ws.onPtyWrite}
        onResize={ws.resize}
        sessionKey={activeTab?.sessionId ?? ''}
        {...(activeTab ? { sessionId: activeTab.sessionId } : {})}
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
        toolbarPrimary={{
          icon: 'keyboard',
          label: 'Show the keyboard',
          onPress: () => terminal.current?.focus(),
        }}
        {...(overlay ? { overlay } : {})}
        onReady={ws.onViewportReady}
        onTitle={ws.reportTerminalTitle}
        {...(tabs.length > 1 ? { onSwitchSession: switchSession } : {})}
        sessionIndex={tabs.findIndex(t => t.sessionId === activeTab?.sessionId)}
        tabStrip={
          tabs.length > 0 ? (
            <SessionTabs
              tabs={tabViews}
              activeSessionId={ws.workspace?.activeSessionId ?? null}
              onSelect={handleSelectTab}
              onClose={setCloseTarget}
              onRename={ws.renameTabById}
              onNew={() => setPickerOpen(true)}
              canAdd={ws.canAdd}
            />
          ) : null
        }
      />

      <NewSessionSheet
        visible={pickerOpen}
        presets={ws.presets}
        busy={creating}
        onDismiss={() => setPickerOpen(false)}
        onCreate={(kind, preset) => void handleCreate(kind, preset)}
      />

      <ConfirmDialog
        visible={closeTarget !== null}
        title="Close this session?"
        message="The tab is removed from this device and the session is detached. The session keeps running on the daemon."
        confirmLabel="Close tab"
        onConfirm={() => {
          if (closeTarget !== null) ws.closeTabById(closeTarget);
          setCloseTarget(null);
        }}
        onDismiss={() => setCloseTarget(null)}
      />

      <Toast message={copyNotice} onDismiss={() => setCopyNotice('')} />
    </>
  );
}
