# Adaptive workspace and terminal themes

Status: approved and implemented, awaiting implementation review.
Baseline: v2.3.3, commit `38f8eea`.
The user approved the recommended scope and authorized implementation. Publication is not part of the current approval.

## Goals

1. Reduce repeated actions and stacked controls on phones.
2. Preserve a user's working context while navigating, resizing a window, or moving to a connected display.
3. Support touch, hardware keyboards, mice, and trackpads without treating terminal shortcuts as application shortcuts.
4. Let users choose terminal colors independently from the application's light, dark, and dynamic-color settings.

Keep the existing SSH, SFTP, and Herdr features. Do not rewrite the transport or replace the terminal engine.

## Current assessment

These findings come from source inspection, not a new device usability study.

| Area | Current behavior | Design consequence |
| --- | --- | --- |
| Host list | A host card opens a terminal, also has an Open terminal button, and adds Files and Workspaces actions | One host consumes several rows and repeats the primary action |
| Navigation | Hosts and Settings share a root shell; SSH and Herdr use separate destinations and different navigation controls | Returning to existing work and switching hosts require different paths |
| Terminal chrome | Top bar, optional pill tabs, extra keys, transient banners, and transfer overlay have separate placement rules | Controls compete with the viewport, especially when the IME is visible |
| Terminal menus | Opening overflow or the Herdr sidebar explicitly hides the keyboard | A non-editing action can interrupt typing and resize the remote PTY |
| Tab targets | Tab chips have a 36dp visual height and a separate close area | Small targets and repeated close controls are a poor phone default |
| Adaptive layout | Some screens branch at 840dp; host detail and Herdr sidebar adapt, but ordinary SSH, files, and settings remain mostly single-surface | A wide window does not become a consistent workbench |
| Files | Breadcrumbs and a horizontally scrolling row expose all sort choices | Secondary controls occupy persistent space |
| Transfers | A global overlay reserves an approximate bottom offset | Placement is not coordinated with each screen's actual controls and IME |
| Settings | One long screen; application theme settings do not configure terminal colors | Appearance and terminal appearance are easy to confuse |
| Pointer input | Native terminal handles touch gestures; no explicit generic-motion wheel or mouse context-click path is present | A custom Canvas view needs deliberate mouse and trackpad support |

Relevant files:

- `ui/screens/MainTabs.kt`, `TerminalScaffold.kt`, `SshTerminalScreen.kt`, `HerdrWorkspaceScreen.kt`, `HostSidebar.kt`, and `FilesScreen.kt`.
- `ui/LayoutPolicy.kt` and `ui/components/TransferBar.kt`.
- `settings/SettingsStore.kt` and `SettingsState.kt`.
- `terminal/TerminalView.kt`, `TerminalStore.kt`, `TerminalRenderer.kt`, and `TerminalFrame.kt`.
- `app/android/terminal-native/remotly_terminal.c`.

## Information architecture

Use three root destinations: Hosts, Sessions, and Settings.

- Hosts contains saved connections and their management actions.
- Sessions contains currently open SSH shells, file tabs, and attached Herdr terminals, grouped by host. It does not claim to restore a shell after process death or enumerate every remote Herdr workspace.
- Settings groups application appearance, terminal appearance, input, files, and about information.

Working content uses one shared workspace shell. SSH terminals, file views, and Herdr attachments keep their existing identities and connection behavior.

The workspace selector offers active work across hosts. Within a host it distinguishes SSH shells, Files, and Herdr sessions/workspaces. A Herdr tab is not mislabeled as an independent SSH shell.

Returning to Hosts, opening Settings, or changing layout never means disconnecting. Close tab, detach Herdr, and disconnect host remain explicit actions with distinct confirmation text.

## Mobile proposal

### Hosts

Replace large action cards with a list. Each row shows the host name, endpoint, and an explicit open-work indicator where applicable.

- Tap resumes the last open surface for that host, or opens a terminal when no surface exists.
- The row menu provides New terminal, Files, Workspaces, Edit, and Remove.
- Add host remains a visible primary action.
- Search is reachable from the toolbar instead of permanently consuming a field-height row. An active search remains visible until dismissed.
- Advanced connection fields are grouped and disclosed in the host editor. Required connection fields remain visible. Do not discard unsaved drafts during navigation or layout changes.

### Terminal workspace

```text
[Back] [Host / current surface v] [Files] [More]
[Tabs, only when multiple surfaces are open]
[Terminal viewport]
[Contextual extra keys, when needed]
[Software keyboard]
```

