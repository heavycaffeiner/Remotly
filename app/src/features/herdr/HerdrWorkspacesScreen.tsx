// Herdr workspaces and tabs on a host.
//
// Herdr keeps terminal workspaces running on the remote machine, so this screen
// is a view onto state that outlives the app: closing Remotly, or losing the
// connection, leaves every workspace where it was. Each action here is one
// `herdr` command over its own SSH exec channel.
//
// Focusing is what makes the persistence usable: a terminal opened afterwards
// attaches to the focused workspace and tab rather than a fresh shell.

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { FlatList, View } from 'react-native';
import { Card, FAB, TouchableRipple, useTheme } from 'react-native-paper';
import {
  useFocusEffect,
  useNavigation,
  useRoute,
} from '@react-navigation/native';
import type { RouteProp } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Field } from '../../components/Form';
import { Screen, IconButton, type ScreenAction } from '../../components/Screen';
import {
  Empty,
  ErrorState,
  Loading,
  SectionHeader,
  StatusChip,
} from '../../components/States';
import { Toast } from '../../components/Toast';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Icon } from '../../components/ui/icon';
import { Segmented } from '../../components/ui/segmented';
import { Separator } from '../../components/ui/separator';
import { Text } from '../../components/ui/text';
import {
  HerdrError,
  type HerdrSession,
  type HerdrTab,
  type HerdrWorkspace,
} from '../../lib/herdr';
import {
  closeHerdrTab,
  closeHerdrWorkspace,
  createHerdrTab,
  createHerdrWorkspace,
  deleteHerdrSession,
  focusHerdrTab,
  focusHerdrWorkspace,
  herdrSnapshot,
  listHerdrSessions,
  renameHerdrTab,
  renameHerdrWorkspace,
  stopHerdrSession,
} from '../../lib/herdrClient';
import type { RootStackParamList } from '../../navigation/types';

type Phase = 'loading' | 'ready' | 'error';
type Nav = NativeStackNavigationProp<RootStackParamList>;
type Route = RouteProp<RootStackParamList, 'HerdrWorkspaces'>;

/** What a text dialog is about to create, or rename, or close. */
type Subject = { kind: 'workspace' | 'tab'; id: string; label: string };
type CreateTarget =
  | { kind: 'workspace' }
  | { kind: 'tab'; workspaceId: string };

/** The name herdr uses for the unnamed session, for its stop and delete calls. */
const DEFAULT_SESSION = 'default';

/** A count with its noun, singular where it should be. */
function count(n: number, noun: string): string {
  return `${String(n)} ${noun}${n === 1 ? '' : 's'}`;
}

/**
 * Whether the failure is a host key this device has not accepted.
 *
 * Each herdr call is a one-shot exec, which has nowhere to show a first-use
 * prompt, so this state is not something retrying can clear.
 */
function isHostKeyFailure(e: unknown): boolean {
  return (
    e instanceof HerdrError &&
    (e.code === 'ssh_host_key_rejected' || e.code === 'ssh_host_key_changed')
  );
}

/** A herdr failure, said in the user's terms. */
function message(e: unknown): string {
  if (isHostKeyFailure(e)) {
    return 'Opening a terminal on this host is what records its key. Every herdr call is accepted once you have.';
  }
  if (e instanceof HerdrError) {
    if (e.code === 'herdr_unreachable') {
      return `The host could not be reached. ${e.detail}`;
    }
    if (e.code === 'herdr_bad_json') {
      return 'The host answered with something other than herdr output. Check that herdr is installed and on the PATH.';
    }
    return e.detail;
  }
  return 'The herdr command failed.';
}

