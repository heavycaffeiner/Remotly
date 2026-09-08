// The path bar.
//
// Scrolls horizontally so a deep path never wraps and changes the list's
// height. Segments come from the path the server returned, which is what makes
// this work for a Windows drive root as well as a Unix root: nothing here
// infers a separator from the host's operating system.

import React, { useCallback } from 'react';
import { ScrollView, View } from 'react-native';
import { Surface, TouchableRipple, useTheme } from 'react-native-paper';
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
  const { colors } = useTheme();
  return (
    <Surface
      elevation={0}
      accessibilityLabel={`Current folder ${path}`}
      style={{
        borderBottomWidth: 1,
        borderBottomColor: colors.outlineVariant as string,
        backgroundColor: colors.surfaceContainerLow as string,
      }}
    >
      <ScrollView
        horizontal
        showsHorizontalScrollIndicator={false}
        contentContainerStyle={{ paddingHorizontal: 8, alignItems: 'center' }}
        style={{ paddingVertical: 4 }}
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
    </Surface>
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
  const { colors } = useTheme();
  const go = useCallback(
    () => onNavigate(crumb.path),
    [onNavigate, crumb.path],
  );
  return (
    <View style={{ flexDirection: 'row', alignItems: 'center' }}>
      <TouchableRipple
        role="button"
        accessibilityLabel={crumb.name}
        // The last crumb is where the user already is.
        accessibilityState={{ disabled: last }}
        disabled={last}
        onPress={go}
        style={{
          height: 44,
          justifyContent: 'center',
          borderRadius: 8,
          paddingHorizontal: 8,
        }}
      >
        <Text
          variant="callout"
          style={{
            fontWeight: last ? '600' : '400',
            color: last
              ? (colors.onSurface as string)
              : (colors.onSurfaceVariant as string),
          }}
        >
          {crumb.name}
        </Text>
      </TouchableRipple>
      {last ? null : (
        <Icon
          name="chevron-right"
          size={14}
          color={colors.onSurfaceVariant as string}
        />
      )}
    </View>
  );
}
