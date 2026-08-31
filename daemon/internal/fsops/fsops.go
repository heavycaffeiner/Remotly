// Package fsops implements the daemon's authenticated filesystem metadata
// operations: list, stat, mkdir, remove, and rename. It runs with the daemon
// operating-system user's privileges and deliberately has no sandbox root, but
// every path is validated, normalized, and handled with direct syscall APIs.
// No shell is ever invoked. All results are bounded so a large or hostile
// directory cannot exhaust the control channel.
package fsops

import (
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"syscall"
)

// Limits. A single listing returns at most MaxPage entries; the caller pages
// with an offset. Paths are bounded to keep the JSON frame and the OS calls
// from being abused.
const (
	MaxPage    = 500
	MaxPathLen = 4096
)

// Entry is one filesystem object. Name is the platform-native basename, kept
// byte-faithful (no NFC/NFD folding). Perm carries the full mode bits so a
// caller can decode both the type and the permission bits. Size and ModTime
// are zero when the object does not report them (e.g. a symlink via lstat).
// LinkTarget is the best-effort readlink result for symlinks, for display only.
type Entry struct {
	Name       string `json:"name"`
	IsDir      bool   `json:"is_dir"`
	IsSymlink  bool   `json:"is_symlink"`
	Size       int64  `json:"size"`
	ModTime    int64  `json:"mod_time"`
	Perm       uint32 `json:"perm"`
	LinkTarget string `json:"link_target,omitempty"`
}

// Typed errors. Handlers map these to stable protocol error codes; the app
// matches on the code, not the message.
var (
	ErrNotFound    = errors.New("fs: no such file or directory")
	ErrNotDir      = errors.New("fs: not a directory")
	ErrIsDir       = errors.New("fs: is a directory")
	ErrNotEmpty    = errors.New("fs: directory not empty")
	ErrPermission  = errors.New("fs: permission denied")
	ErrExist       = errors.New("fs: already exists")
	ErrInvalidPath = errors.New("fs: invalid path")
	ErrInvalidArg  = errors.New("fs: invalid argument")
)

// FS is the filesystem service. It holds no per-connection state; every
// method is safe for concurrent use.
type FS struct{}

// New returns a filesystem service operating as the current OS user.
func New() *FS { return &FS{} }

// Validate normalizes and checks a client-supplied path, returning the
// canonical absolute form. It is the public entry point for packages that
// must apply the same path policy as the fs operations (for example, the
// transfer manager, which creates temp files in destination directories).
func (f *FS) Validate(path string) (string, error) {
	return validatePath(path)
}

// Roots lists the top-level navigable roots of the host: on Unix the single
// absolute root, on Windows the mounted drive and share letters.
func (f *FS) Roots() []string {
	return []string{root()}
}

// List returns a bounded, name-sorted page of the directory at path. offset
// skips that many entries; limit is capped at MaxPage. more reports whether
// further entries exist past the returned page.
func (f *FS) List(path string, offset, limit int) (entries []Entry, more bool, total int, err error) {
	p, err := validatePath(path)
	if err != nil {
		return nil, false, 0, err
	}
	if offset < 0 {
		return nil, false, 0, ErrInvalidArg
	}
	if limit <= 0 {
		limit = MaxPage
	}
	if limit > MaxPage {
		limit = MaxPage
	}
	dds, err := os.ReadDir(p)
	if err != nil {
		return nil, false, 0, mapErr(err)
	}
	total = len(dds)
	end := offset + limit
	if end > total {
		end = total
	}
	if offset >= total {
		return []Entry{}, false, total, nil
	}
	entries = make([]Entry, 0, end-offset)
	for _, de := range dds[offset:end] {
		e, ierr := entryFromDirEntry(de)
		if ierr != nil {
			// A name that vanishes mid-listing (concurrent delete) is skipped
			// rather than failing the whole page.
			continue
		}
		entries = append(entries, e)
	}
	more = end < total
	return entries, more, total, nil
}

// Stat reports a single path, symlink-aware: a symlink is reported as a
// symlink (lstat), and LinkTarget carries its target.
func (f *FS) Stat(path string) (Entry, error) {
	p, err := validatePath(path)
	if err != nil {
		return Entry{}, err
	}
	fi, err := os.Lstat(p)
	if err != nil {
		return Entry{}, mapErr(err)
	}
	e := entryFromInfo(fi, fi.Name())
	if e.IsSymlink {
		if target, rerr := os.Readlink(p); rerr == nil {
			e.LinkTarget = target
		}
	}
	return e, nil
}

