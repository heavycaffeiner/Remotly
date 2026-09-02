package main

import (
	"os"
	"time"

	"golang.org/x/term"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// resizePollInterval is how often the console is measured on Windows.
//
// Fast enough that a resize settles before the user types into the new shape,
// slow enough to be free: it is two syscalls per tick against a handle that is
// already open.
const resizePollInterval = 500 * time.Millisecond

// watchResize forwards terminal size changes to the session and returns a
// stop function.
//
// Windows has no SIGWINCH, so the console size is polled and only a real
// change is sent. Sending unconditionally would repaint a full-screen
// application twice a second.
func watchResize(stream *localctl.AttachStream) func() {
	done := make(chan struct{})
	go func() {
		lastCols, lastRows := localctl.TerminalSize(os.Stdout, term.GetSize)
		ticker := time.NewTicker(resizePollInterval)
		defer ticker.Stop()
		for {
			select {
			case <-ticker.C:
				cols, rows := localctl.TerminalSize(os.Stdout, term.GetSize)
				if cols == lastCols && rows == lastRows {
					continue
				}
				lastCols, lastRows = cols, rows
				_ = stream.Resize(cols, rows)
			case <-done:
				return
			}
		}
	}()
	return func() { close(done) }
}
