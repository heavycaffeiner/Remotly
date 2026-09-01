#!/usr/bin/env bash
# Produce the bundled terminal font assets.
#
# Three groups are bundled:
#
#   1. JetBrains Mono Nerd Font, four styles. The primary text face. Its fixed
#      advance defines the cell grid, so the grid cannot shift between devices.
#
#   2. The Nerd Font symbol ranges, once, as a shared face. The icon outlines
#      are byte-identical across all four styles, so bundling them per style
#      would pay for the same 2 MB four times. The renderer draws symbols from
#      this one face regardless of bold or italic.
#
#      The non-Mono ("NerdFont") build is used on purpose. The "NerdFontMono"
#      build pre-compresses every icon so its ink fits a single 600-unit cell,
#      which is the squeezing this bundle exists to avoid. The non-Mono build
#      keeps the natural ink (832 to 1229 units, up to two cells wide), and the
#      renderer gives a symbol two columns when the next cell is free.
#
#   3. Noto Sans Mono CJK, Regular, for KR, JP, and SC. The four regional
#      builds share one cmap but draw Han differently, so the region is a real
#      choice and cannot be derived from the codepoint. Bold is synthesized by
#      the renderer rather than bundled, which halves the CJK payload.
#
# Requires: python3 with fonttools, curl, and unzip.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/../.." && pwd)"
FONT_RES="$ANDROID_DIR/app/src/main/res/font"
LICENSE_DIR="$ANDROID_DIR/app/src/main/assets/licenses"

NERD_VERSION="3.5.1"
NERD_URL="https://github.com/ryanoasis/nerd-fonts/releases/download/v${NERD_VERSION}/JetBrainsMono.zip"
NERD_SHA256="fab782a66f7d3019da64f6572db9fc5d3a4bcb19f9fa13e2d8a62e3693d6396e"

NOTO_TAG="Sans2.004"
NOTO_BASE="https://github.com/notofonts/noto-cjk/releases/download/${NOTO_TAG}"

# region:archive:sha256
NOTO_ARCHIVES=(
  "kr:12_NotoSansMonoCJKkr.zip:8c1368d3faac3c43991a91392fb73d985409ffe078cb731c7e303e226e4fd619"
  "jp:11_NotoSansMonoCJKjp.zip:6c8faf475ce78fa37486dd5d8920e4bb4450b1b0f3c497edf3ba2d25cf52ab78"
  "sc:13_NotoSansMonoCJKsc.zip:e252c39994f8a278676507600a955663c23c24a7827dc63a4300b2f7b427cd5d"
)

CACHE="${REMOTLY_FONT_CACHE:-${XDG_CACHE_HOME:-$HOME/.cache}/remotly-fonts}"

PYTHON="${PYTHON:-python3}"
if ! "$PYTHON" -c "import fontTools" 2>/dev/null; then
  echo "ERROR: fonttools is required ($PYTHON -m pip install --user fonttools)" >&2
  exit 1
fi

mkdir -p "$FONT_RES" "$LICENSE_DIR" "$CACHE"

# Text coverage for the four styled faces: Latin, Greek, Cyrillic, punctuation,
# arrows, math, box drawing, and block elements. No CJK, which comes from the
# Noto faces, and no symbols, which come from the shared symbol face.
TEXT_UNICODES="U+0000-024F,U+0370-03FF,U+0400-04FF,U+2000-206F,U+2070-209F,\
U+20A0-20BF,U+2100-214F,U+2190-21FF,U+2200-22FF,U+2300-23FF,U+2500-257F,\
U+2580-259F,U+25A0-25FF,U+2600-26FF,U+FE00-FE0F"

# The Nerd Font symbol ranges: the BMP private use area, which holds Powerline,
# Seti, Devicons, Font Awesome, Weather, Octicons and Codicons, plus the
# supplementary plane block that holds Material Design icons.
SYMBOL_UNICODES="U+E000-F8FF,U+F0000-F1AF0"

# CJK coverage: jamo, kana, bopomofo, the CJK symbol and punctuation blocks,
# Han (URO, Extension A, and the compatibility ideographs), and the fullwidth
# forms. Extension B and beyond are deliberately absent; they are rare enough
# that the platform font is the right place for them.
CJK_UNICODES="U+1100-11FF,U+2E80-2EFF,U+3000-303F,U+3040-309F,U+30A0-30FF,\
U+3100-312F,U+3130-318F,U+31F0-31FF,U+3200-32FF,U+3300-33FF,U+3400-4DBF,\
U+4E00-9FFF,U+A960-A97F,U+AC00-D7A3,U+D7B0-D7FF,U+F900-FAFF,U+FE30-FE4F,\
U+FF00-FFEF"

