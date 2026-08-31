package pairing

import (
	"bytes"
	"encoding/base64"
	"strings"
	"testing"
)

func testPayload() URIPayload {
	var id [tokenIDLen]byte
	var secret [secretLen]byte
	for i := range id {
		id[i] = byte(i + 1)
	}
	for i := range secret {
		secret[i] = byte(i + 101)
	}
	var ephem, daemon [32]byte
	for i := range ephem {
		ephem[i] = byte(i)
	}
	for i := range daemon {
		daemon[i] = byte(200 + i%56)
	}
	return URIPayload{
		Version:      uriVersion,
		TokenID:      id[:],
		Secret:       secret[:],
		Expires:      1_893_456_789,
		EphemeralPub: ephem,
		DaemonPub:    daemon,
		Hints: []Hint{
			{Kind: HintIPv4, Addr: "192.168.1.10", Port: 8443},
			{Kind: HintIPv6, Addr: "fe80::42", Port: 8443},
			{Kind: HintName, Addr: "myhost", Port: 9000},
		},
		DaemonName: "dev-1",
	}
}

// validRaw extracts the raw payload bytes from an encoded URI.
func validRaw(t *testing.T, p URIPayload) []byte {
	t.Helper()
	uri, err := EncodeURI(p)
	if err != nil {
		t.Fatalf("EncodeURI: %v", err)
	}
	if !strings.HasPrefix(uri, uriScheme) {
		t.Fatalf("URI missing scheme prefix: %q", uri)
	}
	raw, err := base64.RawURLEncoding.DecodeString(uri[len(uriScheme):])
	if err != nil {
		t.Fatalf("decode payload: %v", err)
	}
	return raw
}

func encodeRaw(t *testing.T, raw []byte) string {
	t.Helper()
	return uriScheme + base64.RawURLEncoding.EncodeToString(raw)
}

func TestURIRoundTrip(t *testing.T) {
	p := testPayload()
	uri, err := EncodeURI(p)
	if err != nil {
		t.Fatalf("EncodeURI: %v", err)
	}
	if !strings.HasPrefix(uri, "remotly://pair?d=") {
		t.Fatalf("URI = %q, want remotely://pair?d= prefix", uri)
	}
	got, err := DecodeURI(uri)
	if err != nil {
		t.Fatalf("DecodeURI: %v", err)
	}
	if got.Version != p.Version {
		t.Fatalf("version = %d, want %d", got.Version, p.Version)
	}
	if !bytes.Equal(got.TokenID, p.TokenID) {
		t.Fatal("token id mismatch")
	}
	if !bytes.Equal(got.Secret, p.Secret) {
		t.Fatal("secret mismatch")
	}
	if got.Expires != p.Expires {
		t.Fatalf("expires = %d, want %d", got.Expires, p.Expires)
	}
	if got.EphemeralPub != p.EphemeralPub {
		t.Fatal("ephemeral pub mismatch")
	}
	if got.DaemonPub != p.DaemonPub {
		t.Fatal("daemon pub mismatch")
	}
	if len(got.Hints) != len(p.Hints) {
		t.Fatalf("hints = %d, want %d", len(got.Hints), len(p.Hints))
	}
	for i := range p.Hints {
		if got.Hints[i] != p.Hints[i] {
			t.Fatalf("hint[%d] = %+v, want %+v", i, got.Hints[i], p.Hints[i])
		}
	}
	if got.DaemonName != p.DaemonName {
		t.Fatalf("daemon name = %q, want %q", got.DaemonName, p.DaemonName)
	}
}

func TestURIWithoutHints(t *testing.T) {
	p := testPayload()
	p.Hints = nil
	uri, err := EncodeURI(p)
	if err != nil {
		t.Fatalf("EncodeURI: %v", err)
	}
	got, err := DecodeURI(uri)
	if err != nil {
		t.Fatalf("DecodeURI: %v", err)
	}
	if len(got.Hints) != 0 {
		t.Fatalf("hints = %d, want 0", len(got.Hints))
	}
	if got.DaemonName != p.DaemonName {
		t.Fatalf("daemon name = %q, want %q", got.DaemonName, p.DaemonName)
	}
}

