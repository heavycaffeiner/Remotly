// Package doctor implements `remotly doctor`: a diagnostic that compares the
// environment of a daemon-path PTY session with a directly launched login
// shell and reports environment-inheritance differences. It never prints
// environment values (names only) and never edits user configuration.
package doctor

import (
	"context"
	_ "embed"
	"errors"
	"fmt"
	"os"
	"runtime"
	"sort"
	"strings"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
)

//go:embed probe_unix.sh
var probeUnix string

//go:embed probe_windows.ps1
var probeWindows string

// ErrTimeout bounds a probe that does not finish (a hanging shell or profile).
var ErrTimeout = errors.New("doctor: probe timed out")

// ErrOutputOversized bounds a probe whose output exceeds the cap.
var ErrOutputOversized = errors.New("doctor: probe output exceeded the size bound")

// Class is the result of a single check.
type Class int

const (
	// Pass: the check succeeded.
	Pass Class = iota
	// Warning: a deviation that is often expected or non-fatal.
	Warning
	// Failure: a real inheritance or configuration problem.
	Failure
	// Skipped: the check could not run in this context (for example, the
	// shell is not present), without that being a fault.
	Skipped
	// Unsupported: the platform or shell does not support the check.
	Unsupported
)

func (c Class) String() string {
	switch c {
	case Pass:
		return "pass"
	case Warning:
		return "warning"
	case Failure:
		return "failure"
	case Skipped:
		return "skipped"
	case Unsupported:
		return "unsupported"
	default:
		return "unknown"
	}
}

// Check is one diagnostic result. Detail is actionable prose; it names
// environment variables and command paths but never environment values.
type Check struct {
	Name   string
	Class  Class
	Detail string
}

// Report is the full doctor output.
type Report struct {
	// ShellPath is the resolved shell executable, when resolution succeeded.
	ShellPath string
	// ShellSource names where the shell came from: config, $SHELL, or account.
	ShellSource string
	Checks      []Check
}

// HasFailure reports whether any check is a failure, which drives the exit
// status.
func (r *Report) HasFailure() bool {
	for _, c := range r.Checks {
		if c.Class == Failure {
			return true
		}
	}
	return false
}

// Options configures a doctor run.
type Options struct {
	// ConfiguredShell is the daemon's configured shell path. Empty resolves
	// from $SHELL and the account entry, exactly as the daemon does.
	ConfiguredShell string
	// Term is the TERM value the daemon sets on sessions.
	Term string
	// Timeout bounds each probe run. Zero uses DefaultTimeout.
	Timeout time.Duration
	// MaxOutput bounds probe output bytes. Zero uses DefaultMaxOutput.
	MaxOutput int
	// Backend starts the probe PTY processes. Nil uses the platform backend.
	// Tests inject a fake to avoid a real shell.
	Backend pty.Backend
}

const (
	// DefaultTimeout bounds a single probe. A healthy probe is well under a
	// second; this exists to catch a hanging profile.
	DefaultTimeout = 10 * time.Second
	// DefaultMaxOutput bounds probe output. The probe is small; a huge output
	// means a pathological profile or an echo loop.
	DefaultMaxOutput = 1 << 20

	probeCols = 80
	probeRows = 24

	beginMark = "REMOTLY-PROBE-BEGIN"
	endMark   = "REMOTLY-PROBE-END"
)

// probeResult is the parsed, normalized output of one probe run.
type probeResult struct {
	ShellName   string
	Version     string
	Login       bool
	Interactive bool
	TTY         bool
	CWD         string
	UMask       string
	PathBroken  bool
	TermValue   string
	ColorTerm   string
	// EnvNames are the environment variable names, sorted. Names only.
	EnvNames []string
	// Commands maps an allowlisted command to its resolved path, or "MISSING".
	Commands map[string]string
	// Aliases and Functions are the defined names, sorted. Names only.
	Aliases   []string
	Functions []string
}

