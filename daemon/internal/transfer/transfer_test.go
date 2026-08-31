package transfer

import (
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/fsops"
)

var (
	devA = [32]byte{1, 2, 3}
	devB = [32]byte{9, 9, 9}
)

func sha256hex(b []byte) string {
	h := sha256.Sum256(b)
	return hex.EncodeToString(h[:])
}

func newMgr(t *testing.T) (*Manager, string) {
	t.Helper()
	dir := t.TempDir()
	return NewManager(Options{FS: fsops.New()}), dir
}

func mustCreate(t *testing.T, m *Manager, p CreateParams) *Transfer {
	t.Helper()
	tr, err := m.Create(p)
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	return tr
}

func uploadAll(t *testing.T, m *Manager, device [32]byte, id string, content []byte, chunk int) {
	t.Helper()
	for off := 0; off < len(content); off += chunk {
		end := off + chunk
		if end > len(content) {
			end = len(content)
		}
		if _, err := m.WriteChunk(device, id, int64(off), content[off:end]); err != nil {
			t.Fatalf("write @%d: %v", off, err)
		}
	}
}

// TestUploadConcurrentSameOffset sends the same chunk from two goroutines at
// once. Advancing the offset only after the write let both callers pass the
// ordering check and count their bytes, so the file was short while the
// transfer reported itself complete and the hash check failed on a file the
// user had already been told was uploaded.
func TestUploadConcurrentSameOffset(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "race.bin")
	content := []byte("0123456789abcdef")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: int64(len(content)), Hash: sha256hex(content), Conflict: ConflictFail,
	})

	// Both goroutines offer the first half at offset 0. Exactly one may be
	// counted; the other is a duplicate and must be acknowledged, not added.
	half := len(content) / 2
	var wg sync.WaitGroup
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, _ = m.WriteChunk(devA, tr.ID, 0, content[:half])
		}()
	}
	wg.Wait()

	off, _ := tr.Status()
	if off != int64(half) {
		t.Fatalf("offset after duplicate chunks = %d, want %d", off, half)
	}

	// The rest still lands at the offset the first half left behind, and the
	// hash proves the file is whole.
	if _, err := m.WriteChunk(devA, tr.ID, int64(half), content[half:]); err != nil {
		t.Fatalf("write tail: %v", err)
	}
	if _, err := m.Complete(devA, tr.ID); err != nil {
		t.Fatalf("complete: %v", err)
	}
	got, err := os.ReadFile(dest)
	if err != nil {
		t.Fatalf("read dest: %v", err)
	}
	if string(got) != string(content) {
		t.Fatalf("dest = %q, want %q", got, content)
	}
}

// TestUploadTotalTempBound proves the documented total bound holds across
// several transfers. Only the per-transfer size was checked, so MaxConcurrent
// uploads could each sit under the cap and still fill the disk between them.
func TestUploadTotalTempBound(t *testing.T) {
	dir := t.TempDir()
	m := NewManager(Options{FS: fsops.New(), MaxTempBytes: 24})
	chunk := []byte("0123456789")

	newUpload := func(name string) *Transfer {
		t.Helper()
		return mustCreate(t, m, CreateParams{
			Device: devA, Direction: Up, Path: filepath.Join(dir, name),
			ExpectedSize: int64(len(chunk)), Hash: sha256hex(chunk),
			Conflict: ConflictFail,
		})
	}

	// Two uploads of ten bytes fit under the twenty-four byte total.
	a := newUpload("a.bin")
	b := newUpload("b.bin")
	if _, err := m.WriteChunk(devA, a.ID, 0, chunk); err != nil {
		t.Fatalf("first upload: %v", err)
	}
	if _, err := m.WriteChunk(devA, b.ID, 0, chunk); err != nil {
		t.Fatalf("second upload: %v", err)
	}

	// The third would take the total to thirty, past the bound, even though
	// its own size is well under it.
	c := newUpload("c.bin")
	if _, err := m.WriteChunk(devA, c.ID, 0, chunk); !errors.Is(err, ErrTooLarge) {
		t.Fatalf("over-budget write = %v, want ErrTooLarge", err)
	}

	// Completing one releases its budget, so the next upload proceeds.
	if _, err := m.Complete(devA, a.ID); err != nil {
		t.Fatalf("complete: %v", err)
	}
	if _, err := m.WriteChunk(devA, c.ID, 0, chunk); err != nil {
		t.Fatalf("write after release: %v", err)
	}
}

