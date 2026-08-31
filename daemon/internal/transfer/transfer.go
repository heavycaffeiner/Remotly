// Package transfer implements the daemon's resumable, authenticated file
// transfers: chunked upload and download with offset resume, whole-file
// SHA-256 integrity verification, cancellation, and atomic upload
// completion. Uploads land in a temporary file in the destination directory,
// are verified for size and hash, and are atomically renamed into place only
// when everything checks out, so a failed upload never overwrites or
// partially clobbers an existing destination. Downloads read from a source
// snapshot and fail clearly if the source is mutated mid-transfer.
//
// State is in-memory for the running daemon's lifetime; restart resume is
// out of scope. Every transfer is bound to the authenticated device that
// created it, and every operation re-checks that binding.
package transfer

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"hash"
	"io"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/fsops"
)

// Direction is the flow of file content relative to the app.
type Direction int

const (
	// Up means the app uploads to the daemon: chunks arrive on the file
	// channel and are written to a destination path.
	Up Direction = iota
	// Down means the daemon downloads to the app: the daemon reads a source
	// path and serves chunks.
	Down
)

// Conflict is the explicit policy for an upload whose destination already
// exists. The default is Fail: uploads never overwrite without being asked.
type Conflict int

const (
	// ConflictFail refuses to overwrite an existing destination.
	ConflictFail Conflict = iota
	// ConflictReplace atomically replaces an existing destination.
	ConflictReplace
)

// Typed errors mapped to protocol codes by the transport handlers.
var (
	ErrNotFound      = errors.New("transfer: not found")
	ErrNotAuthorized = errors.New("transfer: not authorized for this transfer")
	ErrCapacity      = errors.New("transfer: too many concurrent transfers")
	ErrTooLarge      = errors.New("transfer: exceeds the size bound")
	ErrBadOffset     = errors.New("transfer: out-of-order offset")
	ErrOverLength    = errors.New("transfer: exceeds expected size")
	ErrHashMismatch  = errors.New("transfer: integrity check failed")
	ErrSourceChanged = errors.New("transfer: source modified during transfer")
	ErrIncomplete    = errors.New("transfer: incomplete")
	ErrConflict      = errors.New("transfer: destination already exists")
	ErrInvalidArg    = errors.New("transfer: invalid argument")
)

// Transfer is one in-flight transfer. Fields guarded by Manager.mu are read
// and written only under it; the open handle is closed exactly once.
type Transfer struct {
	ID           string
	Device       [32]byte
	Direction    Direction
	Path         string
	ExpectedSize int64
	Hash         string // lowercase hex sha256, for the whole file
	Conflict     Conflict

	received int64     // upload: bytes durably written to the temp file
	tempPath string    // upload: the temp file path
	srcSize  int64     // download: source size at snapshot
	srcMtime time.Time // download: source mtime at snapshot

	file *os.File // upload temp handle (or download source handle)

	createdAt    time.Time
	lastActiveAt time.Time
	done         atomic.Bool
}

// Status reports the current byte progress of a transfer.
func (tr *Transfer) Status() (offset, size int64) { return tr.received, tr.ExpectedSize }

// Options bounds the transfer manager.
type Options struct {
	FS            *fsops.FS
	MaxConcurrent int           // max live transfers (default 8)
	MaxChunkSize  int64         // max bytes per chunk (default 1 MiB)
	MaxTempBytes  int64         // total temp storage bound (default 4 GiB); 0 = 4 GiB
	InactiveTTL   time.Duration // reap transfers idle this long (default 10 min)
	Now           func() time.Time
}

func (o *Options) withDefaults() {
	if o.FS == nil {
		o.FS = fsops.New()
	}
	if o.MaxConcurrent <= 0 {
		o.MaxConcurrent = 8
	}
	if o.MaxChunkSize <= 0 {
		o.MaxChunkSize = 1 << 20
	}
	if o.MaxTempBytes <= 0 {
		o.MaxTempBytes = 4 << 30
	}
	if o.InactiveTTL <= 0 {
		o.InactiveTTL = 10 * time.Minute
	}
	if o.Now == nil {
		o.Now = time.Now
	}
}