func TestURIEncodeValidation(t *testing.T) {
	base := testPayload()
	cases := []struct {
		name   string
		mutate func(*URIPayload)
	}{
		{"bad version", func(p *URIPayload) { p.Version = 2 }},
		{"token id 15 bytes", func(p *URIPayload) { p.TokenID = p.TokenID[:15] }},
		{"token id 17 bytes", func(p *URIPayload) { p.TokenID = append(p.TokenID, 0) }},
		{"secret 31 bytes", func(p *URIPayload) { p.Secret = p.Secret[:31] }},
		{"too many hints", func(p *URIPayload) {
			p.Hints = make([]Hint, MaxURIHints+1)
			for i := range p.Hints {
				p.Hints[i] = Hint{Kind: HintIPv4, Addr: "10.0.0.1", Port: 1}
			}
		}},
		{"bad hint kind", func(p *URIPayload) { p.Hints[0].Kind = HintKind(4) }},
		{"empty hint addr", func(p *URIPayload) { p.Hints[0].Addr = "" }},
		{"oversized hint addr", func(p *URIPayload) {
			p.Hints[0].Addr = strings.Repeat("a", maxHintAddr+1)
		}},
		{"hint port 0", func(p *URIPayload) { p.Hints[0].Port = 0 }},
		{"hint port 65536", func(p *URIPayload) { p.Hints[0].Port = 65536 }},
		{"empty daemon name", func(p *URIPayload) { p.DaemonName = "" }},
		{"daemon name too long", func(p *URIPayload) { p.DaemonName = strings.Repeat("n", maxDaemonName+1) }},
		{"daemon name control char", func(p *URIPayload) { p.DaemonName = "a\x01b" }},
		{"daemon name bad utf8", func(p *URIPayload) { p.DaemonName = "a\xffb" }},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			p := base
			tc.mutate(&p)
			if _, err := EncodeURI(p); err == nil {
				t.Fatal("expected error")
			}
		})
	}
}

// hintCountOffset is the index of the hint count byte in a payload:
// version(1)+id(16)+secret(32)+expires(4)+ephem(32)+daemon(32).
const hintCountOffset = 1 + tokenIDLen + secretLen + 4 + 32 + 32

