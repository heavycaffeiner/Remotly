package protocol

import (
	"errors"
	"runtime"
	"strconv"
	"sync"
	"testing"
	"time"
)

// recHandler records inbound frames.
type recHandler struct {
	mu     sync.Mutex
	frames []Frame
	err    error
}

func (r *recHandler) handle(f Frame) error {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.frames = append(r.frames, f)
	return r.err
}

func (r *recHandler) count() int {
	r.mu.Lock()
	defer r.mu.Unlock()
	return len(r.frames)
}

// testMux builds a mux with the default bounds unless cfg overrides them.
func openTerm(t *testing.T, m *Mux) uint32 {
	t.Helper()
	id, err := m.OpenTerm(nil)
	if err != nil {
		t.Fatalf("open term: %v", err)
	}
	return id
}

func testMux(cfg MuxConfig) (*Mux, *recHandler) {
	h := &recHandler{}
	cfg.CtrlHandler = h.handle
	cfg.EncodeClose = EncodeChannelClose
	m, err := NewMux(cfg)
	if err != nil {
		panic(err)
	}
	return m, h
}

func termFrame(id uint32, n byte) Frame {
	return Frame{Type: ChannelTerm, ID: id, Payload: []byte{n}}
}

func ctrlFrame(payload []byte) Frame {
	return Frame{Type: ChannelCtrl, ID: 0, Payload: payload}
}

func TestMuxControlPriority(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	id, err := m.OpenTerm(nil)
	if err != nil {
		t.Fatalf("open: %v", err)
	}
	if err := m.Send(termFrame(id, 't')); err != nil {
		t.Fatalf("send term: %v", err)
	}
	if err := m.Send(ctrlFrame([]byte(`{"id":1}`))); err != nil {
		t.Fatalf("send ctrl: %v", err)
	}
	f, err := m.Read()
	if err != nil || f.Type != ChannelCtrl {
		t.Fatalf("first frame: %+v, %v", f, err)
	}
	f, err = m.Read()
	if err != nil || f.Type != ChannelTerm || f.ID != id || f.Payload[0] != 't' {
		t.Fatalf("second frame: %+v, %v", f, err)
	}
}

func TestMuxRoundRobin(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	a := openTerm(t, m)
	b := openTerm(t, m)
	for i := 0; i < 3; i++ {
		if err := m.Send(termFrame(a, byte('a')+byte(i))); err != nil {
			t.Fatal(err)
		}
		if err := m.Send(termFrame(b, byte('A')+byte(i))); err != nil {
			t.Fatal(err)
		}
	}
	var got []uint32
	for i := 0; i < 6; i++ {
		f, err := m.Read()
		if err != nil {
			t.Fatalf("read %d: %v", i, err)
		}
		got = append(got, f.ID)
	}
	want := []uint32{a, b, a, b, a, b}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("order: got %v, want %v", got, want)
		}
	}
}

func TestMuxCloseAfterDrain(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	id := openTerm(t, m)
	for i := 0; i < 3; i++ {
		if err := m.Send(termFrame(id, byte(i))); err != nil {
			t.Fatal(err)
		}
	}
	if err := m.CloseTerm(id, ReasonDetached); err != nil {
		t.Fatalf("close: %v", err)
	}
	for i := 0; i < 3; i++ {
		f, err := m.Read()
		if err != nil || f.Type != ChannelTerm || f.ID != id {
			t.Fatalf("frame %d: %+v, %v", i, f, err)
		}
	}
	f, err := m.Read()
	if err != nil {
		t.Fatalf("close notice: %v", err)
	}
	if f.Type != ChannelCtrl {
		t.Fatalf("close notice type: %+v", f)
	}
	n, err := ParseNotification(f.Payload)
	if err != nil {
		t.Fatalf("parse close: %v", err)
	}
	if n.Type != TypeChannelClose || *n.ChannelID != id || *n.Reason != ReasonDetached {
		t.Fatalf("close notice: %+v", n)
	}
	// The channel is gone: further input is rejected.
	if err := m.Send(termFrame(id, 'x')); !errors.Is(err, ErrChannelClosed) {
		t.Fatalf("send after close: %v", err)
	}
	if err := m.Deliver(termFrame(id, 'x')); !errors.Is(err, ErrUnknownChannel) {
		t.Fatalf("deliver after close: %v", err)
	}
}

