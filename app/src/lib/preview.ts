// App-side preview sanitizer.
//
// The daemon sanitizes session previews, but the daemon is an untrusted peer
// across the wire: a compromised or buggy daemon could still send escape
// sequences or control bytes in a preview. The overview renders previews as
// plain text, so this module re-sanitizes every preview before display
// (defense in depth) using the same semantics as the daemon: strip escape
// sequences and C0/C1 controls, collapse horizontal whitespace, and truncate
// to a bounded size on a code point boundary.
//
// Input is any string from the trust boundary (JSON-decoded, so surrogate
// pairs are possible). Output is always a single plain-text line of at most
// MAX_PREVIEW_BYTES UTF-8 bytes.

export const MAX_PREVIEW_BYTES = 120;

// UTF-8 encoded length of one code point.
function utf8Len(cp: number): number {
  if (cp < 0x80) return 1;
  if (cp < 0x800) return 2;
  if (cp < 0x10000) return 3;
  return 4;
}

// Removes escape sequences introduced by ESC: two-byte escapes, CSI
// sequences (ESC [ ... final byte), and OSC strings (ESC ] ... BEL or ST).
// The state machine mirrors the daemon's so the two sides agree on what a
// preview may contain.
function stripEscapes(input: string): string {
  let out = '';
  // 0 normal, 1 just after ESC, 2 inside CSI, 3 inside OSC.
  let esc = 0;
  for (const ch of input) {
    const cp = ch.codePointAt(0)!;
    if (esc !== 0) {
      switch (esc) {
        case 1:
          if (cp === 0x1b) {
            // a fresh ESC restarts the escape
          } else if (cp === 0x5b) {
            esc = 2;
          } else if (cp === 0x5d) {
            esc = 3;
          } else if (cp === 0x07 || (cp >= 0x40 && cp <= 0x7e)) {
            esc = 0;
          } else if (cp >= 0x20 && cp <= 0x3f) {
            esc = 2;
          } else {
            // Not an escape after all: keep the character.
            esc = 0;
            out += ch;
          }
          break;
        case 2:
          if (cp >= 0x40 && cp <= 0x7e) {
            esc = 0;
          } else if (cp === 0x1b || (cp >= 0x20 && cp <= 0x3f)) {
            // ST start or CSI parameters: keep consuming
          } else {
            // Plain text: the sequence ended early; keep the character.
            esc = 0;
            out += ch;
          }
          break;
        case 3:
          if (cp === 0x07) {
            esc = 0;
          } else if (cp === 0x1b) {
            esc = 1;
          }
          break;
      }
      continue;
    }
    if (cp === 0x1b) {
      esc = 1;
      continue;
    }
    out += ch;
  }
  return out;
}

// Sanitizes one preview line: strips escape sequences, drops C0 controls
// (tabs and spaces become the collapsed space), drops DEL and C1 controls,
// collapses any run of whitespace to a single space, trims, and truncates to
// MAX_PREVIEW_BYTES UTF-8 bytes without splitting a code point.
export function sanitizePreview(input: string): string {
  const cleaned = stripEscapes(input);
  let out = '';
  let bytes = 0;
  let lastSpace = false;
  for (const ch of cleaned) {
    const cp = ch.codePointAt(0)!;
    if (cp === 0x09 || cp === 0x20 || ch.trim() === '') {
      // Any whitespace (tab, space, or a Unicode space) collapses to one
      // space. ch.trim()==='' covers non-ASCII space code points.
      if (!lastSpace) {
        out += ' ';
        bytes += 1;
        lastSpace = true;
      }
      continue;
    }
    if (cp < 0x20 || cp === 0x7f || (cp >= 0x80 && cp <= 0x9f)) {
      // Remaining C0 controls, DEL, and C1 controls.
      continue;
    }
    const size = utf8Len(cp);
    if (bytes + size > MAX_PREVIEW_BYTES) break;
    out += ch;
    bytes += size;
    lastSpace = false;
  }
  return out.trim();
}

// Bounds the number of preview bytes retained anywhere in the overview.
export function previewByteLength(text: string): number {
  let bytes = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0)!;
    bytes += utf8Len(cp);
    if (bytes > MAX_PREVIEW_BYTES) return bytes;
  }
  return bytes;
}