// Run executes the diagnostic. It returns a report whose checks classify each
// finding; a report with failures is not an error. It returns an error only
// for an operational failure that prevents the run (cannot resolve the shell,
// cannot start a probe).
func Run(ctx context.Context, opts Options) (*Report, error) {
	if opts.Timeout <= 0 {
		opts.Timeout = DefaultTimeout
	}
	if opts.MaxOutput <= 0 {
		opts.MaxOutput = DefaultMaxOutput
	}
	if opts.Backend == nil {
		opts.Backend = pty.New()
	}

	rep := &Report{}

	program, args, source, err := pty.ShellFromConfig(opts.ConfiguredShell, "")
	if err != nil {
		rep.ShellPath = opts.ConfiguredShell
		rep.Checks = append(rep.Checks, Check{
			Name:   "shell.selection",
			Class:  Failure,
			Detail: "no usable shell: " + err.Error() + ". Set a valid shell in the daemon config or fix $SHELL.",
		})
		return rep, nil
	}
	rep.ShellPath = program
	rep.ShellSource = source
	rep.Checks = append(rep.Checks, Check{
		Name:   "shell.selection",
		Class:  Pass,
		Detail: fmt.Sprintf("shell %s resolved from %s", program, source),
	})

	cwd, err := pty.ValidateCwd("")
	if err != nil {
		cwd = ""
	}
	probe := selectProbe()

	// (a) The daemon path: the exact spawn the daemon performs, including the
	// environment override (session.BuildEnv).
	daemonEnv := session.BuildEnv(opts.Term, "doctor-probe")
	daemon, derr := runProbe(ctx, opts, pty.StartRequest{
		Program: program, Args: args, Command: probe,
		Cwd: cwd, Env: daemonEnv, Cols: probeCols, Rows: probeRows,
	})
	// (b) A directly launched login shell with the inherited environment and
	// no daemon override.
	direct, xerr := runProbe(ctx, opts, pty.StartRequest{
		Program: program, Args: args, Command: probe,
		Cwd: cwd, Env: os.Environ(), Cols: probeCols, Rows: probeRows,
	})

	if derr != nil || xerr != nil {
		// An operational failure in either probe prevents the comparison.
		if derr != nil {
			rep.Checks = append(rep.Checks, Check{
				Name:   "probe.daemon",
				Class:  Failure,
				Detail: "daemon-path probe failed: " + derr.Error(),
			})
		}
		if xerr != nil {
			rep.Checks = append(rep.Checks, Check{
				Name:   "probe.direct",
				Class:  Failure,
				Detail: "direct probe failed: " + xerr.Error(),
			})
		}
		return rep, nil
	}

	rep.Checks = append(rep.Checks, compare(daemon, direct, opts.Term)...)
	return rep, nil
}

// selectProbe returns the probe script for the current platform.
func selectProbe() string {
	if runtime.GOOS == "windows" {
		return probeWindows
	}
	return probeUnix
}

// runProbe starts the probe in a PTY, reads its output to the END marker (or a
// bound), and parses the marked section.
func runProbe(ctx context.Context, opts Options, req pty.StartRequest) (*probeResult, error) {
	if err := pty.Validate(req); err != nil {
		return nil, err
	}
	proc, err := opts.Backend.Start(req)
	if err != nil {
		return nil, err
	}
	defer proc.Close()

	out, err := readProbe(ctx, proc, opts.Timeout, opts.MaxOutput)
	if err != nil {
		return nil, err
	}
	res, perr := parseProbe(out)
	if perr != nil {
		return nil, perr
	}
	return res, nil
}

