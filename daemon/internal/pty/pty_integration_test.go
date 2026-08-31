//go:build !windows

package pty

import (
	"bytes"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

func testEnv(t *testing.T, session string) []string {
	t.Helper()
	return []string{
		"PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
		"HOME=" + t.TempDir(),
		"TERM=xterm-256color",
		"COLORTERM=truecolor",
		"REMOTLY_SESSION=" + session,
		"LANG=C.UTF-8",
	}
}

// startBash starts a PTY running bash with the given arguments. It does not
// drain output; the test owns reading.
//
// bash specifically, not the account shell. These tests drive an interactive
// shell with bash flags and bash-specific expectations, and resolving $SHELL
// handed them whatever the developer happens to use: under zsh the same "-l"
// is a non-interactive login shell, and on the temporary HOME below it starts
// zsh-newuser-install, which waits for input and prints nothing the tests are
// looking for. The suite then hung rather than failing.
func startBash(t *testing.T, args []string) Process {
	t.Helper()
	prog, err := exec.LookPath("bash")
	if err != nil {
		t.Skipf("bash not available: %v", err)
	}
	b := New()
	if args == nil {
		args = []string{"-l"}
	}
	cwd, err := ValidateCwd("")
	if err != nil {
		t.Fatal(err)
	}
	p, err := b.Start(StartRequest{
		Program: prog,
		Args:    args,
		Cwd:     cwd,
		Env:     testEnv(t, "test-"+t.Name()),
		Cols:    100,
		Rows:    30,
	})
	if err != nil {
		t.Fatalf("start: %v", err)
	}
	t.Cleanup(func() {
		p.Kill()
		p.Close()
	})
	return p
}

// drain continuously reads the process into a buffer for marker assertions.
// It must be the sole reader of p.
func drain(t *testing.T, p Process) *drainBuf {
	t.Helper()
	d := &drainBuf{done: make(chan struct{})}
	go func() {
		buf := make([]byte, 32<<10)
		for {
			n, err := p.Read(buf)
			if n > 0 {
				d.mu.Lock()
				d.buf.Write(buf[:n])
				d.mu.Unlock()
			}
			if err != nil {
				d.once.Do(func() { close(d.done) })
				return
			}
		}
	}()
	t.Cleanup(func() { p.Close() })
	return d
}

type drainBuf struct {
	mu   sync.Mutex
	buf  bytes.Buffer
	once sync.Once
	done chan struct{}
}

func (d *drainBuf) readUntil(t *testing.T, marker string, timeout time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		d.mu.Lock()
		s := d.buf.String()
		finished := isClosed(d.done)
		d.mu.Unlock()
		if i := strings.Index(s, marker); i >= 0 {
			return s
		}
		if finished {
			t.Fatalf("process exited without %q; output:\n%s", marker, tail(s))
		}
		if time.Now().After(deadline) {
			t.Fatalf("timeout waiting for %q; output:\n%s", marker, tail(s))
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func (d *drainBuf) waitEOF(t *testing.T, timeout time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		select {
		case <-d.done:
		default:
			if time.Now().After(deadline) {
				d.mu.Lock()
				s := d.buf.String()
				d.mu.Unlock()
				t.Fatalf("timeout waiting for EOF; output tail:\n%s", tail(s))
			}
			time.Sleep(20 * time.Millisecond)
			continue
		}
		d.mu.Lock()
		s := d.buf.String()
		d.mu.Unlock()
		return s
	}
}

func isClosed(ch chan struct{}) bool {
	select {
	case <-ch:
		return true
	default:
		return false
	}
}

func tail(s string) string {
	if len(s) > 2000 {
		return s[len(s)-2000:]
	}
	return s
}

// readDirect reads p (the sole reader) until marker appears.
//
// The read runs on its own goroutine because Process.Read blocks: a shell
// that prints nothing (a first-run wizard waiting for input, a non-interactive
// login shell) left the inline version parked in the syscall forever, so the
// deadline below was never reached and the whole package hung until the Go
// test timeout instead of failing here.
func readDirect(t *testing.T, p Process, marker string, timeout time.Duration) string {
	t.Helper()
	type chunk struct {
		b   []byte
		err error
	}
	ch := make(chan chunk, 8)
	go func() {
		buf := make([]byte, 32<<10)
		for {
			n, err := p.Read(buf)
			c := chunk{err: err}
			if n > 0 {
				c.b = append([]byte(nil), buf[:n]...)
			}
			select {
			case ch <- c:
			default:
			}
			if err != nil {
				return
			}
		}
	}()
	var out bytes.Buffer
	deadline := time.After(timeout)
	for {
		select {
		case c := <-ch:
			if len(c.b) > 0 {
				out.Write(c.b)
				if strings.Contains(out.String(), marker) {
					return out.String()
				}
			}
			if c.err != nil {
				if c.err == io.EOF {
					t.Fatalf("EOF before %q; so far:\n%s", marker, tail(out.String()))
				}
				t.Fatalf("read: %v; so far:\n%s", c.err, tail(out.String()))
			}
		case <-deadline:
			t.Fatalf("timeout waiting for %q; so far:\n%s", marker, tail(out.String()))
		}
	}
}

func TestPTYRunCommandAndExit(t *testing.T) {
	p := startBash(t, []string{"-l", "-c", "echo rem-test-marker; exit 3"})
	d := drain(t, p)
	st := p.Wait()
	out := d.waitEOF(t, 10*time.Second)
	if !st.Exited {
		t.Fatal("not exited")
	}
	if st.Code != 3 {
		t.Fatalf("exit code %d, want 3 (status %+v)", st.Code, st)
	}
	if !strings.Contains(out, "rem-test-marker") {
		t.Fatalf("marker missing:\n%s", tail(out))
	}
}

func TestPTYInteractiveEcho(t *testing.T) {
	p := startBash(t, []string{"-l"})
	time.Sleep(300 * time.Millisecond)
	if _, err := p.Write([]byte("echo rem-echo-$((40+2))\n")); err != nil {
		t.Fatal(err)
	}
	out := readDirect(t, p, "rem-echo-42", 5*time.Second)
	if !strings.Contains(out, "rem-echo-42") {
		t.Fatal("echo output missing")
	}
}

func TestPTYResize(t *testing.T) {
	p := startBash(t, []string{"-l"})
	time.Sleep(300 * time.Millisecond)
	if err := p.Resize(130, 45); err != nil {
		t.Fatal(err)
	}
	if _, err := p.Write([]byte("stty size\n")); err != nil {
		t.Fatal(err)
	}
	// "45 130" is the stty size output (rows cols) and appears only there,
	// not in the echoed command, so it is an unambiguous marker. Waiting on
	// it also avoids matching an echo that can precede the command output.
	out := readDirect(t, p, "45 130", 5*time.Second)
	if !strings.Contains(out, "45 130") {
		t.Fatalf("resize not effective:\n%s", tail(out))
	}
}

func TestPTYInvalidResize(t *testing.T) {
	p := startBash(t, []string{"-l"})
	time.Sleep(200 * time.Millisecond)
	if err := p.Resize(0, 24); err == nil {
		t.Fatal("zero cols accepted")
	}
	if err := p.Resize(80, 5000); err == nil {
		t.Fatal("huge rows accepted")
	}
}

func TestPTYInterruptForeground(t *testing.T) {
	p := startBash(t, []string{"-l"})
	time.Sleep(300 * time.Millisecond)
	if _, err := p.Write([]byte("sleep 30\n")); err != nil {
		t.Fatal(err)
	}
	time.Sleep(400 * time.Millisecond)
	if _, err := p.Write([]byte("\x03")); err != nil {
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond)
	if _, err := p.Write([]byte("echo rem-alive\n")); err != nil {
		t.Fatal(err)
	}
	out := readDirect(t, p, "rem-alive", 5*time.Second)
	if !strings.Contains(out, "rem-alive") {
		t.Fatalf("shell not alive after interrupt:\n%s", tail(out))
	}
}

func TestPTYKillAndWaitConcurrent(t *testing.T) {
	p := startBash(t, nil)
	time.Sleep(200 * time.Millisecond)
	var wg sync.WaitGroup
	var kills, closes, waits int32
	for i := 0; i < 8; i++ {
		wg.Add(3)
		go func() { defer wg.Done(); _ = p.Kill(); atomic.AddInt32(&kills, 1) }()
		go func() { defer wg.Done(); _ = p.Close(); atomic.AddInt32(&closes, 1) }()
		go func() {
			defer wg.Done()
			st := p.Wait()
			if !st.Exited {
				t.Error("wait reported not exited")
			}
			atomic.AddInt32(&waits, 1)
		}()
	}
	wg.Wait()
	if atomic.LoadInt32(&kills) != 8 || atomic.LoadInt32(&waits) != 8 {
		t.Fatalf("kills=%d waits=%d", kills, waits)
	}
}

func TestPTYLargeOutput(t *testing.T) {
	const size = 1 << 20
	script := "big=$(head -c " + strconv.Itoa(size) + " /dev/zero | tr '\\0' 'x'); printf 'rem-big-%s rem-big-end\\n' \"$big\""
	p := startBash(t, []string{"-l", "-c", script})
	d := drain(t, p)
	out := d.waitEOF(t, 30*time.Second)
	if !strings.Contains(out, "rem-big-end") {
		t.Fatal("large output truncated or lost")
	}
	// The payload is size 'x' bytes between the markers.
	i := strings.Index(out, "rem-big-")
	j := strings.Index(out, " rem-big-end")
	if i < 0 || j < i {
		t.Fatal("markers not found")
	}
	if got := j - (i + len("rem-big-")); got != size {
		t.Fatalf("payload %d bytes, want %d", got, size)
	}
}

func TestPTYUnreadBacklogDelivers(t *testing.T) {
	p := startBash(t, []string{"-l"})
	time.Sleep(300 * time.Millisecond)
	if _, err := p.Write([]byte("yes rem-blocked | head -c 200000\n")); err != nil {
		t.Fatal(err)
	}
	// Do not read for a while; the PTY buffer and pipe must hold or the
	// producer must block without wedging the daemon side.
	time.Sleep(1 * time.Second)
	buf := make([]byte, 32<<10)
	var got int
	deadline := time.Now().Add(20 * time.Second)
	for got < 200000 && time.Now().Before(deadline) {
		n, err := p.Read(buf)
		got += n
		if err != nil {
			break
		}
	}
	if got < 200000 {
		t.Fatalf("backlog short: got %d want >= 200000", got)
	}
}

func TestPTYEnvironmentOverrides(t *testing.T) {
	b := New()
	home := t.TempDir()
	env := []string{
		"PATH=/usr/bin:/bin",
		"HOME=" + home,
		"TERM=xterm-256color",
		"COLORTERM=truecolor",
		"REMOTLY_SESSION=envtest",
	}
	prog := "/bin/sh"
	if _, err := os.Stat(prog); err != nil {
		t.Skip("no /bin/sh")
	}
	cwd, _ := ValidateCwd(home)
	script := `printf '%s|%s|%s\n' "$TERM" "$COLORTERM" "$REMOTLY_SESSION"; env | sort; echo rem-env-done`
	p, err := b.Start(StartRequest{
		Program: prog, Args: []string{"-c", script}, Cwd: cwd, Env: env, Cols: 80, Rows: 24,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { p.Kill(); p.Close() }()
	out := readDirect(t, p, "rem-env-done", 5*time.Second)
	// The PTY line discipline maps LF to CRLF (ONLCR); strip the CR.
	lines := strings.Split(strings.TrimSuffix(out, "\n"), "\n")
	for i, l := range lines {
		lines[i] = strings.TrimSuffix(l, "\r")
	}
	if len(lines) < 2 {
		t.Fatalf("short output: %q", out)
	}
	if lines[0] != "xterm-256color|truecolor|envtest" {
		t.Fatalf("override line: %q", lines[0])
	}
	// The shell and the env command add PWD, SHLVL, and _ themselves, so
	// assert the five passed variables by value and that no REMOTLY_ key
	// other than the session id leaked in.
	vars := map[string]string{}
	for _, l := range lines[1:] {
		if l == "rem-env-done" || l == "" || !strings.Contains(l, "=") {
			continue
		}
		kv := strings.SplitN(l, "=", 2)
		vars[kv[0]] = kv[1]
	}
	for k, want := range map[string]string{
		"COLORTERM":       "truecolor",
		"HOME":            home,
		"PATH":            "/usr/bin:/bin",
		"REMOTLY_SESSION": "envtest",
		"TERM":            "xterm-256color",
	} {
		if got, ok := vars[k]; !ok || got != want {
			t.Fatalf("env %s=%q, want %q", k, got, want)
		}
	}
	for k := range vars {
		if strings.HasPrefix(k, "REMOTLY_") && k != "REMOTLY_SESSION" {
			t.Fatalf("unexpected REMOTLY_ variable: %s", k)
		}
	}
}

// TestPTYBinaryInputRoundTrip verifies raw bytes (including invalid UTF-8)
// pass through the PTY intact: the stream is byte-oriented.
func TestPTYBinaryInputRoundTrip(t *testing.T) {
	b := New()
	prog := "/bin/sh"
	if _, err := os.Stat(prog); err != nil {
		t.Skip("no /bin/sh")
	}
	home := t.TempDir()
	cwd, _ := ValidateCwd(home)
	p, err := b.Start(StartRequest{
		Program: prog,
		Args:    []string{"-c", "dd bs=1 count=16 2>/dev/null | od -An -tx1; echo rem-bin-done"},
		Cwd:     cwd,
		Env:     testEnv(t, "binary"),
		Cols:    80, Rows: 24,
	})
	if err != nil {
		t.Fatal(err)
	}
	defer func() { p.Kill(); p.Close() }()
	raw := []byte{0x00, 0xff, 0xfe, 0x01, 0x80, 0xed, 0xa0, 0x80, 0x27, 0x0a, 0x00, 0x00, 0x41, 0x42, 0x43, 0x0d, '\n'}
	if _, err := p.Write(raw); err != nil {
		t.Fatal(err)
	}
	out := readDirect(t, p, "rem-bin-done", 5*time.Second)
	if !strings.Contains(out, "ed a0 80") {
		t.Fatalf("invalid UTF-8 bytes not preserved:\n%s", out)
	}
	if !strings.Contains(out, "41 42 43") {
		t.Fatalf("ascii bytes not preserved:\n%s", out)
	}
}

// TestPTYWriteAfterClose verifies Write reports an error after Close and
// that Close is idempotent.
func TestPTYCloseIdempotent(t *testing.T) {
	p := startBash(t, nil)
	time.Sleep(200 * time.Millisecond)
	if err := p.Close(); err != nil {
		t.Fatal(err)
	}
	if err := p.Close(); err != nil {
		t.Fatalf("second close: %v", err)
	}
	if _, err := p.Write([]byte("x")); err == nil {
		t.Fatal("write after close succeeded")
	}
}
