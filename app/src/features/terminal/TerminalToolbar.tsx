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
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '../../components/ui/dialog';

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

      <Dialog open={open} onClose={() => setOpen(false)}>
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
        </DialogHeader>
        <DialogContent className="pb-5">
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
              className={cn(
                'h-12 flex-row items-center gap-3 rounded-md px-3 active:bg-accent',
                a.disabled === true && 'opacity-50',
                a.destructive === true && 'mt-1 border-t border-border',
              )}
            >
              <Icon
                name={a.icon}
                className={
                  a.destructive === true
                    ? 'text-destructive'
                    : 'text-foreground'
                }
              />
              <Text
                className={a.destructive === true ? 'text-destructive' : ''}
              >
                {a.title}
              </Text>
            </Pressable>
          ))}
        </DialogContent>
      </Dialog>
    </View>
  );
}
