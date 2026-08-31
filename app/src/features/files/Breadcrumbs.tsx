// The path bar.
//
// Scrolls horizontally so a deep path never wraps and changes the list's
// height. Segments come from the path the server returned, which is what makes
// this work for a Windows drive root as well as a Unix root: nothing here
// infers a separator from the host's operating system.

import React, { useCallback } from 'react';
import { Pressable, ScrollView, View } from 'react-native';
import { cn } from '../../lib/utils';
import { Icon } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';
import type { Breadcrumb } from '../../lib/files';

interface BreadcrumbsProps {
  crumbs: readonly Breadcrumb[];
  /** The full path, announced to a screen reader even when truncated. */
  path: string;
  onNavigate: (path: string) => void;
}

export function Breadcrumbs({
  crumbs,
  path,
  onNavigate,
}: BreadcrumbsProps): React.ReactElement {
  return (
    <View
      accessibilityLabel={`Current folder ${path}`}
      className="border-b border-border bg-card"
    >
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ paddingHorizontal: 8, alignItems: 'center' }}
        className="py-1"
      >
        {crumbs.map((crumb, index) => (
          <Crumb
            key={crumb.path}
            crumb={crumb}
            last={index === crumbs.length - 1}
            onNavigate={onNavigate}
          />
        ))}
      </ScrollView>
    </View>
  );
}

function Crumb({
  crumb,
  last,
  onNavigate,
}: {
  crumb: Breadcrumb;
  last: boolean;
  onNavigate: (path: string) => void;
}): React.ReactElement {
  const go = useCallback(
    () => onNavigate(crumb.path),
    [onNavigate, crumb.path],
  );
  return (
    <View className="flex-row items-center">
      <Pressable
        role="button"
        accessibilityLabel={crumb.name}
        // The last crumb is where the user already is.
        accessibilityState={{ disabled: last }}
        disabled={last}
        onPress={go}
        className="h-11 justify-center rounded-md px-2 active:bg-accent"
      >
        <Text
          className={cn(
            'text-sm',
            last ? 'font-semibold text-foreground' : 'text-muted-foreground',
          )}
        >
          {crumb.name}
        </Text>
      </Pressable>
      {last ? null : (
        <Icon
          name="chevron-right"
          size={14}
          className="text-muted-foreground"
        />
      )}
    </View>
  );
}