// TestUploadRoundTrip sends a multi-chunk upload and verifies the destination
// content and the returned whole-file hash.
func TestUploadRoundTrip(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "out.bin")
	content := []byte("remotly transfer content, multi chunk")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: int64(len(content)), Hash: sha256hex(content), Conflict: ConflictFail,
	})
	uploadAll(t, m, devA, tr.ID, content, 7)
	gotHash, err := m.Complete(devA, tr.ID)
	if err != nil {
		t.Fatalf("complete: %v", err)
	}
	if gotHash != sha256hex(content) {
		t.Fatalf("hash = %s, want %s", gotHash, sha256hex(content))
	}
	got, err := os.ReadFile(dest)
	if err != nil || string(got) != string(content) {
		t.Fatalf("dest = %q err=%v, want the content", got, err)
	}
	// No temp file should remain.
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if _, ok := tempIDFromName(e.Name()); ok {
			t.Errorf("leftover temp: %s", e.Name())
		}
	}
}

// TestUploadEmpty verifies an empty upload completes to an empty file.
func TestUploadEmpty(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "empty.bin")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 0, Hash: sha256hex(nil), Conflict: ConflictFail,
	})
	if _, err := m.Complete(devA, tr.ID); err != nil {
		t.Fatalf("complete: %v", err)
	}
	fi, err := os.Stat(dest)
	if err != nil || fi.Size() != 0 {
		t.Fatalf("empty file: %v size=%v", err, fi.Size())
	}
}

// TestUploadIdempotentResend acknowledges a re-sent chunk without rewriting,
// and refuses an out-of-order offset.
func TestUploadIdempotentResend(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "r.bin")
	content := []byte("0123456789abcdef")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: int64(len(content)), Hash: sha256hex(content), Conflict: ConflictFail,
	})
	if off, err := m.WriteChunk(devA, tr.ID, 0, content[:6]); err != nil || off != 6 {
		t.Fatalf("first chunk off=%d err=%v", off, err)
	}
	// Re-send the same bytes at offset 0: acknowledged, no advance.
	if off, err := m.WriteChunk(devA, tr.ID, 0, content[:6]); err != nil || off != 6 {
		t.Fatalf("resend off=%d err=%v, want 6/nil", off, err)
	}
	// Out-of-order offset is refused.
	if _, err := m.WriteChunk(devA, tr.ID, 10, content[10:12]); !errors.Is(err, ErrBadOffset) {
		t.Fatalf("out-of-order err = %v, want ErrBadOffset", err)
	}
	// Resume from the current offset.
	uploadAll(t, m, devA, tr.ID, content, 6)
	if _, err := m.Complete(devA, tr.ID); err != nil {
		t.Fatalf("complete: %v", err)
	}
}

// TestUploadResumeQuery reports the current offset so a reconnecting app can
// resume, and enforces the device binding.
func TestUploadResumeQuery(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "resume.bin")
	content := []byte("0123456789")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: int64(len(content)), Hash: sha256hex(content), Conflict: ConflictFail,
	})
	_, _ = m.WriteChunk(devA, tr.ID, 0, content[:4])
	off, size, dirKind, _, ok := m.Get(devA, tr.ID)
	if !ok || off != 4 || size != 10 || dirKind != Up {
		t.Fatalf("get off=%d size=%d dir=%v ok=%v, want 4/10/Up", off, size, dirKind, ok)
	}
	// A different device cannot query or write this transfer.
	if _, _, _, _, ok := m.Get(devB, tr.ID); ok {
		t.Error("another device should not read the transfer")
	}
	if _, err := m.WriteChunk(devB, tr.ID, 4, content[4:6]); !errors.Is(err, ErrNotAuthorized) {
		t.Fatalf("foreign write err = %v, want ErrNotAuthorized", err)
	}
}

