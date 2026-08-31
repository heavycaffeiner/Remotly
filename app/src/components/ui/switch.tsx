import * as React from 'react';
import { Switch as RNSwitch } from 'react-native';
import { themeColors, useAppliedColorScheme } from '../../theme/useColorScheme';

type SwitchProps = Omit<
  React.ComponentProps<typeof RNSwitch>,
  'trackColor' | 'thumbColor'
>;

/**
 * The platform switch.
 *
 * RN's Switch takes colors as props rather than styles, so the theme values
 * are read here instead of through a class name.
 */
function Switch({ value, ...props }: SwitchProps): React.ReactElement {
  const scheme = useAppliedColorScheme();
  const c = themeColors[scheme];
  return (
    <RNSwitch
      value={value}
      trackColor={{ false: c.border, true: c.primary }}
      thumbColor={c.background}
      {...props}
    />
  );
}

export { Switch };
