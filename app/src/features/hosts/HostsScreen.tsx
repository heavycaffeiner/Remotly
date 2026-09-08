// The Hosts destination: saved SSH hosts.
//
// Row actions sit behind a visible overflow button. Long press is a shortcut,
// never the only path: a hidden long press is undiscoverable and unusable with
// a screen reader.

import React, { useCallback, useMemo, useRef, useState } from 'react';
import { FlatList, View } from 'react-native';
import { Card, TouchableRipple, useTheme } from 'react-native-paper';
import { Fab } from '../../components/ui/fab';
import type { IconName } from '../../lib/icons';
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

  const openWorkspaces = useCallback(
    (entry: HostListEntry) => {
      setMenuFor(null);
      navigation.navigate('HerdrWorkspaces', {
        hostId: entry.id,
        hostName: entry.name,
      });
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
        icon: 'magnify',
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
        <View style={{ flex: 1, paddingHorizontal: 16 }}>
          {showSearch ? (
            <Input
              value={query}
              onChangeText={setQuery}
              placeholder="Search hosts"
              accessibilityLabel="Search hosts"
              autoCapitalize="none"
              autoCorrect={false}
              style={{ marginBottom: 12 }}
            />
          ) : null}

          <FlatList
            data={visible}
            keyExtractor={item => item.id}
            contentContainerStyle={{ paddingTop: 8, paddingBottom: 96, gap: 8 }}
            ListEmptyComponent={
              <View style={{ alignItems: 'center', padding: 24 }}>
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
        <Fab
          icon="plus"
          accessibilityLabel="Add a host"
          onPress={() => setAddOpen(true)}
          style={{ position: 'absolute', bottom: 20, right: 20, zIndex: 10 }}
        />
      ) : null}

      <Sheet open={addOpen} onClose={() => setAddOpen(false)}>
        <SheetHeader>
          <SheetTitle>Add a host</SheetTitle>
        </SheetHeader>
        <SheetContent style={{ gap: 8, paddingBottom: 24 }}>
          <MenuRow
            icon="console"
            label="Add SSH host"
            description="Connect to any remote server with standard SSH"
            onPress={() => {
              setAddOpen(false);
              navigation.navigate('SshHostEditor');
            }}
          />
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
        onWorkspaces={openWorkspaces}
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

// A row in one of this screen's action sheets.
function MenuRow({
  icon,
  label,
  description,
  destructive = false,
  onPress,
}: {
  icon: IconName;
  label: string;
  description?: string;
  destructive?: boolean;
  onPress: () => void;
}): React.ReactElement {
  const { colors } = useTheme();
  return (
    <TouchableRipple
      role="button"
      accessibilityLabel={label}
      onPress={onPress}
      style={{
        minHeight: 44,
        borderRadius: 16,
        overflow: 'hidden',
        paddingHorizontal: 12,
        paddingVertical: 6,
        justifyContent: 'center',
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
        <View
          style={{
            height: 36,
            width: 36,
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 999,
            backgroundColor: (destructive
              ? colors.errorContainer
              : colors.secondaryContainer) as string,
          }}
        >
          <Icon
            name={icon}
            size={22}
            color={
              (destructive
                ? colors.error
                : colors.onSecondaryContainer) as string
            }
          />
        </View>
        <View style={{ flex: 1 }}>
          <Text
            variant="body"
            style={{
              fontWeight: '500',
              ...(destructive ? { color: colors.error as string } : {}),
            }}
          >
            {label}
          </Text>
          {description === undefined ? null : (
            <Text variant="caption">{description}</Text>
          )}
        </View>
      </View>
    </TouchableRipple>
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
  const { colors } = useTheme();
  const handleOpen = useCallback(() => onOpen(entry), [onOpen, entry]);
  const handleMenu = useCallback(() => onMenu(entry), [onMenu, entry]);
  return (
    <Card mode="outlined" onPress={handleOpen} onLongPress={handleMenu}>
      <View
        accessibilityLabel={entry.accessibilityLabel}
        style={{
          flexDirection: 'row',
          alignItems: 'center',
          gap: 12,
          paddingHorizontal: 12,
          paddingVertical: 10,
        }}
      >
        <View
          style={{
            height: 34,
            width: 34,
            alignItems: 'center',
            justifyContent: 'center',
            borderRadius: 999,
            backgroundColor: colors.secondaryContainer as string,
          }}
        >
          <Icon name="console" color={colors.onSecondaryContainer as string} />
        </View>
        <View style={{ flex: 1, gap: 2 }}>
          <Text numberOfLines={1} style={{ fontWeight: '500' }}>
            {entry.name}
          </Text>
          <Text variant="caption" numberOfLines={1}>
            {entry.detail}
          </Text>
        </View>
        {entry.sessions === undefined ? null : (
          <Badge
            variant="secondary"
            icon="console"
            label={String(entry.sessions)}
          />
        )}
        <IconButton
          icon="dots-vertical"
          label={`Actions for ${entry.name}`}
          onPress={handleMenu}
        />
      </View>
    </Card>
  );
}

interface HostActionsMenuProps {
  entry: HostListEntry | null;
  onDismiss: () => void;
  onOpen: (entry: HostListEntry) => void;
  onFiles: (entry: HostListEntry) => void;
  onWorkspaces: (entry: HostListEntry) => void;
  onEdit: (entry: HostListEntry) => void;
  onRemove: (entry: HostListEntry) => void;
}

function HostActionsMenu({
  entry,
  onDismiss,
  onOpen,
  onFiles,
  onWorkspaces,
  onEdit,
  onRemove,
}: HostActionsMenuProps): React.ReactElement | null {
  if (entry === null) return null;
  return (
    <Sheet open onClose={onDismiss}>
      <SheetHeader>
        <SheetTitle>{entry.name}</SheetTitle>
      </SheetHeader>
      <SheetContent style={{ gap: 4, paddingBottom: 24 }}>
        <MenuRow
          icon="console"
          label="Open terminal"
          onPress={() => onOpen(entry)}
        />
        <MenuRow icon="folder" label="Files" onPress={() => onFiles(entry)} />
        <MenuRow
          icon="view-dashboard"
          label="Workspaces"
          description="Herdr workspaces running on this host"
          onPress={() => onWorkspaces(entry)}
        />
        <MenuRow icon="pencil" label="Edit" onPress={() => onEdit(entry)} />
        <MenuRow
          icon="delete"
          label="Remove"
          destructive
          onPress={() => onRemove(entry)}
        />
      </SheetContent>
    </Sheet>
  );
}
