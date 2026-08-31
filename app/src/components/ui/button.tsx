import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { Pressable, type PressableProps } from 'react-native';
import { cn } from '../../lib/utils';
import { TextClassContext } from './text';

const buttonVariants = cva(
  'group flex flex-row items-center justify-center gap-2 rounded-md active:opacity-80',
  {
    variants: {
      variant: {
        default: 'bg-primary',
        destructive: 'bg-destructive',
        outline: 'border border-input bg-background',
        secondary: 'bg-secondary',
        ghost: 'active:bg-accent',
        link: '',
      },
      size: {
        // Every size clears the 44dp minimum touch target.
        default: 'h-12 px-5 py-3',
        sm: 'h-11 rounded-md px-3',
        lg: 'h-14 rounded-md px-8',
        icon: 'h-12 w-12',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
);

/** Label colors, applied through TextClassContext so children inherit them. */
const buttonTextVariants = cva('text-base font-medium', {
  variants: {
    variant: {
      default: 'text-primary-foreground',
      destructive: 'text-destructive-foreground',
      outline: 'text-foreground',
      secondary: 'text-secondary-foreground',
      ghost: 'text-foreground',
      link: 'text-primary underline',
    },
    size: { default: '', sm: 'text-sm', lg: 'text-lg', icon: '' },
  },
  defaultVariants: { variant: 'default', size: 'default' },
});

type ButtonProps = PressableProps & VariantProps<typeof buttonVariants>;

function Button({
  className,
  variant,
  size,
  disabled,
  ...props
}: ButtonProps): React.ReactElement {
  return (
    <TextClassContext.Provider value={buttonTextVariants({ variant, size })}>
      <Pressable
        role="button"
        disabled={disabled ?? false}
        // Announced as disabled rather than only looking dimmed.
        accessibilityState={{ disabled: disabled ?? false }}
        className={cn(
          buttonVariants({ variant, size }),
          disabled === true && 'opacity-50',
          className,
        )}
        {...props}
      />
    </TextClassContext.Provider>
  );
}

export { Button, buttonTextVariants, buttonVariants };
