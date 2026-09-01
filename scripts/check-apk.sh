#!/usr/bin/env bash
# Inspects a built APK for what must and must not be in it.
#
# Usage: check-apk.sh [path-to-apk]
# Defaults to the release APK, falling back to the debug one.
# No pipefail here. `grep -q` exits as soon as it matches, which hands the
# upstream `printf` a SIGPIPE, and pipefail would then report the successful
# match as a pipeline failure.
set -eu

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$ROOT/app/android/app/build/outputs/apk"

APK="${1:-}"
if [ -z "$APK" ]; then
  for candidate in \
    "$OUT/release/app-release.apk" \
    "$OUT/release/app-release-unsigned.apk" \
    "$OUT/debug/app-debug.apk"
  do
    if [ -f "$candidate" ]; then
      APK="$candidate"
      break
    fi
  done
fi

if [ -z "$APK" ] || [ ! -f "$APK" ]; then
  echo "ERROR: no APK found. Build one first." >&2
  exit 1
fi

echo "inspecting $(basename "$APK")"
listing="$(unzip -l "$APK")"
status=0

sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/opt/android-sdk}}"
aapt2="$(find "$sdk/build-tools" -name aapt2 2>/dev/null | sort | tail -1)"

# A release build obfuscates resource file names (res/qR.ttf), so a resource
# has to be looked up in the resource table rather than by path.
resources=""
if [ -n "$aapt2" ] && [ -x "$aapt2" ]; then
  resources="$("$aapt2" dump resources "$APK" 2>/dev/null || true)"
fi

require() {
  if grep -q -- "$1" <<<"$listing"; then
    echo "  ok    present: $2"
  else
    echo "  FAIL  missing: $2" >&2
    status=1
  fi
}

# Checks a resource by name, which survives release obfuscation.
require_resource() {
  if [ -z "$resources" ]; then
    echo "  skip  cannot check $2 without aapt2"
    return
  fi
  if grep -q -- "$1" <<<"$resources"; then
    echo "  ok    present: $2"
  else
    echo "  FAIL  missing: $2" >&2
    status=1
  fi
}

forbid() {
  if grep -Eiq -- "$1" <<<"$listing"; then
    echo "  FAIL  present but forbidden: $2" >&2
    grep -Ei -- "$1" <<<"$listing" >&2 || true
    status=1
  else
    echo "  ok    absent: $2"
  fi
}

# The retired client must leave no trace in a shipped artifact.
forbid 'lynx|sparkling' "Lynx and Sparkling assets"

# Terminal fonts define the cell grid, so a build without them renders on a
# platform fallback and the grid geometry changes.
require_resource 'font/jetbrains_mono_regular' "JetBrains Mono regular"
require_resource 'font/jetbrains_mono_bold' "JetBrains Mono bold"
require_resource 'font/jetbrains_mono_italic' "JetBrains Mono italic"
require_resource 'font/jetbrains_mono_bold_italic' "JetBrains Mono bold italic"
# The Nerd Font symbols and the CJK faces. Without the symbol face a prompt
# draws its icons from the platform font, which has none of them; without a
# CJK face the terminal falls back to whatever the device ships.
require_resource 'font/nerd_symbols' "Nerd Font symbols"
require_resource 'font/noto_sans_mono_cjk_kr' "Noto Sans Mono CJK KR"
require_resource 'font/noto_sans_mono_cjk_jp' "Noto Sans Mono CJK JP"
require_resource 'font/noto_sans_mono_cjk_sc' "Noto Sans Mono CJK SC"
# Assets keep their names in a release build.
require 'assets/licenses/OFL-JetBrainsMono.txt' "JetBrains Mono license notice"
require 'assets/licenses/OFL-NotoSansCJK.txt' "Noto Sans CJK license notice"

# The native libraries, for every shipped ABI.
for abi in armeabi-v7a arm64-v8a x86_64; do
  require "lib/$abi/libremotly_terminal.so" "terminal library ($abi)"
  require "lib/$abi/libgojni.so" "sshcore library ($abi)"
done

# A keystore or a private key inside the APK would ship a signing identity or a
# credential to every user.
forbid '\.keystore|\.jks|BEGIN [A-Z ]*PRIVATE KEY' "keystore or private key"

# Version metadata, when the tooling is available.
if [ -n "$aapt2" ] && [ -x "$aapt2" ]; then
  badging="$("$aapt2" dump badging "$APK" 2>/dev/null | head -1)"
  echo "  info  $badging"
  pkg_version="$(node -p "require('$ROOT/app/package.json').version" 2>/dev/null || true)"
  if [ -n "$pkg_version" ]; then
    if grep -q -- "versionName='$pkg_version'" <<<"$badging"; then
      echo "  ok    version matches package.json ($pkg_version)"
    else
      echo "  FAIL  version does not match package.json ($pkg_version)" >&2
      status=1
    fi
  fi
fi

echo ""
if [ "$status" -eq 0 ]; then
  echo "APK inspection passed"
else
  echo "APK inspection failed" >&2
fi
exit "$status"
