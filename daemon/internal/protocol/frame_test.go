package protocol

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"os"
	"strconv"
	"testing"

	"golang.org/x/crypto/chacha20poly1305"
)

// vectorKey matches the M0-06 spike's frame vector generator.
func vectorKey() [32]byte {
	return sha256.Sum256([]byte("remotly-frame-vector-key"))
}

func TestFrameRoundTrip(t *testing.T) {
	key := vectorKey()
	c := NewChaCha(key, key)
	cases := []struct {
		ch      byte
		id      uint32
		payload []byte
	}{
		{ChannelCtrl, 0, []byte("hello")},
		{ChannelTerm, 7, nil},
		{ChannelFile, 300, []byte("a longer payload for a file channel")},
		{ChannelCtrl, 0, []byte("wide chars: \xed\x95\x9c\xea\xb8\x80")},
		{ChannelTerm, 1<<32 - 1, []byte("max id")},
	}
	peer := NewChaCha(key, key)
	for i, tc := range cases {
		frame, err := c.SealFrame(tc.ch, tc.id, tc.payload)
		if err != nil {
			t.Fatalf("case %d seal: %v", i, err)
		}
		ch, id, payload, err := peer.OpenFrame(frame)
		if err != nil {
			t.Fatalf("case %d open: %v", i, err)
		}
		if ch != tc.ch || id != tc.id || !bytes.Equal(payload, tc.payload) {
			t.Fatalf("case %d round trip: got (%d, %d, %q), want (%d, %d, %q)",
				i, ch, id, payload, tc.ch, tc.id, tc.payload)
		}
	}
}

func TestFrameVectors(t *testing.T) {
	data, err := os.ReadFile("testdata/frame-vectors.json")
	if err != nil {
		t.Fatalf("read vectors: %v", err)
	}
	var vectors []struct {
		ChannelType string `json:"channel_type"`
		ChannelID   string `json:"channel_id"`
		Plaintext   string `json:"plaintext"`
		Frame       string `json:"frame"`
	}
	if err := json.Unmarshal(data, &vectors); err != nil {
		t.Fatalf("parse vectors: %v", err)
	}
	if len(vectors) == 0 {
		t.Fatal("no vectors")
	}
	for i, v := range vectors {
		ch, err := strconv.Atoi(v.ChannelType)
		if err != nil {
			t.Fatalf("vector %d: %v", i, err)
		}
		id, err := strconv.Atoi(v.ChannelID)
		if err != nil {
			t.Fatalf("vector %d: %v", i, err)
		}
		plain, err := hex.DecodeString(v.Plaintext)
		if err != nil {
			t.Fatalf("vector %d: %v", i, err)
		}
		want, err := hex.DecodeString(v.Frame)
		if err != nil {
			t.Fatalf("vector %d: %v", i, err)
		}
		key := vectorKey()
		c := NewChaCha(key, key)
		got, err := c.SealFrame(byte(ch), uint32(id), plain)
		if err != nil {
			t.Fatalf("vector %d seal: %v", i, err)
		}
		if !bytes.Equal(got, want) {
			t.Fatalf("vector %d: seal mismatch\ngot  %x\nwant %x", i, got, want)
		}
		peer := NewChaCha(key, key)
		gch, gid, gplain, err := peer.OpenFrame(want)
		if err != nil {
			t.Fatalf("vector %d open: %v", i, err)
		}
		if gch != byte(ch) || gid != uint32(id) || !bytes.Equal(gplain, plain) {
			t.Fatalf("vector %d: open mismatch", i)
		}
	}
}

func TestFrameNonceAdvances(t *testing.T) {
	key := vectorKey()
	c := NewChaCha(key, key)
	frame, err := c.SealFrame(ChannelTerm, 1, []byte("first"))
	if err != nil {
		t.Fatalf("seal: %v", err)
	}
	peer := NewChaCha(key, key)
	if _, _, _, err := peer.OpenFrame(frame); err != nil {
		t.Fatalf("open: %v", err)
	}
	// Replaying the same frame must fail: the nonce counter has advanced.
	if _, _, _, err := peer.OpenFrame(frame); !errors.Is(err, ErrDecrypt) {
		t.Fatalf("replay: %v", err)
	}
	// A fresh cipher on the same keys still decodes the first frame.
	fresh := NewChaCha(key, key)
	if _, _, _, err := fresh.OpenFrame(frame); err != nil {
		t.Fatalf("fresh: %v", err)
	}
}

func TestFrameRejects(t *testing.T) {
	key := vectorKey()
	good, err := NewChaCha(key, key).SealFrame(ChannelTerm, 1, []byte("ping"))
	if err != nil {
		t.Fatalf("seal: %v", err)
	}

	tampered := append([]byte{}, good...)
	tampered[len(tampered)-1] ^= 0x01

	// Header with a ciphertext length that exceeds the payload cap.
	oversized := append([]byte{ChannelCtrl, 0x00},
		AppendVarint(nil, MaxPayloadLen+chacha20poly1305.Overhead+1)...)

	// Header with a ciphertext length below the 16-byte tag.
	shortTag := []byte{ChannelCtrl, 0x00, 0x0F, 0x00}

	// Valid-length header, too little ciphertext.
	truncated := append([]byte{}, good[:len(good)-1]...)

	// 35-bit channel id: the varint is well formed but not a uint32.
	// 0x10 in the fifth group is 2^32.
	bigID := []byte{ChannelTerm, 0x80, 0x80, 0x80, 0x80, 0x10, 0x10, 0x00}

	cases := map[string]struct {
		data []byte
		want error
	}{
		"empty":       {nil, ErrFrameTruncated},
		"bad channel": {[]byte{0xFF, 0x00, 0x10, 0x00}, ErrBadChannel},
		"truncated varint": {
			[]byte{ChannelCtrl, 0x80, 0x80, 0x80, 0x80, 0x80}, ErrVarintTruncated,
		},
		"long varint":    {[]byte{ChannelCtrl, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80}, ErrVarintTooLong},
		"35-bit id":      {bigID, ErrVarintTooLong},
		"oversized":      {oversized, ErrFrameTooLarge},
		"short tag":      {shortTag, ErrFrameTooSmall},
		"truncated body": {truncated, ErrFrameTruncated},
		"tampered tag":   {tampered, ErrDecrypt},
	}
	for name, tc := range cases {
		c := NewChaCha(key, key)
		_, _, _, err := c.OpenFrame(tc.data)
		if !errors.Is(err, tc.want) {
			t.Fatalf("%s: got %v, want %v", name, err, tc.want)
		}
	}
}

func TestFrameSealBounds(t *testing.T) {
	key := vectorKey()
	c := NewChaCha(key, key)
	if _, err := c.SealFrame(ChannelTerm, 1, make([]byte, MaxPayloadLen)); err != nil {
		t.Fatalf("max payload: %v", err)
	}
	if _, err := c.SealFrame(ChannelTerm, 1, make([]byte, MaxPayloadLen+1)); !errors.Is(err, ErrFrameTooLarge) {
		t.Fatalf("over payload: %v", err)
	}
	if _, err := c.SealFrame(0xFF, 1, nil); !errors.Is(err, ErrBadChannel) {
		t.Fatalf("bad channel: %v", err)
	}
}