func TestURIDecodeMalformed(t *testing.T) {
	full := validRaw(t, testPayload())
	noHints := validRaw(t, func() URIPayload { p := testPayload(); p.Hints = nil; return p }())

	// freeFormRaw builds a syntactically complete payload without running the
	// encode-side validation, so a field can be set beyond its bound.
	freeFormRaw := func(hintCount byte, name string) []byte {
		var r []byte
		r = append(r, uriVersion)
		r = append(r, make([]byte, tokenIDLen)...)
		r = append(r, make([]byte, secretLen)...)
		r = append(r, 0, 0, 0, 0)
		r = append(r, make([]byte, 32)...)
		r = append(r, make([]byte, 32)...)
		r = append(r, hintCount)
		for i := 0; i < int(hintCount); i++ {
			r = append(r, byte(HintIPv4))
			r = appendVarint(r, 8)
			r = append(r, "10.0.0.1"...)
			r = append(r, 0, 1)
		}
		r = appendVarint(r, uint64(len(name)))
		r = append(r, name...)
		return r
	}

	cases := []struct {
		name string
		raw  []byte
	}{
		{"empty payload", []byte{}},
		{"truncated version only", full[:1]},
		{"truncated token id", full[:5]},
		{"truncated secret", full[:16+10]},
		{"truncated expires", full[:16+32+2]},
		{"truncated ephemeral pub", full[:16+32+4+16]},
		{"truncated daemon pub", full[:16+32+4+32+10]},
		{"truncated hint count", full[:hintCountOffset]},
		{"bad version", func() []byte { r := append([]byte{}, full...); r[0] = 2; return r }()},
		{"trailing byte", append(append([]byte{}, full...), 0)},
		{"bad hint kind", func() []byte {
			r := append([]byte{}, full...)
			r[hintCountOffset+1] = 4 // hint 0 kind byte (4 exceeds HintRelay)
			return r
		}()},
		{"empty hint addr", func() []byte {
			r := append([]byte{}, full...)
			r[hintCountOffset+2] = 0 // hint 0 addr_len varint
			return r
		}()},
		{"zero hint port", func() []byte {
			r := append([]byte{}, full...)
			// hint 0 starts at hintCountOffset+1: kind(1)+addr_len(1)+
			// addr(12 "192.168.1.10")+port(2), so the port is at +15 and
			// +16 from the hint count byte.
			r[hintCountOffset+15] = 0
			r[hintCountOffset+16] = 0
			return r
		}()},
		{"empty daemon name", func() []byte {
			r := append([]byte{}, noHints...)
			r[len(r)-2] = 0 // name_len varint; last byte is the name
			return r
		}()},
		{"daemon name too long", freeFormRaw(0, strings.Repeat("n", maxDaemonName+1))},
		{"daemon name control char", freeFormRaw(0, "a\x01b")},
		{"daemon name bad utf8", freeFormRaw(0, "a\xffb")},
		{"hint count exceeds max", freeFormRaw(MaxURIHints+1, "a")},
		{"varint too long", func() []byte {
			// No hints; the name_len position holds a 5-byte varint.
			r := append([]byte{}, noHints[:hintCountOffset+1]...)
			return append(r, 0xff, 0xff, 0xff, 0xff, 0xff)
		}()},
		{"payload too large", bytes.Repeat([]byte{0}, maxURIPayload+1)},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if _, err := DecodeURI(encodeRaw(t, tc.raw)); err == nil {
				t.Fatal("expected error")
			}
		})
	}

	t.Run("not base64", func(t *testing.T) {
		if _, err := DecodeURI(uriScheme + "!!!not-base64!!!"); err == nil {
			t.Fatal("expected error")
		}
	})

	t.Run("wrong scheme", func(t *testing.T) {
		if _, err := DecodeURI("https://example.com/pair?d=abc"); err == nil {
			t.Fatal("expected error")
		}
	})
	t.Run("empty d param", func(t *testing.T) {
		if _, err := DecodeURI("remotly://pair?d="); err == nil {
			t.Fatal("expected error")
		}
	})
}

func TestDecodeURIRejectsPaddingAndStdAlphabet(t *testing.T) {
	raw := validRaw(t, testPayload())
	std := base64.StdEncoding.EncodeToString(raw)
	if _, err := DecodeURI(uriScheme + std); err == nil {
		t.Fatal("accepted standard-alphabet base64")
	}
	padded := base64.RawStdEncoding.EncodeToString(raw)
	padded += "="
	if _, err := DecodeURI(uriScheme + padded); err == nil {
		t.Fatal("accepted padded base64")
	}
}

func TestURIRoundTripRelayHint(t *testing.T) {
	p := testPayload()
	p.Hints = []Hint{
		{Kind: HintRelay, Addr: "relay.example.com", Port: 443},
		{Kind: HintIPv4, Addr: "192.168.1.10", Port: 8443},
	}
	uri, err := EncodeURI(p)
	if err != nil {
		t.Fatalf("EncodeURI: %v", err)
	}
	got, err := DecodeURI(uri)
	if err != nil {
		t.Fatalf("DecodeURI: %v", err)
	}
	if len(got.Hints) != 2 {
		t.Fatalf("hints = %d, want 2", len(got.Hints))
	}
	if got.Hints[0].Kind != HintRelay || got.Hints[0].Addr != "relay.example.com" || got.Hints[0].Port != 443 {
		t.Fatalf("relay hint = %+v", got.Hints[0])
	}
}