// TestUploadOverLength refuses a chunk that would push past the expected size.
func TestUploadOverLength(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "ol.bin")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 8, Hash: sha256hex([]byte("12345678")), Conflict: ConflictFail,
	})
	if _, err := m.WriteChunk(devA, tr.ID, 6, []byte("abcd")); !errors.Is(err, ErrOverLength) {
		t.Fatalf("over-length err = %v, want ErrTooLarge/ErrOverLength", err)
	}
}

// TestUploadHashMismatch fails completion and leaves the destination and temp
// untouched: the existing destination keeps its prior content.
func TestUploadHashMismatch(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "hm.bin")
	// Pre-existing destination with prior content.
	if err := os.WriteFile(dest, []byte("ORIGINAL"), 0o644); err != nil {
		t.Fatal(err)
	}
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 8, Hash: sha256hex([]byte("expected")), Conflict: ConflictReplace,
	})
	// Send 8 bytes that do not match the expected hash.
	if _, err := m.WriteChunk(devA, tr.ID, 0, []byte("WRONG8B ")); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Complete(devA, tr.ID); !errors.Is(err, ErrHashMismatch) {
		t.Fatalf("complete err = %v, want ErrHashMismatch", err)
	}
	// The original destination must be intact.
	got, _ := os.ReadFile(dest)
	if string(got) != "ORIGINAL" {
		t.Fatalf("destination clobbered: %q", got)
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if _, ok := tempIDFromName(e.Name()); ok {
			t.Errorf("temp not cleaned after hash failure: %s", e.Name())
		}
	}
}

// TestUploadConflictFail refuses to overwrite an existing destination when the
// policy is fail.
func TestUploadConflictFail(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "cf.bin")
	if err := os.WriteFile(dest, []byte("keep"), 0o644); err != nil {
		t.Fatal(err)
	}
	_, err := m.Create(CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 4, Hash: sha256hex([]byte("keep")), Conflict: ConflictFail,
	})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("create err = %v, want ErrConflict", err)
	}
}

// TestUploadConflictReplace atomically replaces an existing destination.
func TestUploadConflictReplace(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "cr.bin")
	if err := os.WriteFile(dest, []byte("old-ten-bytes!"), 0o644); err != nil {
		t.Fatal(err)
	}
	content := []byte("brand-new-content")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: int64(len(content)), Hash: sha256hex(content), Conflict: ConflictReplace,
	})
	uploadAll(t, m, devA, tr.ID, content, 5)
	if _, err := m.Complete(devA, tr.ID); err != nil {
		t.Fatalf("complete: %v", err)
	}
	got, _ := os.ReadFile(dest)
	if string(got) != string(content) {
		t.Fatalf("replaced content = %q, want new content", got)
	}
}

