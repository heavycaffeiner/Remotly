// The terminal's compact top bar.
//
// Deliberately shorter than a screen app bar: every dp here is a terminal row
// the user does not get.

import * as React from 'react';
import { Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { cn } from '../../lib/utils';
import { IconButton } from '../../components/Screen';
import { Icon, type IconName } from '../../components/ui/icon';
import { Text } from '../../components/ui/text';
import {
  Sheet,
  SheetContent,
  SheetHeader,
  SheetTitle,
} from '../../components/ui/sheet';

export interface TerminalMenuAction {
  key: string;
  title: string;
  icon: IconName;
  onPress: () => void;
  disabled?: boolean;
  /** Shown in the destructive color, below a divider. */
  destructive?: boolean;
}

interface TerminalToolbarProps {
  title: string;
  subtitle?: string;
  onBack: () => void;
  /** A single action shown directly in the bar. */
  primaryAction?: {
    icon: IconName;
    label: string;
    onPress: () => void;
    disabled?: boolean;
  };
  actions: readonly TerminalMenuAction[];
}

export function TerminalToolbar({
  title,
  subtitle,
  onBack,
  primaryAction,
  actions,
}: TerminalToolbarProps): React.ReactElement {
  const insets = useSafeAreaInsets();
  const [open, setOpen] = React.useState(false);

  return (
    <View
      style={{ paddingTop: insets.top }}
      className="border-b border-border bg-card"
    >
      <View className="h-12 flex-row items-center px-1">
        <IconButton icon="arrow-left" label="Go back" onPress={onBack} />
        <View className="flex-1">
          <Text className="text-base font-medium" numberOfLines={1}>
            {title}
          </Text>
          {subtitle === undefined ? null : (
            <Text variant="caption" numberOfLines={1}>
              {subtitle}
            </Text>
          )}
        </View>
        {primaryAction === undefined ? null : (
          <IconButton
            icon={primaryAction.icon}
            label={primaryAction.label}
            disabled={primaryAction.disabled ?? false}
            onPress={primaryAction.onPress}
          />
        )}
        {actions.length === 0 ? null : (
          <IconButton
            icon="more"
            label="Terminal actions"
            onPress={() => setOpen(true)}
          />
        )}
      </View>

      <Sheet open={open} onClose={() => setOpen(false)}>
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
        </SheetHeader>
        <SheetContent className="gap-1 pb-6">
          {actions.map(a => (
            <Pressable
              key={a.key}
              role="button"
              disabled={a.disabled ?? false}
              accessibilityState={{ disabled: a.disabled ?? false }}
              onPress={() => {
                setOpen(false);
                a.onPress();
              }}
              android_ripple={{ color: 'rgba(0, 0, 0, 0.08)' }}
              className={cn(
                'h-14 flex-row items-center gap-4 rounded-2xl px-4 active:bg-surface-variant/40',
                a.disabled === true && 'opacity-40',
              )}
            >
              <View
                className={cn(
                  'h-10 w-10 items-center justify-center rounded-full',
                  a.destructive === true
                    ? 'bg-destructive-container'
                    : 'bg-secondary-container',
                )}
              >
                <Icon
                  name={a.icon}
                  size={20}
                  className={
                    a.destructive === true
                      ? 'text-destructive'
                      : 'text-on-secondary-container'
                  }
                />
              </View>
              <Text
                className={cn(
                  'text-base font-medium flex-1',
                  a.destructive === true
                    ? 'text-destructive'
                    : 'text-on-surface',
                )}
              >
                {a.title}
              </Text>
            </Pressable>
          ))}
        </SheetContent>
      </Sheet>
    </View>
  );
}