fetch() {
  local url="$1" dest="$2" want="$3"
  if [ -f "$dest" ]; then
    local have
    have="$(sha256sum "$dest" | cut -d' ' -f1)"
    if [ "$have" = "$want" ]; then
      return 0
    fi
    echo ">> cached $(basename "$dest") has the wrong digest; refetching" >&2
    rm -f "$dest"
  fi
  echo ">> fetching $(basename "$dest")"
  # Downloaded to a temporary name and moved into place only once the digest
  # matches, so an interrupted fetch cannot leave a truncated file in the cache
  # for the next run to trust.
  local tmp="$dest.part"
  curl -fsSL -o "$tmp" "$url"
  local have
  have="$(sha256sum "$tmp" | cut -d' ' -f1)"
  if [ "$have" != "$want" ]; then
    rm -f "$tmp"
    echo "ERROR: $(basename "$dest") digest mismatch" >&2
    echo "  expected $want" >&2
    echo "  actual   $have" >&2
    exit 1
  fi
  mv "$tmp" "$dest"
}

subset() {
  local src="$1" dest="$2" unicodes="$3"
  shift 3
  "$PYTHON" -m fontTools.subset "$src" \
    --unicodes="$unicodes" \
    --output-file="$dest" \
    "$@"
  echo ">> $(basename "$dest")  $(stat -c%s "$dest") bytes"
}

# --- JetBrains Mono Nerd Font ----------------------------------------------

NERD_ZIP="$CACHE/JetBrainsMono-${NERD_VERSION}.zip"
fetch "$NERD_URL" "$NERD_ZIP" "$NERD_SHA256"

NERD_WORK="$CACHE/nerd-${NERD_VERSION}"
rm -rf "$NERD_WORK"
mkdir -p "$NERD_WORK"
unzip -o -q "$NERD_ZIP" -d "$NERD_WORK" \
  'JetBrainsMonoNerdFont-Regular.ttf' \
  'JetBrainsMonoNerdFont-Bold.ttf' \
  'JetBrainsMonoNerdFont-Italic.ttf' \
  'JetBrainsMonoNerdFont-BoldItalic.ttf' \
  'OFL.txt'

echo ">> JetBrains Mono Nerd Font ${NERD_VERSION}"
copy_style() {
  local style="$1" dest="$2"
  subset "$NERD_WORK/JetBrainsMonoNerdFont-${style}.ttf" \
    "$FONT_RES/$dest" "$TEXT_UNICODES" \
    --layout-features='*' --name-IDs='*'
}
copy_style Regular    jetbrains_mono_regular.ttf
copy_style Bold       jetbrains_mono_bold.ttf
copy_style Italic     jetbrains_mono_italic.ttf
copy_style BoldItalic jetbrains_mono_bold_italic.ttf

# The symbol face, drawn for every style. Regular is the source because the
# icon outlines do not vary by style; only the text glyphs do, and those are
# dropped here.
echo ">> Nerd Font symbols (shared across styles)"
subset "$NERD_WORK/JetBrainsMonoNerdFont-Regular.ttf" \
  "$FONT_RES/nerd_symbols.ttf" "$SYMBOL_UNICODES" \
  --layout-features='*' --name-IDs='*'

cp "$NERD_WORK/OFL.txt" "$LICENSE_DIR/OFL-JetBrainsMono.txt"

# --- Noto Sans Mono CJK -----------------------------------------------------

echo ">> Noto Sans Mono CJK ${NOTO_TAG}"
for entry in "${NOTO_ARCHIVES[@]}"; do
  region="${entry%%:*}"
  rest="${entry#*:}"
  archive="${rest%%:*}"
  digest="${rest#*:}"

  zip_path="$CACHE/$archive"
  fetch "$NOTO_BASE/$archive" "$zip_path" "$digest"

  work="$CACHE/noto-$region"
  rm -rf "$work"
  mkdir -p "$work"
  unzip -o -q "$zip_path" -d "$work" \
    "NotoSansMonoCJK${region}-Regular.otf" 'LICENSE'

  # Hinting is dropped: it costs ~3 MB per face and Android renders these at
  # sizes where the hinted and unhinted results are indistinguishable. Only the
  # shaping features a terminal can reach are kept.
  subset "$work/NotoSansMonoCJK${region}-Regular.otf" \
    "$FONT_RES/noto_sans_mono_cjk_${region}.otf" "$CJK_UNICODES" \
    --layout-features=ccmp,locl --no-hinting

  if [ ! -f "$LICENSE_DIR/OFL-NotoSansCJK.txt" ]; then
    cp "$work/LICENSE" "$LICENSE_DIR/OFL-NotoSansCJK.txt"
  fi