// TestDownloadRoundTrip streams a source in chunks and verifies content and
// hash, and that a source mutation mid-transfer fails clearly.
func TestDownloadRoundTrip(t *testing.T) {
	m, dir := newMgr(t)
	src := filepath.Join(dir, "src.bin")
	content := []byte("download source content here 0123456789")
	if err := os.WriteFile(src, content, 0o644); err != nil {
		t.Fatal(err)
	}
	tr := mustCreate(t, m, CreateParams{Device: devA, Direction: Down, Path: src})
	if tr.Hash != sha256hex(content) {
		t.Fatalf("derived hash = %s, want %s", tr.Hash, sha256hex(content))
	}
	if tr.ExpectedSize != int64(len(content)) {
		t.Fatalf("size = %d, want %d", tr.ExpectedSize, len(content))
	}
	var got []byte
	for off := int64(0); off < int64(len(content)); off += 6 {
		chunk, err := m.ReadChunk(devA, tr.ID, off, 6)
		if err != nil {
			t.Fatalf("read @%d: %v", off, err)
		}
		got = append(got, chunk...)
	}
	if string(got) != string(content) {
		t.Fatalf("downloaded = %q, want source content", got)
	}
	// Past end-of-file returns an empty chunk, not an error.
	if tail, err := m.ReadChunk(devA, tr.ID, int64(len(content)), 8); err != nil || len(tail) != 0 {
		t.Fatalf("tail read = %q err=%v, want empty", tail, err)
	}
	// Complete marks the download done on the daemon side.
	if h, err := m.Complete(devA, tr.ID); err != nil || h != sha256hex(content) {
		t.Fatalf("complete = %s err=%v", h, err)
	}
}

// TestDownloadSourceMutation fails the transfer if the source changes size or
// mtime after the snapshot.
func TestDownloadSourceMutation(t *testing.T) {
	m, dir := newMgr(t)
	src := filepath.Join(dir, "mut.bin")
	if err := os.WriteFile(src, []byte("original-content"), 0o644); err != nil {
		t.Fatal(err)
	}
	tr := mustCreate(t, m, CreateParams{Device: devA, Direction: Down, Path: src})
	if _, err := m.ReadChunk(devA, tr.ID, 0, 4); err != nil {
		t.Fatalf("first read: %v", err)
	}
	// Mutate the source (different size forces a size change).
	if err := os.WriteFile(src, []byte("completely different, longer content"), 0o644); err != nil {
		t.Fatal(err)
	}
	if _, err := m.ReadChunk(devA, tr.ID, 4, 4); !errors.Is(err, ErrSourceChanged) {
		t.Fatalf("mutated read err = %v, want ErrSourceChanged", err)
	}
	// The transfer is now failed; further reads are not authorized/found.
	if _, err := m.ReadChunk(devA, tr.ID, 0, 4); !errors.Is(err, ErrNotFound) {
		t.Fatalf("post-failure read err = %v, want ErrNotFound", err)
	}
}

// TestCancelUpload removes the temp file and never creates the destination.
func TestCancelUpload(t *testing.T) {
	m, dir := newMgr(t)
	dest := filepath.Join(dir, "cx.bin")
	tr := mustCreate(t, m, CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 16, Hash: sha256hex(make([]byte, 16)), Conflict: ConflictFail,
	})
	if _, err := m.WriteChunk(devA, tr.ID, 0, make([]byte, 4)); err != nil {
		t.Fatal(err)
	}
	if err := m.Cancel(devA, tr.ID); err != nil {
		t.Fatalf("cancel: %v", err)
	}
	if _, err := os.Stat(dest); !os.IsNotExist(err) {
		t.Errorf("destination should not exist after cancel, got %v", err)
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if _, ok := tempIDFromName(e.Name()); ok {
			t.Errorf("temp not removed on cancel: %s", e.Name())
		}
	}
}

// TestReap removes an idle upload and its temp file.
func TestReap(t *testing.T) {
	dir := t.TempDir()
	now := time.Now()
	m := NewManager(Options{FS: fsops.New(), InactiveTTL: time.Minute, Now: func() time.Time { return now }})
	dest := filepath.Join(dir, "reap.bin")
	tr, err := m.Create(CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 8, Hash: sha256hex(make([]byte, 8)), Conflict: ConflictFail,
	})
	if err != nil {
		t.Fatal(err)
	}
	// Still active: not reaped.
	if n := m.Reap(); n != 0 {
		t.Fatalf("reap = %d, want 0 while active", n)
	}
	// Age it past the TTL.
	now = now.Add(2 * time.Minute)
	if n := m.Reap(); n != 1 {
		t.Fatalf("reap = %d, want 1 after TTL", n)
	}
	entries, _ := os.ReadDir(dir)
	for _, e := range entries {
		if _, ok := tempIDFromName(e.Name()); ok {
			t.Errorf("temp not removed on reap: %s", e.Name())
		}
	}
	_ = tr
}

