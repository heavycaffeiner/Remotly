package pairing

import (
	"encoding/base64"
	"encoding/binary"
	"errors"
	"fmt"
	"strings"
	"unicode/utf8"
)

// URI payload bounds. The payload is small by construction; these caps stop a
// crafted URI from allocating amplification before it is rejected.
const (
	uriVersion    = 1
	maxHints      = 8
	maxHintAddr   = 255
	maxDaemonName = 100
	maxURIPayload = 4096

	uriScheme = "remotly://pair?d="
)

// MaxURIHints is the maximum number of connection hints a pairing URI carries.
const MaxURIHints = maxHints

var errURITruncated = errors.New("pairing: URI payload truncated")

// HintKind classifies a connection hint.
type HintKind uint8

const (
	HintIPv4  HintKind = 0
	HintIPv6  HintKind = 1
	HintName  HintKind = 2
	HintRelay HintKind = 3
)

// Hint is one way to reach the daemon. IPv4/IPv6/Name hints are the daemon's
// own reachable LAN addresses plus the LAN port. A relay hint carries the
// relay's host and port; the app derives the daemon's relay identity from the
// daemon public key already in the URI, so no extra credential is needed.
type Hint struct {
	Kind HintKind
	Addr string
	Port int
}

// URIPayload is the decoded pairing URI. It is the complete, self-contained
// credential an app needs to start a pairing handshake.
type URIPayload struct {
	Version      int
	TokenID      []byte
	Secret       []byte
	Expires      int64 // unix seconds
	EphemeralPub [32]byte
	DaemonPub    [32]byte
	Hints        []Hint
	DaemonName   string
}

// EncodeURI renders p as the canonical remotely://pair?d=<base64url> URI. The
// payload layout is:
//
//	version(1) | token_id(16) | secret(32) | expires_unix(4, BE) |
//	ephemeral_pub(32) | daemon_pub(32) | hint_count(1) |
//	hints: kind(1) | addr_len(varint) | addr | port(2, BE) ... |
//	daemon_name_len(varint) | daemon_name
func EncodeURI(p URIPayload) (string, error) {
	if p.Version != uriVersion {
		return "", fmt.Errorf("pairing: unsupported URI version %d (want %d)", p.Version, uriVersion)
	}
	if len(p.TokenID) != tokenIDLen {
		return "", errors.New("pairing: token id must be 16 bytes")
	}
	if len(p.Secret) != secretLen {
		return "", errors.New("pairing: secret must be 32 bytes")
	}
	if len(p.Hints) > maxHints {
		return "", errors.New("pairing: too many hints")
	}
	if err := validateName(p.DaemonName, maxDaemonName); err != nil {
		return "", fmt.Errorf("pairing: daemon name: %w", err)
	}
	for _, h := range p.Hints {
		if h.Kind < HintIPv4 || h.Kind > HintRelay {
			return "", errors.New("pairing: bad hint kind")
		}
		if len(h.Addr) < 1 || len(h.Addr) > maxHintAddr {
			return "", errors.New("pairing: hint address out of range")
		}
		if h.Port < 1 || h.Port > 65535 {
			return "", errors.New("pairing: hint port out of range")
		}
	}

	var buf []byte
	buf = append(buf, uriVersion)
	buf = append(buf, p.TokenID...)
	buf = append(buf, p.Secret...)
	var exp [4]byte
	binary.BigEndian.PutUint32(exp[:], uint32(p.Expires))
	buf = append(buf, exp[:]...)
	buf = append(buf, p.EphemeralPub[:]...)
	buf = append(buf, p.DaemonPub[:]...)
	buf = append(buf, byte(len(p.Hints)))
	for _, h := range p.Hints {
		buf = append(buf, byte(h.Kind))
		buf = appendVarint(buf, uint64(len(h.Addr)))
		buf = append(buf, h.Addr...)
		var port [2]byte
		binary.BigEndian.PutUint16(port[:], uint16(h.Port))
		buf = append(buf, port[:]...)
	}
	buf = appendVarint(buf, uint64(len(p.DaemonName)))
	buf = append(buf, p.DaemonName...)

	return uriScheme + base64.RawURLEncoding.EncodeToString(buf), nil
}

// DecodeURI parses a canonical pairing URI and validates every field. The
// input is hostile: a malformed URI is rejected before any of its contents are
// used, and no field can allocate more than its documented bound.
func DecodeURI(s string) (*URIPayload, error) {
	if !strings.HasPrefix(s, uriScheme) {
		return nil, errors.New("pairing: not a pairing URI")
	}
	encoded := s[len(uriScheme):]
	if encoded == "" {
		return nil, errors.New("pairing: empty URI payload")
	}
	raw, err := base64.RawURLEncoding.DecodeString(encoded)
	if err != nil {
		return nil, fmt.Errorf("pairing: bad URI payload encoding: %w", err)
	}
	if len(raw) > maxURIPayload {
		return nil, errors.New("pairing: URI payload too large")
	}
	return parsePayload(raw)
}

