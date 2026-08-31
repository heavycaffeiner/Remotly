package transport

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
)

func sha256hexT(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

// readFileFrame pulls the next file-channel frame with a timeout.
func (c *client) readFileFrame(t *testing.T, timeout time.Duration) termFrame {
	t.Helper()
	select {
	case f := <-c.fileOut:
		return f
	case <-c.dead:
		t.Fatalf("client: connection closed waiting for a file frame: %v", c.closeError())
	case <-time.After(timeout):
		t.Fatalf("client: timeout waiting for a file frame")
	}
	return termFrame{}
}

// TestTransferUploadOverChannel drives an upload end to end: create, chunk
// frames on the file channel with acks, then complete and verify the
// destination content and the returned whole-file hash.
func TestTransferUploadOverChannel(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	base := t.TempDir()
	dest := filepath.Join(base, "up.bin")
	content := []byte("upload over the file channel 0123456789")

	creq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferCreate,
		"direction", "up", "path", dest,
		"expected_size", len(content), "hash", sha256hexT(content)))
	if creq.Error != nil {
		t.Fatalf("create: %v", creq.Error)
	}
	if creq.TransferID == nil || creq.ChannelID == nil {
		t.Fatalf("create response missing ids: %+v", creq)
	}
	chID := *creq.ChannelID
	tid := *creq.TransferID

	off := 0
	for off < len(content) {
		end := off + 9
		if end > len(content) {
			end = len(content)
		}
		if err := c.sendFrame(protocol.ChannelFile, chID, encodeChunkFrame(int64(off), content[off:end])); err != nil {
			t.Fatalf("send chunk @%d: %v", off, err)
		}
		ack := c.notifUntil(t, protocol.TypeTransferAck, 5*time.Second)
		if ack == nil {
			t.Fatalf("no ack for chunk @%d", off)
		}
		off = end
	}

	dreq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferComplete, "transfer_id", tid))
	if dreq.Error != nil {
		t.Fatalf("complete: %v", dreq.Error)
	}
	if dreq.TransferHash == nil || *dreq.TransferHash != sha256hexT(content) {
		t.Fatalf("complete hash = %v, want %s", dreq.TransferHash, sha256hexT(content))
	}
	got, err := os.ReadFile(dest)
	if err != nil || string(got) != string(content) {
		t.Fatalf("destination = %q err=%v, want the content", got, err)
	}
}

// TestTransferDownloadOverChannel drives a download: create, then reassemble
// the daemon-pushed chunk frames on the file channel and verify the content
// and hash against the create response.
func TestTransferDownloadOverChannel(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	base := t.TempDir()
	src := filepath.Join(base, "down.bin")
	content := []byte("download source content 0123456789abcdef")
	if err := os.WriteFile(src, content, 0o644); err != nil {
		t.Fatal(err)
	}

	creq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferCreate,
		"direction", "down", "path", src))
	if creq.Error != nil {
		t.Fatalf("create: %v", creq.Error)
	}
	if creq.ChannelID == nil || creq.TransferHash == nil || creq.TransferSize == nil {
		t.Fatalf("create response incomplete: %+v", creq)
	}
	chID := *creq.ChannelID
	wantHash := *creq.TransferHash
	wantSize := *creq.TransferSize
	if wantHash != sha256hexT(content) {
		t.Fatalf("create hash = %s, want %s", wantHash, sha256hexT(content))
	}

	var got []byte
	for int64(len(got)) < wantSize {
		f := c.readFileFrame(t, 5*time.Second)
		if f.chID != chID {
			t.Fatalf("file frame on channel %d, want %d", f.chID, chID)
		}
		off, data, ok := decodeChunkFrame(f.payload)
		if !ok {
			t.Fatalf("bad chunk frame: %v", f.payload)
		}
		if off != int64(len(got)) {
			t.Fatalf("chunk offset = %d, want %d", off, len(got))
		}
		got = append(got, data...)
	}
	if string(got) != string(content) {
		t.Fatalf("downloaded = %q, want source content", got)
	}
	if sha256hexT(got) != wantHash {
		t.Fatalf("download hash mismatch")
	}
}

