// The key sequences that move around a terminal multiplexer.
//
// Sent as bytes into the attached pty rather than driven over the herdr socket
// API: a gesture has to land in the frame it was made, and a socket call is a
// fresh SSH exec channel plus a snapshot read to work out what "next" means.
// The multiplexer already knows.

/** What a gesture asks the multiplexer to do. */
export type MuxAction =
  | 'tab-next'
  | 'tab-previous'
  | 'pane-next'
  | 'pane-previous'
  | 'workspace-next'
  | 'workspace-previous';

/** Ctrl+B, herdr's prefix. Every binding below is reached through it. */
const PREFIX = 0x02;

const ESC = 0x1b;
const CR = 0x0d;
const TAB = 0x09;

/** CSI Z, which is what a terminal sends for Shift+Tab. */
const SHIFT_TAB = [ESC, 0x5b, 0x5a];
const ARROW_UP = [ESC, 0x5b, 0x41];
const ARROW_DOWN = [ESC, 0x5b, 0x42];

/**
 * The bytes for one action, against herdr's default bindings.
 *
 * Tabs and panes have bindings: `prefix+n` / `prefix+p` cycle tabs and
 * `prefix+tab` / `prefix+shift+tab` cycle panes. Workspaces have none, both
 * `next_workspace` and `previous_workspace` ship unbound, so those go through
 * the picker instead: `prefix+w` opens it, an arrow moves the selection, and
 * Enter confirms.
 */
export function herdrKeys(action: MuxAction): Uint8Array {
  switch (action) {
    case 'tab-next':
      return Uint8Array.from([PREFIX, 0x6e]);
    case 'tab-previous':
      return Uint8Array.from([PREFIX, 0x70]);
    case 'pane-next':
      return Uint8Array.from([PREFIX, TAB]);
    case 'pane-previous':
      return Uint8Array.from([PREFIX, ...SHIFT_TAB]);
    case 'workspace-next':
      return Uint8Array.from([PREFIX, 0x77, ...ARROW_DOWN, CR]);
    case 'workspace-previous':
      return Uint8Array.from([PREFIX, 0x77, ...ARROW_UP, CR]);
  }
}
