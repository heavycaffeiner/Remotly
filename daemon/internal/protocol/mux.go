package protocol

import (
	"errors"
	"sync"
)

// Frame is one logical protocol frame, before or after sealing.
type Frame struct {
	Type    byte
	ID      uint32
	Payload []byte
}

// FrameHandler consumes an inbound frame on one channel. It runs in the
// connection reader goroutine and must not block long.
type FrameHandler func(f Frame) error

// EncodeClose renders a channel.close notification for the control channel.
// The mux injects it so it never has to know the control schema.
type EncodeClose func(id uint32, reason string) ([]byte, error)

// MuxConfig bounds one connection's multiplexer.
type MuxConfig struct {
	// MaxChannels is the largest number of channels, control included.
	MaxChannels int
	// QueueFrames and QueueBytes bound each channel's send queue.
	QueueFrames int
	QueueBytes  int
	// CtrlHandler receives control frames. Required.
	CtrlHandler FrameHandler
	// EncodeClose renders channel.close notifications. Required.
	EncodeClose EncodeClose
}

func (c *MuxConfig) fill() {
	if c.MaxChannels <= 0 {
		c.MaxChannels = MaxChannels
	}
	if c.QueueFrames <= 0 {
		c.QueueFrames = ChannelQueueCap
	}
	if c.QueueBytes <= 0 {
		c.QueueBytes = ChannelQueueBytes
	}
}

var errWait = errors.New("protocol: no frame available")

// channel is one connection-local term channel with a bounded send queue.
type channel struct {
	id      uint32
	handler FrameHandler

	queue   []Frame
	qFrames int
	qBytes  int
	qFrCap  int
	qByCap  int

	closing     bool // close requested; queue drains, then the notification is sent
	closed      bool // removed from the mux
	closeReason string
}

// Mux multiplexes the channels of one connection. It is safe for concurrent
// use: the reader goroutine calls Deliver, the writer goroutine calls Read,
// and request handlers call Send and CloseTerm.
//
// Send-side contract: every channel, control included, has a bounded FIFO
// queue. Send never blocks; a full or closed queue returns an error so the
// caller applies the documented backpressure rule for that channel type.
//
// Read-side contract: Read returns frames in per-channel order with control
// priority and round-robin fairness among term channels. A channel.close
// notification is emitted only after the channel's own queue is empty, so no
// frame can arrive after its close notice.
type Mux struct {
	cfg MuxConfig

	mu   sync.Mutex
	done chan struct{}
	sig  chan struct{}

	ctrlQ   []Frame
	ctrlFr  int
	ctrlByt int

	chans     map[uint32]*channel
	order     []*channel // allocation order for round robin
	rr        int
	nextOdd   uint32
	chanCount int
	closed    bool
}

// NewMux builds a multiplexer for one connection. The control channel (id 0)
// exists from the start.
func NewMux(cfg MuxConfig) (*Mux, error) {
	if cfg.CtrlHandler == nil || cfg.EncodeClose == nil {
		return nil, ErrCtrlFrame
	}
	cfg.fill()
	return &Mux{
		cfg:     cfg,
		done:    make(chan struct{}),
		sig:     make(chan struct{}, 1),
		chans:   make(map[uint32]*channel),
		nextOdd: 1,
	}, nil
}

// signal wakes a blocked Read. Callers hold m.mu.
func (m *Mux) signal() {
	select {
	case m.sig <- struct{}{}:
	default:
	}
}

