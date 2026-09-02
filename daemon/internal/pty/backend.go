// Package pty provides production PTY process backends for Linux, macOS,
// and Windows. The contract is deliberately narrow: start a shell (or a
// command through the shell) in a terminal-sized PTY, move bytes, resize,
// signal, wait, and close. Session identity and metadata live in the session
// package, which consumes this contract without platform knowledge.
package pty

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"strings"
)

// Dimension limits. Anything outside is a client or configuration error,
// not a backend concern.
const (
	MinCols   = 1
	MaxCols   = 1000
	MinRows   = 1
	MaxRows   = 1000
	maxEnvLen = 1 << 20 // total environment bytes
)

// StartRequest fully describes a PTY process. All fields are validated
// before any system call; a bad request never produces a half-started
// process.
type StartRequest struct {
	// Program is an absolute path to the shell executable.
	Program string
	// Args are the shell's own arguments, for example ["-l"] or
	// ["-i", "-l"]. Never contains the Command; the backend appends the
	// command form for the platform.
	Args    []string
	Command string // optional command to run through the shell and exit
	// KeepShell drops back to an interactive shell after Command finishes
	// instead of ending the session with it. Set for sessions a user drives
	// from a terminal; an agent session leaves it false, because its exit is
	// what tells the app the stream ended.
	KeepShell bool
	Cwd       string
	Env       []string // full KEY=VALUE environment, already overridden
	Cols      uint16
	Rows      uint16
}

// ExitStatus is the terminal state of a PTY process.
type ExitStatus struct {
	Exited bool
	// Code is the process exit code, or -1 when the process was terminated
	// by a signal or could not be determined.
	Code int
	// Signal names the terminating signal on platforms that report one.
	Signal string
	// WaitErr is the raw wait error, for logging. It never contains
	// terminal content.
	WaitErr error
}

// Process is a running PTY process. Read is intended for a single drain
// goroutine (the session manager); Write, Resize, Signal, and Kill are safe
// to call concurrently from any goroutine. Close is idempotent.
type Process interface {
	Read(p []byte) (int, error)
	Write(p []byte) (int, error)
	Resize(cols, rows uint16) error
	// Signal sends a graceful stop. On Unix this is the given signal to the
	// process; on Windows Interrupt maps to a console Ctrl-C event and other
	// signals terminate the process tree.
	Signal(sig os.Signal) error
	// Kill forces termination, including descendants, and is idempotent.
	Kill() error
	// Wait blocks until the process has fully exited.
	Wait() ExitStatus
	// Close releases transport resources (PTY handles). It is safe to call
	// at any time, including concurrently with Wait and Kill, and at most
	// one call performs the release.
	Close() error
}

// Backend starts PTY processes.
type Backend interface {
	Start(req StartRequest) (Process, error)
}

// Validate checks a StartRequest before the platform backend is invoked.
func Validate(req StartRequest) error {
	if !filepath.IsAbs(req.Program) {
		return errors.New("pty: program must be an absolute path")
	}
	if len(req.Program) > 4096 || strings.ContainsRune(req.Program, '\x00') {
		return errors.New("pty: program path is invalid")
	}
	if req.Command != "" {
		if len(req.Command) > 8192 || strings.ContainsRune(req.Command, '\x00') {
			return errors.New("pty: command is invalid")
		}
	}
	if req.Cols < MinCols || req.Cols > MaxCols || req.Rows < MinRows || req.Rows > MaxRows {
		return fmt.Errorf("pty: size %dx%d out of range", req.Cols, req.Rows)
	}
	if len(req.Env) == 0 {
		return errors.New("pty: empty environment")
	}
	total := 0
	for _, kv := range req.Env {
		total += len(kv)
		if i := strings.IndexByte(kv, '='); i <= 0 {
			return errors.New("pty: environment entry must be KEY=VALUE")
		} else if strings.ContainsRune(kv[:i], '\x00') {
			return errors.New("pty: environment key is invalid")
		}
	}
	if total > maxEnvLen {
		return errors.New("pty: environment exceeds size limit")
	}
	return nil
}

// ValidateCwd resolves and validates a session working directory. An empty
// value means the user's home. The result is absolute.
func ValidateCwd(cwd string) (string, error) {
	if cwd == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", fmt.Errorf("pty: resolve home: %w", err)
		}
		cwd = home
	}
	if !filepath.IsAbs(cwd) {
		return "", errors.New("pty: cwd must be an absolute path")
	}
	cwd = filepath.Clean(cwd)
	fi, err := os.Lstat(cwd)
	if err != nil {
		return "", fmt.Errorf("pty: cwd: %w", err)
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		// Follow the link but verify the target is a real directory the
		// process can enter; a symlink to a directory is a normal user setup.
		cwd, err = filepath.EvalSymlinks(cwd)
		if err != nil {
			return "", fmt.Errorf("pty: cwd: %w", err)
		}
		fi, err = os.Stat(cwd)
		if err != nil {
			return "", fmt.Errorf("pty: cwd: %w", err)
		}
	}
	if !fi.IsDir() {
		return "", errors.New("pty: cwd is not a directory")
	}
	if err := access(cwd); err != nil {
		return "", fmt.Errorf("pty: cwd not accessible: %w", err)
	}
	return cwd, nil
}

// AccessDir is a hook for tests and the Windows backend.
var AccessDir = access

func access(dir string) error {
	f, err := os.Open(dir)
	if err != nil {
		return err
	}
	_, err = f.Stat()
	return errors.Join(err, f.Close())
}

// ReadOnce reads a chunk, mapping the platform's "slave side closed" error
// to io.EOF so callers see a clean end of stream.
func ReadOnce(p []byte, r io.Reader) (int, error) {
	n, err := r.Read(p)
	if err != nil && closedErr(err) {
		return n, io.EOF
	}
	return n, err
}
