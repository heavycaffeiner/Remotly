// The Hosts destination: paired Remotly hosts and saved SSH hosts in one list.
//
// Row actions sit behind a visible overflow button. Long press is a shortcut,
// never the only path: a hidden long press is undiscoverable and unusable with
// a screen reader.

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import {
  useFocusEffect,
  useIsFocused,
  useNavigation,
} from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { Screen, IconButton, type ScreenAction } from '../../components/Screen';
import {
  Empty,
  ErrorState,
  Loading,
  StatusChip,
} from '../../components/States';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toast } from '../../components/Toast';
import { Badge } from '../../components/ui/badge';
import { Button } from '../../components/ui/button';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';
import { Icon } from '../../components/ui/icon';
import { Input } from '../../components/ui/input';
import { Text } from '../../components/ui/text';
import { cn } from '../../lib/utils';
import { getHosts, type HostRecord } from '../../lib/hosts';
import { sshHosts, type SshHostView } from '../../lib/sshHosts';
import { getTransport, type TransportStatus } from '../../lib/transport';
import { userFacingMessage, toRemotlyError } from '../../lib/errors';
import type { RootStackParamList } from '../../navigation/types';
import {
  daemonStatus,
  filterHosts,
  mapWithConcurrency,
  STATUS_TONE,
  toDaemonEntry,
  toSshEntry,
  withSessionCount,
  type HostFilter,
  type HostListEntry,
} from './hostPresentation';
import { sshSessionCounts } from '../../lib/sshSessions';
import { parseWorkspace } from '../../lib/workspace';
import { loadWorkspaceDocument } from '../../lib/workspaceStore';

type Phase = 'loading' | 'ready' | 'error';

/** Above this many hosts, search earns its header space. */
const SEARCH_THRESHOLD = 8;
/** Status queries in flight at once. */
const STATUS_CONCURRENCY = 4;

const FILTERS: readonly { label: string; value: HostFilter }[] = [
  { label: 'All', value: 'all' },
  { label: 'Remotly', value: 'daemon' },
  { label: 'SSH', value: 'ssh' },
];

type Nav = NativeStackNavigationProp<RootStackParamList>;

