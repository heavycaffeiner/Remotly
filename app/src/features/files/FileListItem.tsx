// One directory entry.
//
// Composed from primitives and defined at module scope, so the row's type is
// stable across renders. This list is virtualized over as many entries as a
// directory holds, and a new component type on every pass costs row state.

import React, { useCallback } from 'react';
import { View } from 'react-native';
import { TouchableRipple, useTheme } from 'react-native-paper';
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
  const { colors } = useTheme();
  const open = useCallback(() => onOpen(entry), [onOpen, entry]);
  const menu = useCallback(() => onMenu(entry), [onMenu, entry]);

  return (
    <TouchableRipple
      role="button"
      accessibilityLabel={entryAccessibilityLabel(entry)}
      onPress={open}
      onLongPress={menu}
      style={{
        minHeight: 48,
        paddingHorizontal: 12,
        paddingVertical: 6,
        justifyContent: 'center',
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
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
          <Icon
            name={entryIcon(entry)}
            size={20}
            color={colors.onSecondaryContainer as string}
          />
        </View>
        <View style={{ flex: 1, gap: 2 }}>
          <Text numberOfLines={1}>{entry.name}</Text>
          <Text variant="caption" numberOfLines={1}>
            {entryDescription(entry)}
          </Text>
        </View>
        <IconButton
          icon="dots-vertical"
          label={`Actions for ${entry.name}`}
          onPress={menu}
        />
      </View>
    </TouchableRipple>
  );
}
