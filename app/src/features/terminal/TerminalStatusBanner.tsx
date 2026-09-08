// A connection banner drawn over the terminal.
//
// It overlays rather than occupying layout height. A banner that appears and
// disappears in the layout flow changes the terminal's measured grid, which
// resizes the remote PTY every time the connection state changes.

import * as React from 'react';
import { ActivityIndicator, Surface, useTheme } from 'react-native-paper';
import { Button } from '../../components/ui/button';
import { Icon } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';

export type TerminalBannerTone = 'info' | 'busy' | 'error';

interface TerminalStatusBannerProps {
  tone: TerminalBannerTone;
  message: string;
  action?: { label: string; onPress: () => void };
}

export function TerminalStatusBanner({
  tone,
  message,
  action,
}: TerminalStatusBannerProps): React.ReactElement {
  const { colors } = useTheme();
  const error = tone === 'error';
  const ink = error
    ? (colors.onErrorContainer as string)
    : (colors.onSurface as string);
  return (
    <Surface
      elevation={2}
      role={error ? 'alert' : undefined}
      accessibilityLiveRegion="polite"
      style={{
        position: 'absolute',
        left: 8,
        right: 8,
        top: 8,
        zIndex: 10,
        flexDirection: 'row',
        alignItems: 'center',
        gap: 8,
        borderRadius: 8,
        borderWidth: 1,
        paddingHorizontal: 12,
        paddingVertical: 8,
        borderColor: error
          ? (colors.error as string)
          : (colors.outlineVariant as string),
        backgroundColor: error
          ? (colors.errorContainer as string)
          : (colors.surfaceContainerHigh as string),
      }}
    >
      {tone === 'busy' ? (
        <ActivityIndicator size="small" />
      ) : (
        <Icon
          name={error ? 'alert-circle' : 'information'}
          size={16}
          color={ink}
        />
      )}
      <Text variant="callout" numberOfLines={2} style={{ flex: 1, color: ink }}>
        {message}
      </Text>
      {action === undefined ? null : (
        <Button
          size="sm"
          variant={error ? 'secondary' : 'outline'}
          onPress={action.onPress}
        >
          {action.label}
        </Button>
      )}
    </Surface>
  );
}
