#!/usr/bin/env bash
# Builds the Go sshcore .aar the Android app links against.
#
# The .aar is a build output, not a source file: CI builds it before Gradle
# runs, and so does scripts/release.sh. Run this by hand only when you want a
# local Gradle build to pick up a change to mobile/sshcore.
#
# Requirements: Go, the Android SDK (ANDROID_HOME), and an NDK. The NDK is
# found under the SDK when ANDROID_NDK_HOME is not set. The gomobile version is
# pinned below; bump it only with intent, since the generated Java API is what
# the Kotlin adapters compile against.
set -euo pipefail

cd "$(dirname "$0")/.."

GOMOBILE_VERSION="v0.0.0-20260818145002-f020ddb2de58"

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"

export ANDROID_SDK_ROOT="$ANDROID_HOME"

# gomobile needs an NDK but does not find one on its own. The version is not
# pinned: any installed NDK links the same static Go runtime, and CI images
# rotate which ones they carry, so pinning would break on their schedule
# rather than ours.
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  # ANDROID_NDK_ROOT and ANDROID_NDK_LATEST_HOME are what the GitHub runner
  # images set; the SDK directory is the fallback for a local install.
  for candidate in "${ANDROID_NDK_ROOT:-}" "${ANDROID_NDK_LATEST_HOME:-}"; do
    if [ -n "$candidate" ] && [ -d "$candidate" ]; then
      ANDROID_NDK_HOME="$candidate"
      break
    fi
  done
fi
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  ANDROID_NDK_HOME="$(find "$ANDROID_HOME/ndk" -maxdepth 1 -mindepth 1 -type d 2>/dev/null | sort -V | tail -1)"
fi
if [ -z "${ANDROID_NDK_HOME:-}" ] || [ ! -d "$ANDROID_NDK_HOME" ]; then
  echo "ERROR: no NDK found; set ANDROID_NDK_HOME or install one under $ANDROID_HOME/ndk" >&2
  exit 1
fi
export ANDROID_NDK_HOME
echo "==> ndk $(basename "$ANDROID_NDK_HOME")"

# Install (or refresh) the pinned toolchain.
#
# gomobile shells out to gobind and finds it on PATH only, so gobind needs a
# real binary: the module's tool directive records the dependency for
# reproducibility but installs nothing. A developer machine tends to have one
# already, which is exactly why this gap only showed up on a clean runner.
go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
go install "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION"
# The mobile module records a tool directive so go.mod keeps the dependency.
(cd mobile && go get -tool "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION" >/dev/null)

# GOBIN is not always on PATH (CI runners in particular), and gomobile looks
# up gobind there rather than beside itself.
GOBIN_DIR="$(go env GOBIN)"
[ -n "$GOBIN_DIR" ] || GOBIN_DIR="$(go env GOPATH)/bin"
export PATH="$GOBIN_DIR:$PATH"

for tool in gomobile gobind; do
  if ! command -v "$tool" >/dev/null 2>&1; then
    echo "ERROR: $tool not found in $GOBIN_DIR after install" >&2
    exit 1
  fi
done
GOMOBILE="$(command -v gomobile)"

# Build for the three ABIs the app ships. gomobile packages all of them into a
# single .aar under jni/<abi>/libgojni.so.
#
# gomobile resolves its own dependency from the current module, so it has to run
# inside mobile/ rather than at the repository root.
OUT="$PWD/app/android/app/libs/sshcore.aar"
mkdir -p "$(dirname "$OUT")"
(
  cd mobile
  "$GOMOBILE" bind \
    -target=android/arm,android/arm64,android/amd64 \
    -androidapi 24 \
    -o "$OUT" \
    ./sshcore
)

echo "built app/android/app/libs/sshcore.aar"
