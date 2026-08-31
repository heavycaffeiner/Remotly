#!/usr/bin/env bash
# Fail when the retired Lynx runtime reappears in active code.
#
# Historical mentions are allowed in the untracked working notes under
# docs/internal/; this checks active dependencies, imports, build commands, and
# runtime branches only.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

status=0

pattern='(@lynx-js|lynx\.bundle|globalThis\.lynx|sparkling-method|sparkling-navigation|SparklingApplication)'
targets=(app/src app/android/app/src app/package.json scripts)

# This script names the patterns it looks for, so exclude it from its own scan.
self="scripts/$(basename "${BASH_SOURCE[0]}")"
if command -v rg >/dev/null 2>&1; then
  hits="$(rg -n -i --glob '!node_modules' "$pattern" "${targets[@]}" || true)"
else
  hits="$(grep -rnEi --exclude-dir=node_modules "$pattern" "${targets[@]}" || true)"
fi
hits="$(printf '%s' "$hits" | grep -v "^$self:" || true)"

if [ -n "$hits" ]; then
  echo "FAIL: active Lynx or Sparkling runtime references found:" >&2
  echo "$hits" >&2
  status=1
fi

bundles="$(find . -type f -name '*.lynx.bundle' -not -path '*/node_modules/*' -print -quit)"
if [ -n "$bundles" ]; then
  echo "FAIL: Lynx bundle asset found: $bundles" >&2
  status=1
fi

if [ "$status" -eq 0 ]; then
  echo "OK: no active Lynx references"
fi
exit "$status"
