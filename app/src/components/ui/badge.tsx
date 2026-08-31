import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';
import { TextClassContext } from './text';

const badgeVariants = cva(
  'flex-row items-center gap-1 rounded-full border px-2.5 py-1',
  {
    variants: {
      variant: {
        default: 'border-transparent bg-primary',
        secondary: 'border-transparent bg-secondary',
        destructive: 'border-transparent bg-destructive',
        outline: 'border-border',
      },
    },
    defaultVariants: { variant: 'default' },
  },
);

const badgeTextVariants = cva('text-xs font-medium', {
  variants: {
    variant: {
      default: 'text-primary-foreground',
      secondary: 'text-secondary-foreground',
      destructive: 'text-destructive-foreground',
      outline: 'text-foreground',
    },
  },
  defaultVariants: { variant: 'default' },
});

type BadgeProps = ViewProps & VariantProps<typeof badgeVariants>;

function Badge({
  className,
  variant,
  ...props
}: BadgeProps): React.ReactElement {
  return (
    <TextClassContext.Provider value={badgeTextVariants({ variant })}>
      <View className={cn(badgeVariants({ variant }), className)} {...props} />
    </TextClassContext.Provider>
  );
}

export { Badge, badgeTextVariants, badgeVariants };
