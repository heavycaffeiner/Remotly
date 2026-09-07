# Remotly app

The React Native client: a standalone SSH terminal and SFTP client. Several
shells per host, SFTP browsing and transfer, and a terminal rendered by
libghostty-vt with inline images, desktop notifications, and bracketed paste.

Android is the shipped platform. iOS builds from the same source but is not
feature-complete and is not released.

## Requirements

| Tool | Version |
| --- | --- |
| Node | 22.11.0 or later |
| JDK | 17 or later (Gradle refuses older) |
| Android SDK | compileSdk 37, build-tools 37.0.0 |
| Android NDK | 28.2.13676358 |
| minSdk | 24 |
| targetSdk | 36 |
| Go | 1.26 or later (sshcore) |
| gomobile | pinned in `scripts/build-sshcore.sh` |
| Zig | required only to rebuild the terminal native library |

Shipped ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`.

Set `ANDROID_HOME` and `ANDROID_NDK_HOME` before any native build.

## Install and run

```sh
pnpm install
pnpm android            # debug build onto a connected device
```

Metro starts automatically with `run-android`. Start it separately with
`pnpm exec react-native start` when attaching to an already-installed build.

## Checks

```sh
pnpm check               # typecheck, lint, format check, jest
pnpm typecheck
pnpm lint
pnpm test
```

Android unit tests, from a JDK 17 or later shell:

```sh
cd android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

## Codegen

The native module and view specs live in `src/specs`. Codegen runs as part of
the Gradle build (`codegenConfig` in `package.json`, java package
`com.remotly.app.specs`). After changing a spec, rebuild the Android app so the
generated interfaces and delegates are regenerated:

```sh
cd android && ./gradlew generateCodegenArtifactsFromSchema
```

A spec change is not complete until the spec, the Kotlin implementation, the JS
wrapper, and the tests are updated together.

## Terminal native library

The terminal is libghostty-vt behind a JNI bridge. Source, build scripts, host
tests, the upstream pin, and the upstream license live in
`android/terminal-native/`.

```sh
cd android/terminal-native
GHOSTTY_DIR=~/opt/ghostty ./build-android.sh          # all shipped ABIs
GHOSTTY_DIR=~/opt/ghostty ./build-android.sh aarch64-linux-android.24
./run-host-tests.sh                                    # host-side terminal core tests
```

`build-android.sh` verifies the ghostty checkout matches `PIN.txt` and refuses
to build otherwise. The C API is untagged, so a drifted checkout miscompiles
silently. Output goes to `android/app/src/main/jniLibs/<abi>/`.

## SSH core

SSH and SFTP run on a Go core bound through gomobile.

```sh
cd ..                    # repository root
./scripts/build-sshcore.sh
cd mobile && go test ./...
```

The result is `android/app/libs/sshcore.aar`, consumed as an AAR dependency.

## Release

```sh
cd ..                    # repository root
./scripts/release.sh
```

Signing keys are not in source control. The development keystore is not
production guidance; supply your own for distribution.

## Source tree

```text
src/
  components/    shared UI and the terminal viewport mount point
  features/      screen-level features
  lib/           pure logic: ssh, sftp, files, sessions, errors
  navigation/    route map, linking, navigators
  specs/         TurboModule and Fabric component specs (codegen input)
  theme/         theme tokens and layout scale
android/
  app/src/main/java/com/remotly/app/
    bridge/      TurboModule implementations
    camera/      clipboard reads for the terminal paste actions
    ssh/         SSH session, host store, secret store, host key verification
    terminal/    TerminalView and the Fabric view manager
  terminal-native/  JNI terminal source, build scripts, host tests, pin
```

## Physical device requirements

The terminal cannot be validated on an emulator alone: IME work needs a real
keyboard app.

- A Pixel-class device with current Gboard.
- A Samsung device with Samsung Keyboard.
- One device at API 24 to 30 for Ed25519 coverage, one at a current API level.
