import * as Slot from '@rn-primitives/slot';
import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { Text as RNText } from 'react-native';
import { cn } from '../../lib/utils';

/**
 * Inherited text class names.
 *
 * A Text nested inside another Text picks up the parent's classes, which is
 * how a card can set a foreground color once for everything inside it.
 */
const TextClassContext = React.createContext<string | undefined>(undefined);

const textVariants = cva('text-foreground', {
  variants: {
    variant: {
      default: 'text-base',
      h1: 'text-3xl font-bold tracking-tight',
      h2: 'text-2xl font-semibold tracking-tight',
      h3: 'text-xl font-semibold tracking-tight',
      title: 'text-lg font-semibold',
      body: 'text-base',
      callout: 'text-sm',
      caption: 'text-xs text-muted-foreground',
      code: 'font-mono text-sm',
      muted: 'text-sm text-muted-foreground',
    },
  },
  defaultVariants: { variant: 'default' },
});

type TextProps = React.ComponentProps<typeof RNText> &
  VariantProps<typeof textVariants> & {
    /** Renders into the caller's child instead of a Text of its own. */
    asChild?: boolean;
  };

function Text({
  className,
  variant,
  asChild = false,
  ...props
}: TextProps): React.ReactElement {
  const context = React.useContext(TextClassContext);
  const Component = asChild ? Slot.Text : RNText;
  return (
    <Component
      className={cn(textVariants({ variant }), context, className)}
      {...props}
    />
  );
}

export { Text, TextClassContext, textVariants };
