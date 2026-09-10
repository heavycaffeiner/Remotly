package sshcore

import (
	"sync"
	"testing"
	"time"
)

func controlCfg(h string, port int) *ExecConfig {
	return &ExecConfig{
		Host: h, User: "tester", Port: port,
		Password:       testPassword,
		ConnectTimeout: 5000,
	}
}

// Accepts the presented key at once, which is what the app does for a host
// whose key it already stored.
type controlListener struct{ decide *Control }

func (l *controlListener) OnHostKey(_, _ string) { l.decide.DecideHostKey(true) }

func dialControl(t *testing.T, ts *testServer) *Control {
	t.Helper()
	listener := &controlListener{}
	c := NewControl(listener)
	listener.decide = c
	t.Cleanup(c.Close)
	done := make(chan *ExecResult, 1)
	go func() { done <- c.Connect(controlCfg(ts.host(), ts.port())) }()
	select {
	case res := <-done:
		if res.Code != "" {
			t.Fatalf("connect failed: %s %s", res.Code, res.Message)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("connect did not return within the deadline")
	}
	return c
}

// Two commands on one handle must cost one handshake. That is the whole point
// of the type: the handshake is what a per-command connection was paying for.
func TestControlRunsTwoCommandsOnOneConnection(t *testing.T) {
	ts := startTestServer(t)
	c := dialControl(t, ts)

	for i := range 2 {
		res := c.Run("printf hello", 3000)
		if res.Code != "" {
			t.Fatalf("run %d failed: %s %s", i, res.Code, res.Message)
		}
		if string(res.Stdout) != "hello" || string(res.Stderr) != "boom" {
			t.Fatalf("run %d output: %q / %q", i, res.Stdout, res.Stderr)
		}
		if res.ExitCode != 3 {
			t.Fatalf("run %d exit code: %d", i, res.ExitCode)
		}
	}

	ts.mu.Lock()
	conns := ts.conns
	ts.mu.Unlock()
	if conns != 1 {
		t.Fatalf("server accepted %d connections, want 1", conns)
	}
}

type lineSink struct {
	mu     sync.Mutex
	lines  []string
	closed chan struct{}
	code   string
}

func (s *lineSink) OnLine(line string) {
	s.mu.Lock()
	s.lines = append(s.lines, line)
	s.mu.Unlock()
}

func (s *lineSink) OnClosed(code, _ string) {
	s.mu.Lock()
	s.code = code
	s.mu.Unlock()
	close(s.closed)
}

func (s *lineSink) snapshot() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.lines...)
}

// A subscription is a command that keeps printing, so the lines have to arrive
// while it runs and the end has to be reported exactly once.
func TestControlStreamDeliversLinesThenCloses(t *testing.T) {
	ts := startTestServer(t)
	c := dialControl(t, ts)

	sink := &lineSink{closed: make(chan struct{})}
	if res := c.Stream("stream", sink); res.Code != "" {
		t.Fatalf("stream failed: %s %s", res.Code, res.Message)
	}

	select {
	case <-sink.closed:
	case <-time.After(10 * time.Second):
		t.Fatal("stream did not close within the deadline")
	}
	if got := sink.snapshot(); len(got) != 3 || got[0] != "one" || got[2] != "three" {
		t.Fatalf("lines: %v", got)
	}
	if sink.code != "" {
		t.Fatalf("clean end reported code %q", sink.code)
	}
}

// A command on a handle that was never connected must say so rather than
// block or panic, because that is what the app hits after a drop.
func TestControlRunWithoutConnect(t *testing.T) {
	c := NewControl(&controlListener{})
	res := c.Run("printf hello", 1000)
	if res.Code != CodeRemoteClosed {
		t.Fatalf("code: %q", res.Code)
	}
}

// Closing while a stream is open ends it, which is how a screen going away
// releases the subscription.
func TestControlCloseEndsStream(t *testing.T) {
	ts := startTestServer(t)
	c := dialControl(t, ts)

	sink := &lineSink{closed: make(chan struct{})}
	if res := c.Stream("sleep", sink); res.Code != "" {
		t.Fatalf("stream failed: %s", res.Code)
	}
	c.Close()

	select {
	case <-sink.closed:
	case <-time.After(10 * time.Second):
		t.Fatal("close did not end the stream")
	}
}

// A resubscribe must not leave the previous reader running on the host, so
// starting a second stream ends the first.
func TestControlStreamReplacesThePrevious(t *testing.T) {
	ts := startTestServer(t)
	c := dialControl(t, ts)

	first := &lineSink{closed: make(chan struct{})}
	if res := c.Stream("sleep", first); res.Code != "" {
		t.Fatalf("first stream failed: %s", res.Code)
	}
	second := &lineSink{closed: make(chan struct{})}
	if res := c.Stream("stream", second); res.Code != "" {
		t.Fatalf("second stream failed: %s", res.Code)
	}

	select {
	case <-first.closed:
	case <-time.After(10 * time.Second):
		t.Fatal("the first stream was left running")
	}
	select {
	case <-second.closed:
	case <-time.After(10 * time.Second):
		t.Fatal("the second stream did not finish")
	}
	if got := second.snapshot(); len(got) != 3 {
		t.Fatalf("second stream lines: %v", got)
	}
}
