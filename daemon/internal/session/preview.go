package session

import (
	"unicode"
	"unicode/utf8"
)

// previewTailBytes bounds how much of the ring tail is scanned for a
// preview line. The last retained line rarely exceeds this.
const previewTailBytes = 512

// previewFromTail derives a plain-text preview from the tail of a session's
// retained output: the last line (a line ends at LF; a trailing LF yields an
// empty preview only when there is no earlier line), with escape sequences,
// C0 and C1 controls stripped, horizontal whitespace collapsed, and the
// result truncated to MaxPreviewLen bytes on a rune boundary. The result is
// always valid UTF-8 and never contains control bytes.
func previewFromTail(tail []byte) string {
	if len(tail) == 0 {
		return ""
	}
	// The last complete line: after the final LF if one is present, else the
	// whole tail (the still-running line).
	start := 0
	for i := len(tail) - 1; i >= 0; i-- {
		if tail[i] == '\n' {
			start = i + 1
			break
		}
	}
	if start == len(tail) {
		// The tail ended exactly at a line boundary; the preview is the
		// line before it.
		end := len(tail)
		for end > 0 && tail[end-1] != '\n' {
			end--
		}
		if end == 0 {
			return ""
		}
		prevStart := 0
		for i := end - 2; i >= 0; i-- {
			if tail[i] == '\n' {
				prevStart = i + 1
				break
			}
		}
		return sanitizeLine(tail[prevStart:end])
	}
	return sanitizeLine(tail[start:])
}

// MaxPreviewLen mirrors protocol.MaxPreviewLen without an import cycle.
const MaxPreviewLen = 120

// sanitizeLine strips everything that is not printable text: escape
// sequences (ESC introducers, CSI, OSC, and lone escapes), C0 controls, and
// C1 controls, keeps horizontal whitespace collapsed to single spaces, and
// truncates to MaxPreviewLen bytes without splitting a rune.
func sanitizeLine(b []byte) string {
	var out []byte
	var lastRune rune
	// Escape sequence state: 0 normal, 1 just after ESC, 2 inside a CSI
	// sequence, 3 inside an OSC string (payload may contain any printable
	// byte, so only BEL or ST terminates it).
	esc := 0
	for i := 0; i < len(b); {
		c := b[i]
		if esc != 0 {
			switch esc {
			case 1:
				switch {
				case c == 0x1b: // a fresh ESC restarts the escape
				case c == 0x5b: // CSI introducer
					esc = 2
				case c == 0x5d: // OSC introducer
					esc = 3
				case c == 0x07, c >= 0x40 && c <= 0x7e: // two-byte escape or stray BEL
					esc = 0
				case c >= 0x20 && c <= 0x3f:
					esc = 2
				default:
					// Not an escape byte after all: drop the ESC and re-process.
					esc = 0
					i--
				}
			case 2:
				switch {
				case c >= 0x40 && c <= 0x7e: // final byte (also ST's backslash)
					esc = 0
				case c == 0x1b, c >= 0x20 && c <= 0x3f: // ST start, params
				default:
					// A plain text byte: the sequence ended early; re-process.
					esc = 0
					i--
				}
			case 3:
				switch {
				case c == 0x07: // OSC terminator BEL
					esc = 0
				case c == 0x1b: // ST start or a fresh escape
					esc = 1
				}
			}
			i++
			continue
		}
		if c == 0x1b {
			esc = 1
			i++
			continue
		}
		if c < 0x20 || c == 0x7f {
			if c == '\t' || c == ' ' {
				if !isSpaceRune(lastRune) {
					out = append(out, ' ')
				}
				lastRune = ' '
			}
			i++
			continue
		}
		if c == 0x9b {
			// C1 set-graph separator, commonly used where ESC [ would be.
			i++
			continue
		}
		r, size := utf8.DecodeRune(b[i:])
		if r == utf8.RuneError && size == 1 {
			i++
			continue
		}
		if r >= 0x80 && r <= 0x9f {
			// C1 controls decoded from legacy encodings.
			i += size
			continue
		}
		if unicode.IsSpace(r) {
			if !isSpaceRune(lastRune) {
				out = append(out, ' ')
			}
			lastRune = ' '
			i += size
			continue
		}
		if len(out)+size > MaxPreviewLen {
			break
		}
		out = append(out, b[i:i+size]...)
		lastRune = r
		i += size
	}
	// Trim the leading and trailing spaces the collapsing produced.
	for len(out) > 0 && out[0] == ' ' {
		out = out[1:]
	}
	for len(out) > 0 && out[len(out)-1] == ' ' {
		out = out[:len(out)-1]
	}
	return string(out)
}

func isSpaceRune(r rune) bool {
	return r == ' '
}
