//go:build !windows

package pty

import (
	"errors"
	"io"
	"os"
	"os/exec"
	"sync"
	"syscall"

	creackpty "github.com/creack/pty"
)

// ptyStartWithSize starts cmd on a new PTY of the given size. The creack
// import is aliased because this package is named pty.
func ptyStartWithSize(cmd *exec.Cmd, cols, rows uint16) (*os.File, error) {
	return creackpty.StartWithSize(cmd, &creackpty.Winsize{Rows: rows, Cols: cols})
}

// unixProcess is a Unix PTY process: a child on a pseudo-terminal whose
// master side the daemon reads and writes.
type unixProcess struct {
	cmd  *exec.Cmd
	ptmx *os.File

	closeOnce sync.Once
	closed    chan struct{}

	waitMu sync.Mutex
	waited bool
	status ExitStatus
}

func (p *unixProcess) Read(b []byte) (int, error) {
	n, err := p.ptmx.Read(b)
	if err != nil && (closedErr(err) || errors.Is(err, os.ErrClosed)) {
		return n, io.EOF
	}
	return n, err
}

func (p *unixProcess) Write(b []byte) (int, error) {
	if p.isClosed() {
		return 0, errors.New("pty: process closed")
	}
	return p.ptmx.Write(b)
}

func (p *unixProcess) Resize(cols, rows uint16) error {
	if cols < MinCols || cols > MaxCols || rows < MinRows || rows > MaxRows {
		return errors.New("pty: size out of range")
	}
	return creackpty.Setsize(p.ptmx, &creackpty.Winsize{Rows: rows, Cols: cols})
}

func (p *unixProcess) Signal(sig os.Signal) error {
	if p.cmd.Process == nil {
		return errors.New("pty: process not started")
	}
	return p.cmd.Process.Signal(sig)
}

func (p *unixProcess) Kill() error {
	if p.cmd.Process == nil {
		return nil
	}
	// Kill the session leader. The child was started in its own session;
	// when the PTY closes, the tty hangs up and remaining descendants in
	// the session are terminated by the kernel.
	if err := p.cmd.Process.Kill(); err != nil {
		if errors.Is(err, os.ErrProcessDone) {
			return nil
		}
		return err
	}
	return nil
}

func (p *unixProcess) Wait() ExitStatus {
	p.waitMu.Lock()
	defer p.waitMu.Unlock()
	if p.waited {
		return p.status
	}
	err := p.cmd.Wait()
	st := ExitStatus{Exited: true, Code: -1, WaitErr: err}
	if err == nil {
		st.Code = 0
	} else if ee, ok := err.(*exec.ExitError); ok {
		st.Code = ee.ExitCode()
		if ws, ok := ee.Sys().(syscall.WaitStatus); ok && ws.Signaled() {
			st.Signal = ws.Signal().String()
		}
	}
	p.waited = true
	p.status = st
	return st
}

func (p *unixProcess) Close() error {
	var err error
	p.closeOnce.Do(func() {
		err = p.ptmx.Close()
		if p.closed != nil {
			close(p.closed)
		}
	})
	return err
}

func (p *unixProcess) isClosed() bool {
	select {
	case <-p.closed:
		return true
	default:
		return false
	}
}
