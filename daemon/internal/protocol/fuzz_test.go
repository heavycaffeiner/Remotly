package protocol

import (
	"encoding/base64"
	"testing"
)

func FuzzVarint(f *testing.F) {
	f.Add([]byte{0x00})
	f.Add([]byte{0x7f})
	f.Add([]byte{0x80, 0x01})
	f.Add([]byte{0xff})
	f.Add([]byte{0x80, 0x80, 0x80, 0x80, 0x80})
	f.Add([]byte{0x80, 0x80, 0x80, 0x80, 0x80, 0x80})
	f.Add([]byte{0xac, 0x02})
	f.Fuzz(func(t *testing.T, data []byte) {
		v, n, err := ReadVarint(data)
		if err != nil {
			return
		}
		if n > len(data) || n == 0 {
			t.Fatalf("consume %d out of range for %d bytes", n, len(data))
		}
		// Whatever decoded must re-encode to a varint that decodes back.
		buf := AppendVarint(nil, v)
		if v2, n2, err2 := ReadVarint(buf); err2 != nil || v2 != v || n2 != len(buf) {
			t.Fatalf("round trip: %v %d %d", err2, v2, n2)
		}
	})
}

func FuzzOpenFrame(f *testing.F) {
	key := vectorKey()
	seed, err := NewChaCha(key, key).SealFrame(ChannelTerm, 1, []byte("fuzz"))
	if err != nil {
		f.Fatal(err)
	}
	f.Add(seed)
	f.Add([]byte{0x00})
	f.Add([]byte{0x01})
	f.Add([]byte{ChannelTerm, 0x01, 0x10})
	f.Add([]byte{0xFF, 0x00, 0x10, 0x00})
	f.Fuzz(func(t *testing.T, data []byte) {
		c := NewChaCha(key, key)
		_, _, _, _ = c.OpenFrame(data)
	})
}

func FuzzControl(f *testing.F) {
	pub := base64.RawURLEncoding.EncodeToString(make([]byte, 32))
	f.Add([]byte(`{"id":1,"type":"hello","device_name":"n","device_pub":"` + pub + `"}`))
	f.Add([]byte(`{"id":2,"type":"session.create","kind":"agent","command":"ls"}`))
	f.Add([]byte(`{"id":3,"type":"session.list"}`))
	f.Add([]byte(`{"id":4,"type":"session.attach","session_id":"` + testSessionID + `"}`))
	f.Add([]byte(`{}`))
	f.Add([]byte(`{"id":1,"type":"session.list"}`))
	f.Fuzz(func(t *testing.T, data []byte) {
		_, _ = Parse(data)
		_, _ = ParseResponse(data)
		_, _ = ParseNotification(data)
	})
}

func FuzzFrameRoundTrip(f *testing.F) {
	f.Add(byte(ChannelTerm), uint32(1), []byte("payload"))
	f.Add(byte(ChannelCtrl), uint32(0), []byte{})
	f.Add(byte(ChannelFile), uint32(300), []byte("file bytes"))
	f.Fuzz(func(t *testing.T, ch byte, id uint32, payload []byte) {
		if int(ch) >= channelCount || len(payload) > 1<<16 {
			return
		}
		key := vectorKey()
		c := NewChaCha(key, key)
		frame, err := c.SealFrame(ch, id, payload)
		if err != nil {
			return
		}
		peer := NewChaCha(key, key)
		gch, gid, gpayload, err := peer.OpenFrame(frame)
		if err != nil || gch != ch || gid != id || string(gpayload) != string(payload) {
			t.Fatalf("round trip: %v", err)
		}
	})
}