// readProbe reads the probe output until the END marker, the size bound, EOF,
// or the timeout. On timeout it kills the process so a hanging profile cannot
// wedge the run.
func readProbe(ctx context.Context, proc pty.Process, timeout time.Duration, maxBytes int) (string, error) {
	type result struct {
		data []byte
		err  error
	}
	ch := make(chan result, 1)
	go func() {
		buf := make([]byte, 32*1024)
		var data []byte
		for {
			n, rerr := proc.Read(buf)
			if n > 0 {
				data = append(data, buf[:n]...)
				if strings.Contains(string(data), endMark) {
					ch <- result{data, nil}
					return
				}
				if len(data) > maxBytes {
					ch <- result{data, ErrOutputOversized}
					return
				}
			}
			if rerr != nil {
				ch <- result{data, nil}
				return
			}
		}
	}()

	timer := time.NewTimer(timeout)
	defer timer.Stop()
	select {
	case <-ctx.Done():
		proc.Kill()
		return "", ctx.Err()
	case <-timer.C:
		proc.Kill()
		return "", ErrTimeout
	case r := <-ch:
		return string(r.data), r.err
	}
}

// parseProbe extracts the marked section and parses its KEY=VALUE lines into a
// probeResult. Everything outside the markers (shell startup noise) is ignored.
func parseProbe(out string) (*probeResult, error) {
	start := strings.Index(out, beginMark)
	if start < 0 {
		return nil, errors.New("probe output missing the BEGIN marker")
	}
	end := strings.Index(out[start:], endMark)
	if end < 0 {
		return nil, errors.New("probe output missing the END marker")
	}
	section := out[start+len(beginMark) : start+end]

	res := &probeResult{Commands: make(map[string]string)}
	for _, line := range strings.Split(section, "\n") {
		line = strings.TrimRight(line, "\r")
		line = strings.TrimSpace(line)
		if line == "" {
			continue
		}
		key, value, ok := strings.Cut(line, "=")
		if !ok {
			continue
		}
		switch key {
		case "SHELLNAME":
			res.ShellName = value
		case "VERSION":
			res.Version = value
		case "LOGIN":
			res.Login = value == "yes"
		case "INTERACTIVE":
			res.Interactive = value == "yes"
		case "TTY":
			res.TTY = value == "yes"
		case "CWD":
			res.CWD = value
		case "UMASK":
			res.UMask = value
		case "PATHBROKEN":
			res.PathBroken = value == "yes"
		case "TERMVAL":
			res.TermValue = value
		case "COLORTERMVAL":
			res.ColorTerm = value
		case "ENV":
			res.EnvNames = append(res.EnvNames, value)
		case "CMD":
			// value is "<name>=<path>"; re-split on the last '='.
			if name, path, found := strings.Cut(value, "="); found {
				res.Commands[name] = path
			}
		case "ALIAS":
			res.Aliases = append(res.Aliases, value)
		case "FUNC":
			res.Functions = append(res.Functions, value)
		}
	}
	sort.Strings(res.EnvNames)
	sort.Strings(res.Aliases)
	sort.Strings(res.Functions)
	return res, nil
}

// allowedDeviation reports whether an environment variable is one the daemon is
// permitted to override: TERM, COLORTERM, or any REMOTLY_*.
func allowedDeviation(name string) bool {
	return name == "TERM" || name == "COLORTERM" || strings.HasPrefix(name, "REMOTLY_")
}

// coreCommands are the minimum commands a usable shell session must resolve.
var coreCommands = []string{"sh", "bash", "zsh", "which", "git", "node", "nvm", "python3", "pyenv", "go"}

