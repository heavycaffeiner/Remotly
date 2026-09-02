package session

import (
	"errors"
	"os"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

func mustCreate(t *testing.T, m *Manager, req Request) (*Session, *fakeProc) {
	t.Helper()
	s, err := m.Create(req)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	b, ok := m.opts.Backend.(*fakeBackend)
	if !ok {
		t.Fatal("fake backend expected")
	}
	return s, b.proc(b.count() - 1)
}

func waitFor(t *testing.T, ch <-chan struct{}, what string) {
	t.Helper()
	select {
	case <-ch:
	case <-time.After(5 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
	}
}

func TestCreateListMetadata(t *testing.T) {
	m, b := newTestManager(t, Options{})
	s1, _ := mustCreate(t, m, Request{Kind: KindShell})
	s2, _ := mustCreate(t, m, Request{Kind: KindAgent, Command: "echo hi", Title: "work"})

	list := m.List()
	if len(list) != 2 {
		t.Fatalf("list %d, want 2", len(list))
	}
	if list[0].ID != s1.ID() || list[1].ID != s2.ID() {
		t.Fatalf("order wrong: %s %s", list[0].ID, list[1].ID)
	}

	meta := s2.Meta()
	if meta.Title != "work" || meta.Kind != KindAgent || meta.Command != "echo hi" {
		t.Fatalf("meta: %+v", meta)
	}
	if len(meta.ID) != 64 {
		t.Fatalf("id length %d, want 64", len(meta.ID))
	}
	if !meta.Running {
		t.Fatal("not running")
	}
	if meta.Cols != 80 || meta.Rows != 24 {
		t.Fatalf("default size %dx%d", meta.Cols, meta.Rows)
	}

	m1 := s1.Meta()
	if m1.Title != "shell" || m1.Kind != KindShell {
		t.Fatalf("shell meta: %+v", m1)
	}
	if !strings.Contains(m1.Command, m.shellPath) {
		t.Fatalf("shell command display %q", m1.Command)
	}

	// The backend request carries the session env overrides.
	req := b.lastReq
	found := map[string]string{}
	for _, kv := range req.Env {
		i := strings.IndexByte(kv, '=')
		found[kv[:i]] = kv[i+1:]
	}
	if found["REMOTLY_SESSION"] != s2.ID() {
		t.Fatalf("REMOTLY_SESSION=%q", found["REMOTLY_SESSION"])
	}
	if found["TERM"] != "xterm-256color" {
		t.Fatalf("TERM=%q", found["TERM"])
	}
	if found["COLORTERM"] != "truecolor" {
		t.Fatalf("COLORTERM=%q", found["COLORTERM"])
	}
}

func TestCreateValidation(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	cases := []struct {
		name string
		req  Request
	}{
		{"bad kind", Request{Kind: "nope"}},
		{"oversized title", Request{Kind: KindShell, Title: strings.Repeat("a", MaxTitleLen+1)}},
		{"invalid utf8 title", Request{Kind: KindShell, Title: "bad\xff"}},
		{"shell with command", Request{Kind: KindShell, Command: "echo"}},
		{"agent without command", Request{Kind: KindAgent}},
		{"oversized command", Request{Kind: KindAgent, Command: strings.Repeat("a", MaxCommandLen+1)}},
		{"invalid utf8 command", Request{Kind: KindAgent, Command: "echo\xff"}},
		{"nul command", Request{Kind: KindAgent, Command: "e\x00cho"}},
		{"huge cols", Request{Kind: KindShell, Cols: pty.MaxCols + 1}},
		{"huge rows", Request{Kind: KindShell, Rows: pty.MaxRows + 1}},
		{"bad cwd", Request{Kind: KindShell, Cwd: "/nonexistent/remotly-test"}},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			if _, err := m.Create(c.req); !errors.Is(err, ErrInvalidRequest) {
				t.Fatalf("err %v, want ErrInvalidRequest", err)
			}
		})
	}
	if n := len(m.List()); n != 0 {
		t.Fatalf("sessions created: %d", n)
	}
}

func TestCreateZeroColsIsDefault(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})
	meta := s.Meta()
	if meta.Cols != 80 || meta.Rows != 24 {
		t.Fatalf("size %dx%d, want 80x24", meta.Cols, meta.Rows)
	}
}

func TestCapacity(t *testing.T) {
	m, _ := newTestManager(t, Options{MaxSessions: 2})
	s1, _ := mustCreate(t, m, Request{Kind: KindShell})
	s2, _ := mustCreate(t, m, Request{Kind: KindShell})
	if _, err := m.Create(Request{Kind: KindShell}); !errors.Is(err, ErrCapacity) {
		t.Fatalf("err %v, want ErrCapacity", err)
	}
	// A slot frees once the session exits and is reaped.
	s2.terminateForTest(t)
	waitFor(t, s2.Done(), "exit")
	s, err := m.Create(Request{Kind: KindShell})
	if err != nil {
		t.Fatalf("create after exit: %v", err)
	}
	_ = s
	_ = s1
}

