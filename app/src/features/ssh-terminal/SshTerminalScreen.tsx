// Tabbed shells over plain SSH, independent of any Remotly daemon.
//
// Several tabs per host, each a separate native session. Only the active tab
// draws into the terminal viewport; the rest stay connected and buffer their
// output. Tabs are live-only, so a closed one is closed for good.

import React, {
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from 'react';
import { View } from 'react-native';
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
import { useSshTabs, type SshHostKeyPrompt } from './useSshTabs';
import { FilesScreen } from '../files/FilesScreen';
import { Toast } from '../../components/Toast';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Text } from '../../components/ui/text';
import { useSettings } from '../../theme/SettingsProvider';
import { log } from '../../lib/log';
import { sshHostDisplayName } from '../../lib/sshHosts';
import type { SshTabPhase } from '../../lib/sshTabs';
import type { RootStackParamList } from '../../navigation/types';

type Nav = NativeStackNavigationProp<RootStackParamList>;

const TAB_STATUS: Record<SshTabPhase, SessionTabStatus> = {
  connecting: 'busy',
  hostKey: 'busy',
  active: 'live',
  closed: 'ended',
  failed: 'gone',
};

export function SshTerminalScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const route = useRoute<RouteProp<RootStackParamList, 'SshTerminal'>>();
  const hostId = route.params?.hostId ?? '';
  const { settings, update } = useSettings();

  const terminal = useRef<TerminalScreenHandle | null>(null);
  const [copyNotice, setCopyNotice] = useState('');
  // Asks the tab strip to open its rename dialog, so the menu item and the
  // long press share one dialog rather than each having their own.
  const [renameRequest, setRenameRequest] = useState(0);
  // Restores the keyboard after the host key dialog closes, but only when it
  // was up beforehand. Opening one unprompted is a keyboard nobody asked for.
  const restoreAfterDialog = useRef<(() => void) | null>(null);

  const write = useCallback((bytes: Uint8Array) => {
    void terminal.current?.write(bytes).catch(err => {
      log.error('terminal write failed', { message: String(err) });
    });
  }, []);

  const ssh = useSshTabs(hostId, write);
  const { state, disconnect } = ssh;

  // The bare session id, which is what lib/sshSessions writes background output
  // under. Prefixing it with the host pointed the native view at a different
  // terminal from the one holding the tab's output: the view adopted an empty
  // terminal while the real screen sat in one nobody drew, which read as
  // rendering a beat behind and as scrollback that would not refresh.
  const sessionIdProp =
    state.activeSessionId === null ? {} : { sessionId: state.activeSessionId };
  const activeTab =
    state.activeSessionId === null
      ? null
      : state.tabs.find(t => t.sessionId === state.activeSessionId) ?? null;

  // Back leaves the sessions running, the same way the daemon workspace does.
  // Closing a tab or Disconnect is what ends one.
  const goBack = useCallback(() => {
    navigation.goBack();
  }, [navigation]);

  const disconnectAll = useCallback(() => {
    disconnect();
    navigation.goBack();
  }, [disconnect, navigation]);

  // Files is a tab on this host, not a separate page: it can be left open
  // alongside a shell and keeps browsing while a transfer runs.
  const openFiles = useCallback(() => ssh.openFiles(), [ssh]);

  const handleSelect = useCallback(
    (sessionId: string) => {
      ssh.selectTab(sessionId);
      terminal.current?.handleSessionSwitch();
    },
    [ssh],
  );

  // A swipe moves to the neighbouring tab. It stops at the ends rather than
  // wrapping, so the gesture cannot silently jump across the whole strip.
  const switchSession = useCallback(
    (direction: -1 | 1) => {
      const index = state.tabs.findIndex(
        t => t.sessionId === state.activeSessionId,
      );
      if (index < 0) return;
      const next = state.tabs[index + direction];
      if (next === undefined) return;
      handleSelect(next.sessionId);
    },
    [state.tabs, state.activeSessionId, handleSelect],
  );

  const actions = useMemo<TerminalMenuAction[]>(
    () => [
      { key: 'files', title: 'Files', icon: 'folder', onPress: openFiles },
      {
        key: 'new',
        title: 'New session',
        icon: 'plus',
        onPress: ssh.newTab,
        disabled: !ssh.canAdd,
      },
      {
        key: 'rename',
        title: 'Rename session',
        icon: 'pencil',
        disabled: activeTab === null,
        onPress: () => setRenameRequest(n => n + 1),
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
        key: 'disconnect',
        title: 'Disconnect',
        icon: 'unplug',
        destructive: true,
        onPress: disconnectAll,
      },
    ],
    [openFiles, ssh.newTab, ssh.canAdd, disconnectAll, activeTab],
  );

  const title = ssh.host === null ? 'SSH' : sshHostDisplayName(ssh.host);
  const subtitle =
    ssh.host === null
      ? undefined
      : `${ssh.host.username}@${ssh.host.host}:${ssh.host.port}`;

  const banner = useMemo(() => {
    if (activeTab === null) return null;
    if (activeTab.phase === 'connecting') {
      return { tone: 'busy' as const, message: 'Connecting' };
    }
    if (activeTab.phase === 'closed') {
      return {
        tone: 'info' as const,
        message: activeTab.detail || 'The session is closed.',
        action: { label: 'Reconnect', onPress: ssh.reconnectActive },
      };
    }
    if (activeTab.phase === 'failed') {
      return {
        tone: 'error' as const,
        message: activeTab.detail || 'The connection failed.',
        action: { label: 'Retry', onPress: ssh.reconnectActive },
      };
    }
    return null;
  }, [activeTab, ssh.reconnectActive]);

  const overlay =
    ssh.fatal !== '' ? (
      <View className="items-center gap-4">
        <Text variant="title" className="text-center text-terminal-foreground">
          {ssh.fatal}
        </Text>
        <Button onPress={goBack}>
          <Text>Go back</Text>
        </Button>
      </View>
    ) : state.tabs.length === 0 && ssh.loaded ? (
      <View className="items-center gap-4">
        <Text variant="title" className="text-center text-terminal-foreground">
          No open sessions
        </Text>
        <Button onPress={ssh.newTab}>
          <Text>New session</Text>
        </Button>
      </View>
    ) : null;

  const tabViews = useMemo<SessionTabView[]>(
    () =>
      state.tabs.map(t => ({
        sessionId: t.sessionId,
        label: t.title,
        status: TAB_STATUS[t.phase],
        ...(t.kind === 'files' ? { icon: 'folder' as const } : {}),
      })),
    [state.tabs],
  );

  return (
    <>
      <TerminalScreen
        ref={terminal}
        title={title}
        {...(subtitle ? { subtitle } : {})}
        onBack={goBack}
        onSend={ssh.send}
        onResize={ssh.resize}
        sessionKey={state.activeSessionId ?? ''}
        {...sessionIdProp}
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
        {...(activeTab?.kind === 'files'
          ? {
              pane: (
                <FilesScreen
                  embedded={{
                    hostId,
                    kind: 'ssh',
                    tabId: activeTab.sessionId,
                  }}
                />
              ),
            }
          : {})}
        onReady={ssh.onViewportReady}
        onTitle={ssh.reportTitle}
        {...(state.tabs.length > 1 ? { onSwitchSession: switchSession } : {})}
        sessionIndex={state.tabs.findIndex(
          t => t.sessionId === state.activeSessionId,
        )}
        tabStrip={
          state.tabs.length > 0 ? (
            <SessionTabs
              tabs={tabViews}
              activeSessionId={state.activeSessionId}
              onSelect={handleSelect}
              onClose={ssh.closeTab}
              onNew={ssh.newTab}
              newKinds={[
                {
                  key: 'shell',
                  label: 'Shell',
                  icon: 'terminal' as const,
                  onPress: ssh.newTab,
                },
                {
                  key: 'files',
                  label: 'Files',
                  icon: 'folder' as const,
                  onPress: openFiles,
                },
              ]}
              onRename={ssh.renameTab}
              renameRequest={renameRequest}
              canAdd={ssh.canAdd}
            />
          ) : null
        }
      />

      <HostKeyDialog
        prompt={ssh.hostKey}
        hostLabel={subtitle ?? title}
        onOpen={() => {
          restoreAfterDialog.current =
            terminal.current?.captureForOverlay() ?? null;
          terminal.current?.hideKeyboard();
        }}
        onAccept={() => {
          ssh.answerHostKey(
            ssh.hostKey?.changed === true ? 'replace' : 'accept',
          );
          restoreAfterDialog.current?.();
          restoreAfterDialog.current = null;
        }}
        onReject={() => {
          ssh.answerHostKey('reject');
          restoreAfterDialog.current = null;
        }}
      />

      <Toast message={copyNotice} onDismiss={() => setCopyNotice('')} />
    </>
  );
}

