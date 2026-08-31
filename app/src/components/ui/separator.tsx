import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';

type SeparatorProps = ViewProps & {
  orientation?: 'horizontal' | 'vertical';
};

/** Decorative by default: it is hidden from assistive technology. */
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
        'bg-border',
        orientation === 'horizontal' ? 'h-px w-full' : 'h-full w-px',
        className,
      )}
      {...props}
    />
  );
}

export { Separator };