// Manager owns all live transfers. All methods are safe for concurrent use.
type Manager struct {
	mu        sync.Mutex
	transfers map[string]*Transfer
	tempBytes int64 // running total of bytes in temp files
	opts      Options
}

// NewManager returns a transfer manager with the given options defaulted.
func NewManager(o Options) *Manager {
	o.withDefaults()
	return &Manager{transfers: make(map[string]*Transfer), opts: o}
}

// CreateParams describes a transfer creation request. Path is validated by
// the manager; ExpectedSize and Hash are required for uploads. For downloads,
// ExpectedSize and Hash may be zero/empty, in which case the manager derives
// them from the source at snapshot time.
type CreateParams struct {
	Device       [32]byte
	Direction    Direction
	Path         string
	ExpectedSize int64
	Hash         string // lowercase hex sha256; "" for downloads (derived)
	Conflict     Conflict
	// ResumeFrom requests an upload resume at this offset; the daemon
	// validates it is within the already-received range.
	ResumeFrom int64
}

// Create opens a transfer. For an upload it creates the temp file in the
// destination directory and, when the destination already exists, enforces
// the conflict policy. For a download it snapshots the source (size, mtime)
// and computes the whole-file hash.
func (m *Manager) Create(p CreateParams) (*Transfer, error) {
	now := m.opts.Now()
	if _, err := m.opts.FS.Validate(p.Path); err != nil {
		return nil, fsErr(err)
	}
	switch p.Direction {
	case Up:
		return m.createUp(p, now)
	case Down:
		return m.createDown(p, now)
	default:
		return nil, ErrInvalidArg
	}
}

func (m *Manager) createUp(p CreateParams, now time.Time) (*Transfer, error) {
	if p.ExpectedSize < 0 {
		return nil, ErrInvalidArg
	}
	if p.ExpectedSize > m.opts.MaxTempBytes {
		return nil, ErrTooLarge
	}
	dest, err := m.opts.FS.Validate(p.Path)
	if err != nil {
		return nil, fsErr(err)
	}
	destDir := filepath.Dir(dest)
	// The destination parent must be an existing directory.
	if fi, serr := os.Stat(destDir); serr != nil || !fi.IsDir() {
		return nil, fsErr(serr)
	}
	// Conflict policy is decided up front so a create that will fail to
	// replace does not consume a temp file.
	if _, serr := os.Lstat(dest); serr == nil {
		if p.Conflict == ConflictFail {
			return nil, ErrConflict
		}
	}
	id := newID()
	temp := tempPath(dest, id)
	f, err := os.OpenFile(temp, os.O_CREATE|os.O_WRONLY|os.O_EXCL, 0o600)
	if err != nil {
		return nil, fsErr(err)
	}
	// Preallocate is not portable; skip. The temp starts at zero length.
	tr := &Transfer{
		ID:           id,
		Device:       p.Device,
		Direction:    Up,
		Path:         dest,
		ExpectedSize: p.ExpectedSize,
		Hash:         p.Hash,
		Conflict:     p.Conflict,
		tempPath:     temp,
		file:         f,
		createdAt:    now,
		lastActiveAt: now,
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.transfers) >= m.opts.MaxConcurrent {
		_ = f.Close()
		_ = os.Remove(temp)
		return nil, ErrCapacity
	}
	m.transfers[id] = tr
	// tempBytes is not charged here: the temp file starts empty and is
	// charged as chunks land, so the running total tracks real disk use
	// rather than what a client promised to send.
	return tr, nil
}

