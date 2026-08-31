// Command m0-04 is a disposable spike proving that a Go process started with a
// minimal, service-like environment can spawn an interactive login shell in a
// PTY that reconstructs the user's normal shell environment.
package main

import (
	"bytes"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"os/exec"
	"os/signal"
	"os/user"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/creack/pty"
)

const defaultTerm = "xterm-256color"

func main() {
	var (
		shell      = flag.String("shell", "", "shell to spawn; default $SHELL then passwd entry")
		cwd        = flag.String("cwd", "", "working directory; default user home")
		minimal    = flag.Bool("minimal", true, "start with a minimal service-like env")
		probePath  = flag.String("probe", "envprobe.sh", "path to the allowlisted env probe")
		rows       = flag.Int("rows", 40, "initial PTY rows")
		cols       = flag.Int("cols", 120, "initial PTY cols")
		sessionID  = flag.String("session", "m0-04-spike", "REMOTLY_SESSION value")
		timeoutStr = flag.String("timeout", "15s", "per-step read timeout")
	)
	flag.Parse()

	timeout, err := time.ParseDuration(*timeoutStr)
	must(err, "parse timeout")

	report := run(shell, cwd, *minimal, *probePath, *rows, *cols, *sessionID, timeout)
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	must(enc.Encode(report), "encode report")
}

type report struct {
	ShellResolution shellResolution   `json:"shell_resolution"`
	ParentEnv       map[string]string `json:"parent_env"`
	ProbePath       string            `json:"probe_path"`
	Probe           map[string]string `json:"probe"`
	Resize          resizeReport      `json:"resize"`
	Interrupt       interruptReport   `json:"interrupt"`
	Exit            exitReport        `json:"exit"`
	ErrorCases      []errorCase       `json:"error_cases"`
}

type shellResolution struct {
	Requested string `json:"requested"`
	Resolved  string `json:"resolved"`
	Source    string `json:"source"`
}

type resizeReport struct {
	Before string `json:"before"`
	After  string `json:"after"`
	Set    string `json:"set"`
}

type interruptReport struct {
	AliveAfterInterrupt string `json:"alive_after_interrupt"`
}

type exitReport struct {
	ExitStatus string `json:"exit_status"`
	Code       int    `json:"code"`
}

type errorCase struct {
	Case     string `json:"case"`
	Expected string `json:"expected"`
	Actual   string `json:"actual"`
}

func run(shellFlag, cwdFlag *string, minimal bool, probePath string, rows, cols int, sessionID string, timeout time.Duration) *report {
	r := &report{}

	shellPath, source := resolveShell(*shellFlag)
	r.ShellResolution = shellResolution{Requested: *shellFlag, Resolved: shellPath, Source: source}

	home := userHome()

	parent := parentEnv(minimal, shellPath, home, sessionID)
	r.ParentEnv = parent

	cmd := exec.Command(shellPath, "-l")
	cmd.Dir = home
	if *cwdFlag != "" {
		cmd.Dir = *cwdFlag
	}
	cmd.Env = flatten(parent)

	ptmx, err := pty.Start(cmd)
	must(err, "start pty")
	defer ptmx.Close()

	must(pty.Setsize(ptmx, &pty.Winsize{Rows: uint16(rows), Cols: uint16(cols)}), "set initial size")

	buf := &ringBuf{}
	go pump(ptmx, buf)

	probeAbs, err := filepath.Abs(probePath)
	must(err, "abs probe path")
	r.ProbePath = probeAbs

	// Run the allowlisted probe inside the login shell.
	probeOut := runCapture(ptmx, buf, "source "+shellQuote(probeAbs), markerFor(sessionID), timeout)
	r.Probe = parseProbe(probeOut)

	// Resize proof.
	before := trimOut(runCapture(ptmx, buf, "stty size 2>/dev/null", markerFor(sessionID+"r1"), timeout))
	newRows, newCols := rows+10, cols+20
	must(pty.Setsize(ptmx, &pty.Winsize{Rows: uint16(newRows), Cols: uint16(newCols)}), "resize")
	after := trimOut(runCapture(ptmx, buf, "stty size 2>/dev/null", markerFor(sessionID+"r2"), timeout))
	r.Resize = resizeReport{Before: before, After: after, Set: fmt.Sprintf("%dx%d", newCols, newRows)}

	// Interrupt proof: foreground sleep, then ETX (SIGINT via the tty line
	// discipline), then confirm the shell is still alive at a prompt.
	writeAll(ptmx, "sleep 30\n")
	time.Sleep(500 * time.Millisecond)
	writeAll(ptmx, "\x03")
	alive := trimOut(runCapture(ptmx, buf, "echo alive-after-interrupt", markerFor(sessionID+"i1"), timeout))
	r.Interrupt = interruptReport{AliveAfterInterrupt: alive}

	// Exit and status.
	writeAll(ptmx, "exit\n")
	done := make(chan error, 1)
	go func() { done <- cmd.Wait() }()
	select {
	case err := <-done:
		r.Exit = exitReport{ExitStatus: describeExit(err), Code: exitCode(err)}
	case <-time.After(timeout):
		cmd.Process.Kill()
		r.Exit = exitReport{ExitStatus: "timeout", Code: -1}
	}

	r.ErrorCases = failureCases()

	return r
}

