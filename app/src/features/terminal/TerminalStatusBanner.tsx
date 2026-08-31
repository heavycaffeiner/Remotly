// A connection banner drawn over the terminal.
//
// It overlays rather than occupying layout height. A banner that appears and
// disappears in the layout flow changes the terminal's measured grid, which
// resizes the remote PTY every time the connection state changes.

import * as React from 'react';
import { ActivityIndicator, View } from 'react-native';
import { cn } from '../../lib/utils';
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
  return (
    <View
      role={tone === 'error' ? 'alert' : undefined}
      accessibilityLiveRegion="polite"
      className={cn(
        'absolute inset-x-2 top-2 z-10 flex-row items-center gap-2 rounded-md border px-3 py-2',
        tone === 'error'
          ? 'border-destructive/40 bg-destructive'
          : 'border-border bg-card',
      )}
    >
      {tone === 'busy' ? (
        <ActivityIndicator size="small" />
      ) : (
        <Icon
          name={tone === 'error' ? 'circle-alert' : 'info'}
          size={16}
          className={
            tone === 'error' ? 'text-destructive-foreground' : 'text-foreground'
          }
        />
      )}
      <Text
        variant="callout"
        numberOfLines={2}
        className={cn(
          'flex-1',
          tone === 'error' && 'text-destructive-foreground',
        )}
      >
        {message}
      </Text>
      {action === undefined ? null : (
        <Button
          size="sm"
          variant={tone === 'error' ? 'secondary' : 'outline'}
          onPress={action.onPress}
        >
          <Text>{action.label}</Text>
        </Button>
      )}
    </View>
  );
}
