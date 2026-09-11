# Remotly app

The native Android app: a standalone SSH terminal and SFTP client, built with
Kotlin and Jetpack Compose. Several shells per host, SFTP browsing and
transfer, herdr workspace management over SSH, and a terminal rendered by
libghostty-vt with inline images, desktop notifications, and bracketed paste.

The interface is Compose Material 3: every color comes from the Material
theme, which follows the wallpaper on devices that support dynamic color.

The module lives at `app/android/app`, package root `com.remotly.app`.

## Requirements

| Tool | Version |
| --- | --- |
| JDK | 17 or later (Gradle refuses older) |
| Android SDK | compileSdk 37, build-tools 37.0.0 |
| Android NDK | 28.2.13676358 |
| minSdk | 24 |
| targetSdk | 36 |
| Go | 1.26 or later (sshcore) |
| gomobile | pinned in `scripts/build-sshcore.sh` |
| Zig | required only to rebuild the terminal native library |

Shipped ABIs: `armeabi-v7a`, `arm64-v8a`, `x86_64`.

Set `ANDROID_HOME` before running `scripts/build-sshcore.sh`, and either
`ANDROID_NDK_HOME` or an NDK installed under `$ANDROID_HOME/ndk`. Gradle reads
the SDK location from `ANDROID_SDK_ROOT` or from `app/android/local.properties`.

## Build and run

The Go SSH core is a build output, not a checked-in binary, so a fresh clone
needs it once before Gradle can resolve it:

```sh
scripts/build-sshcore.sh          # produces app/android/app/libs/sshcore.aar
cd app/android
./gradlew installDebug            # builds and installs onto a connected device
```

Rebuild `sshcore.aar` again only after a change under `mobile/sshcore`.

## Checks

```sh
scripts/check.sh               # toolchain, repo hygiene, Kotlin tests and build, Go tests
scripts/check.sh --fast        # skips the Gradle build and tests
scripts/check.sh --release     # adds a release APK build and its inspection
```

`scripts/check.sh` runs, in order: a toolchain version check
(`scripts/check-toolchain.sh`); repository hygiene, meaning no generated
artifact would be committed (`scripts/check-artifacts.sh`) and no secret or
terminal content reaches a log line (`scripts/check-secrets.sh`); the Android
build, `./gradlew testDebugUnitTest` and `./gradlew assembleDebug`, offline by
default (`GRADLE_ONLINE=1` to resolve dependencies online after a change);
and the Go modules under `mobile`, `go test ./...`. `--release` adds
`./gradlew assembleRelease` and `scripts/check-apk.sh`.

Gradle directly, from a JDK 17 or later shell:

```sh
cd app/android
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew assembleRelease
```

## Terminal native library

The terminal is libghostty-vt behind a JNI bridge. Source, build scripts, host
tests, the upstream pin, and the upstream license live in
`app/android/terminal-native/`.

```sh
cd app/android/terminal-native
GHOSTTY_DIR=~/opt/ghostty ./build-android.sh          # all shipped ABIs
GHOSTTY_DIR=~/opt/ghostty ./build-android.sh aarch64-linux-android.24
./run-host-tests.sh                                    # host-side terminal core tests
```

`build-android.sh` verifies the ghostty checkout matches `PIN.txt` and refuses
to build otherwise. The C API is untagged, so a drifted checkout miscompiles
silently. Output goes to `app/android/app/src/main/jniLibs/<abi>/`.

## SSH core

SSH and SFTP run on a Go core bound through gomobile.

```sh
./scripts/build-sshcore.sh
cd mobile && go test ./...
```

The result is `app/android/app/libs/sshcore.aar`, consumed as a local AAR
dependency in `app/android/app/build.gradle`.

## Herdr

The sidebar and the workspace terminal drive the `herdr` CLI on the remote
host. `com.remotly.app.herdr.HerdrCommands` builds each command string and
parses the document it prints; `com.remotly.app.herdr.HerdrClient` runs it
through the herdr bridge, `com.remotly.app.ssh.HerdrBridge`.

