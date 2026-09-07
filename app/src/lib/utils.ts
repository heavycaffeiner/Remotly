export type ClassValue =
  | string
  | number
  | boolean
  | undefined
  | null
  | { [key: string]: unknown }
  | ClassValue[];

function flatten(input: ClassValue, out: string[]): void {
  if (!input) return;
  if (typeof input === 'string') {
    const trimmed = input.trim();
    if (trimmed) out.push(trimmed);
  } else if (typeof input === 'number') {
    out.push(String(input));
  } else if (Array.isArray(input)) {
    for (let i = 0; i < input.length; i++) {
      flatten(input[i], out);
    }
  } else if (typeof input === 'object') {
    for (const key in input) {
      if (Object.prototype.hasOwnProperty.call(input, key) && input[key]) {
        out.push(key);
      }
    }
  }
}

/**
 * The palette's color roots, from tailwind.config.js.
 *
 * Conflicts are decided by matching colors explicitly rather than by treating
 * a whole prefix as exclusive. `text-` and `bg-` each cover several unrelated
 * axes (color, font size, alignment, background repeat), so a prefix rule has
 * to be patched for every non-color utility that ships; naming the palette
 * instead means an unlisted utility is left alone by construction.
 */
const COLOR_ROOTS: Record<string, true> = {
  border: true,
  input: true,
  ring: true,
  background: true,
  foreground: true,
  primary: true,
  secondary: true,
  tertiary: true,
  surface: true,
  destructive: true,
  muted: true,
  accent: true,
  popover: true,
  card: true,
  terminal: true,
  transparent: true,
  current: true,
  black: true,
  white: true,
  // Material role colors are spelled as their own tokens: text-on-surface,
  // bg-on-secondary-container, and so on.
  on: true,
  outline: true,
};

/** Prefixes that can carry a color from the palette. */
const COLOR_PREFIXES = ['text-', 'bg-'] as const;

/**
 * The conflict group a class belongs to, or null when it is unconstrained.
 *
 * Utility classes are emitted in stylesheet order, not source order, so two
 * colors on one element resolve by whichever rule the engine sorts last rather
 * than by which the caller wrote last. Naming that conflict here is what makes
 * a className passed by a caller override the one a variant already set.
 */
function conflictGroup(cls: string): string | null {
  // A variant selector scopes the class, so `active:bg-x` does not conflict
  // with a bare `bg-y`. The scope is carried into the group.
  const bare = cls.slice(cls.lastIndexOf(':') + 1);
  const scope = cls.slice(0, cls.length - bare.length);
  for (const prefix of COLOR_PREFIXES) {
    if (!bare.startsWith(prefix)) continue;
    // The root is the palette entry; a shade or variant follows it, as in
    // `bg-surface-variant` or `text-muted-foreground`. An opacity suffix
    // (`bg-black/60`) modifies the same color and is dropped before matching.
    // Anything whose root is not in the palette is some other utility sharing
    // the prefix and is left alone.
    const value = bare.slice(prefix.length).split('/')[0] ?? '';
    const root = value.split('-')[0] ?? '';
    // An arbitrary value, as in `bg-[#fff]`, is a color by construction.
    if (value.startsWith('[') || COLOR_ROOTS[root] === true) {
      return `${scope}${prefix}color`;
    }
  }
  return null;
}

/**
 * Combines class names, keeping the last of any conflicting pair.
 */
export function cn(...inputs: ClassValue[]): string {
  const list: string[] = [];
  for (let i = 0; i < inputs.length; i++) {
    flatten(inputs[i], list);
  }
  const parts: string[] = [];
  for (const cls of list) {
    for (const token of cls.split(/\s+/)) {
      if (token) parts.push(token);
    }
  }
  // Walked backwards so the last of each group wins and everything else keeps
  // its order.
  const seen = new Set<string>();
  const kept: string[] = [];
  for (let i = parts.length - 1; i >= 0; i--) {
    const group = conflictGroup(parts[i]);
    if (group !== null) {
      if (seen.has(group)) continue;
      seen.add(group);
    }
    kept.push(parts[i]);
  }
  return kept.reverse().join(' ');
}