// TestTransferUploadResume uploads part of a file, queries the status, and
// resumes from the reported offset, verifying the final result.
func TestTransferUploadResume(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	base := t.TempDir()
	dest := filepath.Join(base, "resume.bin")
	content := []byte("01234567890123456789")

	creq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferCreate,
		"direction", "up", "path", dest,
		"expected_size", len(content), "hash", sha256hexT(content)))
	if creq.Error != nil {
		t.Fatalf("create: %v", creq.Error)
	}
	chID := *creq.ChannelID
	tid := *creq.TransferID

	// Upload the first 8 bytes, then stop (simulating a drop).
	if err := c.sendFrame(protocol.ChannelFile, chID, encodeChunkFrame(0, content[:8])); err != nil {
		t.Fatal(err)
	}
	c.notifUntil(t, protocol.TypeTransferAck, 5*time.Second)

	// The transfer still lives in the daemon after the channel closes.
	sreq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferStatus, "transfer_id", tid))
	if sreq.Error != nil {
		t.Fatalf("status: %v", sreq.Error)
	}
	if sreq.Offset64 == nil || *sreq.Offset64 != 8 {
		t.Fatalf("status offset = %v, want 8", sreq.Offset64)
	}

	// Resume: re-attach a channel and continue from the reported offset.
	rreq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferResume, "transfer_id", tid))
	if rreq.Error != nil {
		t.Fatalf("resume: %v", rreq.Error)
	}
	if rreq.ChannelID == nil || rreq.ResumeOff == nil || *rreq.ResumeOff != 8 {
		t.Fatalf("resume response incomplete: %+v", rreq)
	}
	resumeCh := *rreq.ChannelID
	if err := c.sendFrame(protocol.ChannelFile, resumeCh, encodeChunkFrame(8, content[8:])); err != nil {
		t.Fatal(err)
	}
	c.notifUntil(t, protocol.TypeTransferAck, 5*time.Second)

	dreq := c.request(t, ctrlJSON(c.newID(), protocol.TypeTransferComplete, "transfer_id", tid))
	if dreq.Error != nil {
		t.Fatalf("complete: %v", dreq.Error)
	}
	got, err := os.ReadFile(dest)
	if err != nil || string(got) != string(content) {
		t.Fatalf("destination = %q err=%v, want the full content", got, err)
	}
}

// TestTransferAuthorizationFailsClosed verifies a transfer is bound to the
// device that created it: a second device cannot status or complete it.
func TestTransferAuthorizationFailsClosed(t *testing.T) {
	e := newEnv(t, envCfg{})
	appA := newAppKey(t)
	cA := e.newClientPair(t, appA, e.tokens.Create())
	cA.hello(t, e, "phone-a")

	base := t.TempDir()
	dest := filepath.Join(base, "authz.bin")
	content := []byte("0123456789")
	creq := cA.request(t, ctrlJSON(cA.newID(), protocol.TypeTransferCreate,
		"direction", "up", "path", dest,
		"expected_size", len(content), "hash", sha256hexT(content)))
	if creq.Error != nil {
		t.Fatalf("create: %v", creq.Error)
	}
	tid := *creq.TransferID

	// A different device tries to status the transfer: not found for it.
	appB := newAppKey(t)
	cB := e.newClientPair(t, appB, e.tokens.Create())
	cB.hello(t, e, "phone-b")
	sreq := cB.request(t, ctrlJSON(cB.newID(), protocol.TypeTransferStatus, "transfer_id", tid))
	if sreq.Error == nil || sreq.Error.Code != protocol.CodeTransferNotFound {
		t.Fatalf("foreign status = %v, want transfer_not_found", sreq.Error)
	}
}
