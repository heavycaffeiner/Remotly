package relayproto

import (
	"bytes"
	"errors"
	"io"
	"testing"
)

func TestRoundTrip(t *testing.T) {
	id := [16]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16}
	cases := []Message{
		NewJoin(RoleDaemon, id),
		NewJoin(RoleApp, id),
		NewJoinAck(),
		NewFrame(makeFrame(19)),
		NewFrame(makeFrame(1000)),
		NewFrame(makeFrame(MaxFrame)),
		NewKeepalive(),
		NewEnd(CodeNoDaemon, "no daemon registered"),
		NewEnd(4001, "authentication failure"),
		NewStreamOpen(1),
		NewStreamOpen(0xFFFFFFFF),
		NewStreamFrame(7, makeFrame(5000)),
		NewStreamClose(7, 1000, "normal"),
		NewStreamPing(7),
		NewStreamPong(7),
	}
	for _, m := range cases {
		b, err := Encode(m)
		if err != nil {
			t.Fatalf("encode type 0x%02x: %v", m.Type, err)
		}
		got, err := Read(bytes.NewReader(b))
		if err != nil {
			t.Fatalf("read type 0x%02x: %v", m.Type, err)
		}
		if got.Type != m.Type {
			t.Fatalf("type = 0x%02x, want 0x%02x", got.Type, m.Type)
		}
		switch m.Type {
		case TypeJoin:
			if got.Version != m.Version || got.Role != m.Role || got.RelayID != m.RelayID {
				t.Fatalf("join fields mismatch: %+v", got)
			}
		case TypeFrame, TypeStreamFrame:
			if !bytes.Equal(got.Data, m.Data) {
				t.Fatalf("frame data mismatch (len %d)", len(m.Data))
			}
			if m.Type == TypeStreamFrame && got.StreamID != m.StreamID {
				t.Fatalf("stream id = %d, want %d", got.StreamID, m.StreamID)
			}
		case TypeEnd, TypeStreamClose:
			if got.Code != m.Code || got.Reason != m.Reason {
				t.Fatalf("end fields = (%d %q), want (%d %q)", got.Code, got.Reason, m.Code, m.Reason)
			}
		case TypeStreamOpen, TypeStreamPing, TypeStreamPong:
			if got.StreamID != m.StreamID {
				t.Fatalf("stream id = %d, want %d", got.StreamID, m.StreamID)
			}
		}
	}
}

func makeFrame(n int) []byte {
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i)
	}
	return b
}

func TestReadMalformed(t *testing.T) {
	id := [16]byte{9}
	goodJoin := func() []byte {
		b, err := Encode(NewJoin(RoleDaemon, id))
		if err != nil {
			t.Fatal(err)
		}
		return b
	}
	malformed := []struct {
		name string
		in   []byte
	}{
		{"unknown type", []byte{0x7f}},
		{"type zero", []byte{0x00}},
		{"bad version", func() []byte { b := goodJoin(); b[1] = 9; return b }()},
		{"bad role", func() []byte { b := goodJoin(); b[2] = 5; return b }()},
		{"join ack nonzero", []byte{TypeJoinAck, 1}},
		{"frame len zero", append([]byte{TypeFrame}, 0)},
		{"frame too small", append(append([]byte{TypeFrame}, byte(MinFrame-1)), make([]byte, MinFrame-1)...)},
		{"frame too large", []byte{TypeFrame, 0xff, 0xff, 0xff, 0xff, 0x0f}},
		{"frame varint too long", []byte{TypeFrame, 0x80, 0x80, 0x80, 0x80, 0x80, 0x01}},
		{"stream open id zero", []byte{TypeStreamOpen, 0, 0, 0, 0}},
		{"stream frame id zero", append([]byte{TypeStreamFrame, 0, 0, 0, 0, 19}, make([]byte, 19)...)},
	}
	for _, tc := range malformed {
		_, err := Read(bytes.NewReader(tc.in))
		if !errors.Is(err, ErrMalformed) {
			t.Errorf("%s: err = %v, want ErrMalformed", tc.name, err)
		}
	}
	// A peer that closes mid-message is an I/O condition, not a protocol
	// error.
	truncated := []struct {
		name string
		in   []byte
	}{
		{"empty", []byte{}},
		{"truncated join", goodJoin()[:10]},
		{"frame truncated data", []byte{TypeFrame, 20, 1, 2, 3}},
		{"stream close truncated", []byte{TypeStreamClose, 0, 0, 0, 1, 0x00}},
		{"end truncated reason", []byte{TypeEnd, 0, 0, 5, 1, 2}},
	}
	for _, tc := range truncated {
		_, err := Read(bytes.NewReader(tc.in))
		if err == nil || (!errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF)) {
			t.Errorf("%s: err = %v, want an EOF condition", tc.name, err)
		}
	}
}

func TestReadEOF(t *testing.T) {
	// A clean EOF mid-message is an I/O error, not malformed.
	_, err := Read(bytes.NewReader([]byte{TypeFrame, byte(20), 1, 2}))
	if !errors.Is(err, io.ErrUnexpectedEOF) {
		t.Fatalf("err = %v, want ErrUnexpectedEOF", err)
	}
	_, err = Read(bytes.NewReader(nil))
	if !errors.Is(err, io.EOF) {
		t.Fatalf("err = %v, want io.EOF", err)
	}
}

func TestEncodeRejectsOutOfRange(t *testing.T) {
	if _, err := Encode(Message{Type: TypeFrame, Data: make([]byte, MinFrame-1)}); err == nil {
		t.Error("small frame accepted")
	}
	if _, err := Encode(Message{Type: TypeFrame, Data: make([]byte, MaxFrame+1)}); err == nil {
		t.Error("large frame accepted")
	}
	if _, err := Encode(Message{Type: TypeEnd, Code: 1000, Reason: string(make([]byte, MaxReason+1))}); err == nil {
		t.Error("long reason accepted")
	}
	if _, err := Encode(Message{Type: TypeStreamOpen, StreamID: 0}); err == nil {
		t.Error("stream id 0 accepted")
	}
	if _, err := Encode(Message{Type: 0x7f}); err == nil {
		t.Error("unknown type accepted")
	}
}

func FuzzReadEncodeRoundTrip(f *testing.F) {
	f.Add([]byte{TypeKeepalive})
	f.Add(append([]byte{TypeFrame, 19}, make([]byte, 19)...))
	f.Add([]byte{TypeStreamFrame, 0, 0, 0, 1, 19})
	f.Fuzz(func(t *testing.T, seed []byte) {
		// Build a valid frame from arbitrary payload bytes and check the
		// round trip; then check that arbitrary bytes either parse to a
		// valid message or are rejected, never panic.
		payload := makeFrame(19 + len(seed))
		if len(seed) > 0 {
			copy(payload[19:], seed)
		}
		b, err := Encode(NewFrame(payload))
		if err != nil {
			t.Fatal(err)
		}
		got, err := Read(bytes.NewReader(b))
		if err != nil {
			t.Fatalf("round trip read: %v", err)
		}
		if got.Type != TypeFrame || !bytes.Equal(got.Data, payload) {
			t.Fatalf("round trip mismatch")
		}

		if _, err := Read(bytes.NewReader(seed)); err != nil && !errors.Is(err, ErrMalformed) && !errors.Is(err, io.EOF) && !errors.Is(err, io.ErrUnexpectedEOF) {
			t.Fatalf("arbitrary read: unexpected err %v", err)
		}
	})
}
