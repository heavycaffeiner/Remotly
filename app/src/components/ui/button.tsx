import * as React from 'react';
import { Button as PaperButton, useTheme } from 'react-native-paper';

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

type PaperButtonProps = React.ComponentProps<typeof PaperButton>;
type PaperMode = NonNullable<PaperButtonProps['mode']>;

const MODE: Record<ButtonVariant, PaperMode> = {
  default: 'contained',
  destructive: 'contained',
  outline: 'outlined',
  secondary: 'contained-tonal',
  tonal: 'contained-tonal',
  elevated: 'elevated',
  ghost: 'text',
  link: 'text',
};

/**
 * Content heights, so a row of buttons lines up with the touch targets around
 * it. Applied as a minimum: the label decides the rest.
 */
const MIN_HEIGHT: Record<ButtonSize, number> = {
  default: 40,
  sm: 32,
  lg: 56,
  icon: 40,
};

interface ButtonProps extends Omit<PaperButtonProps, 'mode'> {
  variant?: ButtonVariant;
  size?: ButtonSize;
}

function Button({
  variant = 'default',
  size = 'default',
  contentStyle,
  labelStyle,
  ...props
}: ButtonProps): React.ReactElement {
  const { colors } = useTheme();
  const destructive = variant === 'destructive';
  return (
    <PaperButton
      mode={MODE[variant]}
      compact={size === 'sm' || size === 'icon'}
      buttonColor={destructive ? colors.error : undefined}
      textColor={destructive ? colors.onError : undefined}
      contentStyle={[
        // A minimum, never a fixed height: Paper sizes the label from the
        // theme's type scale, and a hard height clipped it at the small size.
        { minHeight: MIN_HEIGHT[size] },
        size === 'icon' ? { minWidth: MIN_HEIGHT.icon } : null,
        contentStyle,
      ]}
      labelStyle={[
        variant === 'link' ? { textDecorationLine: 'underline' } : null,
        labelStyle,
      ]}
      {...props}
    />
  );
}

export { Button };
