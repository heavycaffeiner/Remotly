// JS half of Material You. The native module reads the wallpaper-derived
// scheme out of the Material3 dynamic themes; this module turns that into a
// partial set of Paper colors. An unavailable platform (below Android 12, or
// an unsupported vendor) resolves to null, which means the default theme.

import { Platform } from 'react-native';

type Scheme = 'light' | 'dark';

/** Semantic roles Paper names that the native module also exposes. */
const ROLES = [
  'primary',
  'onPrimary',
  'primaryContainer',
  'onPrimaryContainer',
  'secondary',
  'onSecondary',
  'secondaryContainer',
  'onSecondaryContainer',
  'tertiary',
  'onTertiary',
  'tertiaryContainer',
  'onTertiaryContainer',
  'background',
  'onBackground',
  'surface',
  'onSurface',
  'surfaceVariant',
  'onSurfaceVariant',
  'outline',
  'outlineVariant',
  'error',
  'onError',
  'errorContainer',
  'onErrorContainer',
] as const;

export type DynamicRole = (typeof ROLES)[number];

export type DynamicScheme = Record<string, string>;

export interface DynamicColors {
  available: boolean;
  light?: DynamicScheme;
  dark?: DynamicScheme;
}

const NONE: DynamicColors = { available: false };

// The native module is Android-only. The spec is required lazily so the
// module registry lookup never runs on a platform without the module.
export function getDynamicColors(): Promise<DynamicColors> {
  if (Platform.OS !== 'android') {
    return Promise.resolve(NONE);
  }
  // Lazy require: the spec file enforces registration at import time, and
  // iOS does not ship the module.
  const NativeDynamicColors =
    require('../specs/NativeRemotlyDynamicColors').default;
  return NativeDynamicColors.get().then(
    (d: {
      available?: boolean;
      light?: DynamicScheme;
      dark?: DynamicScheme;
    }) => ({
      available: d.available === true,
      light: d.light ?? undefined,
      dark: d.dark ?? undefined,
    }),
    // Dynamic color is a cosmetic enhancement; a broken bridge falls back to
    // the default theme rather than blocking startup.
    () => NONE,
  );
}

/** Picks the roles Paper will actually override, or an empty set. */
export function dynamicColorsFor(
  colors: DynamicColors,
  scheme: Scheme,
): Partial<Record<DynamicRole, string>> {
  const source = colors.available ? colors[scheme] : undefined;
  if (source === undefined) return {};
  const out: Partial<Record<DynamicRole, string>> = {};
  for (const role of ROLES) {
    const value = source[role];
    if (typeof value === 'string' && value.startsWith('#')) {
      out[role] = value;
    }
  }
  return out;
}