// Send enqueues an outbound frame. It never blocks: a full or closed queue
// returns an error for the caller to handle.
func (m *Mux) Send(f Frame) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return ErrMuxClosed
	}
	if f.Type == ChannelCtrl {
		if f.ID != 0 {
			return ErrCtrlFrame
		}
		if m.ctrlFr+1 > m.cfg.QueueFrames || m.ctrlByt+len(f.Payload) > m.cfg.QueueBytes {
			return ErrChannelFull
		}
		m.ctrlQ = append(m.ctrlQ, f)
		m.ctrlFr++
		m.ctrlByt += len(f.Payload)
		m.signal()
		return nil
	}
	if f.Type != ChannelTerm && f.Type != ChannelFile {
		return ErrBadChannel
	}
	ch, ok := m.chans[f.ID]
	if !ok || ch.closing || ch.closed {
		return ErrChannelClosed
	}
	if ch.qFrames+1 > ch.qFrCap || ch.qBytes+len(f.Payload) > ch.qByCap {
		return ErrChannelFull
	}
	ch.queue = append(ch.queue, f)
	ch.qFrames++
	ch.qBytes += len(f.Payload)
	m.signal()
	return nil
}

// Read returns the next outbound frame, applying control priority and
// round-robin term fairness. It blocks until a frame is available or the
// mux is drained after Close, and returns ErrMuxClosed in that case.
func (m *Mux) Read() (Frame, error) {
	for {
		m.mu.Lock()
		f, err := m.take()
		m.mu.Unlock()
		switch {
		case err == nil:
			return f, nil
		case err == errWait:
			// nothing queued; wait for a sender or close
		default:
			return Frame{}, err
		}
		select {
		case <-m.done:
			return m.drain()
		case <-m.sig:
		}
	}
}

// drain serves everything left after Close. It returns the next frame or
// ErrMuxClosed once the queues are empty.
func (m *Mux) drain() (Frame, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	f, err := m.take()
	if err == errWait {
		return Frame{}, ErrMuxClosed
	}
	if err != nil {
		return Frame{}, err
	}
	return f, nil
}

// take returns the next frame without blocking. Callers hold m.mu.
func (m *Mux) take() (Frame, error) {
	if f, ok := m.takeCtrlLocked(); ok {
		return f, nil
	}
	n := len(m.order)
	for i := 0; i < n; i++ {
		ch := m.order[m.rr]
		m.rr++
		if m.rr >= n {
			m.rr = 0
		}
		if ch.closed {
			continue
		}
		if len(ch.queue) > 0 {
			f := ch.queue[0]
			ch.queue = ch.queue[1:]
			ch.qFrames--
			ch.qBytes -= len(f.Payload)
			return f, nil
		}
		if ch.closing {
			// The channel drained; emit its close notification now.
			// It goes out directly, ordered after every frame of this
			// channel, and the channel is removed.
			payload, err := m.cfg.EncodeClose(ch.id, ch.closeReason)
			if err != nil {
				return Frame{}, err
			}
			ch.closed = true
			delete(m.chans, ch.id)
			m.dropFromOrder(ch)
			// The slot is reclaimed with the channel. Without this the
			// count only ever rose, so a connection that opened and
			// closed channels normally (every tab switch attaches and
			// detaches one) walked up to MaxChannels and then failed
			// every further attach for the life of the connection.
			m.chanCount--
			return Frame{Type: ChannelCtrl, ID: 0, Payload: payload}, nil
		}
	}
	if m.closed {
		return Frame{}, ErrMuxClosed
	}
	return Frame{}, errWait
}

// dropFromOrder removes a closed channel from the round-robin ring and keeps
// the cursor pointing at the same position. Callers hold m.mu.
//
// Without this the ring grows for the life of the connection: closed entries
// are skipped on every pass, so a long session pays for channels that no
// longer exist.
func (m *Mux) dropFromOrder(ch *channel) {
	for i, c := range m.order {
		if c != ch {
			continue
		}
		m.order = append(m.order[:i], m.order[i+1:]...)
		// Entries after i shifted down by one; keep the cursor on the
		// channel it was about to visit.
		if m.rr > i {
			m.rr--
		}
		if m.rr >= len(m.order) {
			m.rr = 0
		}
		return
	}
}

