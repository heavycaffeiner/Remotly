// Package pairing implements the daemon-side pairing and device identity:
// the long-term daemon key, one-time pairing tokens, the canonical pairing
// URI, and the paired-device store. All state is per OS user and stored with
// user-only permissions under the Remotly data directory.
package pairing

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// File permissions for state that holds key material or device records. The
// data directory itself is created 0700 by paths.Ensure; these files are
// additionally 0600 so a loosened directory does not expose them.
const stateFileMode = 0o600

// maxStateBytes bounds any pairing state file before it is read. The real
// files are small (a few KB at most); anything larger is treated as corrupt
// rather than allocated.
const maxStateBytes = 1 << 20

// writeJSONAtomic marshals v as indented JSON and replaces path with the new
// content atomically. The write goes to a temp file in the same directory, is
// fsync'd, then renamed over the target, so a crash leaves either the old or
// the new content, never a partial file. The target must not be a symlink:
// state must never be redirected through a link the daemon does not own.
func writeJSONAtomic(path string, v any) error {
	raw, err := json.MarshalIndent(v, "", "  ")
	if err != nil {
		return fmt.Errorf("pairing: encode %s: %w", filepath.Base(path), err)
	}
	raw = append(raw, '\n')

	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("pairing: create temp: %w", err)
	}
	tmpName := tmp.Name()
	cleanup := func() {
		_ = tmp.Close()
		_ = os.Remove(tmpName)
	}

	if _, err := tmp.Write(raw); err != nil {
		cleanup()
		return fmt.Errorf("pairing: write %s: %w", filepath.Base(path), err)
	}
	if err := tmp.Sync(); err != nil {
		cleanup()
		return fmt.Errorf("pairing: sync %s: %w", filepath.Base(path), err)
	}
	if err := tmp.Chmod(stateFileMode); err != nil {
		cleanup()
		return fmt.Errorf("pairing: chmod %s: %w", filepath.Base(path), err)
	}
	if err := tmp.Close(); err != nil {
		_ = os.Remove(tmpName)
		return fmt.Errorf("pairing: close %s: %w", filepath.Base(path), err)
	}

	if fi, err := os.Lstat(path); err == nil {
		if fi.Mode()&os.ModeSymlink != 0 {
			_ = os.Remove(tmpName)
			return fmt.Errorf("pairing: %s must not be a symlink", path)
		}
		if !fi.Mode().IsRegular() {
			_ = os.Remove(tmpName)
			return fmt.Errorf("pairing: %s is not a regular file", path)
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		_ = os.Remove(tmpName)
		return fmt.Errorf("pairing: stat %s: %w", path, err)
	}

	if err := os.Rename(tmpName, path); err != nil {
		_ = os.Remove(tmpName)
		return fmt.Errorf("pairing: replace %s: %w", path, err)
	}
	return nil
}

// readJSONFile reads and decodes path into v. The file is untrusted input:
// it must be a regular file (never a symlink), at most maxBytes, and valid
// JSON with no unknown fields. A missing file returns os.ErrNotExist.
func readJSONFile(path string, maxBytes int64, v any) error {
	fi, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return err
	}
	if err != nil {
		return fmt.Errorf("pairing: stat %s: %w", path, err)
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		return fmt.Errorf("pairing: %s must not be a symlink", path)
	}
	if !fi.Mode().IsRegular() {
		return fmt.Errorf("pairing: %s is not a regular file", path)
	}
	if fi.Size() > maxBytes {
		return fmt.Errorf("pairing: %s exceeds %d bytes", path, maxBytes)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return fmt.Errorf("pairing: read %s: %w", path, err)
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(v); err != nil {
		return fmt.Errorf("pairing: decode %s: %w", path, err)
	}
	return nil
}