func (m *Manager) createDown(p CreateParams, now time.Time) (*Transfer, error) {
	src, err := m.opts.FS.Validate(p.Path)
	if err != nil {
		return nil, fsErr(err)
	}
	fi, err := os.Lstat(src)
	if err != nil {
		return nil, fsErr(err)
	}
	if fi.IsDir() {
		return nil, ErrInvalidArg
	}
	f, err := os.Open(src)
	if err != nil {
		return nil, fsErr(err)
	}
	h := sha256.New()
	if _, err := ioCopyBuffered(h, f); err != nil {
		_ = f.Close()
		return nil, fsErr(err)
	}
	hashHex := hex.EncodeToString(h.Sum(nil))
	id := newID()
	tr := &Transfer{
		ID:           id,
		Device:       p.Device,
		Direction:    Down,
		Path:         src,
		ExpectedSize: fi.Size(),
		Hash:         hashHex,
		srcSize:      fi.Size(),
		srcMtime:     fi.ModTime(),
		file:         f,
		createdAt:    now,
		lastActiveAt: now,
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	if len(m.transfers) >= m.opts.MaxConcurrent {
		_ = f.Close()
		return nil, ErrCapacity
	}
	m.transfers[id] = tr
	return tr, nil
}

// WriteChunk appends one upload chunk. offset must equal the transfer's
// current offset; a re-send at an earlier offset is acknowledged idempotently
// without rewriting, and an offset past the current one is refused. It returns
// the new offset.
func (m *Manager) WriteChunk(device [32]byte, id string, offset int64, data []byte) (int64, error) {
	m.mu.Lock()
	tr := m.transfers[id]
	if tr == nil {
		m.mu.Unlock()
		return 0, ErrNotFound
	}
	if tr.Direction != Up || tr.Device != device {
		m.mu.Unlock()
		return 0, ErrNotAuthorized
	}
	if offset < 0 || int64(len(data)) < 0 {
		m.mu.Unlock()
		return 0, ErrInvalidArg
	}
	if int64(len(data)) > m.opts.MaxChunkSize {
		m.mu.Unlock()
		return 0, ErrTooLarge
	}
	// Reject overflow before comparing: offset+len must not wrap.
	if offset > tr.ExpectedSize || int64(len(data)) > tr.ExpectedSize-offset {
		m.mu.Unlock()
		return 0, ErrOverLength
	}
	if offset < tr.received {
		// Already-received region: acknowledge without rewriting.
		tr.lastActiveAt = m.opts.Now()
		cur := tr.received
		m.mu.Unlock()
		return cur, nil
	}
	if offset != tr.received {
		m.mu.Unlock()
		return 0, ErrBadOffset
	}
	// The documented total bound, enforced where the bytes actually land.
	// Only the per-transfer size was checked, so MaxConcurrent uploads of
	// MaxTempBytes each could fill the disk between them while the running
	// total was never consulted.
	if m.tempBytes+int64(len(data)) > m.opts.MaxTempBytes {
		m.mu.Unlock()
		return 0, ErrTooLarge
	}
	tr.lastActiveAt = m.opts.Now()
	file := tr.file
	// The range is claimed before the lock is dropped, so a second chunk at
	// this offset takes the idempotent path above instead of writing the same
	// bytes twice and counting them twice. Advancing only after the write let
	// two callers both pass this check: the file ended up short while the
	// transfer reported itself complete, and the hash check then failed on a
	// file the user had already been told was uploaded.
	tr.received = offset + int64(len(data))
	m.tempBytes += int64(len(data))
	m.mu.Unlock()

	n, err := file.WriteAt(data, offset)
	if err != nil {
		m.fail(tr.ID, fsErr(err))
		return 0, fsErr(err)
	}
	if n != len(data) {
		// WriteAt returns a non-nil error for a short write, so this is
		// defensive; the accounting above would otherwise be wrong.
		m.fail(tr.ID, io.ErrShortWrite)
		return 0, io.ErrShortWrite
	}
	m.mu.Lock()
	cur := tr.received
	m.mu.Unlock()
	return cur, nil
}

// ReadChunk serves one download chunk at offset, up to maxLen bytes. It
// re-checks the source snapshot on every read and fails the transfer if the
// source has been modified. At or past end-of-file it returns an empty slice.
func (m *Manager) ReadChunk(device [32]byte, id string, offset int64, maxLen int64) ([]byte, error) {
	m.mu.Lock()
	tr := m.transfers[id]
	if tr == nil {
		m.mu.Unlock()
		return nil, ErrNotFound
	}
	if tr.Direction != Down || tr.Device != device {
		m.mu.Unlock()
		return nil, ErrNotAuthorized
	}
	if offset < 0 {
		m.mu.Unlock()
		return nil, ErrInvalidArg
	}
	if maxLen <= 0 || maxLen > m.opts.MaxChunkSize {
		maxLen = m.opts.MaxChunkSize
	}
	src := tr.Path
	srcSize := tr.srcSize
	srcMtime := tr.srcMtime
	file := tr.file
	tr.lastActiveAt = m.opts.Now()
	m.mu.Unlock()

	// Source mutation detection: size or mtime differing from the snapshot
	// means the file changed underneath us.
	fi, err := os.Lstat(src)
	if err != nil {
		m.fail(id, fsErr(err))
		return nil, fsErr(err)
	}
	if fi.Size() != srcSize || !fi.ModTime().Equal(srcMtime) {
		m.fail(id, ErrSourceChanged)
		return nil, ErrSourceChanged
	}
	if offset >= srcSize {
		return []byte{}, nil
	}
	n := srcSize - offset
	if n > maxLen {
		n = maxLen
	}
	buf := make([]byte, n)
	got, rerr := file.ReadAt(buf, offset)
	if rerr != nil && rerr.Error() != "EOF" {
		m.fail(id, fsErr(rerr))
		return nil, fsErr(rerr)
	}
	return buf[:got], nil
}

// Complete finalizes a transfer. For an upload it verifies the received size
// and whole-file hash, syncs the temp file, and atomically renames it into
// the destination per the conflict policy. For a download it is a no-op that
// marks the transfer done. It returns the verified whole-file hash.
func (m *Manager) Complete(device [32]byte, id string) (string, error) {
	m.mu.Lock()
	tr := m.transfers[id]
	if tr == nil {
		m.mu.Unlock()
		return "", ErrNotFound
	}
	if tr.Device != device {
		m.mu.Unlock()
		return "", ErrNotAuthorized
	}
	if tr.done.Swap(true) {
		m.mu.Unlock()
		return "", ErrIncomplete
	}
	if tr.Direction == Down {
		// Nothing to verify on the daemon side; the app verifies the hash it
		// accumulated.
		m.removeLocked(id)
		return tr.Hash, nil
	}
	received := tr.received
	expected := tr.ExpectedSize
	temp := tr.tempPath
	dest := tr.Path
	conflict := tr.Conflict
	wantHash := tr.Hash
	file := tr.file
	m.mu.Unlock()

	if received != expected {
		m.fail(id, ErrIncomplete)
		return "", ErrIncomplete
	}
	// Verify the whole-file hash of the temp file before touching the
	// destination.
	gotHash, err := hashFile(temp)
	if err != nil {
		m.fail(id, fsErr(err))
		return "", fsErr(err)
	}
	if wantHash != "" && !secureEqualHex(gotHash, wantHash) {
		m.fail(id, ErrHashMismatch)
		return "", ErrHashMismatch
	}
	if err := file.Sync(); err != nil {
		m.fail(id, fsErr(err))
		return "", fsErr(err)
	}
	// Re-check the destination conflict at the last moment; a concurrent
	// create between Create and Complete could have made the destination
	// appear.
	if _, serr := os.Lstat(dest); serr == nil && conflict == ConflictFail {
		m.fail(id, ErrConflict)
		return "", ErrConflict
	}
	if err := file.Close(); err != nil {
		m.fail(id, fsErr(err))
		return "", fsErr(err)
	}
	if err := os.Rename(temp, dest); err != nil {
		_ = os.Remove(temp)
		m.fail(id, fsErr(err))
		return "", fsErr(err)
	}
	m.finish(id)
	return gotHash, nil
}

// Cancel tears down a transfer, removing its temp file (upload) and closing
// any handle. The destination is never touched on cancel.
func (m *Manager) Cancel(device [32]byte, id string) error {
	m.mu.Lock()
	tr := m.transfers[id]
	if tr == nil {
		m.mu.Unlock()
		return ErrNotFound
	}
	if tr.Device != device {
		m.mu.Unlock()
		return ErrNotAuthorized
	}
	if !tr.done.CompareAndSwap(false, true) {
		m.mu.Unlock()
		return nil
	}
	m.removeLocked(id)
	m.mu.Unlock()
	if tr.tempPath != "" {
		_ = tr.file.Close()
		_ = os.Remove(tr.tempPath)
	} else {
		_ = tr.file.Close()
	}
	return nil
}

// Get returns a live transfer and its current progress, for resume queries.
func (m *Manager) Get(device [32]byte, id string) (offset, size int64, dir Direction, hash string, ok bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	tr := m.transfers[id]
	if tr == nil || tr.Device != device {
		return 0, 0, Up, "", false
	}
	return tr.received, tr.ExpectedSize, tr.Direction, tr.Hash, true
}

// Reap removes transfers idle past the TTL and, for uploads, their temp
// files. It returns the number reaped.
func (m *Manager) Reap() int {
	now := m.opts.Now()
	m.mu.Lock()
	var toRemove []*Transfer
	for _, tr := range m.transfers {
		if now.Sub(tr.lastActiveAt) > m.opts.InactiveTTL {
			if !tr.done.CompareAndSwap(false, true) {
				continue
			}
			toRemove = append(toRemove, tr)
		}
	}
	for _, tr := range toRemove {
		m.removeLocked(tr.ID)
	}
	m.mu.Unlock()
	for _, tr := range toRemove {
		if tr.tempPath != "" {
			_ = tr.file.Close()
			_ = os.Remove(tr.tempPath)
		} else {
			_ = tr.file.Close()
		}
	}
	return len(toRemove)
}

// SweepTemp removes temp files in dir that match the transfer temp pattern
// but belong to no live transfer. It never touches files that do not match
// the pattern. It returns the number removed.
func (m *Manager) SweepTemp(dir string) (int, error) {
	dir, err := m.opts.FS.Validate(dir)
	if err != nil {
		return 0, fsErr(err)
	}
	m.mu.Lock()
	live := make(map[string]bool, len(m.transfers))
	for _, tr := range m.transfers {
		live[tr.ID] = true
	}
	m.mu.Unlock()
	entries, err := os.ReadDir(dir)
	if err != nil {
		return 0, fsErr(err)
	}
	removed := 0
	for _, e := range entries {
		id, ok := tempIDFromName(e.Name())
		if !ok || live[id] {
			continue
		}
		if err := os.Remove(filepath.Join(dir, e.Name())); err == nil {
			removed++
		}
	}
	return removed, nil
}

// fail marks a transfer failed, removes it, and cleans its temp file. Removal
// is guarded by the map delete (not the done flag) so it also works when the
// caller has already flipped done, as Complete does while finalizing.
func (m *Manager) fail(id string, _ error) {
	m.mu.Lock()
	tr, ok := m.transfers[id]
	if !ok {
		m.mu.Unlock()
		return
	}
	m.removeLocked(id)
	m.mu.Unlock()
	cleanupTransfer(tr)
}

// cleanupTransfer closes the transfer's handle and removes its temp file if
// one exists. It is idempotent in effect because the transfer is already
// out of the map when this runs.
func cleanupTransfer(tr *Transfer) {
	if tr == nil || tr.file == nil {
		return
	}
	_ = tr.file.Close()
	if tr.tempPath != "" {
		_ = os.Remove(tr.tempPath)
	}
}

// finish removes a completed transfer. The temp file has already been renamed
// into the destination, so there is nothing left to delete on disk.
func (m *Manager) finish(id string) {
	m.mu.Lock()
	m.removeLocked(id)
	m.mu.Unlock()
}

func (m *Manager) removeLocked(id string) {
	if tr, ok := m.transfers[id]; ok && tr.tempPath != "" {
		m.tempBytes -= tr.received
	}
	delete(m.transfers, id)
}

// --- internals -------------------------------------------------------------

// tempPath renders the temp file path for an upload: a hidden file in the
// destination directory named .<basename>.remotly-tmp-<id>.
const tempMarker = ".remotly-tmp-"

func tempPath(dest, id string) string {
	dir := filepath.Dir(dest)
	base := filepath.Base(dest)
	return filepath.Join(dir, "."+base+tempMarker+id)
}

// tempIDFromName recovers the transfer id from a temp file name, if the name
// carries the transfer temp marker. It matches the last occurrence so a
// basename that itself contains the marker does not confuse it.
func tempIDFromName(name string) (string, bool) {
	idx := indexMarker(name, tempMarker)
	if idx < 0 {
		return "", false
	}
	id := name[idx+len(tempMarker):]
	if id == "" {
		return "", false
	}
	return id, true
}

func indexMarker(s, marker string) int {
	// find the last occurrence of the marker
	last := -1
	for i := 0; i+len(marker) <= len(s); i++ {
		if s[i:i+len(marker)] == marker {
			last = i
		}
	}
	return last
}

// newID returns a random transfer identifier (32 hex chars).
func newID() string {
	var b [16]byte
	if _, err := rand.Read(b[:]); err != nil {
		// Fall back to a time-based id; collisions are improbable and the id
		// is namespaced per transfer anyway.
		nanos := time.Now().UnixNano()
		for i := range b {
			b[i] = byte(nanos >> (8 * i))
		}
	}
	return hex.EncodeToString(b[:])
}

// hashFile streams a file through sha256 and returns the lowercase hex digest.
func hashFile(path string) (string, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", err
	}
	defer f.Close()
	h := sha256.New()
	if _, err := ioCopyBuffered(h, f); err != nil {
		return "", err
	}
	return hex.EncodeToString(h.Sum(nil)), nil
}

