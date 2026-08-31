//go:build linux

package service

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// fakeRunner records every call and returns canned output keyed by the
// command line. It lets the manager logic be tested without a live systemd.
type fakeRunner struct {
	calls     []string
	responses map[string]string
}

func (f *fakeRunner) run(name string, args ...string) (string, error) {
	line := name + " " + strings.Join(args, " ")
	f.calls = append(f.calls, line)
	if r, ok := f.responses[line]; ok {
		return r, nil
	}
	return "", nil
}

func (f *fakeRunner) called(sub string) bool {
	for _, c := range f.calls {
		if strings.Contains(c, sub) {
			return true
		}
	}
	return false
}

// isolate points the systemd user directory at a temp location for the test.
func isolate(t *testing.T) string {
	t.Helper()
	xdg := t.TempDir()
	t.Setenv("XDG_CONFIG_HOME", xdg)
	return xdg
}

func TestLinuxInstallWritesUnitAndEnables(t *testing.T) {
	isolate(t)
	fake := &fakeRunner{}
	m := linuxManager{run: fake.run}
	if err := m.Install("/opt/remotly/remotly", "/tmp/daemon.log"); err != nil {
		t.Fatalf("Install: %v", err)
	}
	p, _ := unitPath()
	content, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("unit not written: %v", err)
	}
	unit := string(content)
	if !strings.Contains(unit, "ExecStart=/opt/remotly/remotly run --log-file /tmp/daemon.log") {
		t.Fatalf("unexpected ExecStart in:\n%s", unit)
	}
	if !strings.Contains(unit, "WantedBy=default.target") {
		t.Fatalf("missing WantedBy in:\n%s", unit)
	}
	if !fake.called("daemon-reload") {
		t.Fatal("expected daemon-reload")
	}
	if !fake.called("enable remotly.service") {
		t.Fatal("expected enable")
	}
}

func TestLinuxInstallQuotedSpaces(t *testing.T) {
	isolate(t)
	fake := &fakeRunner{}
	m := linuxManager{run: fake.run}
	bin := "/home/u/App Data/remotly/remotly"
	logf := "/home/u/App Data/remotly/daemon.log"
	if err := m.Install(bin, logf); err != nil {
		t.Fatalf("Install: %v", err)
	}
	p, _ := unitPath()
	content, _ := os.ReadFile(p)
	if !strings.Contains(string(content), `"/home/u/App Data/remotly/remotly"`) {
		t.Fatalf("spaced binary not quoted in:\n%s", content)
	}
}

func TestLinuxStartStopUninstallOrder(t *testing.T) {
	isolate(t)
	fake := &fakeRunner{}
	m := linuxManager{run: fake.run}
	if err := m.Install("/opt/remotly/remotly", "/tmp/log"); err != nil {
		t.Fatal(err)
	}
	fake.calls = nil
	if err := m.Start(); err != nil {
		t.Fatal(err)
	}
	if !fake.called("start remotly.service") {
		t.Fatal("expected start")
	}
	fake.calls = nil
	if err := m.Stop(); err != nil {
		t.Fatal(err)
	}
	if !fake.called("stop remotly.service") {
		t.Fatal("expected stop")
	}
	fake.calls = nil
	if err := m.Uninstall(); err != nil {
		t.Fatal(err)
	}
	if !fake.called("disable --now remotly.service") {
		t.Fatal("expected disable --now")
	}
	p, _ := unitPath()
	if _, err := os.Stat(p); !os.IsNotExist(err) {
		t.Fatal("unit file should be removed after uninstall")
	}
}

func TestLinuxState(t *testing.T) {
	xdg := isolate(t)
	bin := "/opt/remotly/remotly"
	// Write the unit file directly so State can read it back.
	if err := os.MkdirAll(filepath.Join(xdg, "systemd", "user"), 0o700); err != nil {
		t.Fatal(err)
	}
	unitPath_, _ := unitPath()
	if err := os.WriteFile(unitPath_, []byte(LinuxUnit(bin, "/tmp/log")), 0o600); err != nil {
		t.Fatal(err)
	}
	fake := &fakeRunner{responses: map[string]string{
		"systemctl --user is-active remotly.service":               "active",
		"systemctl --user show remotly.service -p MainPID --value": "4242",
	}}
	m := linuxManager{run: fake.run}
	st, err := m.State()
	if err != nil {
		t.Fatal(err)
	}
	if !st.Installed || !st.Running {
		t.Fatalf("expected installed+running, got %+v", st)
	}
	if st.Binary != bin {
		t.Fatalf("binary %q want %q", st.Binary, bin)
	}
	if st.PID != 4242 {
		t.Fatalf("pid %d want 4242", st.PID)
	}
}

func TestLinuxStateInactive(t *testing.T) {
	isolate(t)
	fake := &fakeRunner{responses: map[string]string{
		"systemctl --user is-active remotly.service": "inactive",
	}}
	m := linuxManager{run: fake.run}
	st, _ := m.State()
	if st.Installed || st.Running {
		t.Fatalf("expected not installed/not running, got %+v", st)
	}
}

func TestLinuxBinaryPath(t *testing.T) {
	xdg := isolate(t)
	bin := "/opt/remotly/remotly"
	if err := os.MkdirAll(filepath.Join(xdg, "systemd", "user"), 0o700); err != nil {
		t.Fatal(err)
	}
	p, _ := unitPath()
	if err := os.WriteFile(p, []byte(LinuxUnit(bin, "/tmp/log")), 0o600); err != nil {
		t.Fatal(err)
	}
	m := linuxManager{run: (&fakeRunner{}).run}
	got, ok, err := m.BinaryPath()
	if err != nil || !ok || got != bin {
		t.Fatalf("BinaryPath got (%q,%v,%v) want (%q,true,nil)", got, ok, err, bin)
	}
}