- The header identifies the host and current surface without repeating the endpoint.
- The selector opens a sheet for switching work across hosts.
- The root navigation bar is not drawn under an active terminal. The selector keeps session switching reachable.
- With multiple tabs, use one row of tabs with 48dp touch targets. On touch layouts, closing and renaming move to the tab menu rather than occupying every chip.
- Files opens the file surface for the same host. Back from its initial directory returns to the preceding terminal, without closing either surface.
- Overflow uses short task groups: current surface, clipboard, appearance, and connection. Common actions remain at one visible entry point.
- Opening a non-editing menu does not hide the software keyboard or change terminal geometry. A dialog that needs text input can deliberately take focus.

### Extra keys

Use three visibility choices: Automatic, Always, and Hidden.

- Automatic shows keys with the software keyboard or when explicitly requested, and hides them for hardware-keyboard-only use.
- Always keeps them visible. Hidden removes them until the user explicitly requests a temporary key panel.
- Keep Esc and Ctrl pinned. Tab, Alt, Shift, arrows, and remaining keys share a scrollable area with a reachable More keys action.
- Remove the duplicate keyboard button from the key row. Tapping the terminal requests typing; the workspace menu has explicit Show keyboard and Hide keyboard actions.
- Modifier state is visible through a pressed state and an accessible label, not color alone.
- Keep one row. Large text does not make the row wrap and repeatedly resize a PTY.

New installations default to Automatic. Migrate the existing enabled boolean to Always and the disabled boolean to Hidden, preserving an explicitly stored preference.

### Gestures

Do not require gestures to discover navigation. Recommend an optional Navigation gestures setting, disabled by default for new installations.

- Explicit controls always provide tab and workspace navigation.
- Double tap selects a terminal word in the standard interaction mode. It does not silently change the Herdr workspace.
- Existing swipe and double-tap navigation can remain available through the optional mode. Preserve existing behavior for migrated users until they choose the standard mode.
- Mouse double-click is always a selection action, not workspace navigation.

## Adaptive window proposal

Layout follows the current app window, not a phone/tablet label, Android PC Mode brand, or orientation flag. Input capability affects control presentation, not whether content fits.

| Available window width | Shell | Working content |
| --- | --- | --- |
| Under 600dp | Mobile root bar; modal workspace selector during work | One primary surface |
| 600dp to 839dp | Collapsed navigation rail with an explicit selector | One primary surface; roomier forms and bounded settings columns |
| 840dp to 1199dp | Shared, collapsible host/session sidebar | One primary surface; list/detail layouts for management screens where they fit |
| 1200dp and above | Shared sidebar with adjustable width | Primary terminal plus optional Files companion pane when both can fit |

A 1600dp window uses additional space for readable columns and the companion pane. It does not stretch a settings form indefinitely or automatically add more terminals.

Use a default sidebar width near 288dp, with a bounded adjustment range of 240dp to 360dp. A collapsed rail replaces the sidebar; do not stack an app rail and a second permanent Herdr drawer.

```text
[Sidebar: hosts and open work] | [Workspace header and tabs]
                             | [Terminal] | [Files, optional]
[Settings and transfers]     | [Workspace status]
```

### Height and fit rules

- A short window, particularly below 480dp before IME insets, uses a compact header and no persistent bottom navigation. All actions remain reachable through the selector and overflow.
- The IME reduces viewport space but does not trigger a different root navigation model.
- Pane-fit decisions use actual remaining content bounds after navigation and insets.
- The optional Terminal + Files arrangement requires at least 960dp of work area, with a terminal target minimum of 560dp and a Files minimum of 320dp.
- Collapse the sidebar if necessary before enabling the companion pane. If both panes cannot fit, show the focused surface alone and preserve the companion request for when space returns.
- Never shrink a text field, touch target, or terminal pane into unusability to keep a nominal desktop layout.

These dimensions are proposed starting values. Verify them with actual fonts and the resize test matrix before making them release criteria.

### Desktop and pointer behavior

- Keep system caption controls outside app hit targets. Consume caption-bar and taskbar insets. Do not implement a custom title bar or place tabs inside system caption space in the first version.
- Keyboard focus, selected surface, and window activation are separate states. Switching app focus must not terminate work or reopen the IME.
- Clicking the terminal focuses it without automatically showing the software keyboard for a physical mouse or hardware-keyboard workflow.
- Mouse wheel and trackpad scrolling use terminal scrollback unless the remote program has enabled mouse reporting. Remote tracking receives correctly encoded pointer actions.
- Mouse selection, right-click context menus, pointer feedback, and tooltips are supported. Right-click goes to a remote application when it owns mouse reporting; otherwise it opens the local terminal menu. A documented Shift modifier bypasses remote tracking for local selection/menu actions.
- Ctrl+mouse wheel can change font size locally. Plain Ctrl chords, Esc, Tab, arrows, and function keys keep their terminal meaning while terminal focus is active.
- Application shortcuts are focus-scoped. Do not capture Ctrl+C, Ctrl+V, Ctrl+W, Ctrl+Tab, Alt chords, or Meta combinations globally. In text fields and file lists, normal editing shortcuts remain available. The system keyboard-shortcut helper can document nonconflicting actions.
- Do not rely on keyboard shortcuts as the only way to reach a feature.

