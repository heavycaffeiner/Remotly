import * as React from 'react';
import { View } from 'react-native';
import { cn } from '../../lib/utils';

interface ProgressProps {
  /** 0 to 1. Omit for an indeterminate bar. */
  value?: number;
  /** Names the operation for assistive technology. */
  label: string;
  className?: string;
}

/**
 * Material Design 3 Linear Progress Bar.
 */
function Progress({
  value,
  label,
  className,
}: ProgressProps): React.ReactElement {
  const determinate = value !== undefined && Number.isFinite(value);
  const pct = determinate ? Math.max(0, Math.min(1, value)) * 100 : 40;
  return (
    <View
      accessibilityRole="progressbar"
      accessibilityLabel={label}
      accessibilityValue={
        determinate ? { min: 0, max: 100, now: Math.round(pct) } : undefined
      }
      className={cn(
        'h-1 overflow-hidden rounded-full bg-surface-container-highest',
        className,
      )}
    >
      <View
        style={{ width: `${pct}%` }}
        className={cn(
          'h-full rounded-full bg-primary',
          !determinate && 'opacity-60',
        )}
      />
    </View>
  );
}

export { Progress };
