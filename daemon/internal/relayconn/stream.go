// Package relayconn maintains the daemon's single outbound relay
// registration: a persistent TCP connection to the relay, the join handshake,
// keepalive, bounded reconnect, and the demultiplexing of per-app sub-streams
// into transport streams that the transport server serves with the same
// encrypted handshake and session logic as a direct connection.
//
// The relay is untrusted. This package never logs relay ids, payloads, or
// client addresses, and it authenticates every app inside its own sub-stream
// Noise handshake, never at the relay layer.
package relayconn

import (
	"context"
	"sync"

	"github.com/heavycaffeiner/remotly/daemon/internal/transport"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

// inboxMsg is one item in a stream's inbound queue: either a data frame or a
// close reported by the relay.
type inboxMsg struct {
	frame []byte
	ce    *transport.CloseError
}

// Stream is one relay sub-stream. It satisfies transport.Stream, so the
// transport server serves it exactly as a direct connection. The stream never
// sees relay ids or the shared registration socket; it only exchanges the
// opaque Remotly transport messages of its own Noise session.
type Stream struct {
	id  uint32
	reg *registration
	in  chan inboxMsg

	// closing is closed once the stream's consumer tears it down. The
	// demultiplexer uses it to drop in-flight frames for a closing stream
	// instead of blocking on a full inbox.
	closing   chan struct{}
	closeOnce sync.Once
}

// SetReadLimit is a no-op: the relay enforces the frame bound.
func (st *Stream) SetReadLimit(n int64) {}

// Read returns the next inbound frame for this stream. A relay stream_close
// is reported as a *transport.CloseError; a lost registration as a 1001
// close.
func (st *Stream) Read(ctx context.Context) ([]byte, error) {
	select {
	case <-ctx.Done():
		return nil, ctx.Err()
	case <-st.reg.closed:
		return nil, &transport.CloseError{Code: 1001, Reason: "relay connection lost"}
	case m := <-st.in:
		if m.ce != nil {
			return nil, m.ce
		}
		return m.frame, nil
	}
}

// Write sends one outbound frame on this stream.
func (st *Stream) Write(ctx context.Context, data []byte) error {
	buf, err := relayproto.Encode(relayproto.NewStreamFrame(st.id, data))
	if err != nil {
		return &transport.CloseError{Code: 1011, Reason: "frame too large"}
	}
	return st.reg.write(buf)
}

// Ping probes stream liveness: it sends a stream_ping and waits for the
// relay to answer with a stream_pong.
func (st *Stream) Ping(ctx context.Context) error {
	done, ok := st.reg.addPing(st.id)
	if !ok {
		return &transport.CloseError{Code: 1001, Reason: "relay connection lost"}
	}
	defer st.reg.delPing(st.id, done)
	buf, err := relayproto.Encode(relayproto.NewStreamPing(st.id))
	if err != nil {
		return err
	}
	if err := st.reg.write(buf); err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case <-st.reg.closed:
		return &transport.CloseError{Code: 1001, Reason: "relay connection lost"}
	case <-done:
		return nil
	}
}

// Close ends this stream, presenting the given close information to the relay
// so it can forward it to the app. It is best effort: a gone registration is
// not an error, and a second Close is a no-op at the relay.
func (st *Stream) Close(code uint16, reason string) error {
	// Signal the demultiplexer first so it stops queueing frames for this
	// stream, then tell the relay, then drop the local record.
	st.closeOnce.Do(func() { close(st.closing) })
	if len(reason) > relayproto.MaxReason {
		reason = reason[:relayproto.MaxReason]
	}
	buf, err := relayproto.Encode(relayproto.NewStreamClose(st.id, code, reason))
	if err != nil {
		return err
	}
	_ = st.reg.write(buf)
	st.reg.dropStream(st.id)
	return nil
}
