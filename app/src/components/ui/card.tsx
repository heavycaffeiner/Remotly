import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';
import { Text, TextClassContext } from './text';

interface CardProps extends ViewProps {
  /** Outlined variant with subtle border; defaults to filled card without border. */
  outlined?: boolean;
}

function Card({
  className,
  outlined = false,
  ...props
}: CardProps): React.ReactElement {
  return (
    <TextClassContext.Provider value="text-card-foreground">
      <View
        className={cn(
          'rounded-2xl bg-card overflow-hidden',
          outlined ? 'border border-outline/25' : '',
          className,
        )}
        {...props}
      />
    </TextClassContext.Provider>
  );
}

function CardHeader({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-1.5 p-5', className)} {...props} />;
}

function CardTitle({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return (
    <Text
      role="heading"
      variant="title"
      className={cn(
        'text-lg font-semibold tracking-tight text-foreground',
        className,
      )}
      {...props}
    />
  );
}

function CardDescription({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return <Text variant="muted" className={className} {...props} />;
}

function CardContent({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-3 p-5 pt-0', className)} {...props} />;
}

function CardFooter({ className, ...props }: ViewProps): React.ReactElement {
  return (
    <View
      className={cn('flex-row items-center gap-2 p-5 pt-0', className)}
      {...props}
    />
  );
}

export {
  Card,
  CardContent,
  CardDescription,
  CardFooter,
  CardHeader,
  CardTitle,
};
