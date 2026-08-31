// One directory entry.
//
// Composed from primitives and defined at module scope, so the row's type is
// stable across renders. This list is virtualized over as many entries as a
// directory holds, and a new component type on every pass costs row state.

import React, { useCallback } from 'react';
import { Pressable, View } from 'react-native';
import { IconButton } from '../../components/Screen';
import { Icon } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';
import type { FileEntry } from '../../lib/files';
import {
  entryAccessibilityLabel,
  entryDescription,
  entryIcon,
} from './filePresentation';

interface FileListItemProps {
  entry: FileEntry;
  onOpen: (entry: FileEntry) => void;
  onMenu: (entry: FileEntry) => void;
}

export function FileListItem({
  entry,
  onOpen,
  onMenu,
}: FileListItemProps): React.ReactElement {
  const open = useCallback(() => onOpen(entry), [onOpen, entry]);
  const menu = useCallback(() => onMenu(entry), [onMenu, entry]);

  return (
    <Pressable
      role="button"
      accessibilityLabel={entryAccessibilityLabel(entry)}
      onPress={open}
      onLongPress={menu}
      className="min-h-14 flex-row items-center gap-3 px-4 py-2 active:bg-accent"
    >
      <Icon name={entryIcon(entry)} className="text-muted-foreground" />
      <View className="flex-1 gap-0.5">
        <Text numberOfLines={1}>{entry.name}</Text>
        <Text variant="caption" numberOfLines={1}>
          {entryDescription(entry)}
        </Text>
      </View>
      <IconButton
        icon="more"
        label={`Actions for ${entry.name}`}
        onPress={menu}
      />
    </Pressable>
  );
}