// secureEqualHex compares two lowercase hex digests in constant time.
func secureEqualHex(a, b string) bool {
	la, lb := []byte(a), []byte(b)
	if len(la) != len(lb) {
		return false
	}
	var diff byte
	for i := range la {
		diff |= la[i] ^ lb[i]
	}
	return diff == 0
}

// fsErr wraps an fsops or os error, preserving typed fsops sentinels for
// errors.Is matching upstream.
func fsErr(err error) error {
	if err == nil {
		return nil
	}
	if errors.Is(err, fsops.ErrNotFound) {
		return ErrNotFound
	}
	if errors.Is(err, fsops.ErrIsDir) {
		return ErrInvalidArg
	}
	if errors.Is(err, fsops.ErrInvalidPath) {
		return ErrInvalidArg
	}
	return err
}

// ioCopyBuffered is a small buffered copy to avoid importing io's unbounded
// ReadFull semantics; it copies until EOF.
func ioCopyBuffered(dst hash.Hash, src *os.File) (int64, error) {
	buf := make([]byte, 1<<20)
	var total int64
	for {
		n, rerr := src.Read(buf)
		if n > 0 {
			dst.Write(buf[:n])
			total += int64(n)
		}
		if rerr != nil {
			if errors.Is(rerr, io.EOF) {
				return total, nil
			}
			return total, rerr
		}
	}
}
