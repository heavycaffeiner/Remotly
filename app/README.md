# Remotly app

The React Native client: a standalone SSH terminal and SFTP client. Several
shells per host, SFTP browsing and transfer, herdr workspace management over
SSH, and a terminal rendered by libghostty-vt with inline images, desktop
notifications, and bracketed paste.

The interface is React Native Paper (Material Design 3): every color comes
from the Paper theme, which follows the wallpaper on devices that support
dynamic color.

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

## Herdr

The Workspaces screen drives the `herdr` CLI on the remote host.
`src/lib/herdr.ts` builds each command string and parses the document it
prints; `src/lib/herdrClient.ts` runs it through the herdr bridge, one SSH
exec per call.

That shape decides what can be exposed. A command that prints one document and
exits works. `session attach`, `agent attach`, and the streaming `pane`
commands need a PTY and stay attached, so they cannot cross the bridge at all;
reaching those means opening a terminal on the host and running herdr there.

A failure arrives as a `{ error: { code, message } }` document. Which stream
carries it depends on the command: `session list --json` answers on stdout,
while the socket-API commands write the document to stderr and exit non-zero.

A host is unreachable here until its key has been accepted in the terminal
once, because a one-shot exec has nowhere to show the first-use prompt.

An exec channel gets a plain non-interactive shell, so PATH is the system
default and a herdr under `~/.local/bin` or a version manager's shims is
invisible to it. When a command comes back "command not found", one lookup runs
`$SHELL -ilc 'command -v herdr'` and the command is repeated with the directory
it named ahead of PATH; the answer is kept for the host. An interactive login
shell because that is the one that read the user's rc files: zsh sets PATH in
`.zshrc`, which a non-interactive login shell skips.

What the host still has to supply is a running `herdr server`. Without it the
CLI answers with a `server_not_running` document, which the screen reports as
such.

"Open terminal" focuses the workspace and attaches a tab that runs `herdr`.
There is one such tab per session, not per workspace: the focused workspace is
session state rather than per client, so a second attached terminal would only
mirror the first. `herdr workspace focus` takes no client scope and the root
command takes no `--workspace`, so this is herdr's model, not a shortcut here.
Two workspaces on screen at once means two sessions, each with its own tab.

To exercise the screen against a real server, run one in a container with
herdr installed and `herdr server` started, publish its SSH port, and add a
host pointing at it (`10.0.2.2:2222` from an emulator).

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
  lib/           pure logic: ssh, sftp, files, sessions, herdr, errors
  navigation/    route map, linking, navigators
  specs/         TurboModule and Fabric component specs (codegen input)
  theme/         Paper theme, dynamic color, and the terminal's own colors
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
