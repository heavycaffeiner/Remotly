import * as React from 'react';
import { Icon as PaperIcon, useTheme } from 'react-native-paper';
import type { IconName } from '../../lib/icons';

interface IconProps {
  name: IconName;
  size?: number;
  color?: string;
}

function Icon({ name, size = 20, color }: IconProps): React.ReactElement {
  const { colors } = useTheme();
  return (
    <PaperIcon source={name} size={size} color={color ?? colors.onSurface} />
  );
}

export { Icon };
export type { IconName } from '../../lib/icons';