export function HostsScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const isFocused = useIsFocused();
  const [phase, setPhase] = useState<Phase>('loading');
  const [entries, setEntries] = useState<HostListEntry[]>([]);
  const [filter, setFilter] = useState<HostFilter>('all');
  const [query, setQuery] = useState('');
  const [searchOpen, setSearchOpen] = useState(false);
  const [addOpen, setAddOpen] = useState(false);
  const [menuFor, setMenuFor] = useState<HostListEntry | null>(null);
  const [removeTarget, setRemoveTarget] = useState<HostListEntry | null>(null);
  const [removing, setRemoving] = useState(false);
  const [notice, setNotice] = useState('');

  // Only the newest load may write state. A slow response from a previous
  // focus must not overwrite the current list.
  const generation = useRef(0);

  const load = useCallback(async () => {
    const gen = generation.current + 1;
    generation.current = gen;
    try {
      const [daemon, ssh] = await Promise.all([
        getHosts().list(),
        sshHosts.list().catch(() => [] as SshHostView[]),
      ]);
      const statuses = await mapWithConcurrency(
        daemon,
        STATUS_CONCURRENCY,
        (h: HostRecord) => getTransport().status(h.id),
      );
      // Tab counts come from the saved workspace rather than the daemon: the
      // list must not open a connection to every host just to draw a badge.
      const documents = await mapWithConcurrency(
        daemon,
        STATUS_CONCURRENCY,
        (h: HostRecord) => loadWorkspaceDocument(h.id).catch(() => ''),
      );
      if (generation.current !== gen) return;
      const now = Date.now();
      const sshCounts = sshSessionCounts();
      setEntries([
        ...daemon.map((h, i) => {
          const status = statuses[i] as TransportStatus | undefined;
          const entry = toDaemonEntry(
            h,
            daemonStatus(status, status === undefined),
            now,
          );
          const doc = documents[i] as string | undefined;
          const saved = doc ? parseWorkspace(doc, h.id) : null;
          return withSessionCount(entry, saved?.tabs.length ?? 0);
        }),
        ...ssh.map(h =>
          withSessionCount(toSshEntry(h), sshCounts.get(h.id) ?? 0),
        ),
      ]);
      setPhase('ready');
    } catch {
      if (generation.current !== gen) return;
      setPhase('error');
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void load();
      const transport = getTransport();
      const offConnected = transport.onEvent('connected', e => {
        setEntries(rows =>
          rows.map(r =>
            r.kind === 'daemon' && r.id === e.hostId
              ? {
                  ...r,
                  status:
                    e.via === 'relay' ? 'connected-relay' : 'connected-direct',
                  statusLabel:
                    e.via === 'relay'
                      ? 'Connected via relay'
                      : 'Connected direct',
                }
              : r,
          ),
        );
      });
      const offDisconnected = transport.onEvent('disconnected', e => {
        setEntries(rows =>
          rows.map(r =>
            r.kind === 'daemon' && r.id === e.hostId
              ? { ...r, status: 'offline', statusLabel: 'Offline' }
              : r,
          ),
        );
      });
      return () => {
        // Drop any in-flight load so it cannot apply after the blur.
        generation.current += 1;
        offConnected();
        offDisconnected();
      };
    }, [load]),
  );

  const visible = useMemo(
    () => filterHosts(entries, filter, query),
    [entries, filter, query],
  );

  const openPrimary = useCallback(
    (entry: HostListEntry) => {
      if (entry.kind === 'daemon') {
        navigation.navigate('Workspace', { hostId: entry.id });
      } else {
        navigation.navigate('SshTerminal', { hostId: entry.id });
      }
    },
    [navigation],
  );

  const openFiles = useCallback(
    (entry: HostListEntry) => {
      setMenuFor(null);
      navigation.navigate('Files', { hostId: entry.id, kind: entry.kind });
    },
    [navigation],
  );

  const openEdit = useCallback(
    (entry: HostListEntry) => {
      setMenuFor(null);
      navigation.navigate('SshHostEditor', { hostId: entry.id });
    },
    [navigation],
  );

  const confirmRemove = useCallback(async () => {
    const target = removeTarget;
    if (target === null || removing) return;
    setRemoving(true);
    try {
      if (target.kind === 'daemon') {
        await getHosts().remove(target.id);
      } else {
        await sshHosts.remove(target.id);
      }
      setRemoveTarget(null);
      await load();
    } catch (e) {
      // The record stays visible: a failed delete must not look like a
      // success, or the user believes their credential is gone when it is not.
      setRemoveTarget(null);
      setNotice(userFacingMessage(toRemotlyError(e, 'storage')));
    } finally {
      setRemoving(false);
    }
  }, [removeTarget, removing, load]);

  const empty = phase === 'ready' && entries.length === 0;
  const showSearch = searchOpen || entries.length > SEARCH_THRESHOLD;

  const actions = useMemo<ScreenAction[]>(() => {
    if (phase !== 'ready' || empty || entries.length > SEARCH_THRESHOLD) {
      return [];
    }
    return [
      {
        key: 'search',
        icon: 'search',
        title: searchOpen ? 'Hide search' : 'Search hosts',
        onPress: () => {
          setSearchOpen(v => !v);
          setQuery('');
        },
      },
    ];
  }, [phase, empty, entries.length, searchOpen]);

  return (
    <Screen title="Hosts" actions={actions}>
      {phase === 'loading' ? <Loading label="Loading hosts" /> : null}

      {phase === 'error' ? (
        <ErrorState
          title="Could not load hosts"
          message="The host store could not be read. Your saved hosts are still on the device."
          onRetry={() => void load()}
        />
      ) : null}

      {empty ? (
        <Empty
          icon="server-off"
          title="No hosts yet"
          message="Pair a Remotly host to run agents and terminals through its daemon, or add an SSH host to connect directly."
          action={{
            label: 'Pair Remotly host',
            onPress: () => navigation.navigate('Pairing', {}),
          }}
          // The add-host FAB is hidden while the list is empty, and it owned
          // the only route to the SSH editor. Without this there is no way to
          // add an SSH host until a Remotly host has been paired first.
          secondaryAction={{
            label: 'Add SSH host',
            onPress: () => navigation.navigate('SshHostEditor'),
          }}
        />
      ) : null}

      {phase === 'ready' && !empty ? (
        <View className="flex-1 px-4">
          {showSearch ? (
            <Input
              value={query}
              onChangeText={setQuery}
              placeholder="Search hosts"
              accessibilityLabel="Search hosts"
              autoCapitalize="none"
              autoCorrect={false}
              className="mb-3 h-12 rounded-full border-outline/30 bg-surface-container-high px-4"
            />
          ) : null}

          <View className="flex-row gap-2 py-2">
            {FILTERS.map(f => (
              <Pressable
                key={f.value}
                role="tab"
                accessibilityLabel={`Show ${f.label} hosts`}
                accessibilityState={{ selected: filter === f.value }}
                onPress={() => setFilter(f.value)}
                className={cn(
                  'h-8 flex-row items-center gap-1.5 rounded-lg border px-3',
                  filter === f.value
                    ? 'border-transparent bg-secondary'
                    : 'border-outline/30 bg-transparent',
                )}
              >
                {filter === f.value ? (
                  <Icon
                    name="check"
                    size={14}
                    className="text-secondary-foreground"
                  />
                ) : null}
                <Text
                  className={cn(
                    'text-xs font-medium',
                    filter === f.value
                      ? 'font-semibold text-secondary-foreground'
                      : 'text-foreground',
                  )}
                >
                  {f.label}
                </Text>
              </Pressable>
            ))}
          </View>

          <FlatList
            data={visible}
            keyExtractor={item => `${item.kind}:${item.id}`}
            contentContainerStyle={{ paddingBottom: 96, gap: 8 }}
            ListEmptyComponent={
              <View className="items-center p-6">
                <Text variant="muted">No hosts match this filter.</Text>
              </View>
            }
            renderItem={({ item }) => (
              <HostRow entry={item} onOpen={openPrimary} onMenu={setMenuFor} />
            )}
          />
        </View>
      ) : null}

      {isFocused && phase === 'ready' ? (
        <View
          style={{
            position: 'absolute',
            bottom: 24,
            right: 24,
            zIndex: 10,
            elevation: 6,
            shadowColor: '#000',
            shadowOffset: { width: 0, height: 4 },
            shadowOpacity: 0.28,
            shadowRadius: 6,
          }}
          className="h-14 w-14 rounded-2xl bg-primary-container"
        >
          <Pressable
            role="button"
            accessibilityLabel="Add a host"
            onPress={() => setAddOpen(true)}
            android_ripple={{
              color: 'rgba(0, 0, 0, 0.12)',
              borderless: false,
            }}
            className="h-full w-full items-center justify-center rounded-2xl overflow-hidden"
          >
            <Icon name="plus" size={28} className="text-on-primary-container" />
          </Pressable>
        </View>
      ) : null}

      <Dialog open={addOpen} onClose={() => setAddOpen(false)}>
        <DialogHeader>
          <DialogTitle>Add a host</DialogTitle>
        </DialogHeader>
        <DialogContent className="pb-5">
          <Button
            variant="ghost"
            className="justify-start"
            onPress={() => {
              setAddOpen(false);
              navigation.navigate('Pairing', {});
            }}
          >
            <Icon name="qr-code" />
            <Text>Pair Remotly host</Text>
          </Button>
          <Button
            variant="ghost"
            className="justify-start"
            onPress={() => {
              setAddOpen(false);
              navigation.navigate('SshHostEditor');
            }}
          >
            <Icon name="terminal" />
            <Text>Add SSH host</Text>
          </Button>
        </DialogContent>
      </Dialog>

      <HostActionsMenu
        entry={menuFor}
        onDismiss={() => setMenuFor(null)}
        onOpen={entry => {
          setMenuFor(null);
          openPrimary(entry);
        }}
        onFiles={openFiles}
        onEdit={openEdit}
        onRemove={entry => {
          setMenuFor(null);
          setRemoveTarget(entry);
        }}
      />

      <ConfirmDialog
        visible={removeTarget !== null}
        destructive
        busy={removing}
        title={removeTarget ? `Remove ${removeTarget.name}?` : ''}
        message={
          removeTarget?.kind === 'ssh'
            ? 'The host, its stored credential, and its pinned host keys are removed from this device. The remote server is not affected.'
            : 'The host is removed from this device. The daemon keeps running and is not affected.'
        }
        confirmLabel="Remove"
        onConfirm={() => void confirmRemove()}
        onDismiss={() => setRemoveTarget(null)}
      />

      <Toast message={notice} onDismiss={() => setNotice('')} />
    </Screen>
  );
}

