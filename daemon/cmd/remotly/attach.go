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
