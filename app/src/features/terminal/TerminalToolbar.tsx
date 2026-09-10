// The terminal's compact top bar.
//
// Deliberately shorter than a screen app bar: every dp here is a terminal row
// the user does not get. The title and the subtitle share one line for the
// same reason, since stacking them costs a row and the subtitle is reference
// information rather than something read continuously.

import * as React from 'react';
import { View } from 'react-native';
import { Surface, TouchableRipple, useTheme } from 'react-native-paper';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { IconButton } from '../../components/Screen';
import { Icon } from '../../components/ui/icon';
import type { IconName } from '../../lib/icons';
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
  /**
   * Called as the menu opens.
   *
   * The terminal's keyboard is put up by the native view, so it outlives an
   * ordinary dismiss and would take the sheet's touches. The owner is the one
   * that can lower it.
   */
  onMenuOpen?: () => void;
}

export function TerminalToolbar({
  title,
  subtitle,
  onBack,
  primaryAction,
  actions,
  onMenuOpen,
}: TerminalToolbarProps): React.ReactElement {
  const insets = useSafeAreaInsets();
  const { colors } = useTheme();
  const [open, setOpen] = React.useState(false);

  return (
    <Surface
      elevation={0}
      style={{
        paddingTop: insets.top,
        borderBottomWidth: 1,
        borderBottomColor: colors.outlineVariant as string,
        backgroundColor: colors.surfaceContainerLow as string,
      }}
    >
      <View
        style={{
          height: 40,
          flexDirection: 'row',
          alignItems: 'center',
          paddingHorizontal: 4,
        }}
      >
        <IconButton icon="arrow-left" label="Go back" onPress={onBack} />
        <View
          style={{
            flex: 1,
            flexDirection: 'row',
            alignItems: 'baseline',
            gap: 8,
          }}
        >
          <Text
            variant="callout"
            style={{ flexShrink: 1, fontWeight: '500' }}
            numberOfLines={1}
          >
            {title}
          </Text>
          {subtitle === undefined ? null : (
            <Text variant="caption" numberOfLines={1} style={{ flexShrink: 1 }}>
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
            icon="dots-vertical"
            label="Terminal actions"
            onPress={() => {
              onMenuOpen?.();
              setOpen(true);
            }}
          />
        )}
      </View>

      <Sheet open={open} onClose={() => setOpen(false)}>
        <SheetHeader>
          <SheetTitle>{title}</SheetTitle>
        </SheetHeader>
        <SheetContent style={{ gap: 4, paddingBottom: 24 }}>
          {actions.map(a => (
            <TouchableRipple
              key={a.key}
              role="button"
              disabled={a.disabled ?? false}
              accessibilityState={{ disabled: a.disabled ?? false }}
              onPress={() => {
                setOpen(false);
                a.onPress();
              }}
              style={{
                height: 44,
                borderRadius: 16,
                overflow: 'hidden',
                paddingHorizontal: 12,
                justifyContent: 'center',
                opacity: a.disabled === true ? 0.4 : 1,
              }}
            >
              <View
                style={{
                  flexDirection: 'row',
                  alignItems: 'center',
                  gap: 12,
                }}
              >
                <View
                  style={{
                    height: 34,
                    width: 34,
                    alignItems: 'center',
                    justifyContent: 'center',
                    borderRadius: 999,
                    backgroundColor:
                      a.destructive === true
                        ? (colors.errorContainer as string)
                        : (colors.secondaryContainer as string),
                  }}
                >
                  <Icon
                    name={a.icon}
                    size={20}
                    color={
                      a.destructive === true
                        ? (colors.error as string)
                        : (colors.onSecondaryContainer as string)
                    }
                  />
                </View>
                <Text
                  variant="body"
                  style={{
                    flex: 1,
                    fontWeight: '500',
                    color:
                      a.destructive === true
                        ? (colors.error as string)
                        : (colors.onSurface as string),
                  }}
                >
                  {a.title}
                </Text>
              </View>
            </TouchableRipple>
          ))}
        </SheetContent>
      </Sheet>
    </Surface>
  );
}
