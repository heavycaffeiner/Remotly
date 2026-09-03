import * as React from 'react';
import { Pressable, type PressableProps } from 'react-native';
import { cn } from '../../lib/utils';
import { TextClassContext } from './text';

export type ButtonVariant =
  | 'default'
  | 'destructive'
  | 'outline'
  | 'secondary'
  | 'tonal'
  | 'elevated'
  | 'ghost'
  | 'link';

export type ButtonSize = 'default' | 'sm' | 'lg' | 'icon';

const BUTTON_CONTAINER_STYLES: Record<ButtonVariant, string> = {
  default: 'bg-primary',
  destructive: 'bg-destructive',
  outline: 'border border-outline bg-transparent',
  secondary: 'bg-secondary-container',
  tonal: 'bg-secondary-container',
  elevated: 'bg-surface-container-low shadow-sm',
  ghost: 'bg-transparent active:bg-surface-variant/40',
  link: '',
};

const BUTTON_SIZE_STYLES: Record<ButtonSize, string> = {
  default: 'h-11 px-6 py-2.5',
  sm: 'h-9 px-4',
  lg: 'h-12 px-8',
  icon: 'h-11 w-11 p-0',
};

const BUTTON_TEXT_STYLES: Record<ButtonVariant, string> = {
  default: 'text-on-primary',
  destructive: 'text-destructive-foreground',
  outline: 'text-primary',
  secondary: 'text-on-secondary-container',
  tonal: 'text-on-secondary-container',
  elevated: 'text-primary',
  ghost: 'text-primary',
  link: 'text-primary underline',
};

const BUTTON_TEXT_SIZES: Record<ButtonSize, string> = {
  default: '',
  sm: 'text-xs',
  lg: 'text-base',
  icon: '',
};

function buttonVariants(props?: {
  variant?: ButtonVariant;
  size?: ButtonSize;
}): string {
  const v = props?.variant ?? 'default';
  const s = props?.size ?? 'default';
  return cn(
    'group flex flex-row items-center justify-center gap-2 rounded-full overflow-hidden active:opacity-85',
    BUTTON_CONTAINER_STYLES[v],
    BUTTON_SIZE_STYLES[s],
  );
}

function buttonTextVariants(props?: {
  variant?: ButtonVariant;
  size?: ButtonSize;
}): string {
  const v = props?.variant ?? 'default';
  const s = props?.size ?? 'default';
  return cn(
    'text-sm font-medium tracking-wide',
    BUTTON_TEXT_STYLES[v],
    BUTTON_TEXT_SIZES[s],
  );
}

interface ButtonProps extends PressableProps {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

function Button({
  className,
  variant = 'default',
  size = 'default',
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
