import * as React from 'react';
import { SegmentedButtons } from 'react-native-paper';

export interface SegmentOption<T extends string> {
  value: T;
  label: string;
  /** Overrides the announced name when the label alone is ambiguous. */
  accessibilityLabel?: string;
}

interface SegmentedProps<T extends string> {
  value: T;
  options: readonly SegmentOption<T>[];
  onChange: (value: T) => void;
}

function Segmented<T extends string>({
  value,
  options,
  onChange,
}: SegmentedProps<T>): React.ReactElement {
  const buttons = React.useMemo(
    () =>
      options.map(option => ({
        value: option.value,
        label: option.label,
        accessibilityLabel: option.accessibilityLabel ?? option.label,
      })),
    [options],
  );
  return (
    <SegmentedButtons
      value={value}
      onValueChange={onChange}
      buttons={buttons}
    />
  );
}

export { Segmented };
