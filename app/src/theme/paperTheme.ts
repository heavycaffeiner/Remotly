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
  if (Object.keys(dynamic).length === 0) return base;
  return {
    ...base,
    colors: { ...base.colors, ...dynamic },
  };
}