interface HostRowProps {
  entry: HostListEntry;
  onOpen: (entry: HostListEntry) => void;
  onMenu: (entry: HostListEntry) => void;
}

// At module scope, not inline in renderItem, so row state is not thrown away
// on every list render.
function HostRow({ entry, onOpen, onMenu }: HostRowProps): React.ReactElement {
  const handleOpen = useCallback(() => onOpen(entry), [onOpen, entry]);
  const handleMenu = useCallback(() => onMenu(entry), [onMenu, entry]);
  return (
    <Pressable
      role="button"
      accessibilityLabel={entry.accessibilityLabel}
      onPress={handleOpen}
      onLongPress={handleMenu}
      android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
      className="flex-row items-center gap-3.5 rounded-2xl bg-card p-4 overflow-hidden active:bg-surface-variant/40"
    >
      <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary">
        <Icon
          name={entry.kind === 'daemon' ? 'server' : 'terminal'}
          className="text-secondary-foreground"
        />
      </View>
      <View className="flex-1 gap-0.5">
        <Text numberOfLines={1} className="font-medium">
          {entry.name}
        </Text>
        <Text variant="caption" numberOfLines={1}>
          {entry.detail}
        </Text>
      </View>
      {entry.sessions === undefined ? null : (
        <Badge variant="secondary">
          <Icon
            name="terminal"
            size={12}
            className="text-secondary-foreground"
          />
          <Text>{String(entry.sessions)}</Text>
        </Badge>
      )}
      {entry.kind === 'daemon' ? (
        <StatusChip
          tone={STATUS_TONE[entry.status]}
          label={entry.statusLabel}
        />
      ) : null}
      <IconButton
        icon="more"
        label={`Actions for ${entry.name}`}
        onPress={handleMenu}
      />
    </Pressable>
  );
}

