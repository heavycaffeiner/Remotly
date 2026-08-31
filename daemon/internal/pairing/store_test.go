package pairing

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"testing"
)

type storeTestValue struct {
	A int    `json:"a"`
	B string `json:"b"`
}

func TestWriteJSONAtomic(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	v := storeTestValue{A: 1, B: "hello"}

	if err := writeJSONAtomic(path, v); err != nil {
		t.Fatalf("writeJSONAtomic: %v", err)
	}

	fi, err := os.Stat(path)
	if err != nil {
		t.Fatal(err)
	}
	if perm := fi.Mode().Perm(); perm != stateFileMode {
		t.Fatalf("mode = %o, want %o", perm, stateFileMode)
	}

	var back storeTestValue
	if err := readJSONFile(path, maxStateBytes, &back); err != nil {
		t.Fatalf("readJSONFile: %v", err)
	}
	if back != v {
		t.Fatalf("round trip = %+v, want %+v", back, v)
	}

	// No temp files may be left behind.
	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "state.json" {
		names := make([]string, 0, len(entries))
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Fatalf("directory entries = %v, want only state.json", names)
	}
}

func TestWriteJSONAtomicReplacesExisting(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	if err := writeJSONAtomic(path, storeTestValue{A: 1}); err != nil {
		t.Fatal(err)
	}
	if err := writeJSONAtomic(path, storeTestValue{A: 2, B: "two"}); err != nil {
		t.Fatal(err)
	}
	var back storeTestValue
	if err := readJSONFile(path, maxStateBytes, &back); err != nil {
		t.Fatal(err)
	}
	if back.A != 2 || back.B != "two" {
		t.Fatalf("after replace = %+v, want A=2 B=two", back)
	}
}

func TestWriteJSONAtomicRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target.json")
	if err := os.WriteFile(target, []byte(`{"a":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "state.json")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("cannot create symlink: %v", err)
	}
	if err := writeJSONAtomic(link, storeTestValue{A: 9}); err == nil {
		t.Fatal("expected error for symlink target")
	}
	// The symlink is preserved and the real target is untouched.
	fi, err := os.Lstat(link)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode()&os.ModeSymlink == 0 {
		t.Fatal("symlink was replaced by a regular file")
	}
	raw, _ := os.ReadFile(target)
	var v storeTestValue
	if err := json.Unmarshal(raw, &v); err != nil || v.A != 1 {
		t.Fatalf("target modified: %q", raw)
	}
	// No temp file leaked.
	entries, _ := os.ReadDir(dir)
	if len(entries) != 2 {
		t.Fatalf("directory entries = %d, want 2 (no temp leak)", len(entries))
	}
}

func TestReadJSONFileMissing(t *testing.T) {
	var v storeTestValue
	err := readJSONFile(filepath.Join(t.TempDir(), "absent.json"), maxStateBytes, &v)
	if !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("err = %v, want os.ErrNotExist", err)
	}
}

func TestReadJSONFileRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := filepath.Join(dir, "target.json")
	if err := os.WriteFile(target, []byte(`{"a":1}`), 0o600); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "state.json")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("cannot create symlink: %v", err)
	}
	var v storeTestValue
	if err := readJSONFile(link, maxStateBytes, &v); err == nil {
		t.Fatal("expected error for symlink")
	}
}

func TestReadJSONFileTooLarge(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "big.json")
	big := make([]byte, maxStateBytes+1)
	if err := os.WriteFile(path, big, 0o600); err != nil {
		t.Fatal(err)
	}
	var v storeTestValue
	if err := readJSONFile(path, maxStateBytes, &v); err == nil {
		t.Fatal("expected size error")
	}
}

func TestReadJSONFileRejectsUnknownFields(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "state.json")
	if err := os.WriteFile(path, []byte(`{"a":1,"unexpected":true}`), 0o600); err != nil {
		t.Fatal(err)
	}
	var v storeTestValue
	if err := readJSONFile(path, maxStateBytes, &v); err == nil {
		t.Fatal("expected unknown-field error")
	}
}

func TestWriteJSONAtomicEncodeError(t *testing.T) {
	path := filepath.Join(t.TempDir(), "state.json")
	// A channel cannot be marshalled; the failure must happen before any file
	// is created.
	if err := writeJSONAtomic(path, make(chan int)); err == nil {
		t.Fatal("expected encode error")
	}
	if _, err := os.Stat(path); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("file created despite encode error: %v", err)
	}
}
