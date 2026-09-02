package localctl

import (
	"bytes"
	"encoding/json"
	"errors"
	"io"
	"testing"
)

func TestFrameRoundTrip(t *testing.T) {
	cases := []struct {
		name    string
		kind    byte
		payload []byte
	}{
		{"output", FrameOutput, []byte("hello")},
		{"input", FrameInput, []byte{0x03}},
		{"resize", FrameResize, EncodeResize(120, 40)},
		{"empty exit", FrameExit, nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			var buf bytes.Buffer
			if err := WriteFrame(&buf, c.kind, c.payload); err != nil {
				t.Fatalf("write: %v", err)
			}
			kind, payload, err := ReadFrame(&buf)
			if err != nil {
				t.Fatalf("read: %v", err)
			}
			if kind != c.kind {
				t.Errorf("kind = %d, want %d", kind, c.kind)
			}
			if !bytes.Equal(payload, c.payload) {
				t.Errorf("payload = %q, want %q", payload, c.payload)
			}
		})
	}
}

// Frames carry terminal bytes, which include every byte value. A payload that
// happens to contain a frame header must not resynchronize the stream.
func TestFrameCarriesArbitraryBytes(t *testing.T) {
	payload := []byte{FrameOutput, 0, 0, 0, 9, 'x', 0xff, 0x00, '\n'}
	var buf bytes.Buffer
	if err := WriteFrame(&buf, FrameInput, payload); err != nil {
		t.Fatal(err)
	}
	if err := WriteFrame(&buf, FrameExit, nil); err != nil {
		t.Fatal(err)
	}

	kind, got, err := ReadFrame(&buf)
	if err != nil {
		t.Fatal(err)
	}
	if kind != FrameInput || !bytes.Equal(got, payload) {
		t.Fatalf("first frame = %d/%q, want %d/%q", kind, got, FrameInput, payload)
	}
	kind, _, err = ReadFrame(&buf)
	if err != nil {
		t.Fatal(err)
	}
	if kind != FrameExit {
		t.Fatalf("second frame kind = %d, want %d", kind, FrameExit)
	}
}

func TestFrameRejectsOversizePayload(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, FrameOutput, make([]byte, maxFramePayload+1)); err == nil {
		t.Fatal("write oversize = nil error, want a rejection")
	}
	// A crafted length header must be refused before allocating for it.
	hdr := []byte{FrameOutput, 0xff, 0xff, 0xff, 0xff}
	if _, _, err := ReadFrame(bytes.NewReader(hdr)); err == nil {
		t.Fatal("read oversize length = nil error, want a rejection")
	}
}

func TestFrameTruncatedIsAnError(t *testing.T) {
	var buf bytes.Buffer
	if err := WriteFrame(&buf, FrameOutput, []byte("abcdef")); err != nil {
		t.Fatal(err)
	}
	cut := buf.Bytes()[:7]
	if _, _, err := ReadFrame(bytes.NewReader(cut)); err == nil {
		t.Fatal("truncated frame = nil error, want a rejection")
	}
}

func TestResizeCodec(t *testing.T) {
	cols, rows, err := DecodeResize(EncodeResize(200, 50))
	if err != nil {
		t.Fatal(err)
	}
	if cols != 200 || rows != 50 {
		t.Fatalf("decoded %dx%d, want 200x50", cols, rows)
	}
	if _, _, err := DecodeResize([]byte{1, 2, 3}); err == nil {
		t.Fatal("short resize payload = nil error, want a rejection")
	}
}

// readJSONLine must stop at the newline so the frames that follow a response
// on the same connection are left for the stream reader.
func TestReadJSONLineLeavesTheStream(t *testing.T) {
	var buf bytes.Buffer
	buf.WriteString(`{"ok":true}` + "\n")
	if err := WriteFrame(&buf, FrameOutput, []byte("after")); err != nil {
		t.Fatal(err)
	}

	line, err := readJSONLine(&buf)
	if err != nil {
		t.Fatalf("readJSONLine: %v", err)
	}
	if string(line) != `{"ok":true}` {
		t.Fatalf("line = %q", line)
	}
	kind, payload, err := ReadFrame(&buf)
	if err != nil {
		t.Fatalf("frame after response: %v", err)
	}
	if kind != FrameOutput || string(payload) != "after" {
		t.Fatalf("frame = %d/%q, want output/\"after\"", kind, payload)
	}
}

func TestReadJSONLineRejectsUnterminated(t *testing.T) {
	_, err := readJSONLine(bytes.NewReader([]byte(`{"ok":true}`)))
	if !errors.Is(err, io.ErrUnexpectedEOF) && !errors.Is(err, io.EOF) {
		t.Fatalf("err = %v, want an EOF", err)
	}
}

// The request terminator must not be read as a frame.
//
// json.Encoder ends every document with a newline and Decode stops at the
// closing brace, so that newline sits in the decoder's buffer. Passing it to
// the frame reader made it the kind byte and the next four bytes a length of
// 50331648, which exceeds the payload bound: the attach died on the client's
// very first frame while an idle connection looked perfectly healthy.
func TestStreamAfterSkipsRequestTerminator(t *testing.T) {
	var wire bytes.Buffer
	if err := json.NewEncoder(&wire).Encode(Request{Op: "attach", SessionID: "abc"}); err != nil {
		t.Fatal(err)
	}
	if err := WriteFrame(&wire, FrameResize, EncodeResize(80, 24)); err != nil {
		t.Fatal(err)
	}
	if err := WriteFrame(&wire, FrameInput, []byte("hi")); err != nil {
		t.Fatal(err)
	}

	dec := json.NewDecoder(&wire)
	var req Request
	if err := dec.Decode(&req); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if req.Op != "attach" {
		t.Fatalf("op = %q", req.Op)
	}

	stream := streamAfter(dec, &wire)
	kind, payload, err := ReadFrame(stream)
	if err != nil {
		t.Fatalf("first frame: %v", err)
	}
	if kind != FrameResize {
		t.Fatalf("first frame kind = %d, want resize (%d)", kind, FrameResize)
	}
	cols, rows, err := DecodeResize(payload)
	if err != nil || cols != 80 || rows != 24 {
		t.Fatalf("resize = %dx%d err %v", cols, rows, err)
	}

	kind, payload, err = ReadFrame(stream)
	if err != nil {
		t.Fatalf("second frame: %v", err)
	}
	if kind != FrameInput || string(payload) != "hi" {
		t.Fatalf("second frame = %d/%q", kind, payload)
	}
}
