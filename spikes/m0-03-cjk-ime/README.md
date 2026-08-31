# M0-03 spike: CJK rendering and Android IME

Disposable risk spike and the start of the CJK compatibility matrix. This host
cannot run Android; the fixtures are the reproducible inputs and the matrix is
filled in on physical devices.

## Fixtures

`fixtures/*.txt` are byte fixtures. `fixtures/expected.json` records the
expected terminal cell width of each line, computed with East Asian Width rules:
CJK and emoji occupy two cells, box drawing one cell, combining marks and
combining jamo zero cells.

- `mixed-scripts.txt`: ASCII + Hangul + kana + han + box drawing + emoji in the
  same lines, the alignment case Claude Code and OpenCode break on.
- `wide-glyphs.txt`: individual wide glyphs and their expected widths.
- `combining.txt`: combining diacritics and combining jamo (NFD Hangul).
- `nfc-nfd.txt`: the same Hangul syllable in NFC and NFD forms.
- `wrapping.txt`: lines that wrap and must reflow on resize.

## IME mapping

The Android `InputConnection` maps onto the libghostty input API from ADR 0001:

- `commitText` -> `ghostty_surface_text` (committed UTF-8, exactly once).
- `setComposingText` -> `ghostty_surface_preedit` with the composition offset.
- `finishComposingText` / cancel -> `ghostty_surface_preedit` with empty text.
- Modifier and control keys -> `ghostty_surface_key` with the `composing` flag,
  never applied to preedit text.

Preedit bytes must never appear in `ghostty_surface_text`; the event trace below
proves this.

## Device checks (mandatory)

On a physical Pixel-class device and a physical Samsung device:

1. Gboard Korean: start composition, inject async terminal output, then commit
   and cancel in separate runs. Verify no cancellation, duplication, reordering,
   or leaked preedit bytes.
2. Samsung Keyboard Korean: same checks.
3. One available Japanese or Chinese IME: record whether it composes correctly;
   note gaps without fixing them here.
4. Extra keys (Esc, Tab, Ctrl, arrows, slash, pipe) during an active composition
   must not commit or corrupt it.
5. Feed every fixture, compare the rendered cursor and line breaks to
   `expected.json` before and after a resize.

## Event trace

Record an `InputConnection` event trace showing preedit bytes are not emitted
before commit. Screenshots for the box-drawing alignment cases.

## Decision

Pass / mitigate / reject, with exact defects. A failed Korean IME acceptance
returns to the M0-01 fallback rather than continuing.

## Handoff

M1-09 receives fixtures, the completed matrix, event traces, accepted terminal
changes, font and metric constraints, and unresolved defects. Every spike-only
patch is marked for rewrite before production use.
