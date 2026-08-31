// Loading, empty, error, and status presentation.
//
// One component each, so a list with no rows looks the same wherever the user
// meets it.

import * as React from 'react';
import { ActivityIndicator, View } from 'react-native';
import { cn } from '../lib/utils';
import { Badge } from './ui/badge';
import { Button } from './ui/button';
import { Icon, type IconName } from './ui/icon';
import { Text } from './ui/text';

/** A status tone. Never carried by color alone: each pairs with an icon. */
export type Tone = 'ok' | 'busy' | 'idle' | 'danger';

const TONE_ICON: Record<Tone, IconName> = {
  ok: 'circle-check',
  busy: 'clock',
  idle: 'circle',
  danger: 'circle-alert',
};

const TONE_BADGE: Record<
  Tone,
  'default' | 'secondary' | 'destructive' | 'outline'
> = {
  ok: 'default',
  busy: 'secondary',
  idle: 'outline',
  danger: 'destructive',
};

export function Loading({ label }: { label: string }): React.ReactElement {
  return (
    <View className="flex-1 items-center justify-center gap-3">
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
  return (
    <View className="flex-1 items-center justify-center gap-4 p-8">
      <Icon name={icon} size={48} className="text-muted-foreground" />
      <Text variant="title" className="text-center">
        {title}
      </Text>
      {message === undefined ? null : (
        <Text variant="muted" className="text-center">
          {message}
        </Text>
      )}
      {action === undefined ? null : (
        <Button onPress={action.onPress}>
          <Text>{action.label}</Text>
        </Button>
      )}
      {secondaryAction === undefined ? null : (
        <Button variant="outline" onPress={secondaryAction.onPress}>
          <Text>{secondaryAction.label}</Text>
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
  return (
    <View className="flex-1 items-center justify-center gap-4 p-8">
      <Icon name="circle-alert" size={48} className="text-destructive" />
      <Text variant="title" className="text-center">
        {title}
      </Text>
      <Text variant="muted" className="text-center">
        {message}
      </Text>
      {onRetry === undefined ? null : (
        <Button onPress={onRetry}>
          <Text>{retryLabel}</Text>
        </Button>
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
  return (
    <View
      accessibilityLiveRegion="polite"
      className={cn(
        'm-4 flex-row items-start gap-3 rounded-md border p-3',
        tone === 'danger'
          ? 'border-destructive/40 bg-destructive/10'
          : 'border-border bg-muted',
      )}
    >
      <Icon
        name={TONE_ICON[tone]}
        className={tone === 'danger' ? 'text-destructive' : 'text-foreground'}
      />
      <View className="flex-1 gap-2">
        <Text variant="callout">{message}</Text>
        {action === undefined && onDismiss === undefined ? null : (
          <View className="flex-row gap-2">
            {action === undefined ? null : (
              <Button size="sm" variant="outline" onPress={action.onPress}>
                <Text>{action.label}</Text>
              </Button>
            )}
            {onDismiss === undefined ? null : (
              <Button size="sm" variant="ghost" onPress={onDismiss}>
                <Text>Dismiss</Text>
              </Button>
            )}
          </View>
        )}
      </View>
    </View>
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
    <Badge variant={TONE_BADGE[tone]} accessibilityLabel={label}>
      <Icon
        name={TONE_ICON[tone]}
        size={12}
        className={
          tone === 'idle' ? 'text-foreground' : 'text-primary-foreground'
        }
      />
      <Text>{label}</Text>
    </Badge>
  );
}

export function SectionHeader({
  title,
}: {
  title: string;
}): React.ReactElement {
  return (
    <Text
      role="heading"
      variant="caption"
      className="px-4 pb-1 pt-5 font-semibold uppercase tracking-wide text-primary"
    >
      {title}
    </Text>
  );
}
