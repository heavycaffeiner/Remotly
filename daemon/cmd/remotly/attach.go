package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"sync/atomic"

	"golang.org/x/term"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// detachLead is Ctrl-A, the prefix of the detach sequence (Ctrl-A then d).
//
// Ctrl-C, Ctrl-D, and Ctrl-Z all belong to the remote program, so detaching
// needs a prefix that is claimed by the attach rather than passed straight
// through. A lone Ctrl-A still reaches the session: it is held back only
// until the next byte says whether it began a detach.
const detachLead = 0x01

// detachHint is the sequence as shown to the user.
const detachHint = "Ctrl-A d"

// The alternate screen buffer. An attach runs on it so the session's output,
// including any clear-screen it emits on the way out, never touches the
// scrollback of the terminal the user launched from. 1049 saves the cursor
// and switches in one sequence, and restores both on the way back.
const enterAltScreen = "\x1b[?1049h"

// restoreTerminal undoes what a session may have left set, then returns to the
// primary screen.
//
// Leaving the alternate screen restores the contents but not the modes: a
// full-screen program that hid the cursor or turned on mouse reporting, and
// then died before its own cleanup ran, would leave the user with an
// invisible cursor and a terminal spraying escape sequences on every click.
// Each of these is a no-op when the mode was never set.
//
// Order matters: the modes are cleared while still on the alternate screen,
// so the primary screen is never written to on the way out.
const restoreTerminal = "" +
	"\x1b[?1000l" + // X11 mouse reporting
	"\x1b[?1002l" + // button-event tracking
	"\x1b[?1003l" + // any-event tracking
	"\x1b[?1006l" + // SGR extended coordinates
	"\x1b[?2004l" + // bracketed paste
	"\x1b[?25h" + // cursor visible
	"\x1b[0m" + // default attributes
	"\x1b[?1049l" // back to the primary screen

// attachTerminal runs a session on this terminal until the user detaches or
// the process exits.
//
// The terminal goes into raw mode so keystrokes reach the remote PTY as they
// are typed: no local line editing, no local echo, and control characters
// (including Ctrl-C) travel to the session rather than acting on this
// process. Detaching is therefore a key sequence rather than a signal.
//
// It returns true when the session's process exited, false when the user
// detached and the session is still running.
func attachTerminal(path, sessionID string) (exited bool, err error) {
	stream, err := localctl.DialAttach(path, sessionID)
	if err != nil {
		return false, err
	}
	defer stream.Close()

	stdin := int(os.Stdin.Fd())
	if term.IsTerminal(stdin) {
		state, rerr := term.MakeRaw(stdin)
		if rerr != nil {
			return false, fmt.Errorf("raw mode: %w", rerr)
		}
		// Restoring is not optional: leaving the terminal raw makes the shell
		// unusable after this returns, including on the error paths.
		defer func() { _ = term.Restore(stdin, state) }()

		// The session draws on the alternate screen, so its output is its own
		// and the terminal is restored on the way out.
		//
		// Without this the session's bytes act on the user's terminal
		// directly: a shell exiting emits a clear-screen and a
		// clear-scrollback, which wiped the terminal the user was working in
		// and left them staring at nothing. Leaving the alternate screen
		// restores what was there before the attach, whatever the session
		// wrote.
		_, _ = os.Stdout.WriteString(enterAltScreen)
		defer func() { _, _ = os.Stdout.WriteString(restoreTerminal) }()
	}

	cols, rows := localctl.TerminalSize(os.Stdout, term.GetSize)
	_ = stream.Resize(cols, rows)

	stopWinch := watchResize(stream)
	defer stopWinch()

	// Input is read on its own goroutine. os.Stdin has no deadline that would
	// let a single loop poll both directions, so the reader is left blocked
	// and the process exits out from under it.
	//
	// detached is set before that goroutine closes the stream, so the read
	// error closing produces here can be told apart from a real failure.
	var detached atomic.Bool
	go func() { _ = pumpInput(stream, &detached) }()

	for {
		chunk, rerr := stream.Read()
		if rerr != nil {
			if detached.Load() {
				return false, nil
			}
			if errors.Is(rerr, localctl.ErrStreamClosed) {
				return true, nil
			}
			return false, rerr
		}
		if _, werr := os.Stdout.Write(chunk); werr != nil {
			return false, werr
		}
	}
}

// pumpInput forwards this terminal's input to the session, watching for the
// detach sequence.
func pumpInput(stream *localctl.AttachStream, detached *atomic.Bool) error {
	buf := make([]byte, 4096)
	armed := false
	for {
		n, err := os.Stdin.Read(buf)
		if n > 0 {
			out, detach, nextArmed := filterDetach(buf[:n], armed)
			armed = nextArmed
			if len(out) > 0 {
				if werr := stream.Write(out); werr != nil {
					return werr
				}
			}
			if detach {
				// Set before the close so the output loop reads this as a
				// detach rather than a broken connection. Closing is what
				// wakes that loop; this goroutine cannot return through it.
				detached.Store(true)
				_ = stream.Close()
				return nil
			}
		}
		if err != nil {
			if errors.Is(err, io.EOF) {
				return nil
			}
			return err
		}
	}
}

// filterDetach strips the detach sequence from in and reports whether it was
// completed. armed carries a prefix byte seen at the end of a previous chunk,
// because the sequence can be split across reads.
//
// A prefix not followed by d is emitted with the byte that followed it, in
// order, so Ctrl-A keeps working as beginning-of-line in a shell.
func filterDetach(in []byte, armed bool) (out []byte, detach bool, nextArmed bool) {
	out = make([]byte, 0, len(in))
	for _, b := range in {
		if armed {
			armed = false
			if b == 'd' {
				return out, true, false
			}
			if b == detachLead {
				// Two prefixes in a row: the first was ordinary input and the
				// second may still begin a detach, so emit one and stay armed.
				out = append(out, detachLead)
				armed = true
				continue
			}
			out = append(out, detachLead, b)
			continue
		}
		if b == detachLead {
			armed = true
			continue
		}
		out = append(out, b)
	}
	return out, false, armed
}