export function HerdrWorkspacesScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const { hostId, hostName } = useRoute<Route>().params;

  const [phase, setPhase] = useState<Phase>('loading');
  const [error, setError] = useState('');
  const [needsHostKey, setNeedsHostKey] = useState(false);
  const [sessions, setSessions] = useState<HerdrSession[]>([]);
  const [session, setSession] = useState<string | null>(null);
  const [workspaces, setWorkspaces] = useState<HerdrWorkspace[]>([]);
  const [tabs, setTabs] = useState<HerdrTab[]>([]);
  const [focusedId, setFocusedId] = useState<string | null>(null);
  const [focusedTabId, setFocusedTabId] = useState<string | null>(null);
  const [notice, setNotice] = useState('');
  const [busyId, setBusyId] = useState('');

  // Which workspace has its tabs open. One at a time: the list is a phone
  // screen, and every expanded card pushes the rest off it.
  const [expanded, setExpanded] = useState('');

  const [createFor, setCreateFor] = useState<CreateTarget | null>(null);
  const [label, setLabel] = useState('');
  const [cwd, setCwd] = useState('');
  const [creating, setCreating] = useState(false);

  const [renameFor, setRenameFor] = useState<Subject | null>(null);
  const [draft, setDraft] = useState('');
  const [renaming, setRenaming] = useState(false);

  const [closeFor, setCloseFor] = useState<Subject | null>(null);
  const [closing, setClosing] = useState(false);

  const [sessionAction, setSessionAction] = useState<'stop' | 'delete' | null>(
    null,
  );
  const [sessionBusy, setSessionBusy] = useState(false);

  // Only the newest load may write state: a slow answer from a previous focus
  // must not overwrite the current list.
  const generation = useRef(0);

  const load = useCallback(
    async (target: string | null) => {
      const mine = ++generation.current;
      try {
        const list = await listHerdrSessions(hostId);
        const snap = await herdrSnapshot(hostId, target);
        if (generation.current !== mine) return;
        setSessions(list);
        setWorkspaces(snap.workspaces);
        setTabs(snap.tabs);
        setFocusedId(snap.focusedWorkspaceId);
        setFocusedTabId(snap.focusedTabId);
        setError('');
        setPhase('ready');
      } catch (e) {
        if (generation.current !== mine) return;
        setNeedsHostKey(isHostKeyFailure(e));
        setError(message(e));
        setPhase('error');
      }
    },
    [hostId],
  );

  useFocusEffect(
    useCallback(() => {
      void load(session);
    }, [load, session]),
  );

  /** Runs one action, then reloads: herdr owns the state, not this screen. */
  const act = useCallback(
    async (id: string, done: string, run: () => Promise<void>) => {
      setBusyId(id);
      try {
        await run();
        setNotice(done);
        await load(session);
      } catch (e) {
        setNotice(message(e));
      } finally {
        setBusyId('');
      }
    },
    [load, session],
  );

  const pickSession = useCallback(
    (name: string) => {
      const next = name === DEFAULT_SESSION ? null : name;
      setSession(next);
      setExpanded('');
      setPhase('loading');
      void load(next);
    },
    [load],
  );

  const focusWorkspace = useCallback(
    (workspace: HerdrWorkspace) =>
      void act(workspace.workspaceId, `Focused ${workspace.label}`, () =>
        focusHerdrWorkspace(hostId, workspace.workspaceId, session),
      ),
    [act, hostId, session],
  );

  const focusTab = useCallback(
    (tab: HerdrTab) =>
      void act(tab.tabId, `Focused tab ${tab.label}`, () =>
        focusHerdrTab(hostId, tab.tabId, session),
      ),
    [act, hostId, session],
  );

  // Focus first, then attach: a terminal opened without focusing lands on
  // whichever workspace herdr had, not the one the user just chose.
  const attach = useCallback(
    async (workspace: HerdrWorkspace) => {
      setBusyId(workspace.workspaceId);
      try {
        await focusHerdrWorkspace(hostId, workspace.workspaceId, session);
        navigation.navigate('SshTerminal', { hostId });
      } catch (e) {
        setNotice(message(e));
      } finally {
        setBusyId('');
      }
    },
    [hostId, session, navigation],
  );

  const beginRename = useCallback((subject: Subject) => {
    setRenameFor(subject);
    setDraft(subject.label);
  }, []);

  const commitRename = useCallback(async () => {
    const subject = renameFor;
    const next = draft.trim();
    if (subject === null || next === '' || renaming) return;
    setRenaming(true);
    try {
      if (subject.kind === 'workspace') {
        await renameHerdrWorkspace(hostId, subject.id, next, session);
      } else {
        await renameHerdrTab(hostId, subject.id, next, session);
      }
      setRenameFor(null);
      setNotice(`Renamed to ${next}`);
      await load(session);
    } catch (e) {
      setRenameFor(null);
      setNotice(message(e));
    } finally {
      setRenaming(false);
    }
  }, [renameFor, draft, renaming, hostId, session, load]);

  const beginCreate = useCallback((target: CreateTarget) => {
    setCreateFor(target);
    setLabel('');
    setCwd('');
  }, []);

  const commitCreate = useCallback(async () => {
    const target = createFor;
    const trimmed = label.trim();
    if (target === null || creating) return;
    if (target.kind === 'workspace' && trimmed === '') return;
    setCreating(true);
    try {
      const where = cwd.trim() === '' ? {} : { cwd: cwd.trim() };
      if (target.kind === 'workspace') {
        await createHerdrWorkspace(
          hostId,
          { label: trimmed, ...where },
          session,
        );
        setNotice(`Created ${trimmed}`);
      } else {
        await createHerdrTab(
          hostId,
          {
            workspaceId: target.workspaceId,
            ...(trimmed === '' ? {} : { label: trimmed }),
            ...where,
          },
          session,
        );
        setNotice('Created a tab');
      }
      setCreateFor(null);
      await load(session);
    } catch (e) {
      setNotice(message(e));
    } finally {
      setCreating(false);
    }
  }, [createFor, label, cwd, creating, hostId, session, load]);

  const commitClose = useCallback(async () => {
    const subject = closeFor;
    if (subject === null || closing) return;
    setClosing(true);
    try {
      if (subject.kind === 'workspace') {
        await closeHerdrWorkspace(hostId, subject.id, session);
      } else {
        await closeHerdrTab(hostId, subject.id, session);
      }
      setCloseFor(null);
      setNotice(`Closed ${subject.label}`);
      await load(session);
    } catch (e) {
      setCloseFor(null);
      setNotice(message(e));
    } finally {
      setClosing(false);
    }
  }, [closeFor, closing, hostId, session, load]);

  const commitSessionAction = useCallback(async () => {
    const kind = sessionAction;
    if (kind === null || sessionBusy) return;
    const name = session ?? DEFAULT_SESSION;
    setSessionBusy(true);
    try {
      if (kind === 'stop') await stopHerdrSession(hostId, name);
      else await deleteHerdrSession(hostId, name);
      setSessionAction(null);
      setNotice(kind === 'stop' ? `Stopped ${name}` : `Deleted ${name}`);
      if (kind === 'delete') setSession(null);
      setPhase('loading');
      await load(kind === 'delete' ? null : session);
    } catch (e) {
      setSessionAction(null);
      setNotice(message(e));
    } finally {
      setSessionBusy(false);
    }
  }, [sessionAction, sessionBusy, session, hostId, load]);

  const actions = useMemo<ScreenAction[]>(
    () => [
      {
        key: 'refresh',
        icon: 'refresh',
        title: 'Refresh',
        onPress: () => void load(session),
        disabled: phase === 'loading',
      },
    ],
    [load, session, phase],
  );

  const menuActions = useMemo<ScreenAction[]>(
    () =>
      phase === 'ready'
        ? [
            {
              key: 'stop-session',
              icon: 'stop',
              title: 'Stop this session',
              onPress: () => setSessionAction('stop'),
            },
            {
              key: 'delete-session',
              icon: 'delete',
              title: 'Delete this session',
              destructive: true,
              onPress: () => setSessionAction('delete'),
            },
          ]
        : [],
    [phase],
  );

  const sessionName = session ?? DEFAULT_SESSION;
  const creatingTab = createFor !== null && createFor.kind === 'tab';

  return (
    <Screen
      title="Workspaces"
      subtitle={hostName}
      onBack={() => navigation.goBack()}
      actions={actions}
      menuActions={menuActions}
    >
      {phase === 'loading' ? <Loading label="Loading workspaces" /> : null}

      {/* A host whose key has never been accepted cannot be reached by any
          one-shot exec, so retrying alone can never succeed. The way out is
          the terminal, which is the only screen that can show the prompt. */}
      {phase === 'error' && needsHostKey ? (
        <Empty
          icon="shield-key"
          title="Accept this host key first"
          message={error}
          action={{
            label: 'Open terminal',
            onPress: () => navigation.navigate('SshTerminal', { hostId }),
          }}
          secondaryAction={{
            label: 'Try again',
            onPress: () => {
              setPhase('loading');
              void load(session);
            },
          }}
        />
      ) : null}

      {phase === 'error' && !needsHostKey ? (
        <ErrorState
          title="Could not reach herdr"
          message={error}
          onRetry={() => {
            setPhase('loading');
            void load(session);
          }}
        />
      ) : null}

      {phase === 'ready' ? (
        <View style={{ flex: 1 }}>
          {sessions.length > 1 ? (
            <View style={{ paddingHorizontal: 16, paddingTop: 8, gap: 8 }}>
              <SectionHeader title="Session" />
              <Segmented
                value={sessionName}
                onChange={pickSession}
                options={sessions.map(s => ({
                  value: s.name,
                  label: s.name,
                  accessibilityLabel: s.running
                    ? `Session ${s.name}, running`
                    : `Session ${s.name}, stopped`,
                }))}
              />
            </View>
          ) : null}

          {workspaces.length === 0 ? (
            <Empty
              icon="view-dashboard"
              title="No workspaces"
              message={`Session ${sessionName} has no workspaces yet. One created here keeps running on the host.`}
              action={{
                label: 'New workspace',
                onPress: () => beginCreate({ kind: 'workspace' }),
              }}
            />
          ) : (
            <FlatList
              data={workspaces}
              keyExtractor={w => w.workspaceId}
              contentContainerStyle={{
                padding: 16,
                paddingBottom: 96,
                gap: 8,
              }}
              renderItem={({ item }) => (
                <WorkspaceCard
                  workspace={item}
                  tabs={tabs.filter(t => t.workspaceId === item.workspaceId)}
                  focused={item.workspaceId === focusedId}
                  focusedTabId={focusedTabId}
                  busyId={busyId}
                  open={expanded === item.workspaceId}
                  onToggle={() =>
                    setExpanded(
                      expanded === item.workspaceId ? '' : item.workspaceId,
                    )
                  }
                  onFocus={focusWorkspace}
                  onAttach={attach}
                  onRename={beginRename}
                  onClose={setCloseFor}
                  onFocusTab={focusTab}
                  onNewTab={workspaceId =>
                    beginCreate({ kind: 'tab', workspaceId })
                  }
                />
              )}
            />
          )}
        </View>
      ) : null}

      {phase === 'ready' && workspaces.length > 0 ? (
        <FAB
          icon="plus"
          accessibilityLabel="New workspace"
          onPress={() => beginCreate({ kind: 'workspace' })}
          style={{ position: 'absolute', bottom: 24, right: 24, zIndex: 10 }}
        />
      ) : null}

      <Dialog open={createFor !== null} onClose={() => setCreateFor(null)}>
        <DialogHeader>
          <DialogTitle>{creatingTab ? 'New tab' : 'New workspace'}</DialogTitle>
        </DialogHeader>
        <DialogContent>
          <Field
            label="Label"
            value={label}
            onChangeText={setLabel}
            autoCapitalize="none"
            autoCorrect={false}
            {...(creatingTab
              ? {
                  placeholder: 'Optional',
                  hint: 'Left empty, herdr numbers the tab.',
                }
              : { hint: 'Shown in herdr and in this list.' })}
          />
          <Field
            label="Working directory"
            value={cwd}
            onChangeText={setCwd}
            autoCapitalize="none"
            autoCorrect={false}
            placeholder="Optional"
            hint="Left empty, herdr uses the session default."
          />
        </DialogContent>
        <DialogFooter>
          <Button
            variant="ghost"
            disabled={creating}
            onPress={() => setCreateFor(null)}
          >
            Cancel
          </Button>
          <Button
            disabled={(!creatingTab && label.trim() === '') || creating}
            loading={creating}
            onPress={() => void commitCreate()}
          >
            Create
          </Button>
        </DialogFooter>
      </Dialog>

      <Dialog open={renameFor !== null} onClose={() => setRenameFor(null)}>
        <DialogHeader>
          <DialogTitle>
            {renameFor?.kind === 'tab' ? 'Rename tab' : 'Rename workspace'}
          </DialogTitle>
        </DialogHeader>
        <DialogContent>
          <Field
            label="Label"
            value={draft}
            onChangeText={setDraft}
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
          />
        </DialogContent>
        <DialogFooter>
          <Button
            variant="ghost"
            disabled={renaming}
            onPress={() => setRenameFor(null)}
          >
            Cancel
          </Button>
          <Button
            disabled={draft.trim() === '' || renaming}
            loading={renaming}
            onPress={() => void commitRename()}
          >
            Rename
          </Button>
        </DialogFooter>
      </Dialog>

      <ConfirmDialog
        visible={closeFor !== null}
        destructive
        busy={closing}
        title={closeFor === null ? '' : `Close ${closeFor.label}?`}
        message={
          closeFor?.kind === 'tab'
            ? 'The tab and its panes are closed on the host. Anything still running in them is ended.'
            : 'The workspace and its panes are closed on the host. Anything still running in them is ended.'
        }
        confirmLabel="Close"
        onConfirm={() => void commitClose()}
        onDismiss={() => setCloseFor(null)}
      />

      <ConfirmDialog
        visible={sessionAction !== null}
        destructive={sessionAction === 'delete'}
        busy={sessionBusy}
        title={
          sessionAction === 'delete'
            ? `Delete session ${sessionName}?`
            : `Stop session ${sessionName}?`
        }
        message={
          sessionAction === 'delete'
            ? 'The session directory and socket are removed from the host. Its workspaces cannot be recovered.'
            : 'The session server stops. Its workspaces come back when it starts again.'
        }
        confirmLabel={sessionAction === 'delete' ? 'Delete' : 'Stop'}
        onConfirm={() => void commitSessionAction()}
        onDismiss={() => setSessionAction(null)}
      />

      <Toast message={notice} onDismiss={() => setNotice('')} />
    </Screen>
  );
}

