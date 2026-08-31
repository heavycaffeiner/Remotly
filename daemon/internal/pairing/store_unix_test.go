//go:build !windows

package pairing

import (
	"os"
	"path/filepath"
	"testing"
)

// A directory is a non-regular filesystem object; it exercises the same
// guard a FIFO would, without platform-specific syscall availability.
func TestReadJSONFileRejectsNonRegular(t *testing.T) {
	dir := t.TempDir()
	sub := filepath.Join(dir, "state.json")
	if err := os.Mkdir(sub, 0o700); err != nil {
		t.Fatal(err)
	}
	var v storeTestValue
	if err := readJSONFile(sub, maxStateBytes, &v); err == nil {
		t.Fatal("expected non-regular-file error")
	}
}

func TestWriteJSONAtomicRejectsNonRegular(t *testing.T) {
	dir := t.TempDir()
	sub := filepath.Join(dir, "state.json")
	if err := os.Mkdir(sub, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := writeJSONAtomic(sub, storeTestValue{A: 1}); err == nil {
		t.Fatal("expected non-regular-file error")
	}
}
