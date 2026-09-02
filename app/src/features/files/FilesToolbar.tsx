// Search, hidden-file toggle, and sort order for the file browser.
//
// The controls are backend-agnostic: they act on the listing the browser
// already holds, so the daemon and SSH behave identically and neither backend
// needs a server-side filter.

import React, { useCallback } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import { Icon } from '../../components/ui/icon';
import { Input } from '../../components/ui/input';
import { Text } from '../../components/ui/text';
import { cn } from '../../lib/utils';
import { SORT_KEYS, type FileView, type SortKey } from '../../lib/files';

const SORT_LABELS: Record<SortKey, string> = {
  name: 'Name',
  size: 'Size',
  mtime: 'Modified',
  kind: 'Type',
};

interface FilesToolbarProps {
  view: FileView;
  /** Entries shown out of the total loaded, for the filter summary. */
  shown: number;
  loaded: number;
  onChange: (next: FileView) => void;
}

export function FilesToolbar({
  view,
  shown,
  loaded,
  onChange,
}: FilesToolbarProps): React.ReactElement {
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
    <View className="gap-2 border-b border-border bg-card px-2 py-2">
      <View className="flex-row items-center gap-2">
        <View className="flex-1">
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
        {filtering ? (
          <Pressable
            role="button"
            accessibilityLabel="Clear the search"
            onPress={clearQuery}
            className="h-12 w-12 items-center justify-center rounded-md active:bg-accent"
          >
            <Icon name="x" size={18} className="text-muted-foreground" />
          </Pressable>
        ) : null}
        <Pressable
          role="button"
          accessibilityLabel={
            view.showHidden ? 'Hide hidden files' : 'Show hidden files'
          }
          accessibilityState={{ selected: view.showHidden }}
          onPress={toggleHidden}
          className={cn(
            'h-12 w-12 items-center justify-center rounded-md active:bg-accent',
            view.showHidden && 'bg-accent',
          )}
        >
          <Icon
            name={view.showHidden ? 'eye' : 'eye-off'}
            size={18}
            className={
              view.showHidden ? 'text-foreground' : 'text-muted-foreground'
            }
          />
        </Pressable>
      </View>

      <ScrollView
        horizontal
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

      {filtering ? (
        <Text variant="caption" className="text-muted-foreground">
          {shown} of {loaded} shown
        </Text>
      ) : null}
    </View>
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
    <Pressable
      role="button"
      // The direction belongs in the name so it is announced, not only drawn
      // as an arrow.
      accessibilityLabel={
        active
          ? `Sort by ${label}, ${descending ? 'descending' : 'ascending'}`
          : `Sort by ${label}`
      }
      accessibilityState={{ selected: active }}
      onPress={press}
      className={cn(
        'h-11 flex-row items-center gap-1 rounded-full border px-3',
        active ? 'border-primary bg-accent' : 'border-border',
      )}
    >
      <Text
        className={cn(
          'text-sm',
          active ? 'font-semibold text-foreground' : 'text-muted-foreground',
        )}
      >
        {label}
      </Text>
      {active ? (
        <Icon
          name={descending ? 'arrow-down' : 'arrow-up'}
          size={14}
          className="text-foreground"
        />
      ) : null}
    </Pressable>
  );
}
