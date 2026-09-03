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

const TEXT_STYLES: Record<TextVariant, string> = {
  default: 'text-base text-foreground',
  h1: 'text-3xl font-bold tracking-tight text-foreground',
  h2: 'text-2xl font-semibold tracking-tight text-foreground',
  h3: 'text-xl font-semibold tracking-tight text-foreground',
  title: 'text-lg font-semibold text-foreground',
  body: 'text-base text-foreground',
  callout: 'text-sm text-foreground',
  caption: 'text-xs text-muted-foreground',
  code: 'font-mono text-sm text-foreground',
  muted: 'text-sm text-muted-foreground',
};

function textVariants(props?: { variant?: TextVariant }): string {
  return TEXT_STYLES[props?.variant ?? 'default'];
}

type TextProps = React.ComponentProps<typeof RNText> & {
  variant?: TextVariant;
};

function Text({
  className,
  variant = 'default',
  ...props
}: TextProps): React.ReactElement {
  const context = React.useContext(TextClassContext);
  return (
    <RNText
      className={cn(TEXT_STYLES[variant], context, className)}
      {...props}
    />
  );
}

export { Text, TextClassContext, textVariants };