interface WorkspaceCardProps {
  workspace: HerdrWorkspace;
  tabs: readonly HerdrTab[];
  focused: boolean;
  focusedTabId: string | null;
  busyId: string;
  open: boolean;
  onToggle: () => void;
  onFocus: (workspace: HerdrWorkspace) => void;
  onAttach: (workspace: HerdrWorkspace) => void;
  onRename: (subject: Subject) => void;
  onClose: (subject: Subject) => void;
  onFocusTab: (tab: HerdrTab) => void;
  onNewTab: (workspaceId: string) => void;
}

// At module scope so row state survives a list render.
function WorkspaceCard({
  workspace,
  tabs,
  focused,
  focusedTabId,
  busyId,
  open,
  onToggle,
  onFocus,
  onAttach,
  onRename,
  onClose,
  onFocusTab,
  onNewTab,
}: WorkspaceCardProps): React.ReactElement {
  const { colors } = useTheme();
  const busy = busyId === workspace.workspaceId;
  const working =
    workspace.agentStatus === 'running' || workspace.agentStatus === 'waiting';

  return (
    <Card mode="outlined">
      <View style={{ padding: 16, gap: 12 }}>
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 14 }}>
          <View
            style={{
              height: 40,
              width: 40,
              alignItems: 'center',
              justifyContent: 'center',
              borderRadius: 999,
              backgroundColor: colors.secondaryContainer as string,
            }}
          >
            <Icon
              name="view-dashboard"
              color={colors.onSecondaryContainer as string}
            />
          </View>
          <View style={{ flex: 1, gap: 2 }}>
            <Text numberOfLines={1} style={{ fontWeight: '500' }}>
              {workspace.label}
            </Text>
            <Text variant="caption" numberOfLines={1}>
              {`${count(workspace.tabCount, 'tab')}, ${count(
                workspace.paneCount,
                'pane',
              )}`}
            </Text>
          </View>
          <IconButton
            icon="pencil"
            label={`Rename ${workspace.label}`}
            disabled={busy}
            onPress={() =>
              onRename({
                kind: 'workspace',
                id: workspace.workspaceId,
                label: workspace.label,
              })
            }
          />
          <IconButton
            icon="close"
            label={`Close ${workspace.label}`}
            disabled={busy}
            onPress={() =>
              onClose({
                kind: 'workspace',
                id: workspace.workspaceId,
                label: workspace.label,
              })
            }
          />
        </View>

        {focused || working ? (
          <View style={{ flexDirection: 'row', gap: 8 }}>
            {focused ? <StatusChip tone="ok" label="Focused" /> : null}
            {working ? (
              <Badge
                variant="secondary"
                icon="robot"
                label={String(workspace.agentStatus)}
              />
            ) : null}
          </View>
        ) : null}

        <View
          style={{ flexDirection: 'row', justifyContent: 'flex-end', gap: 8 }}
        >
          {focused ? null : (
            <Button
              variant="outline"
              size="sm"
              disabled={busy}
              accessibilityLabel={`Focus ${workspace.label}`}
              onPress={() => onFocus(workspace)}
            >
              Focus
            </Button>
          )}
          <Button
            size="sm"
            disabled={busy}
            loading={busy}
            accessibilityLabel={`Open a terminal on ${workspace.label}`}
            onPress={() => onAttach(workspace)}
          >
            Open terminal
          </Button>
        </View>

        <Separator />

        <TouchableRipple
          role="button"
          accessibilityLabel={`${open ? 'Hide' : 'Show'} tabs in ${
            workspace.label
          }`}
          accessibilityState={{ expanded: open }}
          onPress={onToggle}
          style={{ borderRadius: 8, paddingVertical: 8 }}
        >
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
            <Icon
              name={open ? 'chevron-down' : 'chevron-right'}
              size={18}
              color={colors.onSurfaceVariant as string}
            />
            <Text variant="callout" style={{ flex: 1 }}>
              {count(tabs.length, 'tab')}
            </Text>
          </View>
        </TouchableRipple>

        {open ? (
          <View style={{ gap: 4 }}>
            {tabs.map(tab => (
              <TabRow
                key={tab.tabId}
                tab={tab}
                focused={tab.tabId === focusedTabId}
                busy={busyId === tab.tabId}
                onFocus={onFocusTab}
                onRename={onRename}
                onClose={onClose}
              />
            ))}
            <Button
              variant="ghost"
              size="sm"
              icon="plus"
              accessibilityLabel={`New tab in ${workspace.label}`}
              onPress={() => onNewTab(workspace.workspaceId)}
            >
              New tab
            </Button>
          </View>
        ) : null}
      </View>
    </Card>
  );
}

