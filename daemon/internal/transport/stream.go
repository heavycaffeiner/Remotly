package transport

import (
	"context"
	"errors"
	"fmt"

	"nhooyr.io/websocket"
)

// CloseError is the cross-transport close information a stream reports when
// the peer ended the stream with an explicit code and reason. Both the direct
// WebSocket and a relay sub-stream surface closes through it, so the
// connection's error mappers never see transport-specific close types.
type CloseError struct {
	Code   uint16
	Reason string
}

func (e *CloseError) Error() string {
	return fmt.Sprintf("transport: stream close %d %s", e.Code, e.Reason)
}

// errTextFrame reports a non-binary frame on a direct WebSocket. Relay
// streams carry only binary Remotly messages, so this error is ws-only.
var errTextFrame = errors.New("transport: text frame")

// Stream is the binary-frame transport under one connection. A direct
// WebSocket and a relay sub-stream both satisfy it, which keeps the
// handshake, cipher, multiplexer, and session logic transport-agnostic.
//
// Read returns the payload of the next binary frame. A peer close is reported
// as a *CloseError; a bare connection loss as io.EOF or net.ErrClosed; a text
// frame on a direct socket as errTextFrame.
type Stream interface {
	// SetReadLimit caps one inbound frame. The relay enforces its own bound,
	// so this is a no-op there.
	SetReadLimit(n int64)
	// Read returns the next inbound binary frame's payload.
	Read(ctx context.Context) ([]byte, error)
	// Write sends one binary frame.
	Write(ctx context.Context, data []byte) error
	// Ping probes liveness and returns once the peer answers.
	Ping(ctx context.Context) error
	// Close ends the stream presenting the given close information.
	Close(code uint16, reason string) error
}

// wsStream adapts a direct WebSocket to the Stream interface, converting the
// library's close errors to the transport's CloseError.
type wsStream struct {
	ws *websocket.Conn
}

func (s *wsStream) SetReadLimit(n int64) { s.ws.SetReadLimit(n) }

func (s *wsStream) Read(ctx context.Context) ([]byte, error) {
	typ, data, err := s.ws.Read(ctx)
	if err != nil {
		var cerr *websocket.CloseError
		if errors.As(err, &cerr) {
			return nil, &CloseError{Code: uint16(cerr.Code), Reason: cerr.Reason}
		}
		return nil, err
	}
	if typ != websocket.MessageBinary {
		return nil, errTextFrame
	}
	return data, nil
}

func (s *wsStream) Write(ctx context.Context, data []byte) error {
	err := s.ws.Write(ctx, websocket.MessageBinary, data)
	if err != nil {
		var cerr *websocket.CloseError
		if errors.As(err, &cerr) {
			return &CloseError{Code: uint16(cerr.Code), Reason: cerr.Reason}
		}
	}
	return err
}

func (s *wsStream) Ping(ctx context.Context) error {
	err := s.ws.Ping(ctx)
	if err != nil {
		var cerr *websocket.CloseError
		if errors.As(err, &cerr) {
			return &CloseError{Code: uint16(cerr.Code), Reason: cerr.Reason}
		}
	}
	return err
}

func (s *wsStream) Close(code uint16, reason string) error {
	return s.ws.Close(websocket.StatusCode(code), reason)
}