func parsePayload(raw []byte) (*URIPayload, error) {
	r := &payloadReader{b: raw}

	version, err := r.u8()
	if err != nil {
		return nil, err
	}
	if version != uriVersion {
		return nil, fmt.Errorf("pairing: unsupported URI version %d (want %d)", version, uriVersion)
	}
	tokenID, err := r.bytes(tokenIDLen)
	if err != nil {
		return nil, err
	}
	secret, err := r.bytes(secretLen)
	if err != nil {
		return nil, err
	}
	expB, err := r.bytes(4)
	if err != nil {
		return nil, err
	}
	var ephemeralPub, daemonPub [32]byte
	if _, err := r.copy(ephemeralPub[:]); err != nil {
		return nil, err
	}
	if _, err := r.copy(daemonPub[:]); err != nil {
		return nil, err
	}
	hintCount, err := r.u8()
	if err != nil {
		return nil, err
	}
	if hintCount > maxHints {
		return nil, errors.New("pairing: too many hints")
	}
	hints := make([]Hint, 0, hintCount)
	for i := 0; i < int(hintCount); i++ {
		kind, err := r.u8()
		if err != nil {
			return nil, err
		}
		if kind > byte(HintRelay) {
			return nil, errors.New("pairing: bad hint kind")
		}
		addrLen, err := r.varint()
		if err != nil {
			return nil, err
		}
		if addrLen < 1 || addrLen > maxHintAddr {
			return nil, errors.New("pairing: hint address out of range")
		}
		addr, err := r.bytes(int(addrLen))
		if err != nil {
			return nil, err
		}
		portB, err := r.bytes(2)
		if err != nil {
			return nil, err
		}
		port := int(binary.BigEndian.Uint16(portB))
		if port < 1 || port > 65535 {
			return nil, errors.New("pairing: hint port out of range")
		}
		hints = append(hints, Hint{Kind: HintKind(kind), Addr: string(addr), Port: port})
	}
	nameLen, err := r.varint()
	if err != nil {
		return nil, err
	}
	if nameLen < 1 || nameLen > maxDaemonName {
		return nil, errors.New("pairing: daemon name out of range")
	}
	name, err := r.bytes(int(nameLen))
	if err != nil {
		return nil, err
	}
	if err := validateName(string(name), maxDaemonName); err != nil {
		return nil, fmt.Errorf("pairing: daemon name: %w", err)
	}
	if r.remaining() != 0 {
		return nil, errors.New("pairing: trailing bytes in URI payload")
	}

	// Copy the slices so the returned payload owns its memory.
	tokenIDCopy := make([]byte, len(tokenID))
	copy(tokenIDCopy, tokenID)
	secretCopy := make([]byte, len(secret))
	copy(secretCopy, secret)
	return &URIPayload{
		Version:      uriVersion,
		TokenID:      tokenIDCopy,
		Secret:       secretCopy,
		Expires:      int64(binary.BigEndian.Uint32(expB)),
		EphemeralPub: ephemeralPub,
		DaemonPub:    daemonPub,
		Hints:        hints,
		DaemonName:   string(name),
	}, nil
}

// validateName enforces the shared name rules: non-empty, bounded, valid UTF-8,
// and free of control characters (which would break terminal and log display).
func validateName(s string, max int) error {
	if len(s) < 1 || len(s) > max {
		return errors.New("length out of range")
	}
	if !utf8.ValidString(s) {
		return errors.New("not valid UTF-8")
	}
	for _, r := range s {
		if r < 0x20 || r == 0x7f {
			return errors.New("contains a control character")
		}
	}
	return nil
}

// payloadReader is a bounded cursor over the URI payload.
type payloadReader struct {
	b []byte
	i int
}

func (r *payloadReader) u8() (byte, error) {
	if r.i+1 > len(r.b) {
		return 0, errURITruncated
	}
	v := r.b[r.i]
	r.i++
	return v, nil
}

func (r *payloadReader) bytes(n int) ([]byte, error) {
	if n < 0 || r.i+n > len(r.b) {
		return nil, errURITruncated
	}
	out := r.b[r.i : r.i+n]
	r.i += n
	return out, nil
}

func (r *payloadReader) copy(dst []byte) (int, error) {
	if r.i+len(dst) > len(r.b) {
		return 0, errURITruncated
	}
	n := copy(dst, r.b[r.i:])
	r.i += n
	return n, nil
}

func (r *payloadReader) varint() (uint64, error) {
	var v uint64
	for i := 0; i < 5; i++ {
		if r.i >= len(r.b) {
			return 0, errURITruncated
		}
		c := r.b[r.i]
		r.i++
		v |= uint64(c&0x7f) << (7 * i)
		if c&0x80 == 0 {
			return v, nil
		}
	}
	return 0, errors.New("pairing: varint too long")
}

func (r *payloadReader) remaining() int { return len(r.b) - r.i }

// appendVarint appends v as unsigned LEB128. Callers keep v within uint32.
func appendVarint(dst []byte, v uint64) []byte {
	for {
		b := byte(v & 0x7f)
		v >>= 7
		if v != 0 {
			b |= 0x80
		}
		dst = append(dst, b)
		if v == 0 {
			return dst
		}
	}
}
