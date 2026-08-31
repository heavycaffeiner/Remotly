import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { cn } from '../../lib/utils';
import { Text, TextClassContext } from './text';

function Card({ className, ...props }: ViewProps): React.ReactElement {
  return (
    <TextClassContext.Provider value="text-card-foreground">
      <View
        className={cn('rounded-lg border border-border bg-card', className)}
        {...props}
      />
    </TextClassContext.Provider>
  );
}

function CardHeader({ className, ...props }: ViewProps): React.ReactElement {
  return <View className={cn('gap-1.5 p-4', className)} {...props} />;
}

function CardTitle({
  className,
  ...props
}: React.ComponentProps<typeof Text>): React.ReactElement {
  return (
    <Text
      role="heading"
      variant="title"
      className={cn('leading-none', className)}
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
  return <View className={cn('gap-3 p-4 pt-0', className)} {...props} />;
}

function CardFooter({ className, ...props }: ViewProps): React.ReactElement {
  return (
    <View
      className={cn('flex-row items-center gap-2 p-4 pt-0', className)}
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