func TestMuxCloseOrderAcrossChannels(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	a := openTerm(t, m)
	b := openTerm(t, m)
	_ = m.Send(termFrame(a, 'a'))
	_ = m.CloseTerm(a, ReasonClosed)
	_ = m.Send(termFrame(b, 'b'))
	// a's notice must come after a's last frame; b is unconstrained.
	var events []string
	for i := 0; i < 3; i++ {
		f, err := m.Read()
		if err != nil {
			t.Fatalf("read %d: %v", i, err)
		}
		if f.Type == ChannelTerm {
			events = append(events, string(f.Payload[0]))
		} else {
			n, _ := ParseNotification(f.Payload)
			events = append(events, "close:"+strconv.FormatUint(uint64(*n.ChannelID), 10))
		}
	}
	lastA := -1
	for i, e := range events {
		if e == "a" {
			lastA = i
		}
	}
	if lastA < 0 {
		t.Fatalf("a frame never seen: %v", events)
	}
	closeA := -1
	for i, e := range events {
		if len(e) > 5 && e[:5] == "close" {
			closeA = i
		}
	}
	if closeA < lastA {
		t.Fatalf("close notice before last frame: %v", events)
	}
}

func TestMuxDropDiscardsQueue(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	id := openTerm(t, m)
	_ = m.Send(termFrame(id, 'a'))
	_ = m.Send(termFrame(id, 'b'))
	if err := m.DropTerm(id, ReasonOverflow); err != nil {
		t.Fatalf("drop: %v", err)
	}
	f, err := m.Read()
	if err != nil || f.Type != ChannelCtrl {
		t.Fatalf("expected immediate close notice, got %+v, %v", f, err)
	}
	n, _ := ParseNotification(f.Payload)
	if *n.Reason != ReasonOverflow || *n.ChannelID != id {
		t.Fatalf("notice: %+v", n)
	}
}

func TestMuxSendQueueBounds(t *testing.T) {
	m, _ := testMux(MuxConfig{QueueFrames: 2, QueueBytes: 1 << 20})
	id := openTerm(t, m)
	for i := 0; i < 2; i++ {
		if err := m.Send(termFrame(id, byte(i))); err != nil {
			t.Fatalf("send %d: %v", i, err)
		}
	}
	if err := m.Send(termFrame(id, 'x')); !errors.Is(err, ErrChannelFull) {
		t.Fatalf("frame cap: %v", err)
	}

	m2, _ := testMux(MuxConfig{QueueFrames: 10, QueueBytes: 10})
	id2 := openTerm(t, m2)
	pad := []byte("abcd")
	if err := m2.Send(Frame{Type: ChannelTerm, ID: id2, Payload: pad}); err != nil {
		t.Fatal(err)
	}
	if err := m2.Send(Frame{Type: ChannelTerm, ID: id2, Payload: pad}); err != nil {
		t.Fatal(err)
	}
	if err := m2.Send(Frame{Type: ChannelTerm, ID: id2, Payload: pad}); !errors.Is(err, ErrChannelFull) {
		t.Fatalf("byte cap: %v", err)
	}
}

func TestMuxCtrlQueueBounds(t *testing.T) {
	m, _ := testMux(MuxConfig{QueueFrames: 1, QueueBytes: 1 << 20})
	if err := m.Send(ctrlFrame([]byte("a"))); err != nil {
		t.Fatal(err)
	}
	if err := m.Send(ctrlFrame([]byte("b"))); !errors.Is(err, ErrChannelFull) {
		t.Fatalf("ctrl cap: %v", err)
	}
}

func TestMuxOpenTermIDs(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	ids := make([]uint32, 0, 3)
	for i := 0; i < 3; i++ {
		id, err := m.OpenTerm(nil)
		if err != nil {
			t.Fatal(err)
		}
		ids = append(ids, id)
	}
	for i, id := range ids {
		if id != uint32(2*i+1) {
			t.Fatalf("id %d: got %d, want %d", i, id, 2*i+1)
		}
	}
}

func TestMuxChannelLimit(t *testing.T) {
	m, _ := testMux(MuxConfig{MaxChannels: 2})
	if _, err := m.OpenTerm(nil); err != nil {
		t.Fatal(err)
	}
	if _, err := m.OpenTerm(nil); !errors.Is(err, ErrTooManyChannels) {
		t.Fatalf("limit: %v", err)
	}
}

