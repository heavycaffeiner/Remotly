package protocol

// Canonical wire vector tests. The vector files in testdata/ are the shared
// source of truth for the v1 freeze: the Go tests here and the JS fixture
// tests in app both read them and must reproduce them byte for byte. The
// files use synthetic keys and secrets only; none is real credential material.

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"testing"

	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

func loadVector(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile(filepath.Join("testdata", name))
	if err != nil {
		t.Fatalf("read vector %s: %v", name, err)
	}
	return b
}

func jsonUnmarshal(b []byte, v interface{}) error { return json.Unmarshal(b, v) }

func TestVectorBase64URL(t *testing.T) {
	var cases []struct{ Bytes, Encoded string }
	if err := jsonUnmarshal(loadVector(t, "base64url.json"), &cases); err != nil {
		t.Fatal(err)
	}
	for i, c := range cases {
		raw, err := hex.DecodeString(c.Bytes)
		if err != nil {
			t.Fatalf("case %d: bad hex %q: %v", i, c.Bytes, err)
		}
		if got := base64.RawURLEncoding.EncodeToString(raw); got != c.Encoded {
			t.Fatalf("case %d: encode %x = %q, want %q", i, raw, got, c.Encoded)
		}
		back, err := base64.RawURLEncoding.DecodeString(c.Encoded)
		if err != nil || !reflect.DeepEqual(back, raw) {
			t.Fatalf("case %d: decode %q = %x (%v), want %x", i, c.Encoded, back, err, raw)
		}
	}
}

func TestVectorVarint(t *testing.T) {
	var cases []struct {
		Value uint64 `json:"value"`
		Bytes string `json:"bytes"`
	}
	if err := jsonUnmarshal(loadVector(t, "varint.json"), &cases); err != nil {
		t.Fatal(err)
	}
	for i, c := range cases {
		want, _ := hex.DecodeString(c.Bytes)
		if got := AppendVarint(nil, c.Value); !reflect.DeepEqual(got, want) {
			t.Fatalf("case %d: encode %d = %x, want %x", i, c.Value, got, want)
		}
		v, n, err := ReadVarint(want)
		if err != nil || v != c.Value || n != len(want) {
			t.Fatalf("case %d: decode %x = (%d,%d,%v), want (%d,%d,nil)", i, want, v, n, err, c.Value, len(want))
		}
	}
}

func TestVectorTransferChunk(t *testing.T) {
	var cases []struct {
		Offset  uint64 `json:"offset"`
		Chunk   string `json:"chunk"`
		Payload string `json:"payload"`
	}
	if err := jsonUnmarshal(loadVector(t, "transfer-chunk.json"), &cases); err != nil {
		t.Fatal(err)
	}
	for i, c := range cases {
		chunk, _ := hex.DecodeString(c.Chunk)
		payload, _ := hex.DecodeString(c.Payload)
		var want []byte
		var ob [8]byte
		binary.BigEndian.PutUint64(ob[:], c.Offset)
		want = append(want, ob[:]...)
		want = append(want, chunk...)
		if !reflect.DeepEqual(payload, want) {
			t.Fatalf("case %d: payload %x, want %x (offset %d, chunk %x)", i, payload, want, c.Offset, chunk)
		}
		// Split back: first 8 bytes are the big-endian offset, rest is the chunk.
		if len(payload) < 8 {
			t.Fatalf("case %d: payload too short", i)
		}
		if off := binary.BigEndian.Uint64(payload[:8]); off != c.Offset {
			t.Fatalf("case %d: offset %d, want %d", i, off, c.Offset)
		}
		if got := hex.EncodeToString(payload[8:]); got != c.Chunk {
			t.Fatalf("case %d: chunk %s, want %s", i, got, c.Chunk)
		}
	}
}