One authenticated SSH connection is held per host and every command runs as a
channel on it (`HerdrBridge`, `sshcore.Control`). The handshake was most of
what a control call cost from a phone: a per-command connection measured about
0.7s, and a chip tap 1.0s end to end. A connection that broke is dropped and
redialled once, which is what a resumed app or a moved network looks like.

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

Opening herdr on a host lands on whichever workspace herdr has focused
(`HerdrWorkspaceScreen`). That screen focuses the workspace, attaches a
terminal running `herdr`, and draws that workspace's herdr tabs as its strip.
Select, add, rename, and close are `tab focus`, `tab create`, `tab rename`,
and `tab close`.

Everything that manages a host lives in the sidebar (`HostSidebar`): its
sessions, their workspaces, and each workspace's tabs, with rename, close, and
new-tab on the row they belong to. It opens from the bar and, where the window
is at least 840dp wide, it is simply always there. It never takes an edge
swipe: a horizontal swipe over an attached terminal moves a herdr tab, and a
drawer on that edge would fight it. The sidebar is also the whole non-gesture
path, which is why the terminal's menu no longer carries tab and workspace
moves.

What both draw comes from one store per host and session (`HerdrStore`). It
bootstraps from `api snapshot` and then follows herdr's own events, so a
change made in the app, by a gesture, or on the desktop lands without a timer.
There is no streaming CLI command (`herdr api` has `snapshot` and `schema`), so
the events come from the control socket: the app runs a reader on the host over
one channel that stays open, `socat`, `python3`, or `perl`, whichever answers
the subscription first. A host where none of them acknowledges falls back to
re-reading every four seconds, which is what every host did before event
support existed.

Only the workspace and tab events are subscribed to. The `pane.*` family
includes a per-scroll event, and asking for it would deliver a line on every
wheel turn. A focus event carries ids alone; labels come from the snapshot and
stay current through `workspace.renamed`, `tab.renamed`, and the full record in
`tab.created`. A move event carries a new order the app will not read field by
field, so those two re-read the snapshot instead.

A chip tap and a workspace move paint before the command is answered, and
herdr's event confirms the same ids afterwards. Applying an event twice is a
no-op because the store keys on herdr's ids.

What the events cannot see is a move herdr's own key bindings made: pressing
`prefix+n` in the terminal changes the focused tab and publishes nothing. The
app's gestures avoid that by going over the socket, but a chord typed by hand
still has to reach the strip and the title, so a live host is re-read every
three seconds while a screen is up and the app is in front, and once more when
the app returns to the foreground. It costs one command on the connection
already held, and it stops while the app is away.

That is the price of following what herdr's own keys did. Without it, typing
`prefix+n` left the strip and the title on the tab the user had just left.

The terminal it attaches lives in the ordinary session store but with
`kind = SshTabKind.Workspace`, and `SshTerminalScreen` filters that kind out of
its strip. So the app's SSH tabs and a workspace's tabs never mix, and coming
back from a workspace lands on the shell the user last had.

There is one such terminal per session, not per workspace: the focused
workspace is session state rather than per client, so a second attached
terminal would only mirror the first. `herdr workspace focus` takes no client
scope and the root command takes no `--workspace`, so this is herdr's model,
not a shortcut here. Entering another workspace retags that terminal. Two
workspaces at once means two sessions, each with its own terminal, and the
sidebar lists the sessions a host has.

The workspace terminal's menu invokes the plugin in `plugin/`: `tab-here`,
`panes-to-tabs`, and `zoom`. `plugin action invoke` answers that the action
started, never with its output, and the events report what it did; a host
without the plugin answers `plugin_action_not_found`.

A one-finger sideways swipe across an attached terminal moves between herdr's
tabs, and a double tap moves to the next workspace, the coarser step. Both go
over the socket rather than as the chord herdr binds (`prefix+n`, `prefix+p`),
for the same reason: **herdr emits no event for a move made by its own key
binding.** A chord left the strip and the title on the tab the session had
just left, which is what an event-fed screen cannot see. A focus command emits
the event, and the target is already known here, so it stays one command:
measured at 0.28s to 0.32s for a tab and 0.11s for a workspace.

