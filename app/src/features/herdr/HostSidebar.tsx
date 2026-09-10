// One host's herdr tree: sessions, their workspaces, and each workspace's tabs.
//
// This is the app's management surface. Before it, workspaces lived on a screen
// of their own and tabs lived in a strip inside the terminal, so nothing showed
// both and every move between two workspaces meant leaving the terminal. Rows
// here carry their own actions, which is what emptied the terminal's menu of
// navigation entries.
//
// State comes from the event-fed store, so a change made on the desktop shows
// up here without this component asking for it.

import * as React from 'react';
import { ScrollView, View } from 'react-native';
import { TouchableRipple, useTheme } from 'react-native-paper';

import { Drawer } from '../../components/ui/drawer';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Field } from '../../components/Form';
import { Icon } from '../../components/ui/icon';
import { IconButton } from '../../components/Screen';
import { Separator } from '../../components/ui/separator';
import { Text } from '../../components/ui/text';
import { Loading, Notice, SectionHeader } from '../../components/States';
import type { HerdrSession, HerdrTab, HerdrWorkspace } from '../../lib/herdr';
import {
  closeHerdrTab,
  closeHerdrWorkspace,
  createHerdrTab,
  createHerdrWorkspace,
  focusHerdrTab,
  listHerdrSessions,
  renameHerdrTab,
  renameHerdrWorkspace,
} from '../../lib/herdrClient';
import {
  applyHerdrLocal,
  herdrHostState,
  refreshHerdrHost,
  subscribeHerdrHost,
  type HerdrHostState,
} from '../../lib/herdrStore';
import { useSyncExternalStore } from 'react';

/** Where a workspace's terminal is asked for. */
export interface HerdrEnterRequest {
  workspaceId: string;
  label: string;
  session: string | null;
}

interface HostSidebarProps {
  hostId: string;
  hostName: string;
  /** The session the caller's terminal is on, null for the default one. */
  session: string | null;
  open: boolean;
  onClose: () => void;
  /** Kept open beside the content where the window has room for it. */
  permanent?: boolean;
  /** The workspace the caller's terminal is showing, marked as current. */
  currentWorkspaceId?: string | null;
  onEnterWorkspace: (request: HerdrEnterRequest) => void;
  onOpenShells: () => void;
  onOpenFiles: () => void;
}

type Subject =
  | { kind: 'workspace'; id: string; label: string }
  | { kind: 'tab'; id: string; label: string; workspaceId: string };

/** A herdr failure, said in the user's terms. */
function message(e: unknown): string {
  return e instanceof Error && e.message !== '' ? e.message : 'herdr failed.';
}

