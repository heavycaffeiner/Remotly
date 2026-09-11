// Search, hidden-file toggle, and sort order for the file browser.
//
// The controls act on the listing the browser already holds, so no server-side
// filter is needed.

import React, { useCallback } from 'react';
import { ScrollView, View } from 'react-native';
import { Chip, IconButton, Surface, useTheme } from 'react-native-paper';
import { Input } from '../../components/ui/input';
import { Text } from '../../components/ui/text';
import { SORT_KEYS, type FileView, type SortKey } from '../../lib/files';

const SORT_LABELS: Record<SortKey, string> = {
  name: 'Name',
  size: 'Size',
  mtime: 'Modified',
  kind: 'Type',
};

/**
 * The line under the controls, which is also the answer to "did the search
 * look at the whole folder": the total it reports is the whole directory,
 * not a page of it.
 */
function countLabel(
  shown: number,
  loaded: number,
  filtering: boolean,
  loading: boolean,
): string {
  if (loading) return 'Reading this folder...';
  const items = loaded === 1 ? '1 item' : `${loaded} items`;
  return filtering ? `${shown} of ${items}` : items;
}

interface FilesToolbarProps {
  view: FileView;
  /** Entries the search matched, out of the whole directory. */
  shown: number;
  loaded: number;
  /** True while the directory is being read, so the count is not final. */
  loading: boolean;
  onChange: (next: FileView) => void;
}

export function FilesToolbar({
  view,
  shown,
  loaded,
  loading,
  onChange,
}: FilesToolbarProps): React.ReactElement {
  const { colors } = useTheme();

  const setQuery = useCallback(
    (query: string) => onChange({ ...view, query }),
    [onChange, view],
  );

  const clearQuery = useCallback(
    () => onChange({ ...view, query: '' }),
    [onChange, view],
  );

  const toggleHidden = useCallback(
    () => onChange({ ...view, showHidden: !view.showHidden }),
    [onChange, view],
  );

  // Tapping the active key flips direction, which is the usual behaviour of a
  // sort control and saves a separate direction toggle.
  const pickSort = useCallback(
    (key: SortKey) => {
      if (key === view.sortKey) {
        onChange({
          ...view,
          direction: view.direction === 'asc' ? 'desc' : 'asc',
        });
        return;
      }
      onChange({ ...view, sortKey: key, direction: 'asc' });
    },
    [onChange, view],
  );

  const filtering = view.query !== '';

  return (
    <Surface
      elevation={0}
      style={{
        gap: 8,
        borderBottomWidth: 1,
        borderBottomColor: colors.outlineVariant as string,
        backgroundColor: colors.surfaceContainerLow as string,
        paddingHorizontal: 8,
        paddingVertical: 6,
      }}
    >
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <View style={{ flex: 1 }}>
          <Input
            accessibilityLabel="Search this folder"
            placeholder="Search this folder"
            value={view.query}
            onChangeText={setQuery}
            autoCapitalize="none"
            autoCorrect={false}
            returnKeyType="search"
            clearButtonMode="while-editing"
          />
        </View>
        <IconButton
          icon={view.showHidden ? 'eye' : 'eye-off'}
          size={18}
          selected={view.showHidden}
          accessibilityLabel={
            view.showHidden ? 'Hide hidden files' : 'Show hidden files'
          }
          accessibilityState={{ selected: view.showHidden }}
          onPress={toggleHidden}
        />
        {/* Last in the row: appearing and disappearing here moves nothing
            else. */}
        {filtering ? (
          <IconButton
            icon="close"
            size={18}
            accessibilityLabel="Clear the search"
            onPress={clearQuery}
          />
        ) : null}
      </View>

      {/* The count shares the row with the sort chips. On a phone the
          browser already spends a breadcrumb bar and a search row above the
          first entry. */}
      <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
        <ScrollView
          horizontal
          style={{ flex: 1 }}
          showsHorizontalScrollIndicator={false}
          contentContainerStyle={{ alignItems: 'center', gap: 4 }}
        >
          {SORT_KEYS.map(key => (
            <SortChip
              key={key}
              sortKey={key}
              active={key === view.sortKey}
              descending={view.direction === 'desc'}
              onPress={pickSort}
            />
          ))}
        </ScrollView>
        <Text variant="caption">
          {countLabel(shown, loaded, filtering, loading)}
        </Text>
      </View>
    </Surface>
  );
}

function SortChip({
  sortKey,
  active,
  descending,
  onPress,
}: {
  sortKey: SortKey;
  active: boolean;
  descending: boolean;
  onPress: (key: SortKey) => void;
}): React.ReactElement {
  const press = useCallback(() => onPress(sortKey), [onPress, sortKey]);
  const label = SORT_LABELS[sortKey];
  return (
    <Chip
      selected={active}
      showSelectedCheck={false}
      // The direction belongs in the name so it is announced, not only drawn
      // as an arrow.
      accessibilityLabel={
        active
          ? `Sort by ${label}, ${descending ? 'descending' : 'ascending'}`
          : `Sort by ${label}`
      }
      accessibilityState={{ selected: active }}
      icon={active ? (descending ? 'arrow-down' : 'arrow-up') : undefined}
      onPress={press}
    >
      {label}
    </Chip>
  );
}