func TestMuxDeliverRouting(t *testing.T) {
	m, ctrl := testMux(MuxConfig{})
	var got []byte
	id, err := m.OpenTerm(func(f Frame) error {
		got = append(got, f.Payload...)
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := m.Deliver(termFrame(id, 'h')); err != nil {
		t.Fatalf("deliver: %v", err)
	}
	if string(got) != "h" {
		t.Fatalf("handler: %q", got)
	}
	if err := m.Deliver(ctrlFrame([]byte(`{}`))); err != nil || ctrl.count() != 1 {
		t.Fatalf("ctrl deliver: %v, %d", err, ctrl.count())
	}
	if err := m.Deliver(termFrame(99, 'x')); !errors.Is(err, ErrUnknownChannel) {
		t.Fatalf("unknown channel: %v", err)
	}
	if err := m.Deliver(ctrlFrameWithID(5, []byte(`{}`))); !errors.Is(err, ErrCtrlFrame) {
		t.Fatalf("ctrl id: %v", err)
	}
	// The file channel type is enabled (M4); an unopened file channel id is an
	// unknown channel, not a bad type. (id 1 is the term channel opened above,
	// so use the next unopened odd id.)
	if err := m.Deliver(Frame{Type: ChannelFile, ID: 3, Payload: nil}); !errors.Is(err, ErrUnknownChannel) {
		t.Fatalf("file type: %v", err)
	}
}

func ctrlFrameWithID(id uint32, payload []byte) Frame {
	return Frame{Type: ChannelCtrl, ID: id, Payload: payload}
}

func TestMuxSendShape(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	if err := m.Send(ctrlFrameWithID(1, nil)); !errors.Is(err, ErrCtrlFrame) {
		t.Fatalf("ctrl id: %v", err)
	}
	// The file channel type is enabled (M4); sending on an unopened file
	// channel id is a closed channel, not a bad type.
	if err := m.Send(Frame{Type: ChannelFile, ID: 1}); !errors.Is(err, ErrChannelClosed) {
		t.Fatalf("file: %v", err)
	}
}

func TestMuxCloseDrains(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	id := openTerm(t, m)
	_ = m.Send(termFrame(id, 'a'))
	_ = m.CloseTerm(id, ReasonClosed)
	m.Close()
	f, err := m.Read()
	if err != nil || f.Type != ChannelTerm || f.Payload[0] != 'a' {
		t.Fatalf("queued frame: %+v, %v", f, err)
	}
	f, err = m.Read()
	if err != nil || f.Type != ChannelCtrl {
		t.Fatalf("close notice: %+v, %v", f, err)
	}
	if _, err := m.Read(); !errors.Is(err, ErrMuxClosed) {
		t.Fatalf("drained: %v", err)
	}
	if err := m.Send(ctrlFrame(nil)); !errors.Is(err, ErrMuxClosed) {
		t.Fatalf("send after close: %v", err)
	}
	if err := m.Deliver(ctrlFrame(nil)); !errors.Is(err, ErrMuxClosed) {
		t.Fatalf("deliver after close: %v", err)
	}
	if !m.Closed() {
		t.Fatal("closed flag")
	}
}

func TestMuxConcurrent(t *testing.T) {
	m, _ := testMux(MuxConfig{})
	var chans []uint32
	for i := 0; i < 4; i++ {
		id, err := m.OpenTerm(nil)
		if err != nil {
			t.Fatal(err)
		}
		chans = append(chans, id)
	}
	var wg sync.WaitGroup
	for ci, id := range chans {
		wg.Add(1)
		go func(ci int, id uint32) {
			defer wg.Done()
			for {
				err := m.Send(termFrame(id, byte(ci)))
				switch {
				case errors.Is(err, ErrMuxClosed):
					return
				case errors.Is(err, ErrChannelFull):
					// The reader is behind; backpressure is expected here.
					runtime.Gosched()
				case err != nil:
					t.Errorf("send: %v", err)
					return
				}
			}
		}(ci, id)
	}
	done := make(chan struct{})
	go func() {
		defer close(done)
		for {
			_, err := m.Read()
			if errors.Is(err, ErrMuxClosed) {
				return
			}
			if err != nil {
				t.Errorf("read: %v", err)
				return
			}
		}
	}()
	// Let the senders run, then tear the mux down.
	time.Sleep(50 * time.Millisecond)
	m.Close()
	wg.Wait()
	<-done
}