// compare builds the checks from the two probe results: per-context sanity on
// the daemon path, and the environment diff between the two contexts.
func compare(daemon, direct *probeResult, term string) []Check {
	var checks []Check

	// Per-context sanity on the daemon path (the environment the daemon
	// actually produces).
	if daemon.Login {
		checks = append(checks, Check{Name: "session.login", Class: Pass,
			Detail: "daemon session is a login shell"})
	} else {
		checks = append(checks, Check{Name: "session.login", Class: Failure,
			Detail: "daemon session is not a login shell; the login profile will not load, so PATH and profile variables are missing"})
	}

	if daemon.TTY {
		checks = append(checks, Check{Name: "session.pty", Class: Pass,
			Detail: "daemon session has a controlling terminal"})
	} else {
		checks = append(checks, Check{Name: "session.pty", Class: Failure,
			Detail: "daemon session has no controlling terminal; programs that expect a TTY will misbehave"})
	}

	// Working directory.
	if daemon.CWD == "" {
		checks = append(checks, Check{Name: "session.cwd", Class: Failure,
			Detail: "working directory could not be determined"})
	} else if fi, err := os.Stat(daemon.CWD); err != nil || !fi.IsDir() {
		checks = append(checks, Check{Name: "session.cwd", Class: Failure,
			Detail: fmt.Sprintf("working directory %s is not an accessible directory", daemon.CWD)})
	} else {
		checks = append(checks, Check{Name: "session.cwd", Class: Pass,
			Detail: "working directory is " + daemon.CWD})
	}

	// PATH command resolution on the daemon path.
	if daemon.PathBroken {
		checks = append(checks, Check{Name: "path.resolution", Class: Failure,
			Detail: "PATH is broken in the daemon session: printenv was not found. The service environment likely lacks a usable PATH and the login profile did not restore one"})
	} else {
		var missing []string
		for _, c := range coreCommands {
			if p, ok := daemon.Commands[c]; !ok || p == "MISSING" {
				missing = append(missing, c)
			}
		}
		if len(missing) == 0 {
			checks = append(checks, Check{Name: "path.resolution", Class: Pass,
				Detail: "all core commands resolve in the daemon session PATH"})
		} else {
			checks = append(checks, Check{Name: "path.resolution", Class: Warning,
				Detail: "commands not resolved in the daemon session PATH: " + strings.Join(missing, ", ")})
		}
	}

	// Profile loading, inferred: a loaded login profile reconstructs a PATH
	// with more than the service base and sets HOME.
	if daemon.EnvHas("HOME") && len(daemon.Commands) > 0 {
		checks = append(checks, Check{Name: "profile.loading", Class: Pass,
			Detail: "login profile appears loaded (HOME set, PATH resolves commands)"})
	} else {
		checks = append(checks, Check{Name: "profile.loading", Class: Warning,
			Detail: "login profile may not be loading: HOME is unset or PATH resolves no commands. Check that the shell is a login shell and the profile path is valid"})
	}

	// Terminal variable overrides.
	if term == "" {
		term = "xterm-256color"
	}
	if daemon.TermValue == term && daemon.ColorTerm == "truecolor" {
		checks = append(checks, Check{Name: "terminal.variables", Class: Pass,
			Detail: fmt.Sprintf("TERM=%s and COLORTERM=truecolor as the daemon sets them", term)})
	} else {
		checks = append(checks, Check{Name: "terminal.variables", Class: Warning,
			Detail: fmt.Sprintf("terminal variables differ from the daemon overrides (TERM got %q, COLORTERM got %q; expected %q and truecolor)", daemon.TermValue, daemon.ColorTerm, term)})
	}

	// Environment diff: the daemon path must differ from a direct login shell
	// only by the allowed variables. Values are never compared (names only),
	// so this is an environment-inheritance check, not a secret dump.
	onlyDaemon, onlyDirect := diffNames(daemon.EnvNames, direct.EnvNames)
	onlyDaemon = filterAllowed(onlyDaemon)
	onlyDirect = filterAllowed(onlyDirect)
	if len(onlyDaemon) == 0 && len(onlyDirect) == 0 {
		checks = append(checks, Check{Name: "env.inheritance", Class: Pass,
			Detail: "daemon session environment matches a direct login shell apart from the allowed overrides"})
	} else {
		var parts []string
		if len(onlyDaemon) > 0 {
			parts = append(parts, "only in daemon session: "+strings.Join(onlyDaemon, ", "))
		}
		if len(onlyDirect) > 0 {
			parts = append(parts, "missing from daemon session: "+strings.Join(onlyDirect, ", "))
		}
		checks = append(checks, Check{Name: "env.inheritance", Class: Failure,
			Detail: "environment inheritance differs beyond the allowed overrides. " + strings.Join(parts, "; ") +
				". Likely a service-environment cause: a minimal launchd/systemd/Windows-service environment that the login profile does not fully reconstruct. Verify the shell is a login shell and inspect its profile for unconditional PATH or variable assignments"})
	}

	// Shell definitions: the daemon session should define the same aliases and
	// functions as a direct login shell. Both contexts use identical shell
	// arguments, so a difference means the daemon path loaded a different rc
	// or profile than a directly launched shell.
	onlyDaemonDef, onlyDirectDef := diffNames(daemon.Aliases, direct.Aliases)
	if len(onlyDaemonDef) == 0 && len(onlyDirectDef) == 0 {
		checks = append(checks, Check{Name: "shell.aliases", Class: Pass,
			Detail: fmt.Sprintf("daemon session defines the same %d alias(es) as a direct login shell", len(direct.Aliases))})
	} else {
		checks = append(checks, Check{Name: "shell.aliases", Class: Failure,
			Detail: "alias set differs from a direct login shell. " + defDiffParts(onlyDaemonDef, onlyDirectDef) +
				". The daemon session may be loading a different rc file; verify the shell arguments and profile paths"})
	}
	onlyDaemonFn, onlyDirectFn := diffNames(daemon.Functions, direct.Functions)
	if len(onlyDaemonFn) == 0 && len(onlyDirectFn) == 0 {
		checks = append(checks, Check{Name: "shell.functions", Class: Pass,
			Detail: fmt.Sprintf("daemon session defines the same %d function(s) as a direct login shell", len(direct.Functions))})
	} else {
		checks = append(checks, Check{Name: "shell.functions", Class: Failure,
			Detail: "function set differs from a direct login shell. " + defDiffParts(onlyDaemonFn, onlyDirectFn) +
				". Version managers (nvm, pyenv) define shell functions; a difference here usually means the interactive rc did not load in the daemon session"})
	}

	return checks
}