// TestCapacity rejects a transfer beyond the concurrent cap.
func TestCapacity(t *testing.T) {
	dir := t.TempDir()
	m := NewManager(Options{FS: fsops.New(), MaxConcurrent: 2})
	for i := 0; i < 2; i++ {
		if _, err := m.Create(CreateParams{
			Device: devA, Direction: Up, Path: filepath.Join(dir, "c"+string(rune('0'+i))+".bin"),
			ExpectedSize: 4, Hash: sha256hex(make([]byte, 4)), Conflict: ConflictFail,
		}); err != nil {
			t.Fatalf("create %d: %v", i, err)
		}
	}
	if _, err := m.Create(CreateParams{
		Device: devA, Direction: Up, Path: filepath.Join(dir, "c3.bin"),
		ExpectedSize: 4, Hash: sha256hex(make([]byte, 4)), Conflict: ConflictFail,
	}); !errors.Is(err, ErrCapacity) {
		t.Fatalf("third create err = %v, want ErrCapacity", err)
	}
}

// TestSweepTemp removes stale transfer temp files but keeps live temps and
// unrelated files untouched.
func TestSweepTemp(t *testing.T) {
	m, dir := newMgr(t)
	// A live transfer with a temp file.
	dest := filepath.Join(dir, "live.bin")
	tr, err := m.Create(CreateParams{
		Device: devA, Direction: Up, Path: dest,
		ExpectedSize: 8, Hash: sha256hex(make([]byte, 8)), Conflict: ConflictFail,
	})
	if err != nil {
		t.Fatal(err)
	}
	// A stale temp matching the pattern but not live.
	stale := filepath.Join(dir, ".dead.bin.remotly-tmp-ffffffffffffffffffffffffffffffff")
	if err := os.WriteFile(stale, []byte("stale"), 0o600); err != nil {
		t.Fatal(err)
	}
	// An unrelated file.
	unrelated := filepath.Join(dir, "notes.txt")
	if err := os.WriteFile(unrelated, []byte("x"), 0o644); err != nil {
		t.Fatal(err)
	}
	removed, err := m.SweepTemp(dir)
	if err != nil {
		t.Fatalf("sweep: %v", err)
	}
	if removed != 1 {
		t.Fatalf("sweep removed = %d, want 1", removed)
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Errorf("stale temp should be removed, got %v", err)
	}
	if _, err := os.Stat(unrelated); err != nil {
		t.Errorf("unrelated file touched: %v", err)
	}
	if _, err := os.Stat(tr.tempPath); err != nil {
		t.Errorf("live temp removed: %v", err)
	}
}

// TestPathValidation rejects relative and overlong paths at creation.
func TestPathValidation(t *testing.T) {
	m, _ := newMgr(t)
	if _, err := m.Create(CreateParams{Device: devA, Direction: Up, Path: "relative", ExpectedSize: 1}); err == nil {
		t.Error("relative path accepted")
	}
	long := "/" + string(make([]byte, 0))
	for i := 0; i < 5000; i++ {
		long += "a"
	}
	if _, err := m.Create(CreateParams{Device: devA, Direction: Down, Path: long}); err == nil {
		t.Error("overlong path accepted")
	}
}

// TestSecureEqualHex checks the constant-time hex comparison.
func TestSecureEqualHex(t *testing.T) {
	if !secureEqualHex("ab", "ab") {
		t.Error("equal hex should match")
	}
	if secureEqualHex("ab", "ac") {
		t.Error("differing hex should not match")
	}
	if secureEqualHex("ab", "a") {
		t.Error("differing lengths should not match")
	}
}
