import { type ClassValue, clsx } from 'clsx';
import { twMerge } from 'tailwind-merge';

/**
 * Merges class names, with later Tailwind classes winning over earlier ones.
 *
 * Plain concatenation leaves both `p-2` and `p-4` in the string and lets
 * whichever the compiler emitted last apply, so a caller could not reliably
 * override a component's default.
 */
export function cn(...inputs: ClassValue[]): string {
  return twMerge(clsx(inputs));
}
