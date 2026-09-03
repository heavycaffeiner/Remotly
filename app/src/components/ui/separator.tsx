import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';

type SeparatorProps = ViewProps & {
  orientation?: 'horizontal' | 'vertical';
};

/**
 * Material Design 3 Divider.
 *
 * Decorative by default: hidden from assistive technology.
 */
function Separator({
  className,
  orientation = 'horizontal',
  ...props
}: SeparatorProps): React.ReactElement {
  return (
    <View
      accessibilityElementsHidden
      importantForAccessibility="no-hide-descendants"
      className={cn(
        'bg-outline-variant/30',
        orientation === 'horizontal' ? 'h-px w-full' : 'h-full w-px',
        className,
      )}
      {...props}
    />
  );
}

export { Separator };