## Files and transfers

On phones, show a path row and one compact file toolbar: Search, Sort, and Add. Hidden files and sort direction belong to Sort, not a permanently scrolling set of chips. Keep all current upload, download, rename, remove, conflict, and resume behavior.

With enough space, use a file table with Name, Size, Modified, and Type columns. Column headers sort the same full directory already fetched. Use context menus with visible alternatives; no drag action is required.

The optional desktop companion pane shows Files for the same host beside one terminal. Reuse the existing file-surface state. Do not duplicate its view in two places or open a second shell.

Replace the global bottom overlay with a shell-owned transfer indicator. It opens one app-wide transfer view. During terminal work, progress uses a compact header/status entry and never covers the last terminal line, extra keys, or the IME. Detailed failures remain visible in transfer history until acknowledged.

## Terminal theme proposal

### User-facing settings

Application appearance and terminal appearance are independent sections.

Initial terminal presets:

1. Classic, preserving the current terminal colors.
2. Nord.
3. Dracula.
4. Catppuccin Mocha.
5. Solarized Dark.
6. Solarized Light.

Verify palette source provenance and any license requirements before bundling preset values.

The theme picker contains an offline preview showing a prompt, ordinary output, ANSI colors, a selection, a cursor, and CJK text. It uses the same native terminal and renderer as the real viewport, with no SSH connection and no user clipboard/input side effects.

A simple Custom option starts from a preset and edits:

- Background and default foreground.
- Cursor and text beneath a block cursor.
- Selection background and foreground.
- The 16 ANSI colors.

Validate opaque `#RRGGBB` values. Show text labels and values rather than color swatches alone. Warn when default text/background contrast is below 4.5:1. Built-in presets must meet the target for default text. Arbitrary application truecolor output cannot be promised to meet it.

Use one global terminal theme. Apply it immediately to existing and new terminal sessions, including retained sessions, without reconnecting, resizing, or clearing scrollback. Switching the application UI between light and dark does not silently switch the terminal palette.

### Color semantics

This is terminal configuration, not a Canvas recoloring filter.

- Set native default foreground, background, cursor, and indexed palette colors.
- Theme the first 16 palette entries. Keep the standard remaining 240 indexed colors.
- Preserve explicit remote truecolor output.
- Respect remote OSC color overrides. The native API separates defaults and overrides; changing a default must not erase an application's active override.
- Report effective colors accurately to remote color queries and color-scheme queries.
- Expose effective cursor color through the frame boundary, since the current renderer always uses default foreground for the cursor.
- Local selection colors belong to the renderer. Preserve remote inverse-video semantics and selected CJK/wide-cell occupancy.
- Apply colors before the first frame for a created or adopted terminal. Loading/error backgrounds and selection handles follow the chosen terminal appearance instead of unrelated hardcoded colors.

### Settings integrity

Extend the existing settings file rather than creating a parallel store. Migrate older schemas without dropping hosts, credentials, file preferences, or input preferences.

Theme editing uses an explicit draft with Preview, Apply, and Cancel. Switching screens does not quietly discard a custom draft. Immediate preset selection and rapid adjustments must not let an older async save overwrite a newer setting. Serialize persistence and surface failures.

Unknown preset ids fall back to Classic. Invalid custom values produce a recoverable validation error. Do not silently overwrite the original settings file to fix malformed external input.

## Engineering constraints

1. `SshSessions.sendInput` currently targets the host's active session. The new workspace shell must bind input to the exact displayed session id, so a focus or navigation change cannot redirect bytes.
2. `resizeHost` currently resizes every tab under a host. Keep one terminal viewport in the first implementation. A Files companion changes only that displayed terminal's geometry; background sessions should not be repeatedly resized because a sidebar or unrelated file surface moved.
3. `TerminalStore` keeps one renderer per session, and the activity uses `singleTask`. Multi-instance windows and duplicate rendering of one session are not safe additions to this scope.
4. Herdr focus is remote session state. Do not claim two attached views of the same Herdr session are independent workspaces.
5. Window resize, navigation, layout transitions, and theme changes must not reset the native parser, replay a startup command, or reopen a session.
6. Native color configuration is available in the currently pinned Ghostty C API. Add only the required JNI/frame fields and rebuild all three shipped ABIs against the same pin. No engine upgrade is implied.
7. Keep one Android app and its existing Go core. A shared shell is UI cohesion, not a new framework or service layer.

## Recommended first scope

Include:

