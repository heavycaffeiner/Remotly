#!/usr/bin/env bash
# Build a Remotly release distribution:
#   - the Android app (release APK, signed with a local keystore)
#   - SHA256SUMS and a dist README
#
# Output goes to dist/ under the project root.
#
# The Android build needs JDK 17 or later; set JAVA_HOME accordingly before
# running.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DIST="$ROOT/dist"
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/opt/android-sdk}}"
BT="$SDK/build-tools/34.0.0"

echo "==> cleaning dist"
rm -rf "$DIST"
mkdir -p "$DIST"

# The single version source, the same one the Android build reads.
APP_VERSION="$(sed -n 's/^remotlyVersion=//p' "$ROOT/app/android/gradle.properties" | tr -d '[:space:]')"
APP_VERSION="${APP_VERSION:-unknown}"
echo "==> app version $APP_VERSION"

# --- Android app -----------------------------------------------------------
if [ "${SKIP_APK:-0}" = "1" ]; then
  echo "==> skipping APK (SKIP_APK=1)"
else
  # Gradle needs JDK 17 or later; an older JDK fails deep in the build with
  # an unrelated-looking error.
  JAVA_BIN="${JAVA_HOME:+$JAVA_HOME/bin/}java"
  jdk_major="$("$JAVA_BIN" -version 2>&1 | awk -F'"' '/version/ {split($2, v, "."); print (v[1] == 1 ? v[2] : v[1]); exit}')"
  if [ -z "$jdk_major" ] || [ "$jdk_major" -lt 17 ]; then
    echo "ERROR: JDK 17 or later is required, found ${jdk_major:-unknown}. Set JAVA_HOME." >&2
    exit 1
  fi

  # The terminal native libraries and the Go sshcore .aar are build inputs, not
  # products of this script. A missing one produces an APK that installs and
  # then fails at runtime, so fail here instead.
  for abi in armeabi-v7a arm64-v8a x86_64; do
    so="$ROOT/app/android/app/src/main/jniLibs/$abi/libremotly_terminal.so"
    if [ ! -f "$so" ]; then
      echo "ERROR: missing $so" >&2
      echo "       build it: (cd app/android/terminal-native && ./build-android.sh)" >&2
      exit 1
    fi
  done

  # The AAR is a build output, not a checked-in binary, so it is built rather
  # than demanded. Rebuilding costs seconds and guarantees the APK links the
  # sshcore in this working tree instead of whatever an earlier build left.
  echo "==> sshcore.aar"
  "$ROOT/scripts/build-sshcore.sh"

  echo "==> APK (release, R8-minified)"
  ( cd "$ROOT/app/android" && ./gradlew assembleRelease )
  # The release variant is signed with the debug keystore when no release
  # credentials are configured; we re-sign below with the release keystore
  # (apksigner replaces the signature).
  APK_RAW="$ROOT/app/android/app/build/outputs/apk/release/app-release.apk"
  if [ ! -f "$APK_RAW" ]; then
    APK_RAW="$ROOT/app/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  fi
  if [ ! -f "$APK_RAW" ]; then
    echo "ERROR: no release APK produced" >&2
    ls -la "$ROOT/app/android/app/build/outputs/apk/release/" >&2 || true
    exit 1
  fi

  # Signing. A distributable build requires an explicit keystore supplied by
  # the operator. The previous version generated one with a fixed password and
  # called the result a release, which produced an artifact that looked signed
  # and was trivially forgeable.
  #
  # zipalign rewrites the archive, which drops the APK Signature Scheme v2
  # block Gradle applied, so whatever comes out of it has to be signed again.
  # The development branch below did not, and shipped an APK with no signature
  # at all: it fails apksigner verify and the installer refuses it.
  ALIGNED="$DIST/remotly-app.zipaligned.apk"
  "$BT/zipalign" -f -p 4 "$APK_RAW" "$ALIGNED"

  if [ -n "${ANDROID_KEYSTORE:-}" ]; then
    : "${ANDROID_KEY_ALIAS:?set ANDROID_KEY_ALIAS with ANDROID_KEYSTORE}"
    if [ ! -f "$ANDROID_KEYSTORE" ]; then
      echo "ERROR: keystore not found: $ANDROID_KEYSTORE" >&2
      exit 1
    fi
    # Passwords come from the environment and are passed to apksigner through
    # env: references, so they never appear in the process list or in this
    # script's output.
    : "${ANDROID_KEYSTORE_PASSWORD:?set ANDROID_KEYSTORE_PASSWORD}"
    export ANDROID_KEYSTORE_PASSWORD
    key_pass_arg=()
    if [ -n "${ANDROID_KEY_PASSWORD:-}" ]; then
      export ANDROID_KEY_PASSWORD
      key_pass_arg=(--key-pass "env:ANDROID_KEY_PASSWORD")
    fi

    echo "==> signing with $ANDROID_KEYSTORE (alias $ANDROID_KEY_ALIAS)"
    "$BT/apksigner" sign \
      --ks "$ANDROID_KEYSTORE" \
      --ks-pass "env:ANDROID_KEYSTORE_PASSWORD" \
      --ks-key-alias "$ANDROID_KEY_ALIAS" \
      "${key_pass_arg[@]}" \
      --out "$DIST/remotly-android.apk" "$ALIGNED"
    RELEASE_SIGNED=1
  else
    # Development build: re-signed with the same debug key Gradle used, because
    # aligning dropped that signature. Named so nobody mistakes it for
    # something distributable.
    DEBUG_KS="$ROOT/app/android/app/debug.keystore"
    if [ ! -f "$DEBUG_KS" ]; then
      echo "ERROR: missing $DEBUG_KS" >&2
      exit 1
    fi
    echo "==> no ANDROID_KEYSTORE set: producing a development build"
    echo "    set ANDROID_KEYSTORE, ANDROID_KEY_ALIAS, and ANDROID_KEYSTORE_PASSWORD to sign a release"
    # The Android debug key's password is the platform's own fixed value, which
    # is why the result is not distributable. Passed by env reference like every
    # other password here, so it stays out of the process list.
    DEBUG_KS_PASSWORD=android
    export DEBUG_KS_PASSWORD
    "$BT/apksigner" sign \
      --ks "$DEBUG_KS" \
      --ks-pass "env:DEBUG_KS_PASSWORD" \
      --ks-key-alias androiddebugkey \
      --key-pass "env:DEBUG_KS_PASSWORD" \
      --out "$DIST/remotly-android-development.apk" "$ALIGNED"
    unset DEBUG_KS_PASSWORD
    RELEASE_SIGNED=0
  fi

  APK_OUT="$DIST/remotly-android.apk"
  [ "$RELEASE_SIGNED" -eq 1 ] || APK_OUT="$DIST/remotly-android-development.apk"

  # Records the signing identity, which is the fingerprint an upgrade install
  # has to match. No secret material.
  #
  # apksigner exits non-zero on an unsigned or broken artifact, and this script
  # runs under `set -e`, so a failure here stops the release rather than
  # leaving a dist nobody can install.
  "$BT/apksigner" verify --print-certs "$APK_OUT" > "$DIST/signing-identity.txt"
  rm -f "$ALIGNED"
  echo "    signed + verified: $APK_OUT"

  # One implementation of the APK checks, so the two cannot drift. It knows
  # that a release build obfuscates resource file names and looks fonts up in
  # the resource table rather than by path.
  echo "==> inspecting the APK"
  "$ROOT/scripts/check-apk.sh" "$APK_OUT"