Workspaces have no chord to send in any case: `next_workspace` and
`previous_workspace` ship unbound, and a typed `prefix+w` did not open herdr's
picker either. Panes still move as chords, since nothing here draws them.

The second tap is claimed in the capture phase, which cancels that touch in
the terminal below, so it opens no keyboard and sends no click. What disarms a
tap is its own movement: a touch the terminal keeps handling reports no
release here, so a fast scroll used to read as a run of taps landing in the
same place. Past 40px of travel the tap is no longer a candidate.

The first tap is an ordinary one and herdr's own view has mouse reporting on,
so it clicks: the pane under the finger takes focus in the workspace being
left. Suppressing that would mean holding every click for the length of the
double-tap window, which is felt in any program that reads the mouse, so the
click stands.

Two fingers are left to the terminal's pinch. A two-finger drag and a pinch
cannot be told apart reliably enough to share a surface with the font size, so
nothing navigates with them. Panes are moved from the terminal's menu; the
sidebar covers tabs and workspaces for anyone who cannot make the gesture.

To exercise the screen against a real server, run one in a container with
herdr installed and `herdr server` started, publish its SSH port, and add a
host pointing at it (`10.0.2.2:2222` from an emulator).

## Files

A directory is fetched whole, once per visit. SFTP readdir has no cursor a
client can resume from, so a page was a slice of a listing the server had
already sent and asking for the next one re-read the directory from the
start. `SftpBridge.list(hostId, path)` is the only call; ordering and search run
over the full list and a `LazyColumn` virtualizes the rows.

Listings are kept per screen in a small LRU, so a directory already read is
drawn immediately and refreshed behind the list. A refresh never blanks what
is on screen, and a listing that arrives after the user has navigated on is
dropped by a generation counter.

The browser opens as a tab in the session strip, beside the shells, so
copying between two places on one host does not mean leaving the terminal.
It owns no SSH session: the SFTP connection is per host and the bridge owns
it, so opening one connects nothing and closing one must not tear down a
channel it never had (`SshSessions.closeTab`). Each tab keeps its directory
in `FilesTabs`, outside composition, because an unselected tab is not
composed and would otherwise come back at the root of a tree the user had
walked into. The terminal underneath stays composed while a browser is in
front, since it is what measures the grid every session is opened against.
The host row's badge counts a host's open tabs, browsers included.

Both transfer directions have a native path that never copies file bytes
through an intermediate buffer: the app moves between the content URI and the
server inside Kotlin, and only throttled progress events reach the screen. The
chunked `FileModule.writeChunk` path remains for a backend that cannot reach
the local file itself.

Both directions ask Keep both or Replace on a name collision instead of
picking one, and Keep both numbers the name with `uniqueName`. The
create-document picker is deliberately not used to settle a download
collision: it renames on its own and never says that it did.

A download needs a destination folder, taken from the `downloadFolderUri`
setting. When none is granted yet the picker opens and the download the user
asked for then continues, rather than being dropped so they can ask twice.
A failed transfer that cannot be resumed does not keep a partial file wearing
the name the user chose; one that can be resumed is kept, and the message
says so. A file that already existed is never deleted on a failed replace.

The Go client runs with `UseConcurrentWrites`, so a write is pipelined rather
than paying a round trip per 32KB packet. That costs an invariant: a write
that fails partway can leave the server holding bytes past the last one it
acknowledged. Two things cover it. A failure the app survives truncates back
to the confirmed length before closing. A failure it does not, a kill or a
dead socket, is covered on the next attempt: `OpenAppend` takes a rewind and
cuts back that far from the file's end rather than trusting the length,
because every byte below the end minus one write's worth belongs to a write
the server acknowledged in full. `SftpTransfers.RESUME_REWIND_BYTES` is that
figure, one value shared by both upload paths, since what has to be covered
is the chunk the interrupted attempt wrote and not the one resuming it.

## Compose UI

One activity, `MainActivity`, hosts every screen as a destination in one
`NavHost` (`RemotlyApp.kt`, route table in `Routes.kt`). The activity is
`singleTask` and its configuration changes are handled rather than triggering
a recreate, so a terminal session and its native view survive the user
leaving through the launcher and coming back.

