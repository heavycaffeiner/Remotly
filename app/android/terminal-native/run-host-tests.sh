#!/usr/bin/env bash
# Build and run the host-side terminal-core tests. Compiles test_terminal.c
# against a host build of libghostty-vt (pinned in PIN.txt) and runs the
# fixtures from spikes/.
#
# Usage: run-host-tests.sh [fixtures-m0-02 [fixtures-m0-03]]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$HERE/../../.." && pwd)"

if [ -f "$HOME/.bashrc.d/android-toolchain.sh" ]; then
  # shellcheck source=/dev/null
  source "$HOME/.bashrc.d/android-toolchain.sh"
fi

GHOSTTY_DIR="${GHOSTTY_DIR:-$HOME/opt/ghostty}"
FIXTURES_02="${1:-$REPO_ROOT/spikes/m0-02-embedding/fixtures}"
FIXTURES_03="${2:-$REPO_ROOT/spikes/m0-03-cjk-ime/fixtures}"

for d in "$FIXTURES_02" "$FIXTURES_03"; do
  if [ ! -d "$d" ]; then
    echo "ERROR: fixtures directory not found: $d" >&2
    exit 1
  fi
done

OUT="$(mktemp -d)"
trap 'rm -rf "$OUT"' EXIT

# ReleaseFast is required: the safety-checked default is ~300x slower on large
# output, which makes the 1 MiB burst test appear to hang.
if [ ! -f "$GHOSTTY_DIR/zig-out/lib/libghostty-vt.so" ]; then
  echo ">> building host libghostty-vt (pinned: $(awk '/^commit:/ {print $2}' "$HERE/PIN.txt"))"
  (cd "$GHOSTTY_DIR" && zig build -Demit-lib-vt=true -Doptimize=ReleaseFast)
fi

gcc \
  -I"$GHOSTTY_DIR/include" \
  "$HERE/test_terminal.c" \
  -L"$GHOSTTY_DIR/zig-out/lib" -lghostty-vt \
  -o "$OUT/test_terminal"

LD_LIBRARY_PATH="$GHOSTTY_DIR/zig-out/lib" "$OUT/test_terminal" "$FIXTURES_02" "$FIXTURES_03"

# The bottom-anchored overlay idiom, which a docked TUI depends on. Linked
# against the static lib through zig cc: the shared build resolves libc symbols
# through zig's own runtime and does not link with the system gcc.
if [ ! -f "$GHOSTTY_DIR/zig-out/lib/libghostty-vt.a" ]; then
  echo ">> building host static libghostty-vt"
  (cd "$GHOSTTY_DIR" && zig build -Demit-lib-vt=true -Doptimize=ReleaseFast)
fi

for t in test_overlay test_kitty test_mouse test_scrollregion test_wheel test_query; do
  echo ">> $t"
  zig cc -std=c11 \
    -I"$GHOSTTY_DIR/zig-out/include" \
    "$HERE/$t.c" \
    "$GHOSTTY_DIR/zig-out/lib/libghostty-vt.a" \
    -o "$OUT/$t"
  "$OUT/$t"
done
