//go:build !windows

package main

import (
	"os"
	"os/signal"
	"syscall"

	"golang.org/x/term"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// watchResize forwards terminal size changes to the session and returns a
// stop function.
//
// SIGWINCH is the exact signal, so nothing is polled: the size is read only
// when the terminal reports that it changed.
func watchResize(stream *localctl.AttachStream) func() {
	ch := make(chan os.Signal, 1)
	signal.Notify(ch, syscall.SIGWINCH)
	done := make(chan struct{})
	go func() {
		for {
			select {
			case <-ch:
				cols, rows := localctl.TerminalSize(os.Stdout, term.GetSize)
				_ = stream.Resize(cols, rows)
			case <-done:
				return
			}
		}
	}()
	return func() {
		signal.Stop(ch)
		close(done)
	}
}
