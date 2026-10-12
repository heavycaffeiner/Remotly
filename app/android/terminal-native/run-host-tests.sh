#!/usr/bin/env bash
# Build and run the host-side terminal-core tests. Compiles test_terminal.c
# against a host build of libghostty-vt (pinned in PIN.txt) and runs the
# fixtures alongside it.
#
# Usage: run-host-tests.sh [terminal-fixtures [cjk-fixtures]]
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

source "$HOME/.config/shell/development.sh"

GHOSTTY_DIR="${GHOSTTY_DIR:-$HOME/Toolchains/ghostty}"
HOST_OUT="$HERE/build/host"
pinned_commit="$(awk '/^commit:/ {print $2}' "$HERE/PIN.txt")"
if [ "$(git -C "$GHOSTTY_DIR" rev-parse HEAD)" != "$pinned_commit" ]; then
  echo "ERROR: Ghostty checkout does not match PIN.txt" >&2
  exit 1
fi
FIXTURES_02="${1:-$HERE/fixtures/terminal}"
FIXTURES_03="${2:-$HERE/fixtures/cjk}"

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
if [ ! -f "$HOST_OUT/lib/libghostty-vt.so" ] || [ ! -f "$HOST_OUT/commit.txt" ] || [ "$(<"$HOST_OUT/commit.txt")" != "$pinned_commit" ]; then
  echo ">> building host libghostty-vt (pinned: $(awk '/^commit:/ {print $2}' "$HERE/PIN.txt"))"
  (cd "$GHOSTTY_DIR" && zig build --prefix "$HOST_OUT" -Demit-lib-vt=true -Doptimize=ReleaseFast)
  printf '%s\n' "$pinned_commit" > "$HOST_OUT/commit.txt"
fi

gcc \
  -I"$GHOSTTY_DIR/include" \
  "$HERE/test_terminal.c" \
  -L"$HOST_OUT/lib" -lghostty-vt \
  -o "$OUT/test_terminal"

LD_LIBRARY_PATH="$HOST_OUT/lib" "$OUT/test_terminal" "$FIXTURES_02" "$FIXTURES_03"

# The bottom-anchored overlay idiom, which a docked TUI depends on. Linked
# against the static lib through zig cc: the shared build resolves libc symbols
# through zig's own runtime and does not link with the system gcc.
if [ ! -f "$HOST_OUT/lib/libghostty-vt.a" ]; then
  echo ">> building host static libghostty-vt"
  (cd "$GHOSTTY_DIR" && zig build --prefix "$HOST_OUT" -Demit-lib-vt=true -Doptimize=ReleaseFast)
fi

for t in test_frame test_theme test_overlay test_kitty test_mouse test_scrollregion test_wheel test_query test_resize_scrollback test_wheel_gate; do
  echo ">> $t"
  zig cc -std=c11 \
    -I"$GHOSTTY_DIR/include" \
    "$HERE/$t.c" \
    "$HOST_OUT/lib/libghostty-vt.a" \
    -o "$OUT/$t"
  "$OUT/$t"
done
