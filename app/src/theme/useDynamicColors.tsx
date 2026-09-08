// Loads the Material You (dynamic color) scheme once at startup and exposes
// it to the theme layer.
//
// The load rides the startup phase: without it the first frame would paint
// the default palette and then repaint into the wallpaper colors, which
// reads as a flash.

import React, {
  createContext,
  useContext,
  useEffect,
  useMemo,
  useState,
} from 'react';
import { Platform } from 'react-native';

import { getDynamicColors, type DynamicColors } from '../lib/dynamicColors';
import { useSettings } from './SettingsProvider';

interface DynamicColorsState {
  /** True once the startup load has settled. */
  ready: boolean;
  /** The wallpaper-derived scheme, or null when it does not apply. */
  colors: DynamicColors | null;
}

const DynamicColorsContext = createContext<DynamicColorsState | null>(null);

// Holds the wallpaper-derived color scheme for the tree. Must be a direct
// child of SettingsProvider, which it reads the user's toggle from.
export function DynamicColorsProvider({
  children,
}: {
  children: React.ReactNode;
}): React.ReactElement {
  const { settings, loaded } = useSettings();
  const [fetched, setFetched] = useState<DynamicColors | null>(() =>
    // Dynamic color is an Android 12+ feature. On other platforms the native
    // module does not exist, so the default theme always applies and there
    // is nothing to load.
    Platform.OS === 'android' ? null : { available: false },
  );

  useEffect(() => {
    if (Platform.OS !== 'android') return;
    let live = true;
    getDynamicColors().then(result => {
      if (live) setFetched(result);
    });
    return () => {
      live = false;
    };
  }, []);

  const value = useMemo<DynamicColorsState>(
    () => ({
      ready: loaded && fetched !== null,
      colors: fetched !== null && settings.dynamicColor ? fetched : null,
    }),
    [loaded, fetched, settings.dynamicColor],
  );

  return (
    <DynamicColorsContext.Provider value={value}>
      {children}
    </DynamicColorsContext.Provider>
  );
}

export function useDynamicColors(): DynamicColorsState {
  const ctx = useContext(DynamicColorsContext);
  if (ctx === null) {
    throw new Error('useDynamicColors requires DynamicColorsProvider');
  }
  return ctx;
}
