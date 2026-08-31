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
 * A determinate or indeterminate progress bar.
 *
 * The indeterminate form is a static partial fill rather than an animation:
 * it says "working" without pulling a frame loop in for a bar that is often
 * on screen for under a second.
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
      className={cn('h-1 overflow-hidden rounded-full bg-muted', className)}
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
