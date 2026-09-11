package sshcore

import (
	"fmt"
	"testing"
	"time"

	"github.com/pkg/sftp"
)

// Read throughput against chunk size, over a real SFTP server on loopback.
//
// Each ReadChunk is one SFTP round trip, so a small chunk pays that latency
// more often. This is why the direct-to-URI download reads in 1MiB rather
// than the 256KB the bridged path uses, and it prints the numbers rather
// than asserting a rate, which would be a flake on shared hardware.
func TestSftpChunkSizeThroughput(t *testing.T) {
	if testing.Short() {
		t.Skip("throughput comparison is not a correctness check")
	}
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	const size = 8 << 20
	payload := make([]byte, size)
	for i := range payload {
		payload[i] = byte(i)
	}

	w, err := conn.OpenWrite("home/bench.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	for _, chunk := range []int{32 << 10, 256 << 10} {
		r, err := conn.OpenRead("home/bench.bin")
		if err != nil {
			t.Fatal(err)
		}
		start := time.Now()
		total := 0
		reads := 0
		for {
			b, err := r.ReadChunk(chunk)
			if err != nil {
				t.Fatal(err)
			}
			if b == nil {
				break
			}
			total += len(b)
			reads++
		}
		elapsed := time.Since(start)
		_ = r.Close()

		if total != size {
			t.Fatalf("chunk %d: read %d bytes, want %d", chunk, total, size)
		}
		mbps := float64(total) / elapsed.Seconds() / (1 << 20)
		fmt.Printf("chunk %6d: %3d reads, %7.1f MiB/s, %v\n",
			chunk, reads, mbps, elapsed.Round(time.Millisecond))
	}
}