fi

# --- checksums + README ----------------------------------------------------
# Every artifact in dist is listed, whichever APK was produced. Naming the
# release APK alone left a development build unlisted, so `sha256sum -c` passed
# over the one file a tester actually installs.
echo "==> SHA256SUMS"
( cd "$DIST" && find . -type f \
    ! -name SHA256SUMS ! -name README.md ! -name signing-identity.txt \
    -printf '%P\n' | sort | xargs sha256sum > SHA256SUMS )

# The README names the artifact that exists, so a reader is not told to
# install a file the build did not produce.
if [ "${SKIP_APK:-0}" = "1" ]; then
  APK_NAME="(not built: SKIP_APK=1)"
elif [ "${RELEASE_SIGNED:-0}" -eq 1 ]; then
  APK_NAME="remotly-android.apk"
else
  APK_NAME="remotly-android-development.apk"
fi

cat > "$DIST/README.md" <<EOF
# Remotly release distribution

## Android app

\`${APK_NAME}\` is an R8-minified APK (applicationId
\`com.remotly.app\`, minSdk 24, targetSdk 36, version ${APP_VERSION}).

The signing identity is recorded in \`signing-identity.txt\`. An upgrade
install only succeeds when the installed app was signed with the same key, so
compare that fingerprint before shipping an update.

Signing inputs come from the environment and are never recorded here:

\`\`\`sh
ANDROID_KEYSTORE=/secure/remotly.jks \\
ANDROID_KEY_ALIAS=remotly \\
ANDROID_KEYSTORE_PASSWORD=... \\
./scripts/release.sh
\`\`\`

Without them the script produces \`remotly-android-development.apk\`, signed
with the debug key. That artifact is for local testing and must not be
distributed.

Install: \`adb install -r ${APK_NAME}\`.

## Verify

\`sha256sum -c SHA256SUMS\`
EOF

echo "==> done"
ls -la "$DIST"
echo ""
echo "dist contents:"
find "$DIST" -type f | sort
