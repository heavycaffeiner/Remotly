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
 * A single-choice segmented control.
 *
 * Exposed as a radiogroup: each segment reports its own selected state, so the
 * choice is legible without relying on the highlight.
 */
function Segmented<T extends string>({
  value,
  options,
  onChange,
}: SegmentedProps<T>): React.ReactElement {
  return (
    <View
      accessibilityRole="radiogroup"
      className="flex-row rounded-md border border-border bg-secondary p-1"
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
              'h-10 flex-1 items-center justify-center rounded',
              selected ? 'bg-primary' : 'active:bg-accent',
            )}
          >
            <Text
              className={cn(
                'text-sm font-medium',
                selected
                  ? 'text-primary-foreground'
                  : 'text-secondary-foreground',
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