export function HostSidebar({
  hostId,
  hostName,
  session,
  open,
  onClose,
  permanent = false,
  currentWorkspaceId = null,
  onEnterWorkspace,
  onOpenShells,
  onOpenFiles,
}: HostSidebarProps): React.ReactElement {
  const [viewSession, setViewSession] = React.useState<string | null>(session);
  const [sessions, setSessions] = React.useState<HerdrSession[]>([]);
  // The workspace on screen shows its tabs. Held as state so a row can be
  // opened or closed by hand, and reset when the terminal moves elsewhere.
  const [expanded, setExpanded] = React.useState<string | null>(
    currentWorkspaceId,
  );
  React.useEffect(() => {
    if (currentWorkspaceId !== null) setExpanded(currentWorkspaceId);
  }, [currentWorkspaceId]);
  const [notice, setNotice] = React.useState('');
  const [renameFor, setRenameFor] = React.useState<Subject | null>(null);
  const [closeFor, setCloseFor] = React.useState<Subject | null>(null);
  const [createOpen, setCreateOpen] = React.useState(false);
  const [draft, setDraft] = React.useState('');
  const [busy, setBusy] = React.useState(false);

  const state: HerdrHostState = useSyncExternalStore(
    React.useCallback(
      cb => subscribeHerdrHost(hostId, viewSession, cb),
      [hostId, viewSession],
    ),
    React.useCallback(
      () => herdrHostState(hostId, viewSession),
      [hostId, viewSession],
    ),
  );

  // The session list changes rarely and has no event, so it is read when the
  // sidebar comes up rather than kept live.
  React.useEffect(() => {
    if (!open && !permanent) return;
    let cancelled = false;
    void (async () => {
      try {
        const found = await listHerdrSessions(hostId);
        if (!cancelled) setSessions(found);
      } catch {
        // The workspace list already reports what a host cannot answer.
      }
    })();
    return () => {
      cancelled = true;
    };
  }, [hostId, open, permanent]);

  const act = React.useCallback(
    async (run: () => Promise<void>) => {
      setBusy(true);
      try {
        await run();
      } catch (e) {
        setNotice(message(e));
        // The optimistic paint has to be undone by the truth, not left.
        await refreshHerdrHost(hostId, viewSession);
      } finally {
        setBusy(false);
      }
    },
    [hostId, viewSession],
  );

  const focusTab = React.useCallback(
    (tab: HerdrTab) => {
      // Painted now and confirmed by the event: the chip has to answer the
      // finger, not the round trip.
      applyHerdrLocal(hostId, viewSession, {
        kind: 'tab-focused',
        tabId: tab.tabId,
        workspaceId: tab.workspaceId,
      });
      void act(() => focusHerdrTab(hostId, tab.tabId, viewSession));
    },
    [act, hostId, viewSession],
  );

  const commitRename = React.useCallback(() => {
    const subject = renameFor;
    const label = draft.trim();
    if (subject === null || label === '') return;
    setRenameFor(null);
    if (subject.kind === 'workspace') {
      applyHerdrLocal(hostId, viewSession, {
        kind: 'workspace-renamed',
        workspaceId: subject.id,
        label,
      });
      void act(() =>
        renameHerdrWorkspace(hostId, subject.id, label, viewSession),
      );
      return;
    }
    applyHerdrLocal(hostId, viewSession, {
      kind: 'tab-renamed',
      tabId: subject.id,
      workspaceId: subject.workspaceId,
      label,
    });
    void act(() => renameHerdrTab(hostId, subject.id, label, viewSession));
  }, [act, draft, hostId, renameFor, viewSession]);

  const commitClose = React.useCallback(() => {
    const subject = closeFor;
    if (subject === null) return;
    setCloseFor(null);
    if (subject.kind === 'workspace') {
      void act(() => closeHerdrWorkspace(hostId, subject.id, viewSession));
      return;
    }
    void act(() => closeHerdrTab(hostId, subject.id, viewSession));
  }, [act, closeFor, hostId, viewSession]);

  const commitCreate = React.useCallback(() => {
    const label = draft.trim();
    if (label === '') return;
    setCreateOpen(false);
    void act(() =>
      createHerdrWorkspace(hostId, { label }, viewSession).then(() => {
        // A created workspace is what the user wants to be looking at.
        setExpanded(null);
      }),
    );
  }, [act, draft, hostId, viewSession]);

  const newTab = React.useCallback(
    (workspaceId: string) =>
      void act(() =>
        createHerdrTab(hostId, { workspaceId, focus: true }, viewSession),
      ),
    [act, hostId, viewSession],
  );

  const body = (
    <>
      <View style={{ paddingHorizontal: 12, paddingTop: 8, gap: 2 }}>
        <Text variant="title" numberOfLines={1}>
          {hostName}
        </Text>
        <Text variant="caption">
          {state.feed === 'live'
            ? 'Live from herdr'
            : state.feed === 'polling'
            ? 'Re-reading on a timer'
            : 'Connecting'}
        </Text>
      </View>

      <ScrollView contentContainerStyle={{ paddingBottom: 16 }}>
        {sessions.length > 1 ? (
          <>
            <SectionHeader title="Sessions" />
            <View style={{ paddingHorizontal: 8, gap: 2 }}>
              {sessions.map(s => {
                const value = s.default ? null : s.name;
                const current = value === viewSession;
                return (
                  <TouchableRipple
                    key={s.name}
                    role="button"
                    accessibilityLabel={`Session ${s.name}${
                      s.running ? '' : ', not running'
                    }`}
                    accessibilityState={{ selected: current }}
                    onPress={() => setViewSession(value)}
                    style={{ borderRadius: 12, padding: 10 }}
                  >
                    <View
                      style={{
                        flexDirection: 'row',
                        alignItems: 'center',
                        gap: 8,
                      }}
                    >
                      <Icon
                        name={current ? 'check-circle' : 'circle-outline'}
                        size={16}
                      />
                      <Text variant="callout" style={{ flex: 1 }}>
                        {s.name}
                      </Text>
                      {s.running ? null : (
                        <Text variant="caption">stopped</Text>
                      )}
                    </View>
                  </TouchableRipple>
                );
              })}
            </View>
          </>
        ) : null}

        <SectionHeader title="Workspaces" />
        {!state.loaded && state.error === null ? (
          <Loading label="Loading workspaces" />
        ) : null}
        {state.error !== null && state.workspaces.length === 0 ? (
          <View style={{ paddingHorizontal: 12 }}>
            <Notice tone="danger" message={state.error} />
          </View>
        ) : null}

        <View style={{ paddingHorizontal: 8, gap: 2 }}>
          {state.workspaces.map(workspace => (
            <WorkspaceRow
              key={workspace.workspaceId}
              workspace={workspace}
              tabs={state.tabs[workspace.workspaceId] ?? []}
              current={workspace.workspaceId === currentWorkspaceId}
              focusedTabId={state.focusedTabId}
              expanded={expanded === workspace.workspaceId}
              busy={busy}
              onToggle={() =>
                setExpanded(prev =>
                  prev === workspace.workspaceId ? null : workspace.workspaceId,
                )
              }
              onEnter={() => {
                onEnterWorkspace({
                  workspaceId: workspace.workspaceId,
                  label: workspace.label,
                  session: viewSession,
                });
                if (!permanent) onClose();
              }}
              onRename={() => {
                setDraft(workspace.label);
                setRenameFor({
                  kind: 'workspace',
                  id: workspace.workspaceId,
                  label: workspace.label,
                });
              }}
              onCloseRequest={() =>
                setCloseFor({
                  kind: 'workspace',
                  id: workspace.workspaceId,
                  label: workspace.label,
                })
              }
              onFocusTab={focusTab}
              onRenameTab={tab => {
                setDraft(tab.label);
                setRenameFor({
                  kind: 'tab',
                  id: tab.tabId,
                  label: tab.label,
                  workspaceId: tab.workspaceId,
                });
              }}
              onCloseTab={tab =>
                setCloseFor({
                  kind: 'tab',
                  id: tab.tabId,
                  label: tab.label,
                  workspaceId: tab.workspaceId,
                })
              }
              onNewTab={() => newTab(workspace.workspaceId)}
            />
          ))}
        </View>

        <View style={{ paddingHorizontal: 8, paddingTop: 4 }}>
          <Button
            variant="ghost"
            size="sm"
            icon="plus"
            disabled={busy}
            accessibilityLabel="New workspace"
            onPress={() => {
              setDraft('');
              setCreateOpen(true);
            }}
          >
            New workspace
          </Button>
        </View>

        <Separator />
        <SectionHeader title="This host" />
        <View style={{ paddingHorizontal: 8, gap: 2 }}>
          <PlainRow
            icon="console"
            label="Shells"
            hint="The app's own SSH tabs"
            onPress={() => {
              onOpenShells();
              if (!permanent) onClose();
            }}
          />
          <PlainRow
            icon="folder"
            label="Files"
            hint="Browse and transfer over SFTP"
            onPress={() => {
              onOpenFiles();
              if (!permanent) onClose();
            }}
          />
        </View>
      </ScrollView>
    </>
  );

  return (
    <>
      <Drawer open={open} onClose={onClose} permanent={permanent}>
        {body}
      </Drawer>

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
          <Button variant="ghost" onPress={() => setRenameFor(null)}>
            Cancel
          </Button>
          <Button disabled={draft.trim() === ''} onPress={commitRename}>
            Rename
          </Button>
        </DialogFooter>
      </Dialog>

      <Dialog open={createOpen} onClose={() => setCreateOpen(false)}>
        <DialogHeader>
          <DialogTitle>New workspace</DialogTitle>
        </DialogHeader>
        <DialogContent>
          <Field
            label="Label"
            value={draft}
            onChangeText={setDraft}
            autoCapitalize="none"
            autoCorrect={false}
            autoFocus
            hint="Shown in herdr and here."
          />
        </DialogContent>
        <DialogFooter>
          <Button variant="ghost" onPress={() => setCreateOpen(false)}>
            Cancel
          </Button>
          <Button disabled={draft.trim() === ''} onPress={commitCreate}>
            Create
          </Button>
        </DialogFooter>
      </Dialog>

      <ConfirmDialog
        visible={closeFor !== null}
        destructive
        title={closeFor === null ? '' : `Close ${closeFor.label}?`}
        message={
          closeFor?.kind === 'tab'
            ? 'The tab and its panes are closed on the host. Anything still running in them is ended.'
            : 'The workspace and its panes are closed on the host. Anything still running in them is ended.'
        }
        confirmLabel="Close"
        onConfirm={commitClose}
        onDismiss={() => setCloseFor(null)}
      />

      {notice === '' ? null : (
        <Notice
          tone="danger"
          message={notice}
          onDismiss={() => setNotice('')}
        />
      )}
    </>
  );
}

