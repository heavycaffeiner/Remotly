#!/usr/bin/env bash
# Reports the toolchain versions this repository needs and fails when one is
# missing or too old.
#
# The Android build in particular fails deep inside Gradle with an unrelated
# error when an older JDK is selected, so it is checked here instead.
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

NODE_MIN_MAJOR=22
JDK_MIN_MAJOR=17
GO_MIN_MINOR=26           # go1.26
NDK_VERSION="28.2.13676358"
BUILD_TOOLS="37.0.0"

status=0
warnings=0

ok()   { printf '  ok    %-14s %s\n' "$1" "$2"; }
bad()  { printf '  FAIL  %-14s %s\n' "$1" "$2" >&2; status=1; }
warn() { printf '  warn  %-14s %s\n' "$1" "$2"; warnings=$((warnings + 1)); }

echo "toolchain"

# --- Node ------------------------------------------------------------------
if command -v node >/dev/null 2>&1; then
  node_version="$(node -v)"
  node_major="${node_version#v}"
  node_major="${node_major%%.*}"
  if [ "$node_major" -ge "$NODE_MIN_MAJOR" ]; then
    ok "node" "$node_version"
  else
    bad "node" "$node_version, need $NODE_MIN_MAJOR or later"
  fi
else
  bad "node" "not found"
fi

if command -v npm >/dev/null 2>&1; then
  ok "npm" "$(npm -v)"
else
  bad "npm" "not found"
fi

# --- JDK -------------------------------------------------------------------
java_bin="java"
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/java" ]; then
  java_bin="$JAVA_HOME/bin/java"
fi
if command -v "$java_bin" >/dev/null 2>&1 || [ -x "$java_bin" ]; then
  java_raw="$("$java_bin" -version 2>&1 | head -1)"
  java_major="$(printf '%s' "$java_raw" | awk -F'"' '{split($2, v, "."); print (v[1] == 1 ? v[2] : v[1])}')"
  if [ -n "$java_major" ] && [ "$java_major" -ge "$JDK_MIN_MAJOR" ]; then
    ok "jdk" "$java_major (${JAVA_HOME:-from PATH})"
  else
    bad "jdk" "$java_major, need $JDK_MIN_MAJOR or later. Set JAVA_HOME."
  fi
else
  bad "jdk" "not found"
fi

# --- Go --------------------------------------------------------------------
if command -v go >/dev/null 2>&1; then
  go_version="$(go version | awk '{print $3}')"
  go_minor="$(printf '%s' "$go_version" | sed 's/^go[0-9]*\.//; s/\..*//')"
  if [ -n "$go_minor" ] && [ "$go_minor" -ge "$GO_MIN_MINOR" ]; then
    ok "go" "$go_version"
  else
    bad "go" "$go_version, need go1.$GO_MIN_MINOR or later"
  fi
else
  bad "go" "not found"
fi

# --- Android SDK and NDK ---------------------------------------------------
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -n "$sdk" ] && [ -d "$sdk" ]; then
  ok "android sdk" "$sdk"
  # Gradle refuses to build when two variables name the SDK through different
  # paths, including a symlink and its target.
  if [ -n "${ANDROID_HOME:-}" ] && [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    if [ "$(readlink -f "$ANDROID_HOME")" != "$(readlink -f "$ANDROID_SDK_ROOT")" ]; then
      bad "android sdk" "ANDROID_HOME and ANDROID_SDK_ROOT name different paths"
    fi
  fi
  if [ -d "$sdk/build-tools/$BUILD_TOOLS" ]; then
    ok "build-tools" "$BUILD_TOOLS"
  else
    warn "build-tools" "$BUILD_TOOLS not found under $sdk/build-tools"
  fi
  if [ -d "$sdk/ndk/$NDK_VERSION" ]; then
    ok "ndk" "$NDK_VERSION"
  else
    warn "ndk" "$NDK_VERSION not found; needed only to rebuild native libraries"
  fi
else
  bad "android sdk" "set ANDROID_SDK_ROOT or ANDROID_HOME"
fi

# --- Optional native rebuild tools -----------------------------------------
# Only needed to regenerate checked-in artifacts, so these warn rather than
# fail.
if command -v gomobile >/dev/null 2>&1; then
  ok "gomobile" "present"
else
  warn "gomobile" "not found; needed only to rebuild sshcore.aar"
fi

if command -v zig >/dev/null 2>&1; then
  ok "zig" "$(zig version)"
else
  warn "zig" "not found; needed only to rebuild libremotly_terminal.so"
fi

ghostty="${GHOSTTY_DIR:-$HOME/opt/ghostty}"
pin_file="$ROOT/app/android/terminal-native/PIN.txt"
if [ -d "$ghostty/.git" ] && [ -f "$pin_file" ]; then
  pinned="$(awk '/^commit:/ {print $2}' "$pin_file")"
  actual="$(cd "$ghostty" && git rev-parse HEAD 2>/dev/null)"
  if [ "$pinned" = "$actual" ]; then
    ok "ghostty" "${pinned:0:12} (matches PIN.txt)"
  else
    warn "ghostty" "checkout ${actual:0:12} differs from PIN.txt ${pinned:0:12}"
  fi
else
  warn "ghostty" "no checkout at $ghostty; needed only to rebuild the terminal"
fi

echo ""
if [ "$status" -ne 0 ]; then
  echo "toolchain incomplete" >&2
  exit 1
fi
if [ "$warnings" -gt 0 ]; then
  echo "toolchain ok, $warnings optional tool(s) missing"
else
  echo "toolchain ok"
fi
