// Resolves the active color scheme from the user's choice and the OS.
//
// NativeWind owns the actual switch: setting its scheme toggles the `dark`
// class on the root, which is what re-resolves every CSS variable.

import { useEffect } from 'react';
import { useColorScheme as useNativeWindColorScheme } from 'nativewind';
import { useWindowDimensions } from 'react-native';
import { useSettings } from './SettingsProvider';
import type { ThemeMode } from '../lib/settings';

/** Keeps NativeWind's scheme in step with the stored preference. */
export function useAppliedColorScheme(): 'light' | 'dark' {
  const { settings } = useSettings();
  const { colorScheme, setColorScheme } = useNativeWindColorScheme();
  const mode: ThemeMode = settings.themeMode;

  useEffect(() => {
    setColorScheme(mode === 'system' ? 'system' : mode);
  }, [mode, setColorScheme]);

  return colorScheme === 'light' ? 'light' : 'dark';
}

/** Raw values for the few APIs that cannot take a class name. */
export const themeColors = {
  light: {
    background: 'hsl(210 20% 98%)',
    foreground: 'hsl(220 20% 10%)',
    card: 'hsl(210 25% 96%)',
    border: 'hsl(215 15% 80%)',
    primary: 'hsl(204 100% 32%)',
    muted: 'hsl(215 20% 90%)',
    terminal: 'hsl(222 47% 8%)',
  },
  dark: {
    background: 'hsl(220 20% 8%)',
    foreground: 'hsl(210 20% 90%)',
    card: 'hsl(220 18% 11%)',
    border: 'hsl(215 15% 28%)',
    primary: 'hsl(209 100% 76%)',
    muted: 'hsl(217 15% 25%)',
    terminal: 'hsl(222 47% 6%)',
  },
} as const;

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
