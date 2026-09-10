// The key sequences that move around a terminal multiplexer.
//
// Sent as bytes into the attached pty rather than driven over the herdr socket
// API: a gesture has to land in the frame it was made, and a socket call is a
// fresh SSH exec channel plus a snapshot read to work out what "next" means.
// The multiplexer already knows.
//
// Workspace moves are the exception and are not here: herdr ships
// `next_workspace` and `previous_workspace` unbound, so there is nothing to
// type. Those are made over the socket by the screen that owns the terminal.

/** What a gesture asks the multiplexer to do. */
export type MuxAction =
  | 'tab-next'
  | 'tab-previous'
  | 'pane-next'
  | 'pane-previous'
  | 'workspace-next'
  | 'workspace-previous';

/** The moves herdr has a binding for, which are the ones that go as keys. */
export type MuxKeyAction = Exclude<
  MuxAction,
  'workspace-next' | 'workspace-previous'
>;

/** Ctrl+B, herdr's prefix. Every binding below is reached through it. */
const PREFIX = 0x02;

const ESC = 0x1b;
const TAB = 0x09;

/** CSI Z, which is what a terminal sends for Shift+Tab. */
const SHIFT_TAB = [ESC, 0x5b, 0x5a];

/**
 * The bytes for one action, against herdr's default bindings.
 *
 * `prefix+n` and `prefix+p` cycle tabs; `prefix+tab` and `prefix+shift+tab`
 * cycle panes.
 */
export function herdrKeys(action: MuxKeyAction): Uint8Array {
  switch (action) {
    case 'tab-next':
      return Uint8Array.from([PREFIX, 0x6e]);
    case 'tab-previous':
      return Uint8Array.from([PREFIX, 0x70]);
    case 'pane-next':
      return Uint8Array.from([PREFIX, TAB]);
    case 'pane-previous':
      return Uint8Array.from([PREFIX, ...SHIFT_TAB]);
  }
}
