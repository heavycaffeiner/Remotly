package session

import (
	"bytes"
	"fmt"
	"testing"
)

func TestRingShortLinesWrap(t *testing.T) {
	r := newRing(10, derivedByteCap(10))
	for i := 0; i < 25; i++ {
		r.append([]byte(fmt.Sprintf("line-%02d\n", i)))
	}
	want := make([]byte, 0)
	for i := 15; i < 25; i++ {
		want = append(want, []byte(fmt.Sprintf("line-%02d\n", i))...)
	}
	if got := r.snapshot(); !bytes.Equal(got, want) {
		t.Fatalf("snapshot %q, want %q", got, want)
	}
}

func TestRingKeepsTrailingPartialLine(t *testing.T) {
	r := newRing(1, derivedByteCap(1))
	r.append([]byte("a\nb\nc"))
	got := r.snapshot()
	if string(got) != "b\nc" {
		t.Fatalf("snapshot %q, want %q", got, "b\nc")
	}
}

func TestRingSingleHugeLine(t *testing.T) {
	r := newRing(1000, 4<<20)
	huge := bytes.Repeat([]byte("z"), 5<<20)
	r.append(huge)
	if r.len() > 4<<20 {
		t.Fatalf("ring %d bytes exceeds cap", r.len())
	}
	got := r.snapshot()
	if !bytes.HasSuffix(huge, got) {
		t.Fatal("retained bytes are not the tail of the stream")
	}
	if len(got) != 4<<20 {
		t.Fatalf("retained %d, want the full cap", len(got))
	}
}

func TestRingManySmallChunksNoNewlines(t *testing.T) {
	// Exactly the byte cap of newline-free data: everything is retained.
	r := newRing(10, 4<<20)
	var all bytes.Buffer
	for i := 0; i < 400; i++ {
		c := bytes.Repeat([]byte{byte('a' + i%26)}, 10240)
		all.Write(c)
		r.append(c)
	}
	if got := r.snapshot(); !bytes.Equal(got, all.Bytes()) {
		t.Fatalf("retained %d bytes, want all %d", len(got), all.Len())
	}
}

func TestRingLongLinesByteEviction(t *testing.T) {
	// 100-line cap but the byte valve (128 bytes/line derived, floored at
	// 4 MiB) evicts first when lines are long.
	lineCap := 100
	r := newRing(lineCap, derivedByteCap(lineCap))
	var all bytes.Buffer
	line := bytes.Repeat([]byte("l"), 4096)
	for i := 0; i < 2000; i++ {
		all.Write(line)
		all.WriteByte('\n')
		r.append(append(append([]byte{}, line...), '\n'))
	}
	if r.len() > maxRingBytes {
		t.Fatalf("ring %d exceeds hard cap", r.len())
	}
	got := r.snapshot()
	if !bytes.HasSuffix(all.Bytes(), got) {
		t.Fatal("retained stream is not a tail")
	}
}

func TestRingEmptySnapshot(t *testing.T) {
	r := newRing(10, derivedByteCap(10))
	if got := r.snapshot(); got != nil {
		t.Fatalf("snapshot %v", got)
	}
	if r.len() != 0 {
		t.Fatalf("len %d", r.len())
	}
}

func TestRingExactLineCapNoEviction(t *testing.T) {
	r := newRing(5, derivedByteCap(5))
	for i := 0; i < 5; i++ {
		r.append([]byte(fmt.Sprintf("x%d\n", i)))
	}
	if r.len() != 15 {
		t.Fatalf("len %d, want 15", r.len())
	}
	if got := r.snapshot(); len(got) != 15 {
		t.Fatalf("snapshot %q", got)
	}
}
