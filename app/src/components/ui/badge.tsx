import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';
import { TextClassContext } from './text';

export type BadgeVariant = 'default' | 'secondary' | 'destructive' | 'outline';

const BADGE_CONTAINER_STYLES: Record<BadgeVariant, string> = {
  default: 'border-transparent bg-primary',
  secondary: 'border-transparent bg-secondary',
  destructive: 'border-transparent bg-destructive',
  outline: 'border-outline/40',
};

const BADGE_TEXT_STYLES: Record<BadgeVariant, string> = {
  default: 'text-primary-foreground text-xs font-medium',
  secondary: 'text-secondary-foreground text-xs font-medium',
  destructive: 'text-destructive-foreground text-xs font-medium',
  outline: 'text-foreground text-xs font-medium',
};

function badgeVariants(props?: { variant?: BadgeVariant }): string {
  return cn(
    'flex-row items-center gap-1 rounded-full border px-2.5 py-1',
    BADGE_CONTAINER_STYLES[props?.variant ?? 'default'],
  );
}

function badgeTextVariants(props?: { variant?: BadgeVariant }): string {
  return BADGE_TEXT_STYLES[props?.variant ?? 'default'];
}

interface BadgeProps extends ViewProps {
  variant?: BadgeVariant;
}

function Badge({
  className,
  variant = 'default',
  ...props
}: BadgeProps): React.ReactElement {
  return (
    <TextClassContext.Provider value={badgeTextVariants({ variant })}>
      <View className={cn(badgeVariants({ variant }), className)} {...props} />
    </TextClassContext.Provider>
  );
}

export { Badge, badgeTextVariants, badgeVariants };
