# CJK test matrix

Maintained from M0 onward. One row per device, keyboard, language mode, and host
fixture. Results are filled in during physical-device runs; blank cells are
pending.

Legend for Result: `P` pass, `F` fail, `G` gap (documented limitation).

## Rendering (output)

| Device | Android | Host fixture | Mixed script | Wide glyph | Combining/NFD | Wrap/resize | Defect ref |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Pixel-class | | mixed-scripts.txt | | | | | |
| Pixel-class | | wide-glyphs.txt | | | | | |
| Pixel-class | | combining.txt | | | | | |
| Pixel-class | | nfc-nfd.txt | | | | | |
| Pixel-class | | wrapping.txt | | | | | |
| Samsung | | mixed-scripts.txt | | | | | |
| Samsung | | wide-glyphs.txt | | | | | |
| Samsung | | combining.txt | | | | | |
| Samsung | | nfc-nfd.txt | | | | | |
| Samsung | | wrapping.txt | | | | | |

## Input (IME)

| Device | Android | Keyboard | Keyboard version | Language mode | Compose+output | Commit once | Cancel | Extra keys | Defect ref |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Pixel-class | | Gboard | | Korean | | | | | |
| Pixel-class | | Samsung Keyboard | | Korean | | | | | |
| Samsung | | Gboard | | Korean | | | | | |
| Samsung | | Samsung Keyboard | | Korean | | | | | |
| Pixel-class | | (Japanese/Chinese IME) | | Japanese or Chinese | | | | | |

## Checks

- Compose+output: preedit survives async terminal output mid-composition without
  cancelling, duplicating, or reordering.
- Commit once: committed text is emitted as UTF-8 exactly once, never before
  commit.
- Cancel: cancelling a composition emits no bytes.
- Extra keys: Esc, Tab, Ctrl, arrows, slash, pipe do not commit or corrupt an
  active composition; Ctrl applies to committed state only.

## Gaps to close before M5

Record any Japanese, Chinese, emoji, or font-fallback limitations here with a
bounded production decision.
