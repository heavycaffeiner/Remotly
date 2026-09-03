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
 * Combines class names cleanly without external dependencies.
 */
export function cn(...inputs: ClassValue[]): string {
  const list: string[] = [];
  for (let i = 0; i < inputs.length; i++) {
    flatten(inputs[i], list);
  }
  return list.join(' ');
}
