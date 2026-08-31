package protocol

import (
	"bytes"
	"encoding/hex"
	"errors"
	"testing"
)

func TestVarintRoundTrip(t *testing.T) {
	values := []uint64{0, 1, 127, 128, 255, 256, 16383, 16384, 1 << 21, 1<<32 - 1}
	for _, v := range values {
		buf := AppendVarint(nil, v)
		if len(buf) > maxVarintBytes {
			t.Fatalf("varint %d encoded in %d bytes", v, len(buf))
		}
		got, n, err := ReadVarint(buf)
		if err != nil || got != v || n != len(buf) {
			t.Fatalf("varint %d: got %d, n=%d, err=%v", v, got, n, err)
		}
	}
}

func TestVarintKnownEncodings(t *testing.T) {
	cases := []struct {
		v   uint64
		hex string
	}{
		{0, "00"},
		{1, "01"},
		{127, "7f"},
		{128, "8001"},
		{300, "ac02"},
		{(1 << 32) - 1, "ffffffff0f"},
	}
	for _, c := range cases {
		buf := AppendVarint(nil, c.v)
		want, _ := hex.DecodeString(c.hex)
		if !bytes.Equal(buf, want) {
			t.Fatalf("varint %d: got %x, want %s", c.v, buf, c.hex)
		}
	}
}

func TestVarintErrors(t *testing.T) {
	// Five continuation bytes with no terminator.
	if _, _, err := ReadVarint([]byte{0x80, 0x80, 0x80, 0x80, 0x80}); !errors.Is(err, ErrVarintTruncated) {
		t.Fatalf("truncated: %v", err)
	}
	// Six continuation bytes: the sixth exceeds the 5-byte bound.
	if _, _, err := ReadVarint([]byte{0x80, 0x80, 0x80, 0x80, 0x80, 0x80}); !errors.Is(err, ErrVarintTooLong) {
		t.Fatalf("too long: %v", err)
	}
	if _, _, err := ReadVarint(nil); !errors.Is(err, ErrVarintTruncated) {
		t.Fatalf("empty: %v", err)
	}
}

func TestVarintTrailingBytes(t *testing.T) {
	buf := append(AppendVarint(nil, 300), 0xFF)
	got, n, err := ReadVarint(buf)
	if err != nil || got != 300 || n != 2 {
		t.Fatalf("trailing: got %d, n=%d, err=%v", got, n, err)
	}
}