interface HostKeyDialogProps {
  prompt: SshHostKeyPrompt | null;
  hostLabel: string;
  /** Called once when the prompt appears, to record keyboard state. */
  onOpen: () => void;
  onAccept: () => void;
  onReject: () => void;
}

// Not dismissable, and fail-closed: no path continues the connection without an
// explicit decision. A changed key says plainly that it can mean interception.
function HostKeyDialog({
  prompt,
  hostLabel,
  onOpen,
  onAccept,
  onReject,
}: HostKeyDialogProps): React.ReactElement | null {
  const open = prompt !== null;
  const onOpenRef = useRef(onOpen);
  onOpenRef.current = onOpen;
  useEffect(() => {
    if (open) onOpenRef.current();
  }, [open]);

  if (prompt === null) return null;
  return (
    <Dialog open onClose={onReject} dismissable={false}>
      <DialogHeader>
        <DialogTitle>
          {prompt.changed ? 'The host key changed' : 'Unknown host key'}
        </DialogTitle>
      </DialogHeader>
      <DialogContent>
        <Text variant="callout">
          {prompt.changed
            ? `The key for ${hostLabel} does not match the one this device accepted before. This can mean the server was rebuilt, or that someone is intercepting the connection.`
            : `${hostLabel} has not been connected to from this device before.`}
        </Text>
        <Text variant="code" className="text-muted-foreground">
          {`${prompt.algorithm} ${prompt.fingerprint}`}
        </Text>
        <Text variant="callout">
          Compare this fingerprint with the server before accepting.
        </Text>
      </DialogContent>
      <DialogFooter>
        <Button variant="ghost" onPress={onReject}>
          <Text>Reject</Text>
        </Button>
        <Button onPress={onAccept}>
          <Text>{prompt.changed ? 'Accept the new key' : 'Accept'}</Text>
        </Button>
      </DialogFooter>
    </Dialog>
  );
}
