// The screen shell: a top app bar over a themed surface.
//
// The bar owns the top safe-area inset. Screens pass content and actions; they
// do not assemble a header of their own.

import * as React from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import {
  Appbar,
  IconButton as PaperIconButton,
  Surface,
  TouchableRipple,
  useTheme,
} from 'react-native-paper';
import { Icon } from './ui/icon';
import type { IconName } from '../lib/icons';
import { Text } from './ui/text';
import { Sheet, SheetContent, SheetHeader, SheetTitle } from './ui/sheet';

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
  const { colors } = useTheme();
  const [menuOpen, setMenuOpen] = React.useState(false);

  return (
    <View style={{ flex: 1, backgroundColor: colors.background }}>
      {bare ? (
        actions.length === 0 ? null : (
          <Surface
            elevation={0}
            style={{
              flexDirection: 'row',
              alignItems: 'center',
              justifyContent: 'flex-end',
              backgroundColor: colors.surfaceContainerLow,
              paddingHorizontal: 8,
              paddingVertical: 4,
            }}
          >
            {actions.map(a => (
              <IconButton
                key={a.key}
                icon={a.icon}
                label={a.title}
                disabled={a.disabled ?? false}
                onPress={a.onPress}
              />
            ))}
          </Surface>
        )
      ) : (
        // Shorter than Material's 64dp: the title and its subtitle take one
        // line of type each, and the rest of that height was empty.
        <Appbar.Header style={{ height: 52 }}>
          {onBack === undefined ? null : (
            <Appbar.BackAction accessibilityLabel="Go back" onPress={onBack} />
          )}
          <Appbar.Content
            title={
              <View>
                <Text variant="h3" numberOfLines={1}>
                  {title}
                </Text>
                {subtitle === undefined ? null : (
                  <Text variant="caption" numberOfLines={1}>
                    {subtitle}
                  </Text>
                )}
              </View>
            }
          />
          {actions.map(a => (
            <Appbar.Action
              key={a.key}
              icon={a.icon}
              accessibilityLabel={a.title}
              disabled={a.disabled ?? false}
              onPress={a.onPress}
            />
          ))}
          {menuActions.length === 0 ? null : (
            <Appbar.Action
              icon="dots-vertical"
              accessibilityLabel="More actions"
              onPress={() => setMenuOpen(true)}
            />
          )}
        </Appbar.Header>
      )}

      {/* A view of its own, kept out of view flattening, so a screen's states
          swap inside a container that exists for the screen's whole life. Left
          as bare children, the branches were mounted next to the appbar and a
          swap could arrive after Android had already dropped their parent: the
          content then measured zero and the screen looked empty. */}
      <View style={{ flex: 1 }} collapsable={false}>
        {children}
      </View>

      <Sheet open={menuOpen} onClose={() => setMenuOpen(false)}>
        <SheetHeader>
          <SheetTitle>Actions</SheetTitle>
        </SheetHeader>
        <SheetContent style={{ gap: 4, paddingBottom: 24 }}>
          {menuActions.map(a => (
            <TouchableRipple
              key={a.key}
              role="button"
              disabled={a.disabled ?? false}
              accessibilityState={{ disabled: a.disabled ?? false }}
              onPress={() => {
                setMenuOpen(false);
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
                    backgroundColor: colors.secondaryContainer,
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
    </View>
  );
}

interface IconButtonProps {
  icon: IconName;
  /** Required: an icon-only control is unusable without an accessible name. */
  label: string;
  onPress: () => void;
  disabled?: boolean;
  style?: StyleProp<ViewStyle>;
}

export function IconButton({
  icon,
  label,
  onPress,
  disabled = false,
  style,
}: IconButtonProps): React.ReactElement {
  return (
    <PaperIconButton
      icon={icon}
      size={22}
      accessibilityLabel={label}
      accessibilityState={{ disabled }}
      disabled={disabled}
      onPress={onPress}
      style={style}
    />
  );
}
