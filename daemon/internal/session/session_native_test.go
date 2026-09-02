//go:build !windows

package session

import (
	"bytes"
	"io"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

func newNativeManager(t *testing.T, opts Options) (*Manager, error) {
	t.Helper()
	opts.Backend = pty.New()
	m, err := New(opts)
	if err != nil {
		return nil, err
	}
	t.Cleanup(func() { _ = m.Shutdown() })
	return m, nil
}

// readUntil accumulates output until marker appears, the stream ends, or
// timeout elapses. It is deadline-bounded even when the producer idles: a
// pump goroutine owns the single-goroutine Read, and the caller selects
// between chunks and the deadline, so a blocked Read cannot swallow the
// timeout. The pump outlives a successful return until the attachment is
// detached (session teardown), at which point its Read returns and it exits.
func readUntil(t *testing.T, a *Attachment, marker string, timeout time.Duration) string {
	t.Helper()
	var out bytes.Buffer
	buf := make([]byte, 32<<10)
	deadline := time.After(timeout)
	type rres struct {
		data []byte
		err  error
	}
	ch := make(chan rres)
	done := make(chan struct{})
	defer close(done)
	go func() {
		for {
			select {
			case <-done:
				return
			default:
			}
			n, err := a.Read(buf)
			var data []byte
			if n > 0 {
				data = make([]byte, n)
				copy(data, buf[:n])
			}
			select {
			case ch <- rres{data, err}:
			case <-done:
				return
			}
		}
	}()
	for {
		if strings.Contains(out.String(), marker) {
			return out.String()
		}
		select {
		case r := <-ch:
			out.Write(r.data)
			if r.err == io.EOF {
				t.Fatalf("EOF before %q; tail: %s", marker, tail2000(out.String()))
			}
			if r.err != nil {
				t.Fatalf("read: %v", r.err)
			}
		case <-deadline:
			t.Fatalf("timeout waiting for %q; tail: %s", marker, tail2000(out.String()))
		}
	}
}

func tail2000(s string) string {
	if len(s) > 2000 {
		return s[len(s)-2000:]
	}
	return s
}

func TestNativeSessionLifecycle(t *testing.T) {
	m, err := newNativeManager(t, Options{Term: "xterm-256color"})
	if err != nil {
		t.Skipf("no shell: %v", err)
	}
	s, err := m.Create(Request{Kind: KindShell, Cols: 100, Rows: 30})
	if err != nil {
		t.Fatal(err)
	}
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	time.Sleep(300 * time.Millisecond)
	if _, err := s.Write([]byte("echo rem-native-$((5*9))\n")); err != nil {
		t.Fatal(err)
	}
	out := readUntil(t, a, "rem-native-45", 10*time.Second)
	if !strings.Contains(out, "rem-native-45") {
		t.Fatalf("output missing:\n%s", tail2000(out))
	}
	if err := s.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	meta := s.Meta()
	if meta.Cols != 120 || meta.Rows != 40 {
		t.Fatalf("size %dx%d", meta.Cols, meta.Rows)
	}
	if err := s.Kill(); err != nil {
		t.Fatal(err)
	}
	select {
	case <-s.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("no exit after kill")
	}
	if meta := s.Meta(); meta.Running {
		t.Fatal("still running after kill")
	}
}

func TestNativeSessionEnvOverrides(t *testing.T) {
	m, err := newNativeManager(t, Options{Term: "xterm-256color"})
	if err != nil {
		t.Skipf("no shell: %v", err)
	}
	s, err := m.Create(Request{Kind: KindShell})
	if err != nil {
		t.Fatal(err)
	}
	a, _, _ := s.Attach()
	time.Sleep(300 * time.Millisecond)
	cmd := "printf 'remenv %s %s %s\\n' \"$TERM\" \"$COLORTERM\" \"$REMOTLY_SESSION\"\n"
	if _, err := s.Write([]byte(cmd)); err != nil {
		t.Fatal(err)
	}
	// The shell echoes the command, which also contains "remenv ", so the
	// marker must be the full output line to match only the real result.
	want := "remenv xterm-256color truecolor " + s.ID()
	out := readUntil(t, a, want, 10*time.Second)
	if !strings.Contains(out, want) {
		t.Fatalf("env line missing %q:\n%s", want, tail2000(out))
	}
}

func TestNativeAgentCommand(t *testing.T) {
	m, err := newNativeManager(t, Options{Term: "xterm-256color"})
	if err != nil {
		t.Skipf("no shell: %v", err)
	}
	s, err := m.Create(Request{Kind: KindAgent, Command: "echo rem-agent-ok; exit 7", Cols: 80, Rows: 24})
	if err != nil {
		t.Fatal(err)
	}
	a, _, _ := s.Attach()
	out := readUntil(t, a, "rem-agent-ok", 10*time.Second)
	if !strings.Contains(out, "rem-agent-ok") {
		t.Fatalf("agent output missing:\n%s", tail2000(out))
	}
	select {
	case <-s.Done():
	case <-time.After(10 * time.Second):
		t.Fatal("agent did not exit")
	}
	meta := s.Meta()
	if meta.Running || meta.Exit.Code != 7 {
		t.Fatalf("meta %+v", meta)
	}
	if meta.Kind != KindAgent || meta.Command != "echo rem-agent-ok; exit 7" {
		t.Fatalf("meta %+v", meta)
	}
}

// A bell from an interactive shell must not raise an event.
//
// zsh rings the bell as ordinary feedback: an ambiguous completion, a failed
// history search, backspace at the start of the line. Treating those as
// notifiable turned normal typing into a stream of notifications. An agent
// session still reports its bell, which is the case the feature exists for.
func TestBellOnlyNotifiesForAgentSessions(t *testing.T) {
	var got []Event
	var mu sync.Mutex
	m, err := newNativeManager(t, Options{
		Events:  &Events{BellEnabled: true},
		OnEvent: func(e Event) { mu.Lock(); got = append(got, e); mu.Unlock() },
	})
	if err != nil {
		t.Skipf("no shell: %v", err)
	}

	shell, err := m.Create(Request{Kind: KindShell, Cols: 80, Rows: 24})
	if err != nil {
		t.Fatal(err)
	}
	a, _, _ := shell.Attach()
	defer a.Close()
	if _, err := shell.Write([]byte("printf '\\a'\n")); err != nil {
		t.Fatal(err)
	}
	readUntil(t, a, "printf", 5*time.Second)
	time.Sleep(500 * time.Millisecond)

	mu.Lock()
	bells := 0
	for _, e := range got {
		if e.Kind == "bell" {
			bells++
		}
	}
	mu.Unlock()
	if bells != 0 {
		t.Fatalf("shell session reported %d bell events, want 0", bells)
	}

	agent, err := m.Create(Request{
		Kind: KindAgent, Command: "printf '\\a'; sleep 1", Cols: 80, Rows: 24,
	})
	if err != nil {
		t.Fatal(err)
	}
	b, _, _ := agent.Attach()
	defer b.Close()
	deadline := time.Now().Add(5 * time.Second)
	for time.Now().Before(deadline) {
		mu.Lock()
		n := 0
		for _, e := range got {
			if e.Kind == "bell" {
				n++
			}
		}
		mu.Unlock()
		if n > 0 {
			return
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatal("agent session reported no bell event, want one")
}

func TestNativeScrollbackReplay(t *testing.T) {
	m, err := newNativeManager(t, Options{ScrollbackLines: 2048})
	if err != nil {
		t.Skipf("no shell: %v", err)
	}
	// An agent session runs a finite command and exits, giving a clean end of
	// stream. An interactive shell would instead idle at a prompt, so a reader
	// could never tell "more output coming" from "done".
	cmd := "for i in $(seq 1 1000); do echo remline-$i; done"
	s, err := m.Create(Request{Kind: KindAgent, Command: cmd, Cols: 80, Rows: 24})
	if err != nil {
		t.Fatal(err)
	}
	// Drain a gate reader to EOF. The agent exits, so the drain loop has
	// appended the final chunk and the ring is now fully populated.
	gate, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	readAll(t, gate)
	// A fresh attach after exit must replay the retained stream, including
	// early lines, not just the tail. The PTY driver emits ONLCR, so each
	// line ends \r\n; matching the terminated line also distinguishes line 1
	// from remline-10/100/1000.
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	out, reason := readAll(t, a)
	if reason != ReasonExited {
		t.Fatalf("reason %v, want exited", reason)
	}
	got := string(out)
	if !strings.Contains(got, "remline-1\r\n") {
		t.Fatalf("early line missing from scrollback:\n%s", tail2000(got))
	}
	if !strings.Contains(got, "remline-1000\r\n") {
		t.Fatalf("last line missing from scrollback:\n%s", tail2000(got))
	}
}
