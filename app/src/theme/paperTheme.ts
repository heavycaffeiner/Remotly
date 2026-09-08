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

// Material's extra-small corner is 4dp, and it lands on the shapes this app
// shows most of: every text field outline, and the terminal's key caps. At 4dp
// a field reads as a box drawn around the text; the rest of the scale is left
// alone so cards and dialogs keep their Material radii.
const CORNER = { extraSmall: 10 } as const;
