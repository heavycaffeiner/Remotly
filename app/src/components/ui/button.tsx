import { cva, type VariantProps } from 'class-variance-authority';
import * as React from 'react';
import { Pressable, type PressableProps } from 'react-native';
import { cn } from '../../lib/utils';
import { TextClassContext } from './text';

const buttonVariants = cva(
  'group flex flex-row items-center justify-center gap-2 rounded-full overflow-hidden active:opacity-85',
  {
    variants: {
      variant: {
        default: 'bg-primary',
        destructive: 'bg-destructive',
        outline: 'border border-outline bg-transparent',
        secondary: 'bg-secondary',
        tonal: 'bg-secondary',
        elevated: 'bg-card shadow-sm',
        ghost: 'bg-transparent active:bg-accent',
        link: '',
      },
      size: {
        default: 'h-11 px-6 py-2.5',
        sm: 'h-9 px-4',
        lg: 'h-12 px-8',
        icon: 'h-11 w-11 p-0',
      },
    },
    defaultVariants: { variant: 'default', size: 'default' },
  },
);

/** Label colors, applied through TextClassContext so children inherit them. */
const buttonTextVariants = cva('text-sm font-medium tracking-wide', {
  variants: {
    variant: {
      default: 'text-primary-foreground',
      destructive: 'text-destructive-foreground',
      outline: 'text-primary',
      secondary: 'text-secondary-foreground',
      tonal: 'text-secondary-foreground',
      elevated: 'text-primary',
      ghost: 'text-primary',
      link: 'text-primary underline',
    },
    size: { default: '', sm: 'text-xs', lg: 'text-base', icon: '' },
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
  const isFilled = variant === 'default' || variant === 'destructive';
  return (
    <TextClassContext.Provider value={buttonTextVariants({ variant, size })}>
      <Pressable
        role="button"
        disabled={disabled ?? false}
        accessibilityState={{ disabled: disabled ?? false }}
        android_ripple={{
          color: isFilled ? 'rgba(255, 255, 255, 0.2)' : 'rgba(0, 0, 0, 0.08)',
          borderless: false,
        }}
        className={cn(
          buttonVariants({ variant, size }),
          disabled === true && 'opacity-38',
          className,
        )}
        {...props}
      />
    </TextClassContext.Provider>
  );
}

export { Button, buttonTextVariants, buttonVariants };