interface WorkspaceRowProps {
  workspace: HerdrWorkspace;
  tabs: readonly HerdrTab[];
  current: boolean;
  focusedTabId: string | null;
  expanded: boolean;
  busy: boolean;
  onToggle: () => void;
  onEnter: () => void;
  onRename: () => void;
  onCloseRequest: () => void;
  onFocusTab: (tab: HerdrTab) => void;
  onRenameTab: (tab: HerdrTab) => void;
  onCloseTab: (tab: HerdrTab) => void;
  onNewTab: () => void;
}

function WorkspaceRow({
  workspace,
  tabs,
  current,
  focusedTabId,
  expanded,
  busy,
  onToggle,
  onEnter,
  onRename,
  onCloseRequest,
  onFocusTab,
  onRenameTab,
  onCloseTab,
  onNewTab,
}: WorkspaceRowProps): React.ReactElement {
  const { colors } = useTheme();
  const tabWord = tabs.length === 1 ? '1 tab' : `${String(tabs.length)} tabs`;
  return (
    <View>
      <View style={{ flexDirection: 'row', alignItems: 'center' }}>
        <TouchableRipple
          role="button"
          accessibilityLabel={`${expanded ? 'Hide' : 'Show'} tabs in ${
            workspace.label
          }`}
          accessibilityState={{ expanded }}
          onPress={onToggle}
          style={{ borderRadius: 12, padding: 8 }}
        >
          <Icon name={expanded ? 'chevron-down' : 'chevron-right'} size={18} />
        </TouchableRipple>
        <TouchableRipple
          role="button"
          accessibilityLabel={`Open a terminal on ${workspace.label}${
            current ? ', showing now' : ''
          }${workspace.focused ? ', focused in herdr' : ''}`}
          accessibilityState={{ selected: current }}
          disabled={busy}
          onPress={onEnter}
          style={{ flex: 1, borderRadius: 12, paddingVertical: 8 }}
        >
          <View style={{ gap: 2 }}>
            <View
              style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}
            >
              <Text
                variant="callout"
                numberOfLines={1}
                style={{
                  flexShrink: 1,
                  fontWeight: current ? '600' : '400',
                  color: (current
                    ? colors.primary
                    : colors.onSurface) as string,
                }}
              >
                {workspace.label}
              </Text>
              {/* Said in words as well as marked, so the state does not live
                  in the colour alone. */}
              {current ? <Text variant="caption">showing</Text> : null}
              {!current && workspace.focused ? (
                <Text variant="caption">focused</Text>
              ) : null}
            </View>
            <Text variant="caption">{tabWord}</Text>
          </View>
        </TouchableRipple>
        <IconButton
          icon="pencil"
          label={`Rename ${workspace.label}`}
          disabled={busy}
          onPress={onRename}
        />
        <IconButton
          icon="close"
          label={`Close ${workspace.label}`}
          disabled={busy}
          onPress={onCloseRequest}
        />
      </View>

      {expanded ? (
        <View style={{ paddingLeft: 28, gap: 2 }}>
          {tabs.map(tab => (
            <View
              key={tab.tabId}
              style={{ flexDirection: 'row', alignItems: 'center' }}
            >
              <TouchableRipple
                role="button"
                accessibilityLabel={`Focus tab ${
                  tab.label === '' ? String(tab.number) : tab.label
                }`}
                accessibilityState={{ selected: tab.tabId === focusedTabId }}
                disabled={busy}
                onPress={() => onFocusTab(tab)}
                style={{ flex: 1, borderRadius: 12, padding: 8 }}
              >
                <View
                  style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}
                >
                  <Icon
                    name={
                      tab.tabId === focusedTabId
                        ? 'check-circle'
                        : 'circle-outline'
                    }
                    size={16}
                  />
                  <Text variant="callout" numberOfLines={1} style={{ flex: 1 }}>
                    {tab.label === '' ? String(tab.number) : tab.label}
                  </Text>
                  {tab.agentStatus === 'working' ? (
                    <Text variant="caption">working</Text>
                  ) : null}
                </View>
              </TouchableRipple>
              <IconButton
                icon="pencil"
                label={`Rename tab ${tab.label}`}
                disabled={busy}
                onPress={() => onRenameTab(tab)}
              />
              <IconButton
                icon="close"
                label={`Close tab ${tab.label}`}
                disabled={busy}
                onPress={() => onCloseTab(tab)}
              />
            </View>
          ))}
          <Button
            variant="ghost"
            size="sm"
            icon="plus"
            disabled={busy}
            accessibilityLabel={`New tab in ${workspace.label}`}
            onPress={onNewTab}
          >
            New tab
          </Button>
        </View>
      ) : null}
    </View>
  );
}

interface PlainRowProps {
  icon: 'console' | 'folder';
  label: string;
  hint: string;
  onPress: () => void;
}

function PlainRow({
  icon,
  label,
  hint,
  onPress,
}: PlainRowProps): React.ReactElement {
  return (
    <TouchableRipple
      role="button"
      accessibilityLabel={`${label}. ${hint}`}
      onPress={onPress}
      style={{ borderRadius: 12, padding: 10 }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 10 }}>
        <Icon name={icon} size={18} />
        <View style={{ flex: 1 }}>
          <Text variant="callout">{label}</Text>
          <Text variant="caption">{hint}</Text>
        </View>
      </View>
    </TouchableRipple>
  );
}