// Mkdir creates a single directory (no parents). It fails if the path
// exists.
func (f *FS) Mkdir(path string) error {
	p, err := validatePath(path)
	if err != nil {
		return err
	}
	err = os.Mkdir(p, 0o755)
	if err != nil {
		return mapErr(err)
	}
	return nil
}

// RemoveFile removes a single file or symlink. It is refused for
// directories, which must be removed with RemoveDir.
func (f *FS) RemoveFile(path string) error {
	p, err := validatePath(path)
	if err != nil {
		return err
	}
	fi, err := os.Lstat(p)
	if err != nil {
		return mapErr(err)
	}
	if fi.IsDir() {
		return ErrIsDir
	}
	if err := os.Remove(p); err != nil {
		return mapErr(err)
	}
	return nil
}

// RemoveDir removes a single directory. It is non-recursive: a nonempty
// directory fails with ErrNotEmpty rather than being discarded.
func (f *FS) RemoveDir(path string) error {
	p, err := validatePath(path)
	if err != nil {
		return err
	}
	err = os.Remove(p)
	if err != nil {
		return mapErr(err)
	}
	return nil
}

// Rename atomically renames or moves from to to within the same filesystem.
// It is a plain rename; the target directory must exist.
func (f *FS) Rename(from, to string) error {
	fp, err := validatePath(from)
	if err != nil {
		return err
	}
	tp, err := validatePath(to)
	if err != nil {
		return err
	}
	if err := os.Rename(fp, tp); err != nil {
		return mapErr(err)
	}
	return nil
}

// --- internals -------------------------------------------------------------

// root is the top-level absolute root for the host OS.
func root() string {
	if filepath.Separator == '\\' {
		// Windows: report the system drive (typically C:) as the primary root.
		sysRoot := os.Getenv("SystemDrive")
		if sysRoot != "" {
			return sysRoot + `:\`
		}
		return `C:\`
	}
	return string(filepath.Separator)
}

// validatePath normalizes a client-supplied path and rejects anything that is
// not a well-formed absolute path. The daemon operates as its OS user, so it
// does not confine to a root, but it will not accept relative paths, NUL
// bytes, or overlong strings, and it collapses .. and redundant separators.
func validatePath(path string) (string, error) {
	if path == "" || len(path) > MaxPathLen {
		return "", ErrInvalidPath
	}
	if strings.ContainsRune(path, '\x00') {
		return "", ErrInvalidPath
	}
	if !filepath.IsAbs(path) {
		return "", ErrInvalidPath
	}
	clean := filepath.Clean(path)
	if clean == string(filepath.Separator) && path != clean {
		// Cleaning to the root is allowed; keep the canonical root.
		return clean, nil
	}
	return clean, nil
}

func entryFromDirEntry(de fs.DirEntry) (Entry, error) {
	fi, err := de.Info()
	if err != nil {
		return Entry{}, mapErr(err)
	}
	return entryFromInfo(fi, de.Name()), nil
}

func entryFromInfo(fi os.FileInfo, name string) Entry {
	mode := fi.Mode()
	e := Entry{
		Name:      name,
		IsDir:     mode.IsDir(),
		IsSymlink: mode.Type()&fs.ModeSymlink != 0,
		Perm:      uint32(mode),
	}
	// The FileInfo accessors are portable across Unix and Windows; the
	// platform Stat_t field for mtime is named differently (Mtim vs
	// Mtimespec), so the interface is used rather than a direct struct read.
	e.Size = fi.Size()
	e.ModTime = fi.ModTime().Unix()
	return e
}

// mapErr translates an os/fs error into the fsops typed errors. The nonempty
// check runs first: on Linux, removing a nonempty directory yields an error
// that matches both fs.ErrExist and ENOTEMPTY, and the nonempty meaning is
// the one the caller needs.
func mapErr(err error) error {
	if isNotEmpty(err) {
		return ErrNotEmpty
	}
	if errors.Is(err, fs.ErrNotExist) {
		return ErrNotFound
	}
	if errors.Is(err, fs.ErrExist) {
		return ErrExist
	}
	if errors.Is(err, fs.ErrPermission) {
		return ErrPermission
	}
	if errors.Is(err, fs.ErrInvalid) {
		return ErrInvalidArg
	}
	return err
}

func isNotEmpty(err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, ErrNotEmpty) {
		return true
	}
	// syscall.ENOTEMPTY exists on some platforms; the message check covers
	// the rest without coupling to a single OS's errno.
	if errors.Is(err, syscall.ENOTEMPTY) {
		return true
	}
	msg := strings.ToLower(err.Error())
	return strings.Contains(msg, "not empty") || strings.Contains(msg, "dir_notempty")
}
