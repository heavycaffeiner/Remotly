import * as React from 'react';
import { View, type ViewProps } from 'react-native';
import { Card as PaperCard } from 'react-native-paper';
import { Text } from './text';

type PaperCardProps = React.ComponentProps<typeof PaperCard>;

interface CardProps extends Omit<PaperCardProps, 'mode' | 'elevation'> {
  /** Outlined variant; defaults to a filled container without a border. */
  outlined?: boolean;
}

function Card({ outlined = false, ...props }: CardProps): React.ReactElement {
  return outlined ? (
    <PaperCard mode="outlined" {...props} />
  ) : (
    <PaperCard mode="contained" {...props} />
  );
}

function CardHeader({ style, ...props }: ViewProps): React.ReactElement {
  return <View style={[{ gap: 6, padding: 20 }, style]} {...props} />;
}

function CardTitle(
  props: React.ComponentProps<typeof Text>,
): React.ReactElement {
  return <Text role="heading" variant="title" {...props} />;
}

function CardDescription(
  props: React.ComponentProps<typeof Text>,
): React.ReactElement {
  return <Text variant="muted" {...props} />;
}

function CardContent({ style, ...props }: ViewProps): React.ReactElement {
  return (
    <View
      style={[{ gap: 12, paddingHorizontal: 20, paddingBottom: 20 }, style]}
      {...props}
    />
  );
}

function CardFooter({ style, ...props }: ViewProps): React.ReactElement {
  return (
    <View
      style={[
        {
          flexDirection: 'row',
          alignItems: 'center',
          gap: 8,
          paddingHorizontal: 20,
          paddingBottom: 20,
        },
        style,
      ]}
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
