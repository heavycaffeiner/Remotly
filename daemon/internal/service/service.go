// Package service installs and controls the Remotly daemon as a per-OS-user
// background service. One daemon per OS user, running in that user's session
// so its PTY sessions inherit the user's login-shell environment (verified by
// `remotly doctor`, RN-12). The mechanism per platform:
//
//	Linux   systemd user service   (~/.config/systemd/user/remotly.service)
//	macOS   launchd LaunchAgent    (~/Library/LaunchAgents/com.remotly.daemon.plist)
//	Windows per-user scheduled task (RemotlyDaemon; InteractiveToken at logon)
//
// Windows uses a per-user scheduled task rather than a Windows service because
// a service runs in session 0 and cannot inherit the user's interactive
// login-shell environment, which the daemon's PTY sessions require. A
// scheduled task with a logon trigger and InteractiveToken runs in the user's
// session, at logon (reboot persistence), with no service-account setup.
package service

import (
	"errors"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
)

// State reports the service's install and run state.
type State struct {
	Installed bool   // the service definition is present
	Running   bool   // the daemon process is active
	PID       int    // process id while running; 0 otherwise
	Binary    string // binary the definition points at; "" when not installed
	Detail    string // one-line platform detail for display
}

// Manager controls the per-user daemon service on the current platform.
// Implementations must be safe to construct without side effects; all
// mutations happen through the methods.
type Manager interface {
	// Install writes the service definition pointing at binaryPath and
	// logFile, atomically, and enables it for reboot persistence. It does not
	// start the process. It is idempotent: repeating it rewrites the
	// definition in place. It refuses to replace a definition that exists but
	// is owned by another user (ownership attack).
	Install(binaryPath, logFile string) error
	// Start brings an installed service up.
	Start() error
	// Stop brings the service down, leaving the definition installed.
	Stop() error
	// Uninstall stops the service and removes the definition. It never touches
	// config or data directories.
	Uninstall() error
	// State reports install and run state. It is best-effort: a missing
	// service manager yields a State with Detail explaining why, not an error,
	// so `status` stays readable.
	State() (State, error)
	// BinaryPath returns the binary the installed definition points at and
	// whether one is installed. It reads the definition file, so it works even
	// when the service manager is unavailable.
	BinaryPath() (string, bool, error)
}

// New returns the platform Manager.
func New() Manager {
	return newManager()
}

// SelfPath returns the absolute, validated path of the running binary.
func SelfPath() (string, error) { return selfPath() }

// runner executes a program with an argument list and returns its combined
// output. It is the single seam for all service-manager calls: every
// implementation passes an argument slice (never a shell string), so no call
// is injectable. Tests substitute a fake runner.
type runner func(name string, args ...string) (string, error)

// realRunner runs the program via the OS exec API.
func realRunner(name string, args ...string) (string, error) {
	out, err := exec.Command(name, args...).CombinedOutput()
	return string(out), err
}

// isUsageError reports whether the exec failure was "command not found" (no
// service manager), as opposed to a real operation error. Callers use it to
// keep `status` readable when the platform has no user service manager.
func isUsageError(out string, err error) bool {
	var ee *exec.Error
	if err != nil && errors.As(err, &ee) {
		return true
	}
	return false
}

// selfPath returns the absolute path of the running binary, validated to be a
// regular file. It is what Install embeds in the service definition.
func selfPath() (string, error) {
	p, err := os.Executable()
	if err != nil {
		return "", fmt.Errorf("resolve self: %w", err)
	}
	p, err = filepath.Abs(p)
	if err != nil {
		return "", err
	}
	fi, err := os.Lstat(p)
	if err != nil {
		return "", fmt.Errorf("stat self %s: %w", p, err)
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		return "", fmt.Errorf("self is a symlink; refusing to install it: %s", p)
	}
	if !fi.Mode().IsRegular() {
		return "", fmt.Errorf("self is not a regular file: %s", p)
	}
	return p, nil
}

// atomicWrite writes data to path via a temp file in the same directory plus
// rename, so a crash never leaves a half-written service definition. On
// Windows, rename over an existing file fails; we remove the target first in
// that case (the brief missing window is acceptable for a definition we
// rewrite immediately).
func atomicWrite(path string, data []byte, perm os.FileMode) error {
	dir := filepath.Dir(path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	tmp, err := os.CreateTemp(dir, ".remotly-def-*.tmp")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer os.Remove(tmpName) // no-op after a successful rename
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(perm); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	if err := os.Rename(tmpName, path); err != nil {
		if os.Remove(path); err != nil && !os.IsNotExist(err) {
			return err
		}
		if err := os.Rename(tmpName, path); err != nil {
			return err
		}
	}
	return nil
}

// readFile reads a definition file, returning "" when it is absent.
func readFile(path string) (string, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		if os.IsNotExist(err) {
			return "", nil
		}
		return "", err
	}
	return string(b), nil
}
