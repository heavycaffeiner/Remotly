import * as React from 'react';
import type { StyleProp, ViewStyle } from 'react-native';
import { ProgressBar } from 'react-native-paper';

interface ProgressProps {
  /** 0 to 1. Omit for an indeterminate bar. */
  value?: number;
  /** Names the operation for assistive technology. */
  label: string;
  style?: StyleProp<ViewStyle>;
}

function Progress({ value, label, style }: ProgressProps): React.ReactElement {
  const determinate = value !== undefined && Number.isFinite(value);
  const clamped = determinate ? Math.max(0, Math.min(1, value)) : 0;
  return (
    <ProgressBar
      progress={determinate ? clamped : undefined}
      indeterminate={!determinate}
      accessibilityRole="progressbar"
      accessibilityLabel={label}
      accessibilityValue={
        determinate
          ? { min: 0, max: 100, now: Math.round(clamped * 100) }
          : undefined
      }
      style={style}
    />
  );
}

export { Progress };
