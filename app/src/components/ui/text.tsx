import * as React from 'react';
import { Text as PaperText, useTheme } from 'react-native-paper';
import type { TypescaleKey } from 'react-native-paper';

export type TextVariant =
  | 'default'
  | 'h1'
  | 'h2'
  | 'h3'
  | 'title'
  | 'body'
  | 'callout'
  | 'caption'
  | 'code'
  | 'muted';

/** The Material type scale slot each app variant renders in. */
const SCALE: Record<TextVariant, TypescaleKey> = {
  default: 'bodyLarge',
  h1: 'headlineLarge',
  h2: 'headlineMedium',
  h3: 'headlineSmall',
  title: 'titleMedium',
  body: 'bodyLarge',
  callout: 'bodyMedium',
  caption: 'bodySmall',
  code: 'bodyMedium',
  muted: 'bodyMedium',
};

/** Variants that read as secondary text rather than primary content. */
const SUBDUED: Partial<Record<TextVariant, true>> = {
  caption: true,
  muted: true,
};

type TextProps = Omit<React.ComponentProps<typeof PaperText>, 'variant'> & {
  variant?: TextVariant;
};

function Text({
  variant = 'default',
  style,
  ...props
}: TextProps): React.ReactElement {
  const { colors } = useTheme();
  return (
    <PaperText
      variant={SCALE[variant]}
      style={[
        {
          color:
            SUBDUED[variant] === true
              ? colors.onSurfaceVariant
              : colors.onSurface,
        },
        variant === 'code' ? { fontFamily: 'monospace' } : null,
        style,
      ]}
      {...props}
    />
  );
}

export { Text };