interface HostActionsMenuProps {
  entry: HostListEntry | null;
  onDismiss: () => void;
  onOpen: (entry: HostListEntry) => void;
  onFiles: (entry: HostListEntry) => void;
  onEdit: (entry: HostListEntry) => void;
  onRemove: (entry: HostListEntry) => void;
}

function HostActionsMenu({
  entry,
  onDismiss,
  onOpen,
  onFiles,
  onEdit,
  onRemove,
}: HostActionsMenuProps): React.ReactElement | null {
  if (entry === null) return null;
  return (
    <Dialog open onClose={onDismiss}>
      <DialogHeader>
        <DialogTitle>{entry.name}</DialogTitle>
      </DialogHeader>
      <DialogContent className="pb-5">
        <Button
          variant="ghost"
          className="justify-start"
          onPress={() => onOpen(entry)}
        >
          <Icon
            name={entry.kind === 'daemon' ? 'layout-dashboard' : 'terminal'}
          />
          <Text>
            {entry.kind === 'daemon' ? 'Open workspace' : 'Open terminal'}
          </Text>
        </Button>
        <Button
          variant="ghost"
          className="justify-start"
          onPress={() => onFiles(entry)}
        >
          <Icon name="folder" />
          <Text>Files</Text>
        </Button>
        {entry.kind === 'ssh' ? (
          <Button
            variant="ghost"
            className="justify-start"
            onPress={() => onEdit(entry)}
          >
            <Icon name="pencil" />
            <Text>Edit</Text>
          </Button>
        ) : null}
        <Button
          variant="ghost"
          className="justify-start"
          onPress={() => onRemove(entry)}
        >
          <Icon name="trash" className="text-destructive" />
          <Text className="text-destructive">Remove</Text>
        </Button>
      </DialogContent>
    </Dialog>
  );
}