`com.remotly.app.ui.terminal.TerminalPane` hosts the native `TerminalView`
through `AndroidView`. It owns the view's lifetime for one session: a screen
never holds the view itself, only a `TerminalHandle`
(`rememberTerminalHandle()`) exposing `openKeyboard`, `hideKeyboard`,
`selectAll`, `copySelection`, and `paste`. The view is recreated, not rebound,
whenever the session key changes; rebinding one instance across a session
switch once let a second shell render over the first one's screen.

### Four things the rewrite got wrong once

1. **`TerminalView.Host.onReady` carries the first grid, not `onResize`.**
   `onResize` fires only when the grid changes, so a screen that opens a
   session and waits for `onResize` before showing it hangs forever: the very
   first measurement only ever arrives through `onReady`.
2. **The failure card sits over the viewport, never in place of it.** The
   viewport is what measures the grid; swapping it out for the card would
   stop a session's first resize from ever landing. `TerminalScaffold` keeps
   `content(...)` composed underneath and draws `TerminalFailureCard` as an
   overlay in the same `Box`.
3. **The window is edge to edge, so chrome has to consume insets itself.** A
   control laid out under the navigation bar is invisible in a screenshot and
   silently untappable, because the system takes those touches before the app
   ever sees them. `TerminalScaffold` applies `safeDrawingPadding()` to its
   whole column for exactly this reason; the extra key row shipped without it
   once, and its keys did nothing at the bottom of the screen.
4. **A session key is the bare session id, never `hostId:sessionId`.**
   `TerminalStore` keys retained terminals and routes pty output by that id
   alone. A composite key makes `TerminalPane` mount an empty terminal while
   the real session's output sits under a key nobody is rendering.

## Source tree

```text
app/android/
  app/src/main/java/com/remotly/app/
    MainActivity.kt         the single activity
    RemotlyApplication.kt   brings up RemotlyCore before any screen exists
    core/                   process-wide store wiring (RemotlyCore)
    ui/
      RemotlyApp.kt         theme root and nav graph
      Routes.kt             destinations and their arguments
      screens/              one file per screen: hosts, editor, terminal,
                             files, herdr workspace, settings, the sidebar
      terminal/             TerminalPane, the key row, focus and resize policy
      components/           RemotlyScreen shell, loading/empty/error states
      theme/                Material 3 theme, dynamic color
    ssh/                    SSH session, host store, secret store, host key
                             verification, the herdr control bridge, transfers
      engine/               SftpOps/SshEngine interfaces and the Go binding
    herdr/                  herdr command building, parsing, and event store
    files/                  file listing and presentation model
    fileio/                 content URI reads and writes for transfers
    transfers/              the transfer registry behind the transfer bar
    session/                the tab model and its swipe gesture
    settings/               settings state and its on-disk store
    util/                   small shared helpers
  terminal-native/          JNI terminal source, build scripts, host tests, pin
mobile/sshcore/              Go SSH and SFTP core, built as an AAR for the app
```

## Release

```sh
scripts/release.sh
```

`scripts/release.sh` builds `sshcore.aar` fresh, builds the release APK,
zipaligns it, and signs it. Without `ANDROID_KEYSTORE`, `ANDROID_KEY_ALIAS`,
and `ANDROID_KEYSTORE_PASSWORD` set, it signs with the development keystore
and writes `dist/remotly-android-development.apk`, which is for local testing
only. With those set it writes `dist/remotly-android.apk`, signed with the
supplied keystore. Either way it also writes `dist/signing-identity.txt` (the
fingerprint an upgrade install has to match), `dist/SHA256SUMS`, and a short
`dist/README.md` naming the artifact that was actually produced. `SKIP_APK=1`
skips the Android build entirely, for when only the checksums step is needed.

Signing keys are not in source control. The development keystore is not
production guidance; supply your own for distribution.

## Physical device requirements

The terminal cannot be validated on an emulator alone: IME work needs a real
keyboard app.

- A Pixel-class device with current Gboard.
- A Samsung device with Samsung Keyboard.
- One device at API 24 to 30 for Ed25519 coverage, one at a current API level.
