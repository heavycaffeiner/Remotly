// The Hosts destination: saved SSH hosts.
//
// Row actions sit behind a visible overflow button. Long press is a shortcut,
// never the only path: a hidden long press is undiscoverable and unusable with
// a screen reader.

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { FlatList, Pressable, View } from 'react-native';
import { useFocusEffect, useNavigation } from '@react-navigation/native';
import type { NativeStackNavigationProp } from '@react-navigation/native-stack';

import { Screen, IconButton, type ScreenAction } from '../../components/Screen';
import { Empty, ErrorState, Loading } from '../../components/States';
import { ConfirmDialog } from '../../components/ConfirmDialog';
import { Toast } from '../../components/Toast';
import { Badge } from '../../components/ui/badge';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet';
import { Icon } from '../../components/ui/icon';
import { Input } from '../../components/ui/input';
import { Text } from '../../components/ui/text';
import { sshHosts, type SshHostView } from '../../lib/sshHosts';
import { userFacingMessage, toRemotlyError } from '../../lib/errors';
import type { RootStackParamList } from '../../navigation/types';
import {
  filterHosts,
  toSshEntry,
  withSessionCount,
  type HostListEntry,
} from './hostPresentation';
import { sshSessionCounts } from '../../lib/sshSessions';

type Phase = 'loading' | 'ready' | 'error';

/** Above this many hosts, search earns its header space. */
const SEARCH_THRESHOLD = 8;

type Nav = NativeStackNavigationProp<RootStackParamList>;

export function HostsScreen(): React.ReactElement {
  const navigation = useNavigation<Nav>();
  const [phase, setPhase] = useState<Phase>('loading');
  const [entries, setEntries] = useState<HostListEntry[]>([]);
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
      const ssh = await sshHosts.list().catch(() => [] as SshHostView[]);
      if (generation.current !== gen) return;
      const sshCounts = sshSessionCounts();
      setEntries(
        ssh.map(h => withSessionCount(toSshEntry(h), sshCounts.get(h.id) ?? 0)),
      );
      setPhase('ready');
    } catch {
      if (generation.current !== gen) return;
      setPhase('error');
    }
  }, []);

  useFocusEffect(
    useCallback(() => {
      void load();
      return () => {
        // Drop any in-flight load so it cannot apply after the blur.
        generation.current += 1;
      };
    }, [load]),
  );

  const visible = useMemo(() => filterHosts(entries, query), [entries, query]);

  const openPrimary = useCallback(
    (entry: HostListEntry) => {
      navigation.navigate('SshTerminal', { hostId: entry.id });
    },
    [navigation],
  );

  const openFiles = useCallback(
    (entry: HostListEntry) => {
      setMenuFor(null);
      navigation.navigate('Files', { hostId: entry.id });
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
      await sshHosts.remove(target.id);
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
          message="Add an SSH host to connect directly."
          action={{
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

          <FlatList
            data={visible}
            keyExtractor={item => item.id}
            contentContainerStyle={{ paddingTop: 8, paddingBottom: 96, gap: 8 }}
            ListEmptyComponent={
              <View className="items-center p-6">
                <Text variant="muted">No hosts match this search.</Text>
              </View>
            }
            renderItem={({ item }) => (
              <HostRow entry={item} onOpen={openPrimary} onMenu={setMenuFor} />
            )}
          />
        </View>
      ) : null}

      {phase === 'ready' ? (
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

      <Sheet open={addOpen} onClose={() => setAddOpen(false)}>
        <SheetHeader>
          <SheetTitle>Add a host</SheetTitle>
        </SheetHeader>
        <SheetContent className="gap-2 pb-6">
          <Pressable
            role="button"
            accessibilityLabel="Add SSH host"
            onPress={() => {
              setAddOpen(false);
              navigation.navigate('SshHostEditor');
            }}
            android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
            className="flex-row items-center gap-4 rounded-2xl p-4 active:bg-surface-variant/40"
          >
            <View className="h-11 w-11 items-center justify-center rounded-full bg-secondary-container">
              <Icon
                name="terminal"
                size={24}
                className="text-on-secondary-container"
              />
            </View>
            <View className="flex-1">
              <Text className="text-base font-medium">Add SSH host</Text>
              <Text variant="caption">
                Connect to any remote server with standard SSH
              </Text>
            </View>
          </Pressable>
        </SheetContent>
      </Sheet>

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
        message="The host, its stored credential, and its pinned host keys are removed from this device. The remote server is not affected."
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
      className="flex-row items-center gap-3.5 rounded-2xl border border-outline-variant/30 bg-card p-4 overflow-hidden active:bg-surface-variant/40"
    >
      <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary-container">
        <Icon name="terminal" className="text-on-secondary-container" />
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
            className="text-on-secondary-container"
          />
          <Text>{String(entry.sessions)}</Text>
        </Badge>
      )}
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
    <Sheet open onClose={onDismiss}>
      <SheetHeader>
        <SheetTitle>{entry.name}</SheetTitle>
      </SheetHeader>
      <SheetContent className="gap-1 pb-6">
        <Pressable
          role="button"
          onPress={() => onOpen(entry)}
          android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
          className="h-14 flex-row items-center gap-4 rounded-2xl px-4 active:bg-surface-variant/40"
        >
          <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary-container">
            <Icon
              name="terminal"
              size={22}
              className="text-on-secondary-container"
            />
          </View>
          <Text className="text-base font-medium flex-1">Open terminal</Text>
        </Pressable>

        <Pressable
          role="button"
          onPress={() => onFiles(entry)}
          android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
          className="h-14 flex-row items-center gap-4 rounded-2xl px-4 active:bg-surface-variant/40"
        >
          <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary-container">
            <Icon
              name="folder"
              size={22}
              className="text-on-secondary-container"
            />
          </View>
          <Text className="text-base font-medium flex-1">Files</Text>
        </Pressable>

        <Pressable
          role="button"
          onPress={() => onEdit(entry)}
          android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
          className="h-14 flex-row items-center gap-4 rounded-2xl px-4 active:bg-surface-variant/40"
        >
          <View className="h-10 w-10 items-center justify-center rounded-full bg-secondary-container">
            <Icon
              name="pencil"
              size={22}
              className="text-on-secondary-container"
            />
          </View>
          <Text className="text-base font-medium flex-1">Edit</Text>
        </Pressable>

        <Pressable
          role="button"
          onPress={() => onRemove(entry)}
          android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
          className="h-14 flex-row items-center gap-4 rounded-2xl px-4 active:bg-surface-variant/40"
        >
          <View className="h-10 w-10 items-center justify-center rounded-full bg-destructive-container">
            <Icon name="trash" size={22} className="text-destructive" />
          </View>
          <Text className="text-base font-medium text-destructive flex-1">
            Remove
          </Text>
        </Pressable>
      </SheetContent>
    </Sheet>
  );
}
