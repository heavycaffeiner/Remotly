// The screen shell: a top app bar over a themed surface.
//
// The bar owns the top safe-area inset. Screens pass content and actions; they
// do not assemble a header of their own.

import * as React from 'react';
import { Pressable, View } from 'react-native';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { cn } from '../lib/utils';
import { Icon, type IconName } from './ui/icon';
import { Text } from './ui/text';
import { Dialog, DialogContent, DialogHeader, DialogTitle } from './ui/dialog';

export interface ScreenAction {
  key: string;
  /** The icon's accessible name, and the label in the overflow sheet. */
  title: string;
  icon: IconName;
  onPress: () => void;
  disabled?: boolean;
  /** Shown in the destructive color inside the overflow sheet. */
  destructive?: boolean;
}

interface ScreenProps {
  title: string;
  subtitle?: string;
  onBack?: () => void;
  /** Shown directly in the bar. */
  actions?: readonly ScreenAction[];
  /** Collected behind an overflow button. */
  menuActions?: readonly ScreenAction[];
  /**
   * Drops the title bar, for a screen rendered as a pane inside another one
   * that already has its own. The actions still render, in a compact row, so
   * an embedded screen does not lose them.
   */
  bare?: boolean;
  children: React.ReactNode;
}

export function Screen({
  title,
  subtitle,
  onBack,
  actions = [],
  menuActions = [],
  bare = false,
  children,
}: ScreenProps): React.ReactElement {
  const insets = useSafeAreaInsets();
  const [menuOpen, setMenuOpen] = React.useState(false);

  return (
    <View className="flex-1 bg-background">
      {bare ? (
        actions.length === 0 ? null : (
          <View className="flex-row items-center justify-end border-b border-border bg-card px-1 py-1">
            {actions.map(a => (
              <IconButton
                key={a.key}
                icon={a.icon}
                label={a.title}
                disabled={a.disabled ?? false}
                onPress={a.onPress}
              />
            ))}
          </View>
        )
      ) : (
        <View
          style={{ paddingTop: insets.top }}
          className="border-b border-border bg-card"
        >
          <View className="h-14 flex-row items-center px-1">
            {onBack === undefined ? null : (
              <IconButton icon="arrow-left" label="Go back" onPress={onBack} />
            )}
            <View className={cn('flex-1', onBack === undefined && 'ml-3')}>
              <Text variant="title" numberOfLines={1}>
                {title}
              </Text>
              {subtitle === undefined ? null : (
                <Text variant="caption" numberOfLines={1}>
                  {subtitle}
                </Text>
              )}
            </View>
            {actions.map(a => (
              <IconButton
                key={a.key}
                icon={a.icon}
                label={a.title}
                disabled={a.disabled ?? false}
                onPress={a.onPress}
              />
            ))}
            {menuActions.length === 0 ? null : (
              <IconButton
                icon="more"
                label="More actions"
                onPress={() => setMenuOpen(true)}
              />
            )}
          </View>
        </View>
      )}

      {children}

      <Dialog open={menuOpen} onClose={() => setMenuOpen(false)}>
        <DialogHeader>
          <DialogTitle>Actions</DialogTitle>
        </DialogHeader>
        <DialogContent className="pb-5">
          {menuActions.map(a => (
            <Pressable
              key={a.key}
              role="button"
              disabled={a.disabled ?? false}
              accessibilityState={{ disabled: a.disabled ?? false }}
              onPress={() => {
                setMenuOpen(false);
                a.onPress();
              }}
              className={cn(
                'h-12 flex-row items-center gap-3 rounded-md px-3 active:bg-accent',
                a.disabled === true && 'opacity-50',
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

interface IconButtonProps {
  icon: IconName;
  /** Required: an icon-only control is unusable without an accessible name. */
  label: string;
  onPress: () => void;
  disabled?: boolean;
  className?: string;
}

export function IconButton({
  icon,
  label,
  onPress,
  disabled = false,
  className,
}: IconButtonProps): React.ReactElement {
  return (
    <Pressable
      role="button"
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      className={cn(
        'h-12 w-12 items-center justify-center rounded-full active:bg-accent',
        disabled && 'opacity-50',
        className,
      )}
    >
      <Icon name={icon} size={22} />
    </Pressable>
  );
}
