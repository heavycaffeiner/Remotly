package service

import (
	"errors"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

func TestAtomicWriteCreatesAndOverwrites(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "def")
	if err := atomicWrite(p, []byte("first"), 0o600); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "first" {
		t.Fatalf("got %q", b)
	}
	if err := atomicWrite(p, []byte("second"), 0o600); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); string(b) != "second" {
		t.Fatalf("overwrite got %q", b)
	}
	fi, _ := os.Stat(p)
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("mode %o want 600", fi.Mode().Perm())
	}
	// No temp files left behind.
	entries, _ := os.ReadDir(dir)
	if len(entries) != 1 {
		t.Fatalf("expected only the target, got %v", entries)
	}
}

func TestIsUsageError(t *testing.T) {
	if !isUsageError("", &exec.Error{Name: "systemctl", Err: errors.New("not found")}) {
		t.Fatal("expected exec.Error to be a usage error")
	}
	if isUsageError("", nil) {
		t.Fatal("nil error should not be a usage error")
	}
	if isUsageError("", errors.New("some op failed")) {
		t.Fatal("plain error should not be a usage error")
	}
}

func TestReadFileAbsent(t *testing.T) {
	if s, err := readFile(filepath.Join(t.TempDir(), "nope")); err != nil || s != "" {
		t.Fatalf("absent: got (%q, %v)", s, err)
	}
}
