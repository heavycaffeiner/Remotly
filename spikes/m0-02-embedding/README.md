# M0-02 spike: LynxJS embedding and throughput

Disposable spike proving libghostty can be built for Android, embedded as a
native Lynx custom view, and sustain interactive terminal output. This host
cannot run Android; the scaffold compiles against the target toolchain and must
be executed on a physical device.

## What is here

- `native/GhosttyTerminalView.kt`: the native boundary. Hosts the libghostty
  surface, draws cell frames with a Canvas, and maps the Android
  `InputConnection` onto `ghostty_surface_text` / `ghostty_surface_preedit`.
- `js/App.tsx`: the Lynx harness that mounts the component and feeds fixtures.
- `fixtures/`: deterministic byte fixtures and `manifest.json`. Regenerate with
  `node fixtures/generate.js`.
- `measure/report-template.md`: the measurement report to fill in on device.

## Build and run (on a workstation with Android SDK and NDK)

1. Pin a libghostty commit (the API is untagged; record the pin in the
   measurement report). Build the shared library for `aarch64-linux-android`
   (plus `x86_64` for emulators) with Zig, using the repo's `pkg/android-ndk`
   shim and the installed NDK. Produce `libghostty.so`.
2. Write the small JNI bridge declaring the pinned `ghostty_surface_*` entry
   points used by `GhosttyTerminalView.kt`, plus the app event loop that pumps
   libghostty callbacks (frame, bell, title) onto the view.
3. Register the view as a Lynx custom component, following the same pattern as
   `LynxUIWebView` in `lynx-family/lynx`.
4. Bundle the `fixtures/` directory as app assets and place `libghostty.so`
   under `jniLibs/`.
5. Build a debug APK and install it on a physical device in the M0-01 target
   range (one Pixel-class, one Samsung).

The exact Lynx custom-component registration API and asset loader vary by Lynx
release. Align them to the installed Lynx version before the first device run.

## Renderer note

libghostty owns the parser, screen model, and selection (`ghostty_surface_has_selection` /
`ghostty_surface_read_selection`) but ships no Android renderer. This spike
draws the frame cells with a Canvas; if Canvas fails the throughput thresholds
below, the OpenGL path is tried before any decision to fall back. Font fallback
and pinch font size are app-level work on top of the surface data.

## What to measure

Replay each fixture, then record results in `measure/report-template.md`:

- Sustained throughput: bytes per second for `burst-1mb.bin`.
- Burst handling: max frame drop during `tui-redraw.bin`.
- Input latency: touch-to-byte time while `burst-1mb.bin` is arriving.
- Memory growth: RSS before and after 10 repeats of each fixture.
- Lifecycle: mount, unmount, remount with no crash, stale callback, or duplicate
  input.
- UTF-8: `split-utf8.bin` fed at every byte boundary renders identically to an
  unsplit stream with no replacement characters. `invalid-utf8.bin` fails safely
  without crashing native code or corrupting later output.

## Pass thresholds

- Sustained throughput of at least 10 MiB/s with no input starvation.
- No dropped frames visible to the user during the 1 MiB burst.
- Input latency under 100 ms while output is arriving.
- No unbounded memory growth across repeated mounts.
- No replacement characters caused solely by chunking.

These thresholds are the M0-02 exit criteria; if they are not met with evidence,
the M0-01 fallback (Termux terminal-view) is evaluated.

## Handoff

M0-03 receives the runnable spike, fixtures, device setup, and measured limits.
M1-09 receives the report and lessons only. The spike code is disposable and
must not be promoted without a production review.
