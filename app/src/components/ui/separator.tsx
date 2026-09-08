import * as React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import { Divider, useTheme } from 'react-native-paper';

interface SeparatorProps {
  orientation?: 'horizontal' | 'vertical';
  style?: StyleProp<ViewStyle>;
}

/** Decorative by default: hidden from assistive technology. */
function Separator({
  orientation = 'horizontal',
  style,
}: SeparatorProps): React.ReactElement {
  const { colors } = useTheme();
  if (orientation === 'vertical') {
    return (
      <Divider
        accessibilityElementsHidden
        importantForAccessibility="no-hide-descendants"
        style={[
          { width: 1, height: '100%', backgroundColor: colors.outlineVariant },
          style,
        ]}
      />
    );
  }
  return (
    <Divider
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      style={style}
    />
  );
}

export { Separator };