// defDiffParts renders the two-sided difference of defined names for a detail
// string. It names definitions (which are not secrets), never their bodies.
func defDiffParts(onlyA, onlyB []string) string {
	var parts []string
	if len(onlyA) > 0 {
		parts = append(parts, "only in daemon session: "+strings.Join(onlyA, ", "))
	}
	if len(onlyB) > 0 {
		parts = append(parts, "missing from daemon session: "+strings.Join(onlyB, ", "))
	}
	return strings.Join(parts, "; ")
}

// EnvHas reports whether the probe saw an environment variable with the given
// name.
func (r *probeResult) EnvHas(name string) bool {
	for _, n := range r.EnvNames {
		if n == name {
			return true
		}
	}
	return false
}

// diffNames returns the names present only in a and only in b. Both inputs are
// sorted.
func diffNames(a, b []string) (onlyA, onlyB []string) {
	setB := make(map[string]struct{}, len(b))
	for _, n := range b {
		setB[n] = struct{}{}
	}
	for _, n := range a {
		if _, ok := setB[n]; !ok {
			onlyA = append(onlyA, n)
		}
	}
	setA := make(map[string]struct{}, len(a))
	for _, n := range a {
		setA[n] = struct{}{}
	}
	for _, n := range b {
		if _, ok := setA[n]; !ok {
			onlyB = append(onlyB, n)
		}
	}
	return onlyA, onlyB
}

// filterAllowed drops the variables the daemon is permitted to override.
func filterAllowed(names []string) []string {
	out := names[:0:0]
	for _, n := range names {
		if !allowedDeviation(n) {
			out = append(out, n)
		}
	}
	return out
}
