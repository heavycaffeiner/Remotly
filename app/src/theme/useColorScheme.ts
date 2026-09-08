// Resolves the active color scheme from the user's choice and the OS.
//
// The scheme is a plain 'light' | 'dark' value: Paper's theme and the palette
// are both keyed off it, so nothing needs to toggle a class or re-read CSS.

import {
  useColorScheme as useSystemScheme,
  useWindowDimensions,
} from 'react-native';
import { useSettings } from './SettingsProvider';
import type { ThemeMode } from '../lib/settings';

export function useAppliedColorScheme(): 'light' | 'dark' {
  const { settings } = useSettings();
  const system = useSystemScheme();
  const mode: ThemeMode = settings.themeMode;
  const scheme = mode === 'system' ? system : mode;
  return scheme === 'light' ? 'light' : 'dark';
}

export type WidthClass = 'compact' | 'medium' | 'expanded';

export function widthClass(width: number): WidthClass {
  if (width >= 840) return 'expanded';
  if (width >= 600) return 'medium';
  return 'compact';
}

export function useWidthClass(): WidthClass {
  const { width } = useWindowDimensions();
  return widthClass(width);
}