done

cat > "$LICENSE_DIR/README.txt" <<EOF
Bundled terminal font assets.

JetBrains Mono Nerd Font ${NERD_VERSION}
  SIL Open Font License 1.1, see OFL-JetBrainsMono.txt
  https://github.com/ryanoasis/nerd-fonts
  Upstream JetBrains Mono: https://github.com/JetBrains/JetBrainsMono
  The four styled faces are subset to Latin, Greek, Cyrillic, punctuation,
  arrows, math, box drawing, and block elements.
  nerd_symbols.ttf carries the Nerd Font symbol ranges once, shared by every
  style, because those outlines do not differ between styles. It comes from
  the non-Mono build, whose icons keep their natural width; the renderer gives
  a wide symbol two columns rather than compressing it into one.

Noto Sans Mono CJK ${NOTO_TAG}
  SIL Open Font License 1.1, see OFL-NotoSansCJK.txt
  https://github.com/notofonts/noto-cjk
  Korean, Japanese, and Simplified Chinese, Regular only, subset to jamo,
  kana, bopomofo, Han (URO, Extension A, compatibility ideographs), and
  fullwidth forms, with hinting removed. The three share a cmap but draw Han
  differently, so the active face follows the user's language rather than the
  codepoint. Bold is synthesized by the renderer.
  Han beyond Extension A falls back to the platform font.

Regenerate with app/android/terminal-native/fonts/prepare-fonts.sh.
EOF

# A missing glyph here is a blank cell in a real terminal, so this fails rather
# than warns.
echo ">> coverage"
"$PYTHON" - "$FONT_RES" <<'PY'
import sys
from pathlib import Path
from fontTools.ttLib import TTFont

font_dir = Path(sys.argv[1])
checks = {
    "jetbrains_mono_regular.ttf": "ABCxyz0189{}[]()<>|/\\-_=+*&^%$#@!?.,:;'\"`~─│┌┐└┘├┤┬┴┼█░▒▓←↑→↓",
    "jetbrains_mono_bold.ttf": "ABCxyz0189─│┌┐└┘",
    "jetbrains_mono_italic.ttf": "ABCxyz0189",
    "jetbrains_mono_bold_italic.ttf": "ABCxyz0189",
    # Powerline separators, a Seti icon, a Devicon, a Codicon, an Octicon, and
    # a Material icon: one from each range the terminal actually draws.
    "nerd_symbols.ttf": "\ue0b0\ue0b2\ue5fa\ue702\uea60\uf400\U000f0001",
    "noto_sans_mono_cjk_kr.otf": "가힣한글ㄱㅎ漢字。、０９Ａ",
    "noto_sans_mono_cjk_jp.otf": "あんアンー日本語漢字。、",
    "noto_sans_mono_cjk_sc.otf": "中文简体汉字。、０９",
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

# The text faces must not carry symbols and the symbol face must not carry
# text: an overlap means a cell could be drawn from either, and which one wins
# would depend on load order.
text = TTFont(font_dir / "jetbrains_mono_regular.ttf")
symbols = TTFont(font_dir / "nerd_symbols.ttf")


def codepoints(font):
    out = set()
    for table in font["cmap"].tables:
        out.update(table.cmap.keys())
    return out


overlap = codepoints(text) & codepoints(symbols)
if overlap:
    sample = ", ".join(f"U+{c:04X}" for c in sorted(overlap)[:8])
    print(f"FAIL text and symbol faces overlap on {len(overlap)} codepoints: {sample}")
    failed = True
else:
    print("ok   text and symbol faces are disjoint")

if failed:
    sys.exit(1)
PY

total=$(du -cb "$FONT_RES"/*.ttf "$FONT_RES"/*.otf | tail -1 | cut -f1)
echo ">> total font payload: $total bytes"
