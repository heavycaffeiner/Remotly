package session

import (
	"bytes"
	"errors"

	"strings"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// waitForCond polls cond until it holds or the timeout elapses.
func waitForCond(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(2 * time.Millisecond)
	}
	t.Fatalf("timed out after %v waiting for %s", timeout, what)
}

// drainAll drains an attachment to EOF and returns the stream.
func drainAll(t *testing.T, at *Attachment) []byte {
	t.Helper()
	out, _ := readAll(t, at)
	return out
}

// readN reads exactly n bytes from a live attachment, failing on timeout.
func readN(t *testing.T, at *Attachment, n int) []byte {
	t.Helper()
	type res struct {
		n   int
		err error
	}
	buf := make([]byte, n)
	done := make(chan res, 1)
	go func() {
		total := 0
		for total < n {
			m, err := at.Read(buf[total:])
			total += m
			if err != nil {
				done <- res{total, err}
				return
			}
		}
		done <- res{total, nil}
	}()
	select {
	case r := <-done:
		if r.err != nil {
			t.Fatalf("read: %v (have %d of %d)", r.err, r.n, n)
		}
		return buf[:r.n]
	case <-time.After(5 * time.Second):
		t.Fatalf("read %d of %d bytes within timeout", n, n)
	}
	return nil
}

// waitRingTotal waits until the session's cumulative output reaches total.
func waitRingTotal(t *testing.T, s *Session, total int64) {
	t.Helper()
	waitForCond(t, 5*time.Second, "output to reach the ring", func() bool {
		s.mu.Lock()
		defer s.mu.Unlock()
		return s.totalBytes >= total
	})
}

func TestAttachFullNoCursor(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.push([]byte("hello "))
	p.push([]byte("world\n"))
	waitRingTotal(t, s, 12)
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")

	at, info, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	defer at.Close()
	if info.Continuity != protocol.ContinuityFull || info.ReplayedFrom != 0 {
		t.Fatalf("info %+v", info)
	}
	if got := drainAll(t, at); !strings.Contains(string(got), "hello world\n") {
		t.Fatalf("replay %q", got)
	}
}

func TestAttachResumeGapless(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.push([]byte("hello "))
	p.push([]byte("world\nline2\n"))
	waitRingTotal(t, s, 18)
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")

	at, info, err := s.AttachFrom(6)
	if err != nil {
		t.Fatal(err)
	}
	defer at.Close()
	if info.Continuity != protocol.ContinuityGapless || info.ReplayedFrom != 6 {
		t.Fatalf("info %+v", info)
	}
	got := drainAll(t, at)
	if string(got) != "world\nline2\n" {
		t.Fatalf("replay from 6 = %q, want %q", got, "world\nline2\n")
	}
}

func TestAttachResumeAtExactEnd(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.push([]byte("abc"))
	waitRingTotal(t, s, 3)

	at, info, err := s.AttachFrom(3)
	if err != nil {
		t.Fatal(err)
	}
	defer at.Close()
	if info.Continuity != protocol.ContinuityGapless || info.ReplayedFrom != 3 {
		t.Fatalf("info %+v", info)
	}
	p.push([]byte("def"))
	if got := readN(t, at, 3); string(got) != "def" {
		t.Fatalf("live after resume = %q, want %q", got, "def")
	}
}

