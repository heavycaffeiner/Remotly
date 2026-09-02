#!/usr/bin/env bash
# Runs every host-runnable check in the repository.
#
# This is the command to run before pushing. It does not need a device, a
# Windows host, or a camera; those are the manual matrices recorded in the
# working notes under docs/internal/.
#
# Usage:
#   scripts/check.sh              typecheck, lint, format, JS tests, Kotlin tests, Go tests
#   scripts/check.sh --fast       skips the Android build (no Gradle)
#   scripts/check.sh --release    adds the release APK build and its inspection
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

FAST=0
RELEASE=0
for arg in "$@"; do
  case "$arg" in
    --fast) FAST=1 ;;
    --release) RELEASE=1 ;;
    *)
      echo "unknown option: $arg" >&2
      echo "usage: scripts/check.sh [--fast] [--release]" >&2
      exit 2
      ;;
  esac
done

failures=()

section() { printf '\n==> %s\n' "$1"; }

run() {
  local name="$1"
  shift
  if "$@"; then
    printf '    ok: %s\n' "$name"
  else
    printf '    FAIL: %s\n' "$name" >&2
    failures+=("$name")
  fi
}

section "toolchain"
if ! ./scripts/check-toolchain.sh; then
  echo "fix the toolchain before running the rest" >&2
  exit 1
fi

section "repository hygiene"
run "no lynx references" ./scripts/check-no-lynx.sh
run "no generated artifacts" ./scripts/check-artifacts.sh
run "no secrets in logs" ./scripts/check-secrets.sh

section "app"
# pnpm install --frozen-lockfile when the lock file is authoritative and
# stale; otherwise the existing install is used, because ci is slow.
if [ ! -d app/node_modules ]; then
  run "pnpm install" bash -c "cd app && pnpm install --frozen-lockfile"
fi
run "pnpm check" bash -c "cd app && pnpm check"

section "android"
if [ "$FAST" -eq 1 ]; then
  echo "    skipped (--fast)"
else
  # Offline by default: every dependency is already resolved, and a check
  # command should not depend on the network being up. Pass GRADLE_ONLINE=1
  # after changing a dependency.
  #
  # `[ ... ] && assignment` as the last command of a branch returns the test's
  # status, which under `set -e` aborts the branch. Written as if/else instead.
  if [ "${GRADLE_ONLINE:-0}" = "1" ]; then
    gradle_flags=""
  else
    gradle_flags="--offline"
  fi
  run "gradlew testDebugUnitTest" \
    bash -c "cd app/android && ./gradlew $gradle_flags testDebugUnitTest"
  run "gradlew assembleDebug" \
    bash -c "cd app/android && ./gradlew $gradle_flags assembleDebug"
fi

section "go modules"
run "mobile" bash -c "cd mobile && go test ./..."
run "daemon" bash -c "cd daemon && go test ./..."
run "relay" bash -c "cd relay && go test ./..."

# The daemon ships for darwin and windows too, and a platform-specific import
# (SIGWINCH, say) breaks only that target. Nothing else here compiles for it,
# so a broken Windows build would surface at release time instead of now.
section "cross compile"
for target in darwin/amd64 darwin/arm64 windows/amd64 windows/arm64; do
  os="${target%/*}"
  arch="${target#*/}"
  run "daemon $target" bash -c \
    "cd daemon && CGO_ENABLED=0 GOOS=$os GOARCH=$arch go build ./..."
done
for target in darwin/amd64 darwin/arm64 windows/amd64; do
  os="${target%/*}"
  arch="${target#*/}"
  run "relay $target" bash -c \
    "cd relay && CGO_ENABLED=0 GOOS=$os GOARCH=$arch go build ./..."
done

if [ "$RELEASE" -eq 1 ]; then
  section "release"
  if [ "${GRADLE_ONLINE:-0}" = "1" ]; then
    gradle_flags=""
  else
    gradle_flags="--offline"
  fi
  run "gradlew assembleRelease" \
    bash -c "cd app/android && ./gradlew $gradle_flags assembleRelease"
  run "apk inspection" ./scripts/check-apk.sh
fi

printf '\n'
if [ "${#failures[@]}" -eq 0 ]; then
  echo "all checks passed"
  exit 0
fi
echo "failed: ${failures[*]}" >&2
exit 1
