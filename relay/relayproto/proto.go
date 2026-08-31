// Package relayproto implements the Remotly relay wire protocol: the typed,
// length-prefixed messages a relay exchanges with its endpoints, as
// specified in docs/protocol.md section 10.
//
// The relay is opaque: it routes whole Remotly transport messages without
// parsing them. This package defines only the envelope, never the payload.
package relayproto

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
)

// Version is the only relay protocol version this build speaks.
const Version = 1

// Roles identify a joining endpoint.
const (
	RoleDaemon byte = 0
	RoleApp    byte = 1
)

// Message types.
const (
	TypeJoin        byte = 0x01
	TypeJoinAck     byte = 0x02
	TypeFrame       byte = 0x03
	TypeKeepalive   byte = 0x04
	TypeEnd         byte = 0x05
	TypeStreamOpen  byte = 0x06
	TypeStreamFrame byte = 0x07
	TypeStreamClose byte = 0x08
	TypeStreamPing  byte = 0x09
	TypeStreamPong  byte = 0x0a
)

// Frame size bounds. MinFrame is the smallest Remotly transport message
// (an empty sealed frame); MaxFrame is the Remotly frame ceiling plus the
// sealed-frame overhead headroom the protocol reserves.
const (
	MinFrame = 19
	MaxFrame = 1<<20 + 128
)

// MaxReason bounds end and stream_close reason strings.
const MaxReason = 127

// Relay-generated close codes. Remotly close codes (1000-1006, 4000-4004)
// pass through the relay unchanged and are not defined here.
const (
	CodeNoDaemon  uint16 = 3001
	CodeReplaced  uint16 = 3002
	CodeLimit     uint16 = 3003
	CodeIdle      uint16 = 3004
	CodeGoingAway uint16 = 3005
	CodeProtocol  uint16 = 3006
	CodePeerGone  uint16 = 3007
)

// ErrMalformed reports a message that violates the wire format. A reader
// treats it as a fatal protocol error for the connection.
var ErrMalformed = errors.New("malformed relay message")

// Message is one relay wire message. Only the fields matching Type are
// meaningful.
type Message struct {
	Type byte
	// TypeJoin
	Version byte
	Role    byte
	RelayID [16]byte
	// TypeFrame, TypeStreamFrame
	Data []byte
	// TypeStreamOpen, TypeStreamFrame, TypeStreamClose, TypeStreamPing,
	// TypeStreamPong
	StreamID uint32
	// TypeEnd, TypeStreamClose
	Code   uint16
	Reason string
}

// JoinPayload is the fixed 18-byte join body.
type JoinPayload struct {
	Version byte
	Role    byte
	RelayID [16]byte
}

func (p *JoinPayload) encode() [18]byte {
	var b [18]byte
	b[0] = p.Version
	b[1] = p.Role
	copy(b[2:], p.RelayID[:])
	return b
}

func decodeJoin(b [18]byte) (JoinPayload, error) {
	p := JoinPayload{Version: b[0], Role: b[1]}
	copy(p.RelayID[:], b[2:])
	if p.Version != Version {
		return p, fmt.Errorf("%w: version %d", ErrMalformed, p.Version)
	}
	if p.Role != RoleDaemon && p.Role != RoleApp {
		return p, fmt.Errorf("%w: role %d", ErrMalformed, p.Role)
	}
	return p, nil
}

// readVarint reads an unsigned LEB128 value of at most 5 bytes.
func readVarint(r io.Reader) (uint64, error) {
	var v uint64
	var shift uint
	for i := 0; i < 5; i++ {
		var b [1]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return 0, err
		}
		v |= uint64(b[0]&0x7f) << shift
		if b[0]&0x80 == 0 {
			return v, nil
		}
		shift += 7
	}
	return 0, fmt.Errorf("%w: varint too long", ErrMalformed)
}

// writeVarint appends an unsigned LEB128 value. Values above 2^35-1 are a
// programming error: no field in the protocol carries one.
func writeVarint(b []byte, v uint64) []byte {
	for v >= 0x80 {
		b = append(b, byte(v)|0x80)
		v >>= 7
	}
	return append(b, byte(v))
}

