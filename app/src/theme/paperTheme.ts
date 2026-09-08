import { DarkTheme, LightTheme } from 'react-native-paper';
import type { Theme } from 'react-native-paper';
import type { DynamicRole } from '../lib/dynamicColors';

type Scheme = 'light' | 'dark';

type DynamicOverride = Partial<Record<DynamicRole, string>>;

export function paperTheme(
  scheme: Scheme,
  dynamic: DynamicOverride = {},
): Theme {
  const base = scheme === 'dark' ? DarkTheme : LightTheme;
  return {
    ...base,
    shapes: { ...base.shapes, corner: { ...base.shapes.corner, ...CORNER } },
    colors: { ...base.colors, ...dynamic },
  };
}

// Material's extra-small corner is 4dp, which reads as a hard edge on the
// small surfaces that use it: menus, tooltips, the segmented buttons, and the
// snackbar. The rest of the scale is left alone so cards and dialogs keep
// their Material radii. Text fields are not covered: they take this corner
// from an imported constant rather than the theme, so nothing here reaches
// them.
const CORNER = { extraSmall: 10 } as const;
