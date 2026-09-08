// Form primitives.
//
// A section groups related fields under one heading; a labelled field ties its
// label and error to the input. Both exist so every form in the app spaces and
// announces itself the same way.

import * as React from 'react';
import { View } from 'react-native';
import { useTheme } from 'react-native-paper';
import { Input } from './ui/input';
import { Text } from './ui/text';

interface FormSectionProps {
  title: string;
  /** Explains the group when the heading alone is not enough. */
  description?: string;
  children: React.ReactNode;
}

export function FormSection({
  title,
  description,
  children,
}: FormSectionProps): React.ReactElement {
  const { colors } = useTheme();
  return (
    <View style={{ gap: 6, paddingHorizontal: 16, paddingTop: 12 }}>
      <Text
        role="heading"
        variant="caption"
        style={{
          fontWeight: '600',
          textTransform: 'uppercase',
          letterSpacing: 1,
          color: colors.primary as string,
        }}
      >
        {title}
      </Text>
      {description === undefined ? null : (
        <Text variant="caption">{description}</Text>
      )}
      {children}
    </View>
  );
}

interface FieldErrorProps {
  /** Nothing renders when empty, so callers can pass state directly. */
  message: string;
}

export function FieldError({
  message,
}: FieldErrorProps): React.ReactElement | null {
  const { colors } = useTheme();
  if (message === '') return null;
  return (
    <Text
      variant="caption"
      accessibilityLiveRegion="polite"
      style={{ color: colors.error as string }}
    >
      {message}
    </Text>
  );
}

type FieldProps = React.ComponentProps<typeof Input> & {
  label: string;
  /** Rendered under the input and announced politely. */
  error?: string;
  /** Shown under the input when there is no error. */
  hint?: string;
};

/**
 * A labelled text field.
 *
 * The label sits in the outline rather than above it: it is the same label
 * either way, and stacking it cost a line of height in every field of every
 * form. It is also the accessible name, so a placeholder is never left doing
 * that job.
 */
export function Field({
  label,
  error = '',
  hint,
  ...props
}: FieldProps): React.ReactElement {
  return (
    <View style={{ gap: 4 }}>
      <Input
        label={label}
        accessibilityLabel={label}
        invalid={error !== ''}
        {...props}
      />
      {error !== '' ? (
        <FieldError message={error} />
      ) : hint === undefined ? null : (
        <Text variant="caption">{hint}</Text>
      )}
    </View>
  );
}