interface TabRowProps {
  tab: HerdrTab;
  focused: boolean;
  busy: boolean;
  onFocus: (tab: HerdrTab) => void;
  onRename: (subject: Subject) => void;
  onClose: (subject: Subject) => void;
}

function TabRow({
  tab,
  focused,
  busy,
  onFocus,
  onRename,
  onClose,
}: TabRowProps): React.ReactElement {
  const { colors } = useTheme();
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center', gap: 4 }}>
      <TouchableRipple
        role="button"
        accessibilityLabel={`Focus tab ${tab.label}`}
        accessibilityState={{ selected: focused, disabled: busy }}
        disabled={busy || focused}
        onPress={() => onFocus(tab)}
        style={{
          flex: 1,
          borderRadius: 8,
          paddingHorizontal: 8,
          paddingVertical: 8,
        }}
      >
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          <Icon
            name={focused ? 'check-circle' : 'circle-outline'}
            size={16}
            color={
              (focused ? colors.primary : colors.onSurfaceVariant) as string
            }
          />
          <View style={{ flex: 1 }}>
            <Text variant="callout" numberOfLines={1}>
              {tab.label}
            </Text>
            <Text variant="caption" numberOfLines={1}>
              {count(tab.paneCount, 'pane')}
            </Text>
          </View>
        </View>
      </TouchableRipple>
      <IconButton
        icon="pencil"
        label={`Rename tab ${tab.label}`}
        disabled={busy}
        onPress={() =>
          onRename({ kind: 'tab', id: tab.tabId, label: tab.label })
        }
      />
      <IconButton
        icon="close"
        label={`Close tab ${tab.label}`}
        disabled={busy}
        onPress={() =>
          onClose({ kind: 'tab', id: tab.tabId, label: tab.label })
        }
      />
    </View>
  );
}
