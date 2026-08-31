#!/usr/bin/env bash
# Builds the Go sshcore .aar for the RN app (RN-05). The .aar is checked into
# the build flow, not hand-run: app/android/app/build.gradle adds
# app/libs as an AAR dependency.
#
# Requirements: Go, the Android SDK (ANDROID_HOME), and the NDK
# (ANDROID_NDK_HOME). The gomobile version is pinned below; bump it only with
# intent, since the generated Java API is what the Kotlin adapters compile
# against.
set -euo pipefail

cd "$(dirname "$0")/.."

GOMOBILE_VERSION="v0.0.0-20260818145002-f020ddb2de58"

: "${ANDROID_HOME:?set ANDROID_HOME to the Android SDK}"
: "${ANDROID_NDK_HOME:?set ANDROID_NDK_HOME to the NDK}"

export ANDROID_SDK_ROOT="$ANDROID_HOME"

# Install (or refresh) the pinned gomobile + gobind toolchain.
go install "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION"
# The mobile module records a tool directive so go.mod keeps the dependency.
(cd mobile && go get -tool "golang.org/x/mobile/cmd/gobind@$GOMOBILE_VERSION" >/dev/null)

# Build for the three ABIs the app ships. gomobile packages all of them into a
# single .aar under jni/<abi>/libgojni.so.
#
# gomobile resolves its own dependency from the current module, so it has to run
# inside mobile/ rather than at the repository root.
OUT="$PWD/app/android/app/libs/sshcore.aar"
(
  cd mobile
  gomobile bind \
    -target=android/arm,android/arm64,android/amd64 \
    -androidapi 24 \
    -o "$OUT" \
    ./sshcore
)

echo "built app/android/app/libs/sshcore.aar"
