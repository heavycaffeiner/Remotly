import * as React from 'react';
import { View, type StyleProp, type ViewStyle } from 'react-native';
import { Surface, useTheme } from 'react-native-paper';
import type { IconName } from '../../lib/icons';
import { Icon } from './icon';
import { Text } from './text';

export type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline';

interface BadgeProps {
  variant?: BadgeVariant;
  /** Leading glyph. Always paired with the label, never carrying meaning alone. */
  icon?: IconName;
  label: string;
  style?: StyleProp<ViewStyle>;
}

/**
 * A compact status pill.
 *
 * Built from Surface rather than Paper's Chip: a chip reads as something to
 * press, and its `selectedColor` does not reach the icon, which left the glyph
 * drawn in the default color on a filled background and effectively invisible.
 */
function Badge({
  variant = 'default',
  icon,
  label,
  style,
}: BadgeProps): React.ReactElement {
  const { colors } = useTheme();
  const fill: Record<BadgeVariant, string | undefined> = {
    default: colors.primary as string,
    secondary: colors.secondaryContainer as string,
    destructive: colors.error as string,
    outline: undefined,
  };
  const ink: Record<BadgeVariant, string> = {
    default: colors.onPrimary as string,
    secondary: colors.onSecondaryContainer as string,
    destructive: colors.onError as string,
    outline: colors.onSurface as string,
  };
  return (
    <Surface
      elevation={0}
      style={[
        {
          flexDirection: 'row',
          alignItems: 'center',
          gap: 4,
          borderRadius: 999,
          paddingHorizontal: 10,
          paddingVertical: 4,
          backgroundColor: fill[variant] ?? 'transparent',
          borderWidth: variant === 'outline' ? 1 : 0,
          borderColor: colors.outlineVariant as string,
        },
        style,
      ]}
    >
      {icon === undefined ? null : (
        <Icon name={icon} size={12} color={ink[variant]} />
      )}
      <View>
        <Text variant="caption" style={{ color: ink[variant] }}>
          {label}
        </Text>
      </View>
    </Surface>
  );
}

export { Badge };