// terminateForTest ends the session's underlying fake process.
func (s *Session) terminateForTest(t *testing.T) {
	t.Helper()
	p, ok := s.proc.(*fakeProc)
	if !ok {
		t.Fatal("fake proc expected")
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
}

func TestIDCollision(t *testing.T) {
	fixed := 0
	m, b := newTestManager(t, Options{IDGen: func() string {
		fixed++
		return "same-id"
	}})
	if _, err := m.Create(Request{Kind: KindShell}); err != nil {
		t.Fatalf("first: %v", err)
	}
	if _, err := m.Create(Request{Kind: KindShell}); !errors.Is(err, ErrIDCollision) {
		t.Fatalf("second: %v, want ErrIDCollision", err)
	}
	// The rejected start must not leak a live process.
	if p := b.proc(1); !p.killedFlag() {
		t.Fatal("collision start not killed")
	}
}

func TestInputRouting(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	before := s.Meta().LastActivity
	if _, err := s.Write([]byte("echo hi\n")); err != nil {
		t.Fatal(err)
	}
	in := p.input()
	if len(in) != 1 || string(in[0]) != "echo hi\n" {
		t.Fatalf("input %q", in)
	}
	if s.Meta().LastActivity == before {
		t.Fatal("last activity not updated")
	}
}

func TestOversizedInput(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	big := make([]byte, MaxInputChunk+1)
	if _, err := s.Write(big); !errors.Is(err, ErrInvalidRequest) {
		t.Fatalf("err %v, want ErrInvalidRequest", err)
	}
	if len(p.input()) != 0 {
		t.Fatal("oversized input routed")
	}
}

func TestWriteAfterExit(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	if _, err := s.Write([]byte("x")); !errors.Is(err, ErrSessionExited) {
		t.Fatalf("err %v, want ErrSessionExited", err)
	}
}

func TestResize(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	if err := s.Resize(120, 40); err != nil {
		t.Fatal(err)
	}
	if r := p.resizes(); len(r) != 1 || r[0] != [2]uint16{120, 40} {
		t.Fatalf("resizes %v", r)
	}
	meta := s.Meta()
	if meta.Cols != 120 || meta.Rows != 40 {
		t.Fatalf("meta size %dx%d", meta.Cols, meta.Rows)
	}
	for _, bad := range [][2]uint16{{0, 24}, {1001, 24}, {80, 0}, {80, 1001}} {
		if err := s.Resize(bad[0], bad[1]); err == nil {
			t.Fatalf("resize %v accepted", bad)
		}
	}
}

func TestResizeAfterExit(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	if err := s.Resize(80, 24); !errors.Is(err, ErrSessionExited) {
		t.Fatalf("err %v, want ErrSessionExited", err)
	}
}

func TestNaturalExit(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.terminate(pty.ExitStatus{Exited: true, Code: 3})
	waitFor(t, s.Done(), "exit")
	meta := s.Meta()
	if meta.Running {
		t.Fatal("still running")
	}
	if meta.Exit.Code != 3 {
		t.Fatalf("exit %+v", meta.Exit)
	}
	if st := m.Stats(); st.Exited != 1 {
		t.Fatalf("stats %+v", st)
	}
	// The exited session stays listed with Running=false for its retention
	// window, where it remains attachable for final replay, and it no
	// longer counts toward the session limit.
	got := m.List()
	if len(got) != 1 || got[0].Running || got[0].ID != s.ID() {
		t.Fatalf("list %+v", got)
	}
}

func TestRetiredDoesNotCountTowardLimit(t *testing.T) {
	m, _ := newTestManager(t, Options{MaxSessions: 1})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	// The live slot is free again even though the exited session is still
	// retained for replay.
	s2, p2 := mustCreate(t, m, Request{Kind: KindShell})
	defer s2.Kill()
	defer p2.terminate(pty.ExitStatus{Exited: true, Code: 0})
	if _, err := m.Create(Request{Kind: KindShell}); !errors.Is(err, ErrCapacity) {
		t.Fatalf("err %v, want ErrCapacity", err)
	}
}

// A killed session leaves the list. Retention exists so a shell that ended on
// its own can still be reattached for its final output; a kill is the user
// saying they are finished, and keeping those listed filled the session list
// with shells they had already closed.
func TestKilledSessionIsNotRetained(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})
	if err := s.Kill(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, s.Done(), "exit")
	if got := len(m.List()); got != 0 {
		t.Fatalf("List has %d entries after a kill, want 0", got)
	}
	if _, err := m.Get(s.ID()); !errors.Is(err, ErrUnknownSession) {
		t.Fatalf("Get after kill = %v, want ErrUnknownSession", err)
	}
}

// A shell that exits by itself is still retained, which is what makes the
// final output readable after the fact.
func TestNaturalExitIsRetained(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	if got := len(m.List()); got != 1 {
		t.Fatalf("List has %d entries after a natural exit, want 1", got)
	}
}