func resolveShell(requested string) (path, source string) {
	if requested != "" {
		if err := validateShell(requested); err != nil {
			return requested, "requested-but-invalid: " + err.Error()
		}
		return requested, "flag"
	}
	if s := os.Getenv("SHELL"); s != "" {
		if err := validateShell(s); err == nil {
			return s, "$SHELL"
		}
	}
	u, err := user.Current()
	if err == nil {
		if s, ok := lookupPasswdShell(u.Username); ok {
			if err := validateShell(s); err == nil {
				return s, "passwd"
			}
		}
	}
	return "/bin/sh", "fallback"
}

func validateShell(shell string) error {
	if !filepath.IsAbs(shell) {
		return fmt.Errorf("not absolute")
	}
	fi, err := os.Stat(shell)
	if err != nil {
		return err
	}
	if fi.IsDir() {
		return fmt.Errorf("is a directory")
	}
	if fi.Mode()&0o111 == 0 {
		return fmt.Errorf("not executable")
	}
	return nil
}

func lookupPasswdShell(username string) (string, bool) {
	f, err := os.Open("/etc/passwd")
	if err != nil {
		return "", false
	}
	defer f.Close()
	data := make([]byte, 1<<16)
	n, _ := f.Read(data)
	for _, line := range strings.Split(string(data[:n]), "\n") {
		parts := strings.Split(line, ":")
		if len(parts) >= 7 && parts[0] == username {
			return parts[6], true
		}
	}
	return "", false
}

func userHome() string {
	if h := os.Getenv("HOME"); h != "" {
		return h
	}
	u, err := user.Current()
	if err == nil {
		return u.HomeDir
	}
	return "/"
}

// parentEnv builds the daemon-side environment before the child shell runs.
func parentEnv(minimal bool, shell, home, sessionID string) map[string]string {
	base := map[string]string{}
	if !minimal {
		for _, kv := range os.Environ() {
			if i := strings.IndexByte(kv, '='); i >= 0 {
				base[kv[:i]] = kv[i+1:]
			}
		}
	}
	if _, ok := base["PATH"]; !ok {
		base["PATH"] = "/usr/local/bin:/usr/bin:/bin"
	}
	base["HOME"] = home
	base["USER"] = os.Getenv("USER")
	base["LOGNAME"] = os.Getenv("LOGNAME")
	base["SHELL"] = shell
	base["LANG"] = os.Getenv("LANG")
	// The only values the daemon deliberately overrides.
	base["TERM"] = defaultTerm
	base["COLORTERM"] = "truecolor"
	base["REMOTLY_SESSION"] = sessionID
	return base
}

func flatten(env map[string]string) []string {
	out := make([]string, 0, len(env))
	for k, v := range env {
		out = append(out, k+"="+v)
	}
	return out
}

func failureCases() []errorCase {
	return []errorCase{
		{Case: "invalid-shell", Expected: "error", Actual: errString(validateShell("/nonexistent/shell"))},
		{Case: "invalid-cwd", Expected: "error", Actual: errString(validateCwd(filepath.Join(os.TempDir(), "remotly-no-such-dir")))},
	}
}

func validateCwd(dir string) error {
	fi, err := os.Stat(dir)
	if err != nil {
		return err
	}
	if !fi.IsDir() {
		return fmt.Errorf("not a directory")
	}
	return nil
}

func errString(err error) string {
	if err == nil {
		return "nil"
	}
	return err.Error()
}

