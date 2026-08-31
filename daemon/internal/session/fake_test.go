package session

import (
	"errors"
	"io"
	"os"
	"sync"
	"testing"

	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// fakeProc is a controllable pty.Process for unit tests. The test drives
// output by pushing chunks to out and termination via terminate; Kill and
// Close model the real backend by closing the output stream.
type fakeProc struct {
	out     chan []byte
	outOnce sync.Once
	pend    []byte

	exitCh    chan pty.ExitStatus
	closeCh   chan struct{}
	closeOnce sync.Once

	mu        sync.Mutex
	inputBuf  [][]byte
	resizeBuf [][2]uint16
	signals   []os.Signal
	killed    bool
	closed    bool
}

func newFakeProc() *fakeProc {
	return &fakeProc{
		out:     make(chan []byte, 256),
		exitCh:  make(chan pty.ExitStatus, 2),
		closeCh: make(chan struct{}),
	}
}

func (f *fakeProc) push(b []byte) {
	f.out <- append([]byte(nil), b...)
}

// terminate ends the stream and reports the exit status, like a process
// exiting on its own.
func (f *fakeProc) terminate(st pty.ExitStatus) {
	f.outOnce.Do(func() { close(f.out) })
	select {
	case f.exitCh <- st:
	default:
	}
}

func (f *fakeProc) Read(p []byte) (int, error) {
	for len(f.pend) == 0 {
		chunk, ok := <-f.out
		if !ok {
			return 0, io.EOF
		}
		f.pend = chunk
	}
	n := copy(p, f.pend)
	f.pend = f.pend[n:]
	return n, nil
}

func (f *fakeProc) Write(p []byte) (int, error) {
	f.mu.Lock()
	f.inputBuf = append(f.inputBuf, append([]byte(nil), p...))
	f.mu.Unlock()
	return len(p), nil
}

func (f *fakeProc) input() [][]byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]byte(nil), f.inputBuf...)
}

func (f *fakeProc) Resize(c, r uint16) error {
	f.mu.Lock()
	f.resizeBuf = append(f.resizeBuf, [2]uint16{c, r})
	f.mu.Unlock()
	return nil
}

func (f *fakeProc) resizes() [][2]uint16 {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][2]uint16(nil), f.resizeBuf...)
}

func (f *fakeProc) Signal(sig os.Signal) error {
	f.mu.Lock()
	f.signals = append(f.signals, sig)
	f.mu.Unlock()
	return nil
}

func (f *fakeProc) Kill() error {
	f.mu.Lock()
	if f.killed {
		f.mu.Unlock()
		return nil
	}
	f.killed = true
	f.mu.Unlock()
	f.outOnce.Do(func() { close(f.out) })
	select {
	case f.exitCh <- pty.ExitStatus{Exited: true, Code: -1, Signal: "KILL"}:
	default:
	}
	return nil
}

func (f *fakeProc) Wait() pty.ExitStatus {
	st, ok := <-f.exitCh
	if !ok {
		return pty.ExitStatus{Exited: true, Code: -1}
	}
	return st
}

func (f *fakeProc) Close() error {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return nil
	}
	f.closed = true
	f.mu.Unlock()
	f.outOnce.Do(func() { close(f.out) })
	f.closeOnce.Do(func() { close(f.closeCh) })
	return nil
}

// fakeBackend hands out fresh fakeProc values and records start requests.
type fakeBackend struct {
	mu       sync.Mutex
	starts   int
	failNext int
	procs    []*fakeProc
	lastReq  pty.StartRequest
}

func (b *fakeBackend) Start(req pty.StartRequest) (pty.Process, error) {
	b.mu.Lock()
	b.starts++
	fail := b.failNext > 0
	if fail {
		b.failNext--
	}
	b.lastReq = req
	b.mu.Unlock()
	if fail {
		return nil, errors.New("fake: start failed")
	}
	p := newFakeProc()
	b.mu.Lock()
	b.procs = append(b.procs, p)
	b.mu.Unlock()
	return p, nil
}

func (b *fakeBackend) proc(n int) *fakeProc {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.procs[n]
}

func (b *fakeBackend) count() int {
	b.mu.Lock()
	defer b.mu.Unlock()
	return len(b.procs)
}

// newTestManager builds a manager on a fake backend. All sessions are
// killed at test end so no fake process outlives its test.
func newTestManager(t *testing.T, opts Options) (*Manager, *fakeBackend) {
	t.Helper()
	b := &fakeBackend{}
	opts.Backend = b
	m, err := New(opts)
	if err != nil {
		t.Fatalf("new manager: %v", err)
	}
	t.Cleanup(func() { _ = m.Shutdown() })
	return m, b
}