func TestAttachResumeGap(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 4})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	// 10 lines of 100 bytes; the ring keeps the last 4 lines (400 bytes),
	// so a cursor at byte 50 is older than the retained window.
	for i := 0; i < 10; i++ {
		line := make([]byte, 100)
		for j := range line {
			line[j] = byte('a' + i%26)
		}
		line[99] = '\n'
		p.push(line)
	}
	waitRingTotal(t, s, 1000)
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")

	at, info, err := s.AttachFrom(50)
	if err != nil {
		t.Fatal(err)
	}
	defer at.Close()
	if info.Continuity != protocol.ContinuityGap {
		t.Fatalf("info %+v", info)
	}
	s.mu.Lock()
	ringStart := uint64(s.totalBytes) - uint64(s.ring.len())
	s.mu.Unlock()
	if info.ReplayedFrom != ringStart {
		t.Fatalf("replayed_from %d, want ring start %d", info.ReplayedFrom, ringStart)
	}
	got := drainAll(t, at)
	if len(got) != int(ringStart) && len(got) != s.ring.len() {
		t.Fatalf("replay %d bytes, want ring size %d", len(got), s.ring.len())
	}
	// The gap means bytes [50, ringStart) are lost: the stream must start
	// at the retained window's oldest byte.
	s.mu.Lock()
	want := bytes.Clone(s.ring.snapshot())
	s.mu.Unlock()
	if !bytes.Equal(got, want) {
		t.Fatalf("gap replay != ring contents")
	}
}

func TestAttachResumeOutOfRange(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.push([]byte("abc"))
	waitRingTotal(t, s, 3)

	if _, _, err := s.AttachFrom(4); !errors.Is(err, ErrCursorOutOfRange) {
		t.Fatalf("err %v, want ErrCursorOutOfRange", err)
	}
	if _, _, err := s.AttachFrom(1 << 40); !errors.Is(err, ErrCursorOutOfRange) {
		t.Fatalf("err %v, want ErrCursorOutOfRange", err)
	}
}

func TestAttachAfterExitReplaysFull(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.push([]byte("final words\n"))
	waitRingTotal(t, s, 12)
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")

	// A late attach still serves the retained stream, then EOF.
	at, info, err := s.AttachFrom(0)
	if err != nil {
		t.Fatal(err)
	}
	defer at.Close()
	if info.Continuity != protocol.ContinuityGapless || info.ReplayedFrom != 0 {
		t.Fatalf("info %+v", info)
	}
	got := drainAll(t, at)
	if string(got) != "final words\n" {
		t.Fatalf("replay %q", got)
	}
	if at.Reason() != ReasonExited {
		t.Fatalf("reason %v", at.Reason())
	}
}

func TestExitedRetentionAndPurge(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024, RetainedAfterExit: 100 * time.Millisecond})

	s, p := mustCreate(t, m, Request{Kind: KindShell})
	p.push([]byte("kept\n"))
	waitRingTotal(t, s, 5)
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")

	// Within the window: listed with Running=false, attachable, and Get
	// resolves it.
	waitForCond(t, 5*time.Second, "retention entry", func() bool {
		got := m.List()
		return len(got) == 1 && !got[0].Running
	})
	if got, err := m.Get(s.ID()); err != nil || got.ID() != s.ID() {
		t.Fatalf("get: %v", err)
	}
	at, info, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	defer at.Close()
	if info.Continuity != protocol.ContinuityFull {
		t.Fatalf("info %+v", info)
	}
	if got := drainAll(t, at); string(got) != "kept\n" {
		t.Fatalf("replay %q", got)
	}

	// After the window: gone from the list and unresolvable.
	time.Sleep(150 * time.Millisecond)
	m.purgeRetired(time.Now())
	if got := m.List(); len(got) != 0 {
		t.Fatalf("list after purge %+v", got)
	}
	if _, err := m.Get(s.ID()); !errors.Is(err, ErrUnknownSession) {
		t.Fatalf("get after purge: %v", err)
	}
}

func TestRetiredSetBounded(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024, RetainedAfterExit: time.Hour})

	for i := 0; i < maxRetiredSessions+2; i++ {
		s, p := mustCreate(t, m, Request{Kind: KindShell})
		p.terminate(pty.ExitStatus{Exited: true, Code: 0})
		waitFor(t, s.Done(), "exit")
	}
	m.mu.Lock()
	n := len(m.retired)
	m.mu.Unlock()
	if n != maxRetiredSessions {
		t.Fatalf("retired = %d, want bound %d", n, maxRetiredSessions)
	}
}