- Shared workspace navigation and open-sessions destination.
- Compact host list, simpler file controls, and grouped settings/editor layout.
- Window-width and height adaptation, collapsible/resizable desktop sidebar.
- One terminal with an optional same-host Files companion pane.
- Hardware keyboard, mouse, trackpad, focus, and caption-inset behavior.
- Six presets, offline preview, one custom terminal theme, native color application.
- Settings migration, persistence ordering, and current input/session regression coverage.

Defer:

- Multiple terminal split panes.
- Multiple app instances and dragging tabs into new windows.
- Drag-and-drop file upload or app-to-app clipboard extensions.
- Theme-file import/export, a theme marketplace, host-specific theme overrides, and arbitrary font import.
- Shell restoration after Android kills the process.
- Transport, protocol, authentication, or architecture rewrites.

Multiple-terminal splits are a separate product decision. If required now, they need explicit per-session input/resize routing and a defined focus/layout model, not just an extra composable.

## Delivery and review gates

1. Scope review completed. The user approved the recommendations and requested direct implementation.
2. The user waived an additional wireframe gate by requesting direct implementation.
3. Implement the shared shell and adaptive state transitions.
4. Simplify mobile management and file/transfer surfaces.
5. Implement native themes, preview, and settings migration.
6. Complete pointer and keyboard behavior, review for regressions, and validate the full matrix.
7. Present the implementation for review. Publish only after the agreed acceptance checks and release decision.

The native colors and input/session boundaries are the highest-risk work. Cosmetic adjustments should not hide failed acceptance checks there.

## Acceptance matrix

Window examples: 360x800, 412x915, 740x360, 600x900, 840x600, 1200x800, and 1600x900 dp. Test both sides of the 600, 840, and 1200dp breakpoints. Include narrow desktop windows, split-screen, and live resize.

Input examples: touch-only, hardware keyboard, keyboard plus mouse, trackpad, and explicitly requested software keyboard while a hardware keyboard is connected.

Required checks:

- Every action is reachable without a hidden gesture. Touch targets are at least 48dp, keyboard focus is visible, dialogs restore focus, and 200% font scaling does not clip controls.
- Opening non-editing menus does not unexpectedly hide the IME or resize the PTY.
- Active host, tab, current directory, selection policy, and open work survive layout changes. Unsaved editor/theme drafts are protected.
- Shrinking a Terminal + Files window shows the focused surface without dropping work. Expanding restores the requested arrangement.
- Closing a surface and returning to a root destination do not get confused with host disconnect.
- Input always reaches the displayed session, including during rapid host/tab switching. Preserve the v2.3.3 Esc/Ctrl regressions and CJK composition behavior.
- Mouse wheel and remote TUI tracking have one owner for each event. No duplicated clicks, scrolls, or raw report text in an ordinary shell.
- Preset/custom colors affect default and indexed terminal colors, not explicit truecolor. OSC overrides and reset behavior remain correct.
- Theme changes preserve scrollback, protocol modes, session identity, and viewport size. Active and retained terminals receive the same global preference.
- Settings writes are ordered, durable, migrated, and recoverable on failure.
- All native ABIs, frame-parser tests, Kotlin tests, Go tests, Android builds, and release signature checks pass.
- Repeat desktop behavior on actual PC Mode or a connected-display device. Emulator resizing is not a substitute for that final check.

The existing host-editor navigation assertion failed on v2.3.2 and v2.3.3 in the API 34 emulator. Resolve or explain that behavior during the navigation redesign; do not silently exclude it from the final matrix.

## Approved decisions

The user accepted these defaults:

1. Use a shared workspace shell with Hosts, Sessions, and Settings roots.
2. Include Terminal + Files side by side, but defer multiple-terminal splits.
3. Include presets and one editable Custom theme; defer importing theme files and per-host overrides.
4. Prefer explicit navigation; keep existing navigation gestures as an optional mode.
5. Preserve session semantics and the current terminal appearance for upgraded users.

## References

Official Android guidance reviewed for this proposal:

- [Window size classes](https://developer.android.com/develop/ui/compose/layouts/adaptive/use-window-size-classes).
- [Desktop windowing](https://developer.android.com/develop/ui/compose/layouts/adaptive/support-desktop-windowing).
- [Input compatibility on large screens](https://developer.android.com/develop/ui/compose/touch-input/input-compatibility-on-large-screens).

Pinned terminal API:

- [Ghostty terminal color defaults and OSC overrides](https://github.com/ghostty-org/ghostty/blob/0ba6250388641f52135414b38c4259aa682c489b/include/ghostty/vt/terminal.h).
- [Ghostty render state colors](https://github.com/ghostty-org/ghostty/blob/0ba6250388641f52135414b38c4259aa682c489b/include/ghostty/vt/render.h).