// An upload that resumes keeps what is already on the server and continues
// after it. With a zero rewind the continuation point is the file's end,
// which is what a caller writing in one packet at a time would ask for.
func TestSftpOpenAppendResumesAtTheEnd(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	head := []byte("first half;")
	w, err := conn.OpenWrite("home/resume.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(head); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	// The transfer is interrupted here and picked up again.
	a, err := conn.OpenAppend("home/resume.bin", 0)
	if err != nil {
		t.Fatal(err)
	}
	if a.Offset() != int64(len(head)) {
		t.Fatalf("resume offset = %d, want %d", a.Offset(), len(head))
	}
	tail := []byte("second half")
	if _, err := a.Write(tail); err != nil {
		t.Fatal(err)
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/resume.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	got, err := r.ReadChunk(1024)
	if err != nil {
		t.Fatal(err)
	}
	if want := string(head) + string(tail); string(got) != want {
		t.Errorf("resumed file = %q, want %q", got, want)
	}
}

// Appending to a path that does not exist yet starts at zero, so a resume of a
// transfer that never wrote anything behaves like a fresh upload. A rewind
// larger than the file cannot drive the offset negative.
func TestSftpOpenAppendOnANewFileStartsAtZero(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	a, err := conn.OpenAppend("home/fresh.bin", 1<<20)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	if a.Offset() != 0 {
		t.Errorf("offset = %d, want 0", a.Offset())
	}
}

// The case the in-process repair cannot cover: the upload died without
// running any cleanup, so the server still holds bytes past the last write it
// acknowledged in full. Resuming at the file's end would bury that gap in the
// middle of the user's file and report success, so the resume rewinds by one
// write's worth and resends instead of trusting the length.
func TestSftpOpenAppendRewindsPastAnUnconfirmedTail(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	// 40 bytes on the server, of which only the first 24 are known good.
	w, err := conn.OpenWrite("home/killed.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte("0123456789abcdef0123456789abcdef01234567")); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	a, err := conn.OpenAppend("home/killed.bin", 16)
	if err != nil {
		t.Fatal(err)
	}
	defer a.Close()
	if a.Offset() != 24 {
		t.Fatalf("resume offset = %d, want 24", a.Offset())
	}
	size, err := a.Size()
	if err != nil {
		t.Fatal(err)
	}
	if size != 24 {
		t.Errorf("file left at %d bytes, want it cut to 24", size)
	}
}

// A concurrent write that fails partway can leave the server holding more
// bytes than the caller ever confirmed. Truncate is how the caller repairs
// that before a resume: cutting back to the confirmed length means the next
// append starts exactly where the caller believes it left off, rather than
// past a gap of bytes it never sent or past a tail it can't account for.
func TestSftpFileTruncateRepairsAFailedWrite(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	confirmed := []byte("confirmed data;")
	w, err := conn.OpenWrite("home/repair.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(confirmed); err != nil {
		t.Fatal(err)
	}
	// Simulates a concurrent write landing on the server before the request
	// that carried it was confirmed as failed to the caller.
	if _, err := w.Write([]byte("stray unconfirmed tail")); err != nil {
		t.Fatal(err)
	}
	if err := w.Truncate(int64(len(confirmed))); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	a, err := conn.OpenAppend("home/repair.bin", 0)
	if err != nil {
		t.Fatal(err)
	}
	if a.Offset() != int64(len(confirmed)) {
		t.Fatalf("resume offset = %d, want %d (the stray tail was not cut)", a.Offset(), len(confirmed))
	}
	tail := []byte("real continuation")
	if _, err := a.Write(tail); err != nil {
		t.Fatal(err)
	}
	if err := a.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/repair.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	got, err := r.ReadChunk(1024)
	if err != nil {
		t.Fatal(err)
	}
	if want := string(confirmed) + string(tail); string(got) != want {
		t.Errorf("repaired file = %q, want %q", got, want)
	}
}

// A download resumes by seeking, so only the missing tail crosses the network.
func TestSftpSeekToResumesADownload(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	payload := []byte("0123456789abcdefghij")
	w, err := conn.OpenWrite("home/seek.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/seek.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if err := r.SeekTo(10); err != nil {
		t.Fatal(err)
	}
	got, err := r.ReadChunk(1024)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "abcdefghij" {
		t.Errorf("resumed read = %q, want %q", got, "abcdefghij")
	}
}

func TestSftpSeekToRejectsANegativeOffset(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	w, err := conn.OpenWrite("home/neg.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/neg.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	if err := r.SeekTo(-1); err == nil {
		t.Error("SeekTo(-1) = nil error, want a rejection")
	}
}

// A panic inside a bound method must come back as an error.
//
// These methods are called from Java threads through gomobile, where a panic
// that unwinds out of the function crosses the JNI boundary and aborts the
// process: the app dies with no diagnosis instead of the transfer failing.
// Reading through a nil handle is the cheapest way to provoke the runtime
// panic that a torn-down connection produces for real.
func TestSftpFilePanicBecomesAnError(t *testing.T) {
	f := &SftpFile{}

	out, err := f.ReadChunk(16)
	if err == nil {
		t.Fatal("ReadChunk on a nil handle = nil error, want a failure")
	}
	if out != nil {
		t.Errorf("ReadChunk returned %d bytes alongside an error", len(out))
	}

	if _, err := f.Write([]byte("x")); err == nil {
		t.Error("Write on a nil handle = nil error, want a failure")
	}
	if err := f.Close(); err == nil {
		t.Error("Close on a nil handle = nil error, want a failure")
	}
	if _, err := f.Size(); err == nil {
		t.Error("Size on a nil handle = nil error, want a failure")
	}
	if err := f.SeekTo(0); err == nil {
		t.Error("SeekTo on a nil handle = nil error, want a failure")
	}
}

// Close must release the mutex even when a close panics, or every later call
// blocks on it forever. A hang is worse than the crash the recover prevents.
func TestSftpCloseReleasesTheLockOnPanic(t *testing.T) {
	s := NewSftp(nil)
	// A client whose Close panics: the same shape as a connection torn down
	// under an in-flight read.
	s.client = &sftp.Client{}
	s.Close()

	done := make(chan struct{})
	go func() {
		s.sftpClient()
		close(done)
	}()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("sftpClient blocked after Close: the mutex was never released")
	}
}
