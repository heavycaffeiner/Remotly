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

// Rounder than Material's own scale, which is what carries the look here: the
// app is lists of cards and chips, and 12dp on a card reads as a rectangle
// with the corners taken off. Only the two tokens the app actually renders
// through are moved: cards take `medium`, chips take `small`. Buttons and
// dialogs are already at pill and 28dp.
const CORNER = { medium: 18, small: 12 } as const;
