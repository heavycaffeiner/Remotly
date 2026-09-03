import * as React from 'react';
import { Pressable, View } from 'react-native';
import { cn } from '../../lib/utils';
import { Text } from './text';

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

/**
 * A single-choice segmented button following Material Design 3 specifications.
 */
function Segmented<T extends string>({
  value,
  options,
  onChange,
}: SegmentedProps<T>): React.ReactElement {
  return (
    <View
      accessibilityRole="radiogroup"
      className="flex-row rounded-full border border-outline/40 bg-surface p-1"
    >
      {options.map(option => {
        const selected = option.value === value;
        return (
          <Pressable
            key={option.value}
            role="radio"
            accessibilityState={{ selected, checked: selected }}
            accessibilityLabel={option.accessibilityLabel ?? option.label}
            onPress={() => onChange(option.value)}
            className={cn(
              'h-10 flex-1 items-center justify-center rounded-full overflow-hidden',
              selected
                ? 'bg-secondary'
                : 'bg-transparent active:bg-surface-variant/40',
            )}
          >
            <Text
              className={cn(
                'text-sm font-medium',
                selected
                  ? 'text-secondary-foreground font-semibold'
                  : 'text-foreground',
              )}
            >
              {option.label}
            </Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export { Segmented };
