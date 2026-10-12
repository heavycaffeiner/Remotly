# Adaptive workspace implementation verification

Release target: v2.4.0.
Baseline: v2.3.3, `38f8eea`.

## Implemented

- Shared Hosts, Sessions, and Settings navigation with a cross-host work selector.
- Compact host rows, search disclosure, advanced host verification disclosure, and protected editor exit.
- Window-bound layouts with 600dp rail, 840dp hideable/resizable sidebar, short-window rail, and optional same-host Terminal + Files at 1200dp when the content bounds fit. Hiding the wide sidebar removes its rail too.
- Vertical sidebar destinations, shared navigation buttons, keyboard and accessibility resizing, and a 48dp workspace-title target.
- Visible Files pane close and dock controls. The header Files button toggles the browser without discarding its directory or search.
- Adaptive Settings section navigation and selectable terminal-theme swatch cards. Hosts show resumable tabs and reuse existing Files tabs.
- Stable one-terminal mounting through companion changes. File directory, history, and search persist per tab.
- Explicit displayed-session input and resize routing, including detached terminal query replies. Background sessions are not resized by a different viewport.
- Simplified file toolbar, Sort sheet, pane-fit table, pointer context menus, and keyboard menu alternatives.
- Shell-owned transfers, terminal header badge, and failed-history retention until explicitly cleared.
- Pinned Esc/Ctrl, Automatic/Always/Hidden extra-key preference, optional legacy navigation gestures, mouse/trackpad scroll, Shift tracking bypass, selection, local menus, and Ctrl+wheel font adjustment.
- Six terminal presets and one custom theme, native defaults and palette configuration, effective cursor frame color, local selection colors, offline preview, contrast warnings, and save/discard handling.
- Schema-5 settings migration, ordered durable writes, visible errors, and explicit backup-before-reset recovery.
- Minimum-API fixes discovered by lint: API-24-compatible timestamps/Base64 and guarded API-29 terminal geometry calls. Existing host ids and credential encoding are retained.
- Native libraries rebuilt for ARMv7, ARM64, and x86_64 against the unchanged Ghostty pin `0ba6250388641f52135414b38c4259aa682c489b`.

## Automated verification

Completed locally:

- All 634 JVM tests passed.
- All 66 Android instrumentation tests passed together on an Android API 34 x86_64 emulator.
- `scripts/check.sh --release` passed, including debug/release builds and APK inspection.
- `lintDebug` passed with no errors. Remaining warnings are advisory; no lint suppressions or baselines were introduced.
- `go vet ./...` and `go test -race ./...` passed.
- Full native host suite passed, including terminal/frame/theme, keyboard, mouse, wheel, query, scroll-region, and resize/scrollback checks.
- `git diff --check`, repository artifact checks, and secret checks passed.

Android coverage includes:

- Existing Esc/Ctrl, IME, reconnect, fonts, and launcher regressions.
- Physical-pointer press/release and motion encoding, duplicate event-path handling, fractional wheels, modifiers, local selection, right-click, stale reconnect events, and font zoom.
- Native theme preservation of history, geometry, modes, truecolor, and OSC overrides.
- Custom-theme successful save before close and failed save preserving its draft; discard confirmation.
- File remount and saveable restoration, ordered Back, sort/hidden preference rollback, context menus, and 200% font-scale 48dp controls.
- Compact/rail/sidebar window layouts, live resizing without reconnects, no background-session resize, stable native handle through companion collapse/restore, and menu/transfer controls not changing viewport geometry.
- Classified touchpad gesture delivery through MainActivity, repeated gestures with stationary remote coordinates, batched travel, local scrollback reversal, remote wheel reports, Shift bypass, and Ctrl zoom.
- Complete sidebar hide/show, keyboard resizing, Files close/dock controls, and Settings section/theme selection at 200% text scale.

The emulator initially exposed test recorder and expectation issues, undersized table presentation, keyboard-menu focus, and Sort-sheet accessibility. These were corrected before the successful full run. One intermediate full run hit a Compose test SnapshotStateObserver multithread error; the affected class and the later full run passed without suppressing checks.

## Emulator interaction checks

- Virtual uinput touchpad contacts exercised the Android InputReader, Activity and terminal paths. Repeated upward gestures moved the first visible line from 281 to 271 to 260; a downward gesture returned it to 270. No click or selection was produced.
- Tablet bounds of 1280x800dp exercised sidebar hide/show, Files close/reopen, header toggle and docking to a tab. Closing the pane retained the Files tab and directory.
- Phone bounds and a 960x480dp short window exercised navigation and terminal controls. Settings section selection and theme cards were checked at default and 200% text scale.
- Captures are local under `/tmp/remotly-fixed-*.png`. Emulator interaction and semantic checks do not replace a physical-device visual audit.

## Build outputs

- Debug APK: `app/android/app/build/outputs/apk/debug/app-debug.apk`.
- Local release build: `app/android/app/build/outputs/apk/release/app-release-unsigned.apk`.
- Android test APK: `app/android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`.

The source version is 2.4.0, with version code 20400. The unsigned local release APK is not distributable. The `v2.4.0` tag uses the existing GitHub workflow to produce the signed `app-release.apk`. Publication requires checking its version, native libraries, signing identity against v2.3.3, and upgrade installation.

## Remaining physical checks

Not available in this environment:

- Actual Android PC Mode or connected-display caption/taskbar behavior.
- Bluetooth/USB keyboard, physical mouse, and trackpad hardware.
- Current Gboard and Samsung Keyboard on physical devices.
- API 24 to 30 runtime coverage; guarded fallbacks are lint checked but not device exercised.
- A live Herdr server with the new shell navigation; transport/control behavior remains covered by existing tests.
- TalkBack and a full 200% font-scale audit of every workspace and settings screen.

The adaptive tests use emulator content/window constraints. They do not certify system desktop windowing or real peripheral behavior.

## Deferred scope

Multiple terminal split panes, multiple app instances, detached-tab windows, drag-and-drop upload, theme import/export, host-specific themes, arbitrary fonts, and restoring remote shells after process death remain outside this implementation.