func (m *Mux) takeCtrlLocked() (Frame, bool) {
	if len(m.ctrlQ) == 0 {
		return Frame{}, false
	}
	f := m.ctrlQ[0]
	m.ctrlQ = m.ctrlQ[1:]
	m.ctrlFr--
	m.ctrlByt -= len(f.Payload)
	return f, true
}

// Deliver routes an inbound frame to its channel handler. It returns a
// protocol error for frames on unknown or closing channels, for a bad
// channel type, or for a control frame with a non-zero id. The handler runs
// without the mux lock held, so it may call Send, OpenTerm, and CloseTerm
// from inside the handler.
func (m *Mux) Deliver(f Frame) error {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return ErrMuxClosed
	}
	var h FrameHandler
	switch f.Type {
	case ChannelCtrl:
		if f.ID != 0 {
			m.mu.Unlock()
			return ErrCtrlFrame
		}
		h = m.cfg.CtrlHandler
	case ChannelTerm, ChannelFile:
		ch, ok := m.chans[f.ID]
		if !ok || ch.closing || ch.closed {
			m.mu.Unlock()
			return ErrUnknownChannel
		}
		h = ch.handler
	default:
		m.mu.Unlock()
		return ErrBadChannel
	}
	m.mu.Unlock()
	if h == nil {
		return nil
	}
	return h(f)
}

// OpenFile allocates a file channel id (same odd allocator as term) for
// transfer chunk data. The channel's inbound handler parses chunk frames; a
// nil handler drops inbound frames. File channels are closed with the same
// type-agnostic CloseTerm path.
func (m *Mux) OpenFile(h FrameHandler) (uint32, error) {
	return m.OpenTerm(h)
}

// OpenTerm allocates the next daemon-owned term channel id (odd, starting at
// 1) and installs its inbound handler atomically. The channel then accepts
// app input. A nil handler drops inbound frames.
func (m *Mux) OpenTerm(h FrameHandler) (uint32, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return 0, ErrMuxClosed
	}
	// MaxChannels counts the control channel, which already exists.
	if 1+m.chanCount+1 > m.cfg.MaxChannels {
		return 0, ErrTooManyChannels
	}
	id := m.nextOdd
	m.nextOdd += 2
	m.chanCount++
	m.chans[id] = &channel{
		id: id, handler: h,
		qFrCap: m.cfg.QueueFrames, qByCap: m.cfg.QueueBytes,
	}
	m.order = append(m.order, m.chans[id])
	return id, nil
}

// CloseTerm initiates an orderly close of a term channel: no new frames are
// queued, the remaining queue drains, and then a channel.close notification
// with the given reason is emitted. It is idempotent.
func (m *Mux) CloseTerm(id uint32, reason string) error {
	return m.closeTerm(id, reason, false)
}

// DropTerm closes a term channel immediately, discarding any queued output.
// It is the overflow path: the reader was too slow, so its data is dropped
// and the close notification carries the reason.
func (m *Mux) DropTerm(id uint32, reason string) error {
	return m.closeTerm(id, reason, true)
}

func (m *Mux) closeTerm(id uint32, reason string, drop bool) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	if m.closed {
		return ErrMuxClosed
	}
	ch, ok := m.chans[id]
	if !ok {
		return ErrUnknownChannel
	}
	if ch.closing || ch.closed {
		return nil
	}
	ch.closing = true
	ch.closeReason = reason
	if drop {
		ch.queue = nil
		ch.qFrames = 0
		ch.qBytes = 0
	}
	m.signal()
	return nil
}

// Close terminates the connection's multiplexing. Queued frames remain
// readable in order; once drained, Read returns ErrMuxClosed. Send and
// Deliver return ErrMuxClosed. It is idempotent.
func (m *Mux) Close() {
	m.mu.Lock()
	if m.closed {
		m.mu.Unlock()
		return
	}
	m.closed = true
	close(m.done)
	m.signal()
	m.mu.Unlock()
}

// Closed reports whether Close has been called.
func (m *Mux) Closed() bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.closed
}