// Killing a session that already exited drops it immediately rather than
// leaving it to sit out its retention window.
func TestKillAfterExitDropsTheRetainedSession(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	if len(m.List()) != 1 {
		t.Fatal("want the exited session retained before the kill")
	}
	if err := s.Kill(); err != nil {
		t.Fatalf("kill after exit: %v", err)
	}
	if got := len(m.List()); got != 0 {
		t.Fatalf("List has %d entries, want 0", got)
	}
}

func TestKillIdempotent(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})
	if err := s.Kill(); err != nil {
		t.Fatal(err)
	}
	waitFor(t, s.Done(), "exit")
	if err := s.Kill(); err != nil {
		t.Fatalf("second kill: %v", err)
	}
	meta := s.Meta()
	if meta.Exit.Signal != "KILL" {
		t.Fatalf("exit %+v", meta.Exit)
	}
	if st := m.Stats(); st.Killed != 1 {
		t.Fatalf("stats %+v", st)
	}
}

func TestGetUnknown(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	if _, err := m.Get("missing"); !errors.Is(err, ErrUnknownSession) {
		t.Fatalf("err %v, want ErrUnknownSession", err)
	}
}

func TestStartFailure(t *testing.T) {
	b := &fakeBackend{failNext: 1}
	m, err := New(Options{Backend: b})
	if err != nil {
		t.Fatal(err)
	}
	_, err = m.Create(Request{Kind: KindShell})
	if err == nil {
		t.Fatal("start failure not reported")
	}
	if len(m.List()) != 0 || b.count() != 0 {
		t.Fatal("session left behind")
	}
}

func TestShutdown(t *testing.T) {
	m, b := newTestManager(t, Options{})
	s1, _ := mustCreate(t, m, Request{Kind: KindShell})
	s2, p2 := mustCreate(t, m, Request{Kind: KindShell})
	p2.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s2.Done(), "exit")

	if err := m.Shutdown(); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	waitFor(t, s1.Done(), "exit")
	if len(m.List()) != 0 {
		t.Fatal("sessions remain after shutdown")
	}
	// proc(0) was killed and closed by Shutdown; proc(1) exited on its own
	// and was closed by the session's wait loop.
	if p := b.proc(0); !p.killedFlag() || !p.closedFlag() {
		t.Fatalf("proc(0) state killed=%v closed=%v", p.killedFlag(), p.closedFlag())
	}
	if p := b.proc(1); p.killedFlag() || !p.closedFlag() {
		t.Fatalf("proc(1) state killed=%v closed=%v", p.killedFlag(), p.closedFlag())
	}
	// Create after shutdown works again.
	if _, err := m.Create(Request{Kind: KindShell}); err != nil {
		t.Fatalf("create after shutdown: %v", err)
	}
}

func TestShutdownZeroSessions(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	if err := m.Shutdown(); err != nil {
		t.Fatal(err)
	}
}

func TestDetachDoesNotAffectLifetime(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}
	if !s.running() {
		t.Fatal("session died after detach")
	}
	if p.killedFlag() {
		t.Fatal("process killed by detach")
	}
}

func TestConcurrentLifecycle(t *testing.T) {
	m, b := newTestManager(t, Options{MaxSessions: 16})
	var wg sync.WaitGroup
	stop := make(chan struct{})

	for i := 0; i < 8; i++ {
		wg.Add(1)
		go func(n int) {
			defer wg.Done()
			for {
				select {
				case <-stop:
					return
				default:
				}
				s, err := m.Create(Request{Kind: KindShell, Cols: 100, Rows: 30})
				if err != nil {
					continue
				}
				s.Write([]byte("x\n"))
				_ = s.Resize(110, 32)
				meta := s.Meta()
				_ = meta.ID
				_ = s.Kill()
				time.Sleep(200 * time.Microsecond)
			}
		}(i)
	}
	wg.Add(1)
	go func() {
		defer wg.Done()
		for {
			select {
			case <-stop:
				return
			default:
			}
			_ = m.List()
			time.Sleep(time.Millisecond)
		}
	}()

	time.Sleep(500 * time.Millisecond)
	close(stop)
	wg.Wait()

	// Shutdown kills and reaps every remaining session.
	if err := m.Shutdown(); err != nil {
		t.Fatalf("shutdown: %v", err)
	}
	if got := len(m.List()); got != 0 {
		t.Fatalf("leftover sessions: %d", got)
	}
	if b.count() == 0 {
		t.Fatal("no sessions were started")
	}
}

// killedFlag reports whether Kill was called on the fake.
func (f *fakeProc) killedFlag() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.killed
}

// closedFlag reports whether Close was called on the fake.
func (f *fakeProc) closedFlag() bool {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.closed
}

func TestSignalRouting(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	if err := s.Signal(os.Interrupt); err != nil {
		t.Fatal(err)
	}
	if len(p.signals) != 1 || p.signals[0] != os.Interrupt {
		t.Fatalf("signals %v", p.signals)
	}
}

func TestTitleLimitBoundary(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	ok := Request{Kind: KindShell, Title: strings.Repeat("t", MaxTitleLen)}
	if _, err := m.Create(ok); err != nil {
		t.Fatalf("boundary title rejected: %v", err)
	}
}