func TestVectorRelayFrame(t *testing.T) {
	var cases []struct {
		Msg      string `json:"msg"`
		Role     string `json:"role"`
		RelayID  string `json:"relay_id"`
		Code     uint16 `json:"code"`
		Reason   string `json:"reason"`
		StreamID uint32 `json:"stream_id"`
		Bytes    string `json:"bytes"`
	}
	if err := jsonUnmarshal(loadVector(t, "relay-frame.json"), &cases); err != nil {
		t.Fatal(err)
	}
	for i, c := range cases {
		var id [16]byte
		if c.RelayID != "" {
			b, _ := hex.DecodeString(c.RelayID)
			copy(id[:], b)
		}
		var m relayproto.Message
		switch c.Msg {
		case "join":
			role := relayproto.RoleDaemon
			if c.Role == "app" {
				role = relayproto.RoleApp
			}
			m = relayproto.NewJoin(role, id)
		case "end":
			m = relayproto.NewEnd(c.Code, c.Reason)
		case "stream_open":
			m = relayproto.NewStreamOpen(c.StreamID)
		default:
			t.Fatalf("case %d: unknown msg %q", i, c.Msg)
		}
		got, err := relayproto.Encode(m)
		if err != nil {
			t.Fatalf("case %d: encode: %v", i, err)
		}
		want, _ := hex.DecodeString(c.Bytes)
		if !reflect.DeepEqual(got, want) {
			t.Fatalf("case %d: encode %q = %x, want %x", i, c.Msg, got, want)
		}
		// Decode back and check the round-trip type.
		dec, err := relayproto.Read(bytes.NewReader(want))
		if err != nil {
			t.Fatalf("case %d: read: %v", i, err)
		}
		if dec.Type != m.Type {
			t.Fatalf("case %d: type %d, want %d", i, dec.Type, m.Type)
		}
	}
}

func TestVectorPairingPayload(t *testing.T) {
	var cases []struct {
		TokenID      string `json:"token_id"`
		Secret       string `json:"secret"`
		Expires      int64  `json:"expires"`
		EphemeralPub string `json:"ephemeral_pub"`
		DaemonPub    string `json:"daemon_pub"`
		Hints        []struct {
			Kind uint8  `json:"kind"`
			Addr string `json:"addr"`
			Port int    `json:"port"`
		} `json:"hints"`
		DaemonName string `json:"daemon_name"`
		URI        string `json:"uri"`
	}
	if err := jsonUnmarshal(loadVector(t, "pairing-payload.json"), &cases); err != nil {
		t.Fatal(err)
	}
	for i, c := range cases {
		tokenID, _ := hex.DecodeString(c.TokenID)
		secret, _ := hex.DecodeString(c.Secret)
		var eph, dpub [32]byte
		if _, err := hex.Decode(eph[:], []byte(c.EphemeralPub)); err != nil {
			t.Fatalf("case %d: eph: %v", i, err)
		}
		if _, err := hex.Decode(dpub[:], []byte(c.DaemonPub)); err != nil {
			t.Fatalf("case %d: dpub: %v", i, err)
		}
		hints := make([]pairing.Hint, 0, len(c.Hints))
		for _, h := range c.Hints {
			hints = append(hints, pairing.Hint{Kind: pairing.HintKind(h.Kind), Addr: h.Addr, Port: h.Port})
		}
		p := pairing.URIPayload{
			Version: 1, TokenID: tokenID, Secret: secret, Expires: c.Expires,
			EphemeralPub: eph, DaemonPub: dpub, Hints: hints, DaemonName: c.DaemonName,
		}
		// Encode must reproduce the canonical URI exactly.
		uri, err := pairing.EncodeURI(p)
		if err != nil {
			t.Fatalf("case %d: encode: %v", i, err)
		}
		if uri != c.URI {
			t.Fatalf("case %d: encode mismatch\n got %s\nwant %s", i, uri, c.URI)
		}
		// Decode must round-trip every field.
		got, err := pairing.DecodeURI(c.URI)
		if err != nil {
			t.Fatalf("case %d: decode: %v", i, err)
		}
		if !reflect.DeepEqual(got.TokenID, tokenID) || !reflect.DeepEqual(got.Secret, secret) ||
			got.Expires != c.Expires || got.EphemeralPub != eph || got.DaemonPub != dpub ||
			got.DaemonName != c.DaemonName || len(got.Hints) != len(hints) {
			t.Fatalf("case %d: decode mismatch: %+v", i, got)
		}
		for j := range hints {
			if got.Hints[j] != hints[j] {
				t.Fatalf("case %d hint %d: got %+v want %+v", i, j, got.Hints[j], hints[j])
			}
		}
	}
}
