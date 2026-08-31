#!/usr/bin/env bash
# Build libremotly_terminal.so for each Android ABI.
#
# For each ABI:
#   1. If the staged static libghostty-vt.a is missing, build it from the
#      pinned ghostty checkout (ReleaseFast) and stage it.
#   2. Compile the JNI bridge as C and link against the static lib into
#      jniLibs/<abi>/libremotly_terminal.so. The archive's C++ objects are
#      self-contained, so no -lc++ is needed and the .so has no
#      libc++_shared.so dependency.
#
# ABIs are built one at a time because the zig build shares zig-out.
# Usage: build-android.sh [abi ...]   (default: all shipped ABIs)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ANDROID_DIR="$(cd "$HERE/.." && pwd)"

if [ -f "$HOME/.bashrc.d/android-toolchain.sh" ]; then
  # shellcheck source=/dev/null
  source "$HOME/.bashrc.d/android-toolchain.sh"
fi

GHOSTTY_DIR="${GHOSTTY_DIR:-$HOME/opt/ghostty}"
NDK="${NDK:-${ANDROID_NDK_HOME:-}}"
if [ -z "$NDK" ]; then
  echo "ERROR: set NDK or ANDROID_NDK_HOME to the Android NDK root" >&2
  exit 1
fi
PREBUILT="$NDK/toolchains/llvm/prebuilt/linux-x86_64"
CLANG_C="$PREBUILT/bin/clang"
SYSROOT="$PREBUILT/sysroot"

JNI_SRC="$HERE/remotly_terminal.c"
JNI_LIBS="$ANDROID_DIR/app/src/main/jniLibs"
STAGE="${TERMINAL_STAGE_DIR:-$HERE/build/stage}"

# The checked-out ghostty must match PIN.txt. The C API is untagged and
# changes shape between commits, so a drifted checkout silently miscompiles.
pinned_commit="$(awk '/^commit:/ {print $2}' "$HERE/PIN.txt")"
if [ -z "$pinned_commit" ]; then
  echo "ERROR: no commit found in $HERE/PIN.txt" >&2
  exit 1
fi
if [ ! -d "$GHOSTTY_DIR/.git" ]; then
  echo "ERROR: GHOSTTY_DIR=$GHOSTTY_DIR is not a git checkout" >&2
  exit 1
fi
actual_commit="$(cd "$GHOSTTY_DIR" && git rev-parse HEAD)"
if [ "$actual_commit" != "$pinned_commit" ]; then
  echo "ERROR: ghostty checkout is $actual_commit, PIN.txt requires $pinned_commit" >&2
  echo "       run: (cd $GHOSTTY_DIR && git checkout $pinned_commit)" >&2
  exit 1
fi

# zig target -> "ndk-triple jniLibs-dir"
declare -A TRIPLE=(
  [aarch64-linux-android.24]="aarch64-linux-android24 arm64-v8a"
  [arm-linux-androideabi.24]="armv7a-linux-androideabi24 armeabi-v7a"
  [x86_64-linux-android.24]="x86_64-linux-android24 x86_64"
)

abis=("$@")
if [ ${#abis[@]} -eq 0 ]; then
  abis=(aarch64-linux-android.24 arm-linux-androideabi.24 x86_64-linux-android.24)
fi

mkdir -p "$STAGE"

for zig_target in "${abis[@]}"; do
  if [ -z "${TRIPLE[$zig_target]+x}" ]; then
    echo "ERROR: unknown ABI target '$zig_target'" >&2
    exit 1
  fi
  read -r ndk_triple dir <<<"${TRIPLE[$zig_target]}"
  a_path="$STAGE/$dir/libghostty-vt.a"
  if [ ! -f "$a_path" ]; then
    echo ">> [$dir] building static libghostty-vt ($zig_target)"
    (cd "$GHOSTTY_DIR" && zig build -Demit-lib-vt=true -Doptimize=ReleaseFast -Dtarget="$zig_target")
    mkdir -p "$STAGE/$dir"
    cp "$GHOSTTY_DIR/zig-out/lib/libghostty-vt.a" "$a_path"
  else
    echo ">> [$dir] using staged $a_path"
  fi

  echo ">> [$dir] linking libremotly_terminal.so"
  mkdir -p "$JNI_LIBS/$dir"
  "$CLANG_C" \
    --target="$ndk_triple" \
    --sysroot="$SYSROOT" \
    -shared -fPIC -O2 -std=c11 -Wno-c23-extensions \
    -I"$GHOSTTY_DIR/include" \
    "$JNI_SRC" \
    "$a_path" \
    -o "$JNI_LIBS/$dir/libremotly_terminal.so" \
    -lm -ldl -lz -llog
  "$PREBUILT/bin/llvm-readelf" -d "$JNI_LIBS/$dir/libremotly_terminal.so" | grep -E "SONAME|NEEDED" || true
done

echo ">> done"
ls -la "$JNI_LIBS"/*/
