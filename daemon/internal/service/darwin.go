//go:build darwin

package service

import (
	"fmt"
	"os"
	"os/user"
	"path/filepath"
	"strings"

	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
)

// Every platform launches the daemon as `<binary> run --log-file <file>` so the
// daemon writes its own log under the 0700 data directory and no platform
// relies on stdout capture. The Windows scheduled task has no file-redirect
// facility, so this keeps all three logging uniformly.

// darwinManager drives the daemon through a per-user launchd LaunchAgent.
// RunAtLoad starts it at login and KeepAlive restarts it on exit, which gives
// reboot persistence and crash recovery in one place. The modern bootstrap /
// bootout / kickstart API is used against the gui/<uid> domain.
type darwinManager struct {
	run runner
}

func newManager() Manager { return darwinManager{run: realRunner} }

func plistPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", DarwinPlistName), nil
}

// currentUID returns the numeric uid of the current user.
func currentUID() int {
	u, err := user.Current()
	if err != nil {
		return -1
	}
	if n, err := strconvAtoi(u.Uid); err == nil {
		return n
	}
	return -1
}

func (m darwinManager) domain() string {
	return DarwinTarget(currentUID())
}

func (m darwinManager) Install(binary, logFile string) error {
	p, err := plistPath()
	if err != nil {
		return err
	}
	if err := paths.AssertOwned(p); err != nil {
		return err
	}
	home, _ := os.UserHomeDir()
	if err := atomicWrite(p, []byte(DarwinPlistXML(binary, logFile, home)), 0o600); err != nil {
		return err
	}
	// Re-bootstrap: unload the old job (ignore errors for a fresh install),
	// then load the new definition. bootstrap also starts it (RunAtLoad).
	m.run("launchctl", "bootout", m.domain())
	out, err := m.run("launchctl", "bootstrap", m.domain(), p)
	if err != nil {
		return fmt.Errorf("launchctl bootstrap: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m darwinManager) Start() error {
	out, err := m.run("launchctl", "kickstart", "-k", m.domain())
	if err != nil {
		return fmt.Errorf("launchctl kickstart: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m darwinManager) Stop() error {
	out, err := m.run("launchctl", "bootout", m.domain())
	if err != nil {
		return fmt.Errorf("launchctl bootout: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m darwinManager) Uninstall() error {
	out, _ := m.run("launchctl", "bootout", m.domain())
	_ = out
	p, err := plistPath()
	if err == nil {
		if err := paths.AssertOwned(p); err != nil {
			return err
		}
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	return nil
}

func (m darwinManager) State() (State, error) {
	st := State{}
	p, err := plistPath()
	if err == nil {
		if content, rerr := readFile(p); rerr == nil && content != "" {
			st.Installed = true
			if binary, _, ok := plistBinary(content); ok {
				st.Binary = binary
			}
		}
	}
	out, err := m.run("launchctl", "print", m.domain())
	if isUsageError(out, err) {
		st.Detail = "launchd gui domain not available"
		return st, nil
	}
	if strings.Contains(out, "not found") || strings.Contains(out, "Does not exist") {
		st.Detail = "job not loaded"
		return st, nil
	}
	if strings.Contains(out, "state = running") {
		st.Running = true
		if pid := pidFrom(out); pid != 0 {
			st.PID = pid
		}
	} else {
		st.Detail = "job loaded, not running"
	}
	return st, nil
}

func (m darwinManager) BinaryPath() (string, bool, error) {
	p, err := plistPath()
	if err != nil {
		return "", false, err
	}
	content, err := readFile(p)
	if err != nil {
		return "", false, err
	}
	binary, _, ok := plistBinary(content)
	return binary, ok, nil
}
