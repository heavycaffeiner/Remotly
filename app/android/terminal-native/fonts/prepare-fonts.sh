#!/usr/bin/env bash
# Produce the bundled terminal font assets.
#
# Only JetBrains Mono is bundled. It is the primary terminal face and its fixed
# advance is what defines the cell grid, so it cannot vary by device.
#
# CJK is deliberately NOT bundled. Android ships Noto Sans CJK on every device,
# and the renderer fits each glyph into its cell box (one cell, or two for a
# wide cell), so alignment comes from the box rather than from the font's own
# metrics. Bundling a pan-CJK face would add 5-15 MB to the APK to solve a
# problem the box fitting already solves.
#
# JetBrains Mono has no Maven artifact, so the files are checked in. Upstream is
# the pinned ghostty checkout, which vendors them under an OFL license.
#
# Requires: python3 with fonttools, and GHOSTTY_DIR.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/../.." && pwd)"
FONT_RES="$ANDROID_DIR/app/src/main/res/font"
LICENSE_DIR="$ANDROID_DIR/app/src/main/assets/licenses"

GHOSTTY_DIR="${GHOSTTY_DIR:-$HOME/opt/ghostty}"
JB_SRC="$GHOSTTY_DIR/src/font/res"

if ! python3 -c "import fontTools" 2>/dev/null; then
  echo "ERROR: fonttools is required (python3 -m pip install --user fonttools brotli)" >&2
  exit 1
fi

mkdir -p "$FONT_RES" "$LICENSE_DIR"

# The NoNF (no Nerd Font) build is the plain family. Only Regular ships in that
# form upstream, so the other three come from the Nerd Font build, whose Latin
# and box-drawing metrics are identical.
copy_jb() {
  local src="$1" dest="$2"
  if [ ! -f "$JB_SRC/$src" ]; then
    echo "ERROR: missing $JB_SRC/$src (set GHOSTTY_DIR)" >&2
    exit 1
  fi
  # Drop the Nerd Font private-use glyphs the terminal never asks for. That is
  # most of the file: ~2.3 MB becomes ~196 KB.
  python3 -m fontTools.subset "$JB_SRC/$src" \
    --unicodes="U+0000-024F,U+0370-03FF,U+0400-04FF,U+2000-206F,U+2070-209F,U+20A0-20BF,U+2100-214F,U+2190-21FF,U+2200-22FF,U+2300-23FF,U+2500-257F,U+2580-259F,U+25A0-25FF,U+2600-26FF,U+FE00-FE0F" \
    --layout-features='*' \
    --name-IDs='*' \
    --output-file="$FONT_RES/$dest" 2>/dev/null
  echo ">> $dest  $(stat -c%s "$FONT_RES/$dest") bytes"
}

echo ">> JetBrains Mono"
copy_jb JetBrainsMonoNoNF-Regular.ttf jetbrains_mono_regular.ttf
copy_jb JetBrainsMonoNerdFont-Bold.ttf jetbrains_mono_bold.ttf
copy_jb JetBrainsMonoNerdFont-Italic.ttf jetbrains_mono_italic.ttf
copy_jb JetBrainsMonoNerdFont-BoldItalic.ttf jetbrains_mono_bold_italic.ttf

cp "$JB_SRC/OFL.txt" "$LICENSE_DIR/OFL-JetBrainsMono.txt"
cat > "$LICENSE_DIR/README.txt" <<'EOF'
Bundled terminal font assets.

JetBrains Mono
  SIL Open Font License 1.1, see OFL-JetBrainsMono.txt
  https://github.com/JetBrains/JetBrainsMono
  Subset to Latin, Greek, Cyrillic, punctuation, arrows, box drawing, and block
  elements. The Nerd Font private-use range is removed.

CJK glyphs come from the platform (Noto Sans CJK, present on every Android
device). The renderer fits each glyph into its terminal cell box, so column
alignment does not depend on the device font's own advance widths.

Regenerate with app/android/terminal-native/fonts/prepare-fonts.sh.
EOF

# A missing glyph here is a blank cell in a real terminal, so this fails rather
# than warns.
echo ">> coverage"
python3 - "$FONT_RES" <<'PY'
import sys
from pathlib import Path
from fontTools.ttLib import TTFont

font_dir = Path(sys.argv[1])
checks = {
    "jetbrains_mono_regular.ttf": "ABCxyz0189{}[]()<>|/\\-_=+*&^%$#@!?.,:;'\"`~─│┌┐└┘├┤┬┴┼█░▒▓←↑→↓",
    "jetbrains_mono_bold.ttf": "ABCxyz0189─│┌┐└┘",
    "jetbrains_mono_italic.ttf": "ABCxyz0189",
    "jetbrains_mono_bold_italic.ttf": "ABCxyz0189",
}

failed = False
for name, sample in checks.items():
    path = font_dir / name
    if not path.exists():
        print(f"FAIL {name}: not produced")
        failed = True
        continue
    cmap = set()
    for table in TTFont(path)["cmap"].tables:
        cmap.update(table.cmap.keys())
    missing = [c for c in sample if ord(c) not in cmap]
    if missing:
        print(f"FAIL {name}: missing {''.join(missing)}")
        failed = True
    else:
        print(f"ok   {name}  {path.stat().st_size:,} bytes")

if failed:
    sys.exit(1)
PY

total=$(du -cb "$FONT_RES"/*.ttf | tail -1 | cut -f1)
echo ">> total font payload: $total bytes"