func readReason(r io.Reader) (string, error) {
	var l [1]byte
	if _, err := io.ReadFull(r, l[:]); err != nil {
		return "", err
	}
	buf := make([]byte, l[0])
	if _, err := io.ReadFull(r, buf); err != nil {
		return "", err
	}
	return string(buf), nil
}

func readStreamID(r io.Reader) (uint32, error) {
	var b [4]byte
	if _, err := io.ReadFull(r, b[:]); err != nil {
		return 0, err
	}
	return binary.BigEndian.Uint32(b[:]), nil
}

// Read decodes one message from r. It returns io.EOF when the peer closes
// cleanly and ErrMalformed (wrapped) on any format violation.
func Read(r io.Reader) (Message, error) {
	var t [1]byte
	if _, err := io.ReadFull(r, t[:]); err != nil {
		return Message{}, err
	}
	var m Message
	var err error
	m.Type = t[0]
	switch t[0] {
	case TypeJoin:
		var b [18]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return Message{}, err
		}
		p, err := decodeJoin(b)
		if err != nil {
			return Message{}, err
		}
		m.Version = p.Version
		m.Role = p.Role
		m.RelayID = p.RelayID
	case TypeJoinAck:
		var s [1]byte
		if _, err := io.ReadFull(r, s[:]); err != nil {
			return Message{}, err
		}
		if s[0] != 0 {
			return Message{}, fmt.Errorf("%w: join ack status %d", ErrMalformed, s[0])
		}
	case TypeFrame:
		n, err := readVarint(r)
		if err != nil {
			return Message{}, err
		}
		if err := checkFrameLen(n); err != nil {
			return Message{}, err
		}
		m.Data = make([]byte, n)
		if _, err := io.ReadFull(r, m.Data); err != nil {
			return Message{}, err
		}
	case TypeKeepalive:
		// No payload.
	case TypeEnd:
		var b [2]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return Message{}, err
		}
		m.Code = binary.BigEndian.Uint16(b[:])
		m.Reason, err = readReason(r)
		if err != nil {
			return Message{}, err
		}
	case TypeStreamOpen, TypeStreamPing, TypeStreamPong:
		m.StreamID, err = readStreamID(r)
		if err != nil {
			return Message{}, err
		}
		if m.StreamID == 0 {
			return Message{}, fmt.Errorf("%w: stream id 0", ErrMalformed)
		}
	case TypeStreamFrame:
		m.StreamID, err = readStreamID(r)
		if err != nil {
			return Message{}, err
		}
		if m.StreamID == 0 {
			return Message{}, fmt.Errorf("%w: stream id 0", ErrMalformed)
		}
		n, err := readVarint(r)
		if err != nil {
			return Message{}, err
		}
		if err := checkFrameLen(n); err != nil {
			return Message{}, err
		}
		m.Data = make([]byte, n)
		if _, err := io.ReadFull(r, m.Data); err != nil {
			return Message{}, err
		}
	case TypeStreamClose:
		m.StreamID, err = readStreamID(r)
		if err != nil {
			return Message{}, err
		}
		if m.StreamID == 0 {
			return Message{}, fmt.Errorf("%w: stream id 0", ErrMalformed)
		}
		var b [2]byte
		if _, err := io.ReadFull(r, b[:]); err != nil {
			return Message{}, err
		}
		m.Code = binary.BigEndian.Uint16(b[:])
		m.Reason, err = readReason(r)
		if err != nil {
			return Message{}, err
		}
	default:
		return Message{}, fmt.Errorf("%w: type 0x%02x", ErrMalformed, t[0])
	}
	return m, nil
}

func checkFrameLen(n uint64) error {
	if n < MinFrame || n > MaxFrame {
		return fmt.Errorf("%w: frame length %d", ErrMalformed, n)
	}
	return nil
}

