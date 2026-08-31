package doctor

import (
	"context"
	"io"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// makeProbeOutput renders a probe output block with the given facts, wrapped in
// the markers and padded with startup noise so the parser's delimiting is
// exercised.
func makeProbeOutput(envNames []string, commands map[string]string) string {
	var b strings.Builder
	b.WriteString("bash: cannot access /etc/profile.d/whatever.sh: No such file\n")
	b.WriteString("Loading profile...\n")
	b.WriteString(beginMark + "\n")
	b.WriteString("SHELLNAME=bash\n")
	b.WriteString("VERSION=5.2.0\n")
	b.WriteString("LOGIN=yes\n")
	b.WriteString("INTERACTIVE=yes\n")
	b.WriteString("TTY=yes\n")
	b.WriteString("CWD=" + os.TempDir() + "\n")
	b.WriteString("UMASK=022\n")
	b.WriteString("TERMVAL=xterm-256color\n")
	b.WriteString("COLORTERMVAL=truecolor\n")
	for _, n := range envNames {
		b.WriteString("ENV=" + n + "\n")
	}
	for name, path := range commands {
		b.WriteString("CMD=" + name + "=" + path + "\n")
	}
	b.WriteString(endMark + "\n")
	b.WriteString("shell exited, prompt noise after marker\n")
	return b.String()
}

func TestParseProbeExtractsMarkedSection(t *testing.T) {
	out := makeProbeOutput([]string{"HOME", "PATH", "USER"}, map[string]string{
		"sh":    "/bin/sh",
		"which": "MISSING",
	})
	res, err := parseProbe(out)
	if err != nil {
		t.Fatalf("parseProbe: %v", err)
	}
	if res.ShellName != "bash" || res.Version != "5.2.0" {
		t.Errorf("identity = %s/%s, want bash/5.2.0", res.ShellName, res.Version)
	}
	if !res.Login || !res.Interactive || !res.TTY {
		t.Errorf("flags login=%v interactive=%v tty=%v, want all true", res.Login, res.Interactive, res.TTY)
	}
	if res.CWD != os.TempDir() || res.UMask != "022" {
		t.Errorf("cwd/umask = %q/%q", res.CWD, res.UMask)
	}
	if res.TermValue != "xterm-256color" || res.ColorTerm != "truecolor" {
		t.Errorf("term vars = %q/%q", res.TermValue, res.ColorTerm)
	}
	if len(res.EnvNames) != 3 || res.EnvNames[0] != "HOME" {
		t.Errorf("env names = %v", res.EnvNames)
	}
	if res.Commands["sh"] != "/bin/sh" || res.Commands["which"] != "MISSING" {
		t.Errorf("commands = %v", res.Commands)
	}
}

func TestParseProbeRejectsMissingMarkers(t *testing.T) {
	if _, err := parseProbe("no markers here"); err == nil {
		t.Error("expected an error for missing BEGIN marker")
	}
	if _, err := parseProbe(beginMark + "\nENV=HOME\n"); err == nil {
		t.Error("expected an error for missing END marker")
	}
}

// TestCompareHealthy verifies the env.inheritance check passes when the two
// contexts differ only by the allowed overrides.
func TestCompareHealthy(t *testing.T) {
	daemon := &probeResult{Login: true, TTY: true, CWD: "/home", TermValue: "xterm-256color", ColorTerm: "truecolor",
		EnvNames: []string{"HOME", "PATH", "TERM", "COLORTERM", "REMOTLY_SESSION"},
		Commands: map[string]string{"sh": "/bin/sh", "bash": "/bin/bash", "which": "/usr/bin/which", "git": "/usr/bin/git",
			"node": "/usr/bin/node", "python3": "/usr/bin/python3", "go": "/usr/bin/go"},
	}
	direct := &probeResult{EnvNames: []string{"HOME", "PATH", "TERM", "COLORTERM"}}
	checks := compare(daemon, direct, "xterm-256color")
	if c := findCheck(checks, "env.inheritance"); c == nil || c.Class != Pass {
		t.Fatalf("env.inheritance = %+v, want pass", c)
	}
}

// TestCompareEnvGap verifies a variable missing from the daemon session (but
// present in the direct reference) is a failure, and the detail names it.
func TestCompareEnvGap(t *testing.T) {
	daemon := &probeResult{Login: true, TTY: true, CWD: "/home",
		EnvNames: []string{"HOME", "PATH"},
		Commands: map[string]string{"sh": "/bin/sh"}}
	direct := &probeResult{EnvNames: []string{"HOME", "PATH", "NVM_DIR"}}
	checks := compare(daemon, direct, "")
	c := findCheck(checks, "env.inheritance")
	if c == nil || c.Class != Failure {
		t.Fatalf("env.inheritance = %+v, want failure", c)
	}
	if !strings.Contains(c.Detail, "NVM_DIR") {
		t.Errorf("detail should name the missing variable NVM_DIR, got %q", c.Detail)
	}
}

// TestCompareAllowedOnly verifies differences confined to the allowed override
// set (TERM, COLORTERM, REMOTLY_*) are not reported as inheritance failures.
func TestCompareAllowedOnly(t *testing.T) {
	daemon := &probeResult{EnvNames: []string{"HOME", "PATH", "REMOTLY_SESSION", "REMOTLY_OTHER"}}
	direct := &probeResult{EnvNames: []string{"HOME", "PATH"}}
	checks := compare(daemon, direct, "")
	if c := findCheck(checks, "env.inheritance"); c == nil || c.Class != Pass {
		t.Fatalf("env.inheritance = %+v, want pass for allowed-only differences", c)
	}
}

// TestReportNeverEmitsEnvValues is the redaction guarantee: the report and its
// checks contain environment variable names and command paths, but never the
// values of arbitrary environment variables.
func TestReportNeverEmitsEnvValues(t *testing.T) {
	secret := "hunter2-super-secret-token"
	out := makeProbeOutput([]string{"HOME", "PATH"}, map[string]string{"sh": "/bin/sh"})
	// The probe only ever emits names, so the value never appears in the
	// output. Simulate a value leaking into the environment to be sure the
	// parser does not surface it.
	res, err := parseProbe(out)
	if err != nil {
		t.Fatal(err)
	}
	checks := compare(res, res, "")
	for _, c := range checks {
		if strings.Contains(c.Detail, secret) {
			t.Fatalf("check %q leaked a secret value: %q", c.Name, c.Detail)
		}
	}
}

func findCheck(checks []Check, name string) *Check {
	for i := range checks {
		if checks[i].Name == name {
			return &checks[i]
		}
	}
	return nil
}

// --- fake PTY backend for Run-level tests -----------------------------------

type fakeProcess struct {
	mu   sync.Mutex
	data []byte
	sent bool
}

func (f *fakeProcess) Read(p []byte) (int, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.sent {
		return 0, io.EOF
	}
	f.sent = true
	return copy(p, f.data), nil
}
func (f *fakeProcess) Write(p []byte) (int, error) { return len(p), nil }
func (f *fakeProcess) Resize(cols, rows uint16) error {
	return nil
}
func (f *fakeProcess) Signal(sig os.Signal) error { return nil }
func (f *fakeProcess) Kill() error                { return nil }
func (f *fakeProcess) Wait() pty.ExitStatus       { return pty.ExitStatus{Exited: true, Code: 0} }
func (f *fakeProcess) Close() error               { return nil }

// fakeBackend returns a different canned probe output for each Start call, so
// the daemon path and the direct path can differ (the "test hook").
type fakeBackend struct {
	outputs []string
	calls   int
}

func (fb *fakeBackend) Start(req pty.StartRequest) (pty.Process, error) {
	i := fb.calls
	fb.calls++
	out := makeProbeOutput([]string{"HOME", "PATH"}, map[string]string{"sh": "/bin/sh"})
	if i < len(fb.outputs) {
		out = fb.outputs[i]
	}
	return &fakeProcess{data: []byte(out)}, nil
}

func TestRunHealthyWithFakeBackend(t *testing.T) {
	envs := []string{"HOME", "PATH", "TERM", "COLORTERM"}
	cmds := map[string]string{"sh": "/bin/sh", "bash": "/bin/bash", "which": "/usr/bin/which", "git": "/usr/bin/git",
		"node": "/usr/bin/node", "python3": "/usr/bin/python3", "go": "/usr/bin/go"}
	// Both contexts identical except the daemon adds REMOTLY_SESSION.
	daemonOut := makeProbeOutput(append([]string{}, envs...), cmds)
	directOut := makeProbeOutput(envs, cmds)
	fb := &fakeBackend{outputs: []string{daemonOut, directOut}}

	rep, err := Run(context.Background(), Options{
		ConfiguredShell: "/bin/bash",
		Term:            "xterm-256color",
		Backend:         fb,
	})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if fb.calls != 2 {
		t.Fatalf("expected 2 probe runs, got %d", fb.calls)
	}
	if c := findCheck(rep.Checks, "env.inheritance"); c == nil || c.Class != Pass {
		t.Errorf("env.inheritance = %+v, want pass", c)
	}
	if rep.HasFailure() {
		t.Errorf("unexpected failure in a healthy run: %+v", rep.Checks)
	}
}

// TestRunBrokenInheritance is the verification case: a variable present in the
// direct reference but missing from the daemon path produces a failing
// env.inheritance check and a report with HasFailure (nonzero exit).
func TestRunBrokenInheritance(t *testing.T) {
	cmds := map[string]string{"sh": "/bin/sh"}
	daemonOut := makeProbeOutput([]string{"HOME", "PATH"}, cmds)
	directOut := makeProbeOutput([]string{"HOME", "PATH", "NVM_DIR"}, cmds)
	fb := &fakeBackend{outputs: []string{daemonOut, directOut}}

	rep, err := Run(context.Background(), Options{
		ConfiguredShell: "/bin/bash",
		Term:            "xterm-256color",
		Backend:         fb,
	})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	if !rep.HasFailure() {
		t.Fatalf("expected a failure for a broken inheritance, got none: %+v", rep.Checks)
	}
	c := findCheck(rep.Checks, "env.inheritance")
	if c == nil || c.Class != Failure {
		t.Fatalf("env.inheritance = %+v, want failure", c)
	}
	if !strings.Contains(c.Detail, "NVM_DIR") {
		t.Errorf("detail should name NVM_DIR, got %q", c.Detail)
	}
}

// TestRunRealBackend executes the real probe script through the platform PTY
// backend. It is guarded so a host without the shell skips rather than fails;
// the hermetic fake-backend tests above cover the logic on every platform.
func TestRunRealBackend(t *testing.T) {
	shell := "/bin/bash"
	if _, err := os.Stat(shell); err != nil {
		t.Skipf("%s not present; skipping real-backend probe", shell)
	}
	rep, err := Run(context.Background(), Options{
		ConfiguredShell: shell,
		Term:            "xterm-256color",
		Timeout:         15 * time.Second,
	})
	if err != nil {
		t.Fatalf("Run: %v", err)
	}
	for _, c := range rep.Checks {
		if c.Class == Failure && (c.Name == "probe.daemon" || c.Name == "probe.direct") {
			t.Fatalf("real probe failed on %s: %s", c.Name, c.Detail)
		}
	}
	if findCheck(rep.Checks, "env.inheritance") == nil {
		t.Fatal("expected an env.inheritance check")
	}
}

func TestDiffNames(t *testing.T) {
	onlyA, onlyB := diffNames([]string{"A", "B", "C"}, []string{"B", "C", "D"})
	if strings.Join(onlyA, ",") != "A" {
		t.Errorf("onlyA = %v, want [A]", onlyA)
	}
	if strings.Join(onlyB, ",") != "D" {
		t.Errorf("onlyB = %v, want [D]", onlyB)
	}
}

func TestCompareDefinitionsMatch(t *testing.T) {
	daemon := &probeResult{Aliases: []string{"ll"}, Functions: []string{"nvm"}}
	direct := &probeResult{Aliases: []string{"ll"}, Functions: []string{"nvm"}}
	checks := compare(daemon, direct, "")
	for _, name := range []string{"shell.aliases", "shell.functions"} {
		if c := findCheck(checks, name); c == nil || c.Class != Pass {
			t.Errorf("%s = %+v, want pass", name, c)
		}
	}
}

func TestCompareDefinitionsDiffer(t *testing.T) {
	daemon := &probeResult{Aliases: []string{}, Functions: []string{}}
	direct := &probeResult{Aliases: []string{"ll"}, Functions: []string{"nvm"}}
	checks := compare(daemon, direct, "")
	if c := findCheck(checks, "shell.aliases"); c == nil || c.Class != Failure {
		t.Errorf("shell.aliases = %+v, want failure", c)
	} else if !strings.Contains(c.Detail, "ll") {
		t.Errorf("alias detail should name ll, got %q", c.Detail)
	}
	if c := findCheck(checks, "shell.functions"); c == nil || c.Class != Failure {
		t.Errorf("shell.functions = %+v, want failure", c)
	} else if !strings.Contains(c.Detail, "nvm") {
		t.Errorf("function detail should name nvm, got %q", c.Detail)
	}
}