func describeExit(err error) string {
	if err == nil {
		return "exit 0"
	}
	if ee, ok := err.(*exec.ExitError); ok {
		if ws, ok := ee.Sys().(syscall.WaitStatus); ok {
			if ws.Signaled() {
				return "signaled " + ws.Signal().String()
			}
			return "exit " + strconv.Itoa(ws.ExitStatus())
		}
	}
	return err.Error()
}

func exitCode(err error) int {
	if err == nil {
		return 0
	}
	if ee, ok := err.(*exec.ExitError); ok {
		if ws, ok := ee.Sys().(syscall.WaitStatus); ok {
			if ws.Signaled() {
				return 128 + int(ws.Signal())
			}
			return ws.ExitStatus()
		}
	}
	return -1
}

// runCapture writes a command followed by a marker line, waits for the marker,
// and returns the cleaned output the command produced before the marker.
func runCapture(ptmx *os.File, buf *ringBuf, command, marker string, timeout time.Duration) string {
	buf.mark()
	writeAll(ptmx, command+" ; echo "+marker+"\n")
	raw, ok := buf.waitFor(marker, timeout)
	if !ok {
		return raw
	}
	return clean(raw)
}

func writeAll(f *os.File, s string) {
	_, _ = f.Write([]byte(s))
}

var markerRe = regexp.MustCompile(`[^a-zA-Z0-9_-]`)

func markerFor(id string) string {
	return "__REMOTLY_PROBE_END_" + markerRe.ReplaceAllString(id, "_") + "__"
}

// shellQuote quotes a path for a POSIX shell. The path is a local spike flag,
// not remote data, so this is not a security boundary.
func shellQuote(s string) string {
	return "'" + strings.ReplaceAll(s, "'", `'\''`) + "'"
}

func pump(ptmx *os.File, buf *ringBuf) {
	data := make([]byte, 4096)
	for {
		n, err := ptmx.Read(data)
		if n > 0 {
			buf.write(data[:n])
		}
		if err != nil {
			return
		}
	}
}

func must(err error, ctx string) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "fatal (%s): %v\n", ctx, err)
		os.Exit(1)
	}
}

var (
	ansiCSI = regexp.MustCompile(`\x1b\[[0-9;?]*[ -/]*[@-~]`)
	ansiOSC = regexp.MustCompile(`\x1b\][^\x07\x1b]*(?:\x07|\x1b\\)`)
)

// clean strips control sequences and carriage returns from PTY output.
func clean(s string) string {
	s = ansiOSC.ReplaceAllString(s, "")
	s = ansiCSI.ReplaceAllString(s, "")
	s = strings.ReplaceAll(s, "\r", "")
	return s
}

func trimOut(s string) string {
	lines := strings.Split(s, "\n")
	for i := len(lines) - 1; i >= 0; i-- {
		if strings.TrimSpace(lines[i]) != "" {
			return strings.TrimSpace(lines[i])
		}
	}
	return ""
}

// parseProbe pulls PROBE_*=* lines out of captured output into a map.
func parseProbe(out string) map[string]string {
	m := map[string]string{}
	for _, line := range strings.Split(out, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "PROBE_") {
			continue
		}
		line = strings.TrimPrefix(line, "PROBE_")
		if i := strings.IndexByte(line, '='); i >= 0 {
			m[line[:i]] = line[i+1:]
		}
	}
	return m
}

type ringBuf struct {
	buf     bytes.Buffer
	markPos int
	mu      chan struct{}
}

func (r *ringBuf) write(p []byte) {
	if r.mu == nil {
		r.mu = make(chan struct{}, 1)
	}
	r.buf.Write(p)
	select {
	case r.mu <- struct{}{}:
	default:
	}
}

func (r *ringBuf) mark() {
	r.markPos = r.buf.Len()
}

// waitFor blocks until a line starting with marker appears, then returns the
// bytes since the last mark. The marker must be a whole line so a command echo
// containing the marker text is not mistaken for the real marker.
func (r *ringBuf) waitFor(marker string, timeout time.Duration) (string, bool) {
	pat := regexp.MustCompile(`(?m)^` + regexp.QuoteMeta(marker) + `\s*$`)
	deadline := time.After(timeout)
	tick := time.NewTicker(20 * time.Millisecond)
	defer tick.Stop()
	for {
		s := r.buf.String()
		if loc := pat.FindStringIndex(s); loc != nil {
			return s[r.markPos:loc[0]], true
		}
		select {
		case <-deadline:
			return s[r.markPos:], false
		case <-tick.C:
		}
	}
}

func init() {
	signal.Ignore(syscall.SIGPIPE)
}
