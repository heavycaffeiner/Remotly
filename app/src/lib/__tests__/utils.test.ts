import { cn } from '../utils';

/**
 * The rule under test: a class a caller passes must override the one a
 * component's variant already set.
 *
 * Utility classes resolve by stylesheet order, not source order, so two colors
 * on one element previously came out as whichever the engine sorted last. That
 * is what drew the toast's label in the toast's own background color and made
 * it read as an empty toast.
 */
describe('cn', () => {
  it('keeps the last of two conflicting colors', () => {
    expect(cn('text-foreground', 'text-background')).toBe('text-background');
  });

  it('keeps a size and a color together', () => {
    // Every Text variant is written this way, so dropping the size here would
    // resize the whole app.
    expect(cn('text-base text-foreground')).toBe('text-base text-foreground');
  });

  it('overrides only the color when a caller passes one', () => {
    expect(cn('text-base text-foreground', 'text-background')).toBe(
      'text-base text-background',
    );
  });

  it('leaves two sizes alone, since neither is a color', () => {
    // Only palette colors are resolved. A size pair is left as written, which
    // is how it behaved before and is not what the toast bug was about.
    expect(cn('text-sm', 'text-lg')).toBe('text-sm text-lg');
  });

  it('does not treat a scoped class as conflicting with a bare one', () => {
    // A pressed state and a resting state are different declarations.
    expect(cn('bg-secondary', 'active:bg-accent')).toBe(
      'bg-secondary active:bg-accent',
    );
  });

  it('keeps non-conflicting utilities in order', () => {
    expect(cn('px-2', 'py-3', 'rounded-md')).toBe('px-2 py-3 rounded-md');
  });

  it('keeps both axes of padding', () => {
    // The prefix table deliberately omits spacing, which is not exclusive.
    expect(cn('px-2 py-3')).toBe('px-2 py-3');
  });

  it('resolves conditional objects and arrays', () => {
    expect(cn(['text-foreground', { 'text-background': true }])).toBe(
      'text-background',
    );
  });

  it('drops falsy entries', () => {
    expect(cn('px-2', false, null, undefined, '')).toBe('px-2');
  });
  it('keeps a border side alongside a border color', () => {
    // The side selector and the color share the `border-` prefix, so treating
    // that prefix as exclusive would drop the edge and leave a borderless row.
    expect(cn('border-t border-border')).toBe('border-t border-border');
  });

  it('keeps an alignment alongside a color', () => {
    // `text-center` shares the prefix with colors but is a different axis.
    // Treating them as one group dropped the color on every centered label.
    expect(cn('text-foreground', 'text-center')).toBe(
      'text-foreground text-center',
    );
  });

  it('keeps size, color, and alignment together', () => {
    expect(cn('text-lg font-semibold text-foreground', 'text-center')).toBe(
      'text-lg font-semibold text-foreground text-center',
    );
  });

  it('leaves two alignments alone, since neither is a color', () => {
    // Alignment is not resolved here. Both survive, and the stylesheet decides
    // as it did before, which is the pre-existing behavior for every utility
    // outside the palette.
    expect(cn('text-left', 'text-center')).toBe('text-left text-center');
  });

  it('leaves a non-color utility on the same prefix alone', () => {
    // bg-cover and text-ellipsis share a prefix with colors but are unrelated
    // axes. A prefix-based rule dropped the color; matching the palette
    // explicitly leaves them untouched.
    expect(cn('bg-card', 'bg-cover')).toBe('bg-card bg-cover');
    expect(cn('text-foreground', 'text-ellipsis')).toBe(
      'text-foreground text-ellipsis',
    );
  });

  it('resolves a compound palette color as one group', () => {
    expect(cn('text-muted-foreground', 'text-primary-foreground')).toBe(
      'text-primary-foreground',
    );
  });

  it('resolves the material role colors', () => {
    // text-on-surface and friends are real palette entries, used by the
    // toolbar and the screen header. Missing them meant a caller's override
    // silently did nothing.
    expect(cn('text-foreground', 'text-on-surface')).toBe('text-on-surface');
    expect(cn('text-on-surface', 'text-destructive')).toBe('text-destructive');
    expect(cn('bg-card', 'bg-on-secondary-container')).toBe(
      'bg-on-secondary-container',
    );
  });

  it('treats an arbitrary value as a color', () => {
    expect(cn('bg-card', 'bg-[#ff0000]')).toBe('bg-[#ff0000]');
  });

  it('resolves a color carrying an opacity suffix', () => {
    // bg-black/60 and bg-outline-variant/30 both ship. Leaving the suffix on
    // made them look like different groups, so neither overrode the other.
    expect(cn('bg-card', 'bg-black/60')).toBe('bg-black/60');
    expect(cn('bg-black/60', 'bg-surface-variant/40')).toBe(
      'bg-surface-variant/40',
    );
  });
});
