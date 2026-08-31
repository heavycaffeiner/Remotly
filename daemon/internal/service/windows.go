//go:build windows

package service

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
)

// windowsManager drives the daemon through a per-user scheduled task. The task
// is created with a logon trigger and InteractiveToken, so it runs in the
// user's interactive session at logon (reboot persistence, login-shell
// inheritance). RestartOnFailure approximates a keep-alive and
// MultipleInstancesPolicy=IgnoreNew prevents a duplicate daemon.
type windowsManager struct {
	run runner
}

func newManager() Manager { return windowsManager{run: realRunner} }

// taskName is the task name used with /TN. A flat name in the root task
// folder avoids needing to create a task folder.
func taskName() string { return WindowsTask }

// taskXMLPath is where the task XML is staged. It lives in the per-user temp
// directory; the file is removed after schtasks consumes it.
func taskXMLPath() (string, error) {
	return filepath.Join(os.TempDir(), "remotly-daemon-task.xml"), nil
}

func (m windowsManager) Install(binary, logFile string) error {
	home, _ := os.UserHomeDir()
	tmp, err := taskXMLPath()
	if err != nil {
		return err
	}
	if err := os.WriteFile(tmp, WindowsTaskXML(binary, logFile, home), 0o600); err != nil {
		return err
	}
	defer os.Remove(tmp)
	// /Create /F replaces an existing task (upgrade); /TN names it; /XML
	// supplies the definition we authored.
	out, err := m.run("schtasks.exe", "/Create", "/F", "/TN", taskName(), "/XML", tmp)
	if err != nil {
		return fmt.Errorf("schtasks create: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m windowsManager) Start() error {
	out, err := m.run("schtasks.exe", "/Run", "/TN", taskName())
	if err != nil {
		return fmt.Errorf("schtasks run: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m windowsManager) Stop() error {
	out, err := m.run("schtasks.exe", "/End", "/TN", taskName())
	if err != nil {
		return fmt.Errorf("schtasks end: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m windowsManager) Uninstall() error {
	out, _ := m.run("schtasks.exe", "/End", "/TN", taskName())
	_ = out
	out, err := m.run("schtasks.exe", "/Delete", "/F", "/TN", taskName())
	if err != nil {
		return fmt.Errorf("schtasks delete: %v: %s", err, strings.TrimSpace(out))
	}
	return nil
}

func (m windowsManager) State() (State, error) {
	st := State{}
	list, err := m.run("schtasks.exe", "/Query", "/TN", taskName(), "/FO", "CSV")
	if isUsageError(list, err) {
		st.Detail = "schtasks not available"
		return st, nil
	}
	if err != nil {
		st.Detail = "task not found"
		return st, nil
	}
	st.Installed = true
	if strings.Contains(list, "Running") {
		st.Running = true
	}
	// The command line is read from the task XML we authored, which is stable
	// to parse (unlike the CSV /V layout).
	if xml, err := m.run("schtasks.exe", "/Query", "/XML", "/TN", taskName()); err == nil {
		if binary, ok := taskBinaryXML(xml); ok {
			st.Binary = binary
		}
	}
	return st, nil
}

func (m windowsManager) BinaryPath() (string, bool, error) {
	xml, err := m.run("schtasks.exe", "/Query", "/XML", "/TN", taskName())
	if err != nil {
		return "", false, nil
	}
	binary, ok := taskBinaryXML(xml)
	return binary, ok, nil
}
