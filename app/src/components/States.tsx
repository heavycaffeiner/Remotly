// Loading, empty, error, and status presentation.
//
// One component each, so a list with no rows looks the same wherever the user
// meets it.

import * as React from 'react';
import { View } from 'react-native';
import { ActivityIndicator, Surface, useTheme } from 'react-native-paper';
import { Badge, type BadgeVariant } from './ui/badge';
import { Button } from './ui/button';
import { Icon } from './ui/icon';
import type { IconName } from '../lib/icons';
import { Text } from './ui/text';

/** A status tone. Never carried by color alone: each pairs with an icon. */
export type Tone = 'ok' | 'busy' | 'idle' | 'danger';

const TONE_ICON: Record<Tone, IconName> = {
  ok: 'check-circle',
  busy: 'clock',
  idle: 'circle-outline',
  danger: 'alert-circle',
};

const TONE_BADGE: Record<Tone, BadgeVariant> = {
  ok: 'default',
  busy: 'secondary',
  idle: 'outline',
  danger: 'destructive',
};

// These three are what a screen swaps in and out while it loads, so they trade
// places with a sibling that also holds text. A layout-only view is flattened
// away on Android, which leaves both branches' text parented to the screen
// itself and the swap fails to mount. Each keeps its own view instead.
export function Loading({ label }: { label: string }): React.ReactElement {
  return (
    <View
      collapsable={false}
      style={{
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        gap: 12,
      }}
    >
      <ActivityIndicator accessibilityLabel={label} />
      <Text variant="muted">{label}</Text>
    </View>
  );
}

interface EmptyProps {
  icon: IconName;
  title: string;
  message?: string;
  action?: { label: string; onPress: () => void };
  /** A second way out, when the empty state has more than one. */
  secondaryAction?: { label: string; onPress: () => void };
}

export function Empty({
  icon,
  title,
  message,
  action,
  secondaryAction,
}: EmptyProps): React.ReactElement {
  const { colors } = useTheme();
  return (
    <View
      collapsable={false}
      style={{
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 32,
      }}
    >
      <Icon name={icon} size={48} color={colors.onSurfaceVariant as string} />
      <Text variant="title" style={{ textAlign: 'center' }}>
        {title}
      </Text>
      {message === undefined ? null : (
        <Text variant="muted" style={{ textAlign: 'center' }}>
          {message}
        </Text>
      )}
      {action === undefined ? null : (
        <Button onPress={action.onPress}>{action.label}</Button>
      )}
      {secondaryAction === undefined ? null : (
        <Button variant="outline" onPress={secondaryAction.onPress}>
          {secondaryAction.label}
        </Button>
      )}
    </View>
  );
}

interface ErrorStateProps {
  title: string;
  message: string;
  onRetry?: () => void;
  retryLabel?: string;
}

export function ErrorState({
  title,
  message,
  onRetry,
  retryLabel = 'Try again',
}: ErrorStateProps): React.ReactElement {
  const { colors } = useTheme();
  return (
    <View
      collapsable={false}
      style={{
        flex: 1,
        alignItems: 'center',
        justifyContent: 'center',
        gap: 16,
        padding: 32,
      }}
    >
      <Icon name="alert-circle" size={48} color={colors.error as string} />
      <Text variant="title" style={{ textAlign: 'center' }}>
        {title}
      </Text>
      <Text variant="muted" style={{ textAlign: 'center' }}>
        {message}
      </Text>
      {onRetry === undefined ? null : (
        <Button onPress={onRetry}>{retryLabel}</Button>
      )}
    </View>
  );
}

interface NoticeProps {
  tone: Tone;
  message: string;
  action?: { label: string; onPress: () => void };
  onDismiss?: () => void;
}

/** An inline message. Reserves layout space rather than floating over it. */
export function Notice({
  tone,
  message,
  action,
  onDismiss,
}: NoticeProps): React.ReactElement {
  const { colors } = useTheme();
  const danger = tone === 'danger';
  return (
    <Surface
      elevation={0}
      accessibilityLiveRegion="polite"
      style={{
        margin: 16,
        flexDirection: 'row',
        alignItems: 'flex-start',
        gap: 12,
        borderRadius: 8,
        borderWidth: 1,
        padding: 12,
        borderColor: danger
          ? (colors.error as string)
          : (colors.outlineVariant as string),
        backgroundColor: danger
          ? (colors.errorContainer as string)
          : (colors.surfaceVariant as string),
      }}
    >
      <Icon
        name={TONE_ICON[tone]}
        color={danger ? (colors.error as string) : (colors.onSurface as string)}
      />
      <View style={{ flex: 1, gap: 8 }}>
        <Text variant="callout">{message}</Text>
        {action === undefined && onDismiss === undefined ? null : (
          <View style={{ flexDirection: 'row', gap: 8 }}>
            {action === undefined ? null : (
              <Button size="sm" variant="outline" onPress={action.onPress}>
                {action.label}
              </Button>
            )}
            {onDismiss === undefined ? null : (
              <Button size="sm" variant="ghost" onPress={onDismiss}>
                Dismiss
              </Button>
            )}
          </View>
        )}
      </View>
    </Surface>
  );
}

/** A compact status. Carries an icon and a label, never color alone. */
export function StatusChip({
  tone,
  label,
}: {
  tone: Tone;
  label: string;
}): React.ReactElement {
  return (
    <Badge variant={TONE_BADGE[tone]} icon={TONE_ICON[tone]} label={label} />
  );
}

export function SectionHeader({
  title,
}: {
  title: string;
}): React.ReactElement {
  const { colors } = useTheme();
  return (
    <Text
      role="heading"
      variant="caption"
      style={{
        paddingHorizontal: 16,
        paddingBottom: 2,
        paddingTop: 14,
        fontWeight: '600',
        textTransform: 'uppercase',
        letterSpacing: 1,
        color: colors.primary as string,
      }}
    >
      {title}
    </Text>
  );
}
