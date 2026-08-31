package session

import (
	"io"
	"sync/atomic"
)

// DetachReason explains why an attachment ended.
type DetachReason int

const (
	// ReasonCancelled: the caller closed the attachment.
	ReasonCancelled DetachReason = iota
	// ReasonOverflow: the reader was too slow; it was dropped to keep the
	// PTY and other readers flowing.
	ReasonOverflow
	// ReasonExited: the session's process exited.
	ReasonExited
)

func (r DetachReason) String() string {
	switch r {
	case ReasonCancelled:
		return "cancelled"
	case ReasonOverflow:
		return "overflow"
	case ReasonExited:
		return "exited"
	}
	return "unknown"
}

// attachment is a single reader on a session's output.
//
// Stream contract: Read returns the retained scrollback (taken atomically
// with registration), then live output, in order, each retained byte exactly
// once. Read returns io.EOF when the stream ends; Reason then says why.
//
// Read is intended for one goroutine. Close is safe from any goroutine.
type attachment struct {
	s     *Session
	id    int
	queue chan []byte

	replay       []byte // retained output, served first
	replayedFrom int64  // offset of the first retained byte
	ahead        []byte // partially delivered live chunk

	reason atomic.Int32
}

// Attachment is the public reader handle returned by Session.Attach.
type Attachment struct {
	a *attachment
}

// Read implements io.Reader. It is single-goroutine.
func (at *Attachment) Read(p []byte) (int, error) {
	a := at.a
	for {
		if len(a.replay) > 0 {
			n := copy(p, a.replay)
			a.replay = a.replay[n:]
			return n, nil
		}
		if len(a.ahead) > 0 {
			n := copy(p, a.ahead)
			a.ahead = a.ahead[n:]
			return n, nil
		}
		chunk, ok := <-a.queue
		if !ok {
			return 0, io.EOF
		}
		n := copy(p, chunk)
		if n < len(chunk) {
			a.ahead = chunk[n:]
		}
		return n, nil
	}
}

// Close detaches the attachment with ReasonCancelled. It is idempotent and
// does not affect the session or the PTY.
func (at *Attachment) Close() error {
	at.a.s.detach(at.a, ReasonCancelled)
	return nil
}

// Reason reports why the stream ended. It is meaningful after Read returned
// io.EOF, or after Close.
func (at *Attachment) Reason() DetachReason {
	return DetachReason(at.a.reason.Load())
}

// ReplayBytes reports how many retained bytes the stream starts with. The
// first ReplayBytes bytes Read delivers are replay; every later byte is live
// output. The value is fixed for the attachment's life.
func (at *Attachment) ReplayBytes() int64 {
	return int64(len(at.a.replay))
}

// ReplayedFrom reports the cumulative output offset of the first byte Read
// delivers, i.e. where the replay started.
func (at *Attachment) ReplayedFrom() int64 {
	return at.a.replayedFrom
}
