// Form primitives.
//
// A section groups related fields under one heading; a labelled field ties its
// label and error to the input. Both exist so every form in the app spaces and
// announces itself the same way.

import * as React from 'react';
import { View } from 'react-native';
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
  return (
    <View className="gap-2 px-4 pt-4">
      <Text
        role="heading"
        variant="caption"
        className="font-semibold uppercase tracking-wide text-primary"
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
  if (message === '') return null;
  return (
    <Text
      variant="caption"
      accessibilityLiveRegion="polite"
      className="text-destructive"
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
 * The visible label is also the accessible name, so a placeholder is never
 * left doing that job.
 */
export function Field({
  label,
  error = '',
  hint,
  ...props
}: FieldProps): React.ReactElement {
  return (
    <View className="gap-1.5">
      <Text variant="callout" className="font-medium">
        {label}
      </Text>
      <Input accessibilityLabel={label} invalid={error !== ''} {...props} />
      {error !== '' ? (
        <FieldError message={error} />
      ) : hint === undefined ? null : (
        <Text variant="caption">{hint}</Text>
      )}
    </View>
  );
}
