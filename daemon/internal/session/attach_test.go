package session

import (
	"bytes"
	"fmt"
	"io"
	"math/rand"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// readAll drains an attachment until EOF and returns the bytes and reason.
func readAll(t *testing.T, a *Attachment) ([]byte, DetachReason) {
	t.Helper()
	var out bytes.Buffer
	buf := make([]byte, 64<<10)
	deadline := time.After(10 * time.Second)
	for {
		select {
		case <-deadline:
			t.Fatalf("read timeout; got %d bytes", out.Len())
		default:
		}
		n, err := a.Read(buf)
		if n > 0 {
			out.Write(buf[:n])
		}
		if err == io.EOF {
			return out.Bytes(), a.Reason()
		}
		if err != nil {
			t.Fatalf("read: %v", err)
		}
	}
}

func chunkSeq(n int, size int) [][]byte {
	chunks := make([][]byte, n)
	for i := range chunks {
		c := make([]byte, size)
		for j := range c {
			c[j] = byte('a' + (i % 26))
		}
		chunks[i] = c
	}
	return chunks
}

func join(b [][]byte) []byte {
	var out bytes.Buffer
	for _, c := range b {
		out.Write(c)
	}
	return out.Bytes()
}

func TestAttachBeforeOutput(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	chunks := chunkSeq(10, 100)
	for _, c := range chunks {
		p.push(c)
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	got, reason := readAll(t, a)
	if !bytes.Equal(got, join(chunks)) {
		t.Fatalf("stream mismatch: got %d bytes, want %d", len(got), len(join(chunks)))
	}
	if reason != ReasonExited {
		t.Fatalf("reason %v", reason)
	}
}

func TestAttachDuringOutput(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	all := chunkSeq(6, 500)
	for _, c := range all[:3] {
		p.push(c)
	}
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	for _, c := range all[3:] {
		p.push(c)
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	got, reason := readAll(t, a)
	if !bytes.Equal(got, join(all)) {
		t.Fatalf("replay+live mismatch: got %d want %d", len(got), len(join(all)))
	}
	if reason != ReasonExited {
		t.Fatalf("reason %v", reason)
	}
}

func TestAttachAfterExit(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	all := chunkSeq(5, 200)
	for _, c := range all {
		p.push(c)
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	waitFor(t, s.Done(), "exit")
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	got, reason := readAll(t, a)
	if !bytes.Equal(got, join(all)) {
		t.Fatalf("retained stream mismatch: got %d want %d", len(got), len(join(all)))
	}
	if reason != ReasonExited {
		t.Fatalf("reason %v", reason)
	}
}

func TestDetachAndReattach(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	a1, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	p.push([]byte("first\n"))
	time.Sleep(50 * time.Millisecond)
	if err := a1.Close(); err != nil {
		t.Fatal(err)
	}
	if got := a1.Reason(); got != ReasonCancelled {
		t.Fatalf("reason %v, want cancelled", got)
	}
	p.push([]byte("second\n"))
	a2, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	got, _ := readAll(t, a2)
	// The reattachment replays the full retained stream.
	if !bytes.Equal(got, []byte("first\nsecond\n")) {
		t.Fatalf("reattach stream %q", got)
	}
}

func TestMultipleReadersSameStream(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	a1, _, _ := s.Attach()
	a2, _, _ := s.Attach()
	all := chunkSeq(8, 300)
	for _, c := range all {
		p.push(c)
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	g1, r1 := readAll(t, a1)
	g2, r2 := readAll(t, a2)
	if !bytes.Equal(g1, join(all)) || !bytes.Equal(g2, join(all)) {
		t.Fatalf("reader streams differ: %d %d vs want %d", len(g1), len(g2), len(join(all)))
	}
	if r1 != ReasonExited || r2 != ReasonExited {
		t.Fatalf("reasons %v %v", r1, r2)
	}
}

// waitFastLen waits until the fast pump has copied at least want bytes.
func waitFastLen(t *testing.T, mu *sync.Mutex, out *bytes.Buffer, want int) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for {
		mu.Lock()
		n := out.Len()
		mu.Unlock()
		if n >= want {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("fast pump stalled at %d of %d bytes", n, want)
		}
		time.Sleep(time.Millisecond)
	}
}

func TestSlowReaderDropped(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	slow, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	fast, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	// "Fast" means actively drained: a reader that is never read overflows
	// too. Pump fast into a buffer in a goroutine so its queue stays empty
	// and only the never-read slow reader overflows.
	var fastMu sync.Mutex
	var fastOut bytes.Buffer
	fastDone := make(chan struct{})
	go func() {
		defer close(fastDone)
		buf := make([]byte, 64<<10)
		for {
			n, err := fast.Read(buf)
			if n > 0 {
				fastMu.Lock()
				fastOut.Write(buf[:n])
				fastMu.Unlock()
			}
			if err != nil {
				return
			}
		}
	}()
	// Do not read from slow at all. Push past its queue capacity so it is
	// dropped; the fast reader must keep receiving everything. Push in
	// small batches and wait for the fast pump to drain between batches,
	// so the fast queue can never overflow and the test is deterministic
	// rather than a scheduling race.
	var all [][]byte
	want := 0
	for i := 0; i < attachmentQueue+100; i++ {
		c := []byte(fmt.Sprintf("line-%d\n", i))
		all = append(all, c)
		want += len(c)
		p.push(c)
		if (i+1)%16 == 0 {
			waitFastLen(t, &fastMu, &fastOut, want)
		}
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	<-fastDone
	fastMu.Lock()
	got := append([]byte(nil), fastOut.Bytes()...)
	fastMu.Unlock()
	if !bytes.Equal(got, join(all)) {
		t.Fatalf("fast reader lost data: got %d want %d", len(got), len(join(all)))
	}
	if reason := fast.Reason(); reason != ReasonExited {
		t.Fatalf("fast reason %v, want exited", reason)
	}
	// The slow reader must have been dropped for overflow. Its queue is
	// closed, so Read returns whatever it buffered and then EOF.
	buf := make([]byte, 64<<10)
	var slowOut bytes.Buffer
	for {
		n, err := slow.Read(buf)
		if n > 0 {
			slowOut.Write(buf[:n])
		}
		if err == io.EOF {
			break
		}
		if err != nil {
			t.Fatal(err)
		}
	}
	if got := slow.Reason(); got != ReasonOverflow {
		t.Fatalf("slow reason %v, want overflow", got)
	}
	if st := m.Stats(); st.ReadersOverflowed != 1 {
		t.Fatalf("stats %+v", st)
	}
	// The drain kept flowing after the drop: a fresh attach replays the full
	// retained stream, which fits within the caps.
	fresh, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	retained, _ := readAll(t, fresh)
	if !bytes.Equal(retained, join(all)) {
		t.Fatalf("ring lost data after drop: got %d want %d", len(retained), len(join(all)))
	}
}

func TestZeroReadersDrains(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	big := make([]byte, 1<<20)
	for i := range big {
		big[i] = 'x'
	}
	// Push 5 MiB with no readers; the drain must keep up (no block).
	done := make(chan struct{})
	go func() {
		for i := 0; i < 5; i++ {
			p.push(big)
		}
		p.terminate(pty.ExitStatus{Exited: true, Code: 0})
		close(done)
	}()
	waitFor(t, done, "push")
	waitFor(t, s.Done(), "exit")
	// Attach now: the ring retained a bounded tail of the stream.
	a, _, err := s.Attach()
	if err != nil {
		t.Fatal(err)
	}
	got, reason := readAll(t, a)
	if len(got) == 0 {
		t.Fatal("ring empty after large stream")
	}
	if len(got) > maxRingBytes {
		t.Fatalf("ring exceeded byte cap: %d", len(got))
	}
	if reason != ReasonExited {
		t.Fatalf("reason %v", reason)
	}
}

func TestMaxAttachments(t *testing.T) {
	m, _ := newTestManager(t, Options{MaxAttachments: 2})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})
	if _, _, err := s.Attach(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Attach(); err != nil {
		t.Fatal(err)
	}
	if _, _, err := s.Attach(); err == nil {
		t.Fatal("third attachment accepted")
	}
}

func TestSimultaneousInput(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	const n = 8
	var wg sync.WaitGroup
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			payload := []byte(fmt.Sprintf("caller-%d\n", i))
			for j := 0; j < 100; j++ {
				if _, err := s.Write(payload); err != nil {
					t.Error(err)
					return
				}
			}
		}(i)
	}
	wg.Wait()
	in := p.input()
	if len(in) != n*100 {
		t.Fatalf("input chunks %d, want %d", len(in), n*100)
	}
	var total int
	counts := map[string]int{}
	for _, c := range in {
		total += len(c)
		counts[string(c)]++
	}
	if total != n*100*len("caller-0\n") {
		t.Fatalf("total bytes %d", total)
	}
	for i := 0; i < n; i++ {
		if counts[fmt.Sprintf("caller-%d\n", i)] != 100 {
			t.Fatalf("caller %d count %d", i, counts[fmt.Sprintf("caller-%d\n", i)])
		}
	}
}

func TestReadSmallBuffer(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	a, _, _ := s.Attach()
	p.push([]byte("hello world"))
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	var out bytes.Buffer
	buf := make([]byte, 4)
	for {
		n, err := a.Read(buf)
		if n > 0 {
			out.Write(buf[:n])
		}
		if err == io.EOF {
			break
		}
	}
	if out.String() != "hello world" {
		t.Fatalf("got %q", out.String())
	}
}

func TestRingBinaryStream(t *testing.T) {
	m, _ := newTestManager(t, Options{ScrollbackLines: 1024})
	s, p := mustCreate(t, m, Request{Kind: KindShell})
	a, _, _ := s.Attach()
	rng := rand.New(rand.NewSource(1))
	var all bytes.Buffer
	for i := 0; i < 200; i++ {
		c := make([]byte, 300)
		rng.Read(c)
		all.Write(c)
		p.push(c)
	}
	p.terminate(pty.ExitStatus{Exited: true, Code: 0})
	got, _ := readAll(t, a)
	// The stream is 60 KiB, within caps, so it must be retained verbatim.
	if !bytes.Equal(got, all.Bytes()) {
		t.Fatalf("binary stream mismatch: got %d want %d", len(got), all.Len())
	}
}
