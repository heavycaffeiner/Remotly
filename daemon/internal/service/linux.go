//go:build linux

package service

import (
	"fmt"
	"os"
	"path/filepath"
	"strconv"
	"strings"

	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
)

// linuxManager drives the daemon through the per-user systemd manager. A user
// service persists across reboot only when the user manager is running, which
// on a normal desktop requires a login session; headless persistence needs
// `loginctl enable-linger <user>`, which this installer reports but does not
// force (it may need elevated privileges). See the RN-13 Result section.
type linuxManager struct {
	run runner
}

func newManager() Manager { return linuxManager{run: realRunner} }

func unitPath() (string, error) {
	xdg := os.Getenv("XDG_CONFIG_HOME")
	if xdg == "" {
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		xdg = filepath.Join(home, ".config")
	}
	return filepath.Join(xdg, "systemd", "user", LinuxUnitName), nil
}

func (m linuxManager) Install(binary, logFile string) error {
	p, err := unitPath()
	if err != nil {
		return err
	}
	if err := paths.AssertOwned(p); err != nil {
		return err
	}
	if err := atomicWrite(p, []byte(LinuxUnit(binary, logFile)), 0o600); err != nil {
		return err
	}
	if out, err := m.run("systemctl", "--user", "daemon-reload"); err != nil {
		return fmt.Errorf("systemctl daemon-reload: %v: %s", err, strings.TrimSpace(out))
	}
	out, err := m.run("systemctl", "--user", "enable", LinuxUnitName)
	if err != nil {
		return fmt.Errorf("systemctl enable: %v: %s", err, strings.TrimSpace(out))
	}
	_ = out
	return nil
}

func (m linuxManager) Start() error {
	out, err := m.run("systemctl", "--user", "start", LinuxUnitName)
	if err != nil {
		return fmt.Errorf("systemctl start: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m linuxManager) Stop() error {
	out, err := m.run("systemctl", "--user", "stop", LinuxUnitName)
	if err != nil {
		return fmt.Errorf("systemctl stop: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m linuxManager) Uninstall() error {
	// Stop and disable together, ignoring "not found" for a clean repeated
	// uninstall. Then remove the unit file and reload so the manager forgets it.
	out, _ := m.run("systemctl", "--user", "disable", "--now", LinuxUnitName)
	_ = out
	p, err := unitPath()
	if err == nil {
		if err := paths.AssertOwned(p); err != nil {
			return err
		}
		if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
			return err
		}
	}
	out, _ = m.run("systemctl", "--user", "daemon-reload")
	_ = out
	return nil
}

func (m linuxManager) State() (State, error) {
	st := State{}
	p, err := unitPath()
	if err == nil {
		if content, rerr := readFile(p); rerr == nil {
			if binary, _, ok := parseExecStart(content); ok {
				st.Installed = true
				st.Binary = binary
			} else if content != "" {
				// A unit file exists but is not ours; report it so the user
				// sees a conflict instead of a silent overwrite.
				st.Detail = "unit file present but not written by Remotly"
			}
		}
	}
	out, err := m.run("systemctl", "--user", "is-active", LinuxUnitName)
	if isUsageError(out, err) {
		st.Detail = "systemd user manager not available"
		return st, nil
	}
	switch strings.TrimSpace(out) {
	case "active":
		st.Running = true
		if pidOut, err := m.run("systemctl", "--user", "show", LinuxUnitName, "-p", "MainPID", "--value"); err == nil {
			if pid, aerr := strconv.Atoi(strings.TrimSpace(pidOut)); aerr == nil {
				st.PID = pid
			}
		}
	case "failed":
		st.Detail = "unit is in failed state; run `systemctl --user status " + LinuxUnitName + "`"
	default:
		st.Detail = strings.TrimSpace(out)
	}
	return st, nil
}

func (m linuxManager) BinaryPath() (string, bool, error) {
	p, err := unitPath()
	if err != nil {
		return "", false, err
	}
	content, err := readFile(p)
	if err != nil {
		return "", false, err
	}
	binary, _, ok := parseExecStart(content)
	return binary, ok, nil
}