// Encode returns m in wire form. The returned slice is owned by the
// caller; each call allocates fresh memory, so items may be handed to other
// goroutines.
func Encode(m Message) ([]byte, error) {
	var buf []byte
	var b [4]byte
	switch m.Type {
	case TypeJoin:
		buf = append(buf, TypeJoin)
		var p JoinPayload
		p.Version = m.Version
		p.Role = m.Role
		p.RelayID = m.RelayID
		enc := p.encode()
		buf = append(buf, enc[:]...)
	case TypeJoinAck:
		buf = append(buf, TypeJoinAck, 0)
	case TypeFrame:
		if err := checkFrameLen(uint64(len(m.Data))); err != nil {
			return nil, err
		}
		buf = append(buf, TypeFrame)
		buf = writeVarint(buf, uint64(len(m.Data)))
		buf = append(buf, m.Data...)
	case TypeKeepalive:
		buf = append(buf, TypeKeepalive)
	case TypeEnd:
		buf = append(buf, TypeEnd)
		binary.BigEndian.PutUint16(b[:2], m.Code)
		buf = append(buf, b[:2]...)
		if len(m.Reason) > MaxReason {
			return nil, fmt.Errorf("%w: reason length %d", ErrMalformed, len(m.Reason))
		}
		buf = append(buf, byte(len(m.Reason)))
		buf = append(buf, m.Reason...)
	case TypeStreamOpen, TypeStreamPing, TypeStreamPong:
		if m.StreamID == 0 {
			return nil, fmt.Errorf("%w: stream id 0", ErrMalformed)
		}
		buf = append(buf, m.Type)
		binary.BigEndian.PutUint32(b[:4], m.StreamID)
		buf = append(buf, b[:4]...)
	case TypeStreamFrame:
		if m.StreamID == 0 {
			return nil, fmt.Errorf("%w: stream id 0", ErrMalformed)
		}
		if err := checkFrameLen(uint64(len(m.Data))); err != nil {
			return nil, err
		}
		buf = append(buf, TypeStreamFrame)
		binary.BigEndian.PutUint32(b[:4], m.StreamID)
		buf = append(buf, b[:4]...)
		buf = writeVarint(buf, uint64(len(m.Data)))
		buf = append(buf, m.Data...)
	case TypeStreamClose:
		if m.StreamID == 0 {
			return nil, fmt.Errorf("%w: stream id 0", ErrMalformed)
		}
		buf = append(buf, TypeStreamClose)
		binary.BigEndian.PutUint32(b[:4], m.StreamID)
		buf = append(buf, b[:4]...)
		binary.BigEndian.PutUint16(b[:2], m.Code)
		buf = append(buf, b[:2]...)
		if len(m.Reason) > MaxReason {
			return nil, fmt.Errorf("%w: reason length %d", ErrMalformed, len(m.Reason))
		}
		buf = append(buf, byte(len(m.Reason)))
		buf = append(buf, m.Reason...)
	default:
		return nil, fmt.Errorf("%w: type 0x%02x", ErrMalformed, m.Type)
	}
	return buf, nil
}

// NewJoin builds a join message for the endpoint.
func NewJoin(role byte, relayID [16]byte) Message {
	return Message{Type: TypeJoin, Version: Version, Role: role, RelayID: relayID}
}

// NewJoinAck is the single successful join acknowledgement.
func NewJoinAck() Message { return Message{Type: TypeJoinAck} }

// NewFrame wraps one opaque Remotly transport message.
func NewFrame(data []byte) Message { return Message{Type: TypeFrame, Data: data} }

// NewKeepalive is an empty liveness probe.
func NewKeepalive() Message { return Message{Type: TypeKeepalive} }

// NewEnd terminates a connection with a close code and short reason.
func NewEnd(code uint16, reason string) Message {
	return Message{Type: TypeEnd, Code: code, Reason: reason}
}

// NewStreamOpen announces a new stream on the daemon connection.
func NewStreamOpen(id uint32) Message { return Message{Type: TypeStreamOpen, StreamID: id} }

// NewStreamFrame carries one opaque Remotly transport message on a stream.
func NewStreamFrame(id uint32, data []byte) Message {
	return Message{Type: TypeStreamFrame, StreamID: id, Data: data}
}

// NewStreamClose ends a stream with a close code and short reason.
func NewStreamClose(id uint32, code uint16, reason string) Message {
	return Message{Type: TypeStreamClose, StreamID: id, Code: code, Reason: reason}
}

// NewStreamPing asks the peer to answer on a stream.
func NewStreamPing(id uint32) Message { return Message{Type: TypeStreamPing, StreamID: id} }

// NewStreamPong answers a stream ping.
func NewStreamPong(id uint32) Message { return Message{Type: TypeStreamPong, StreamID: id} }
