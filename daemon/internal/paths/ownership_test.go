//go:build !windows

package paths

import (
	"os"
	"path/filepath"
	"testing"
)

func TestAssertOwnedOwnAndAbsent(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "file")
	if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := AssertOwned(p); err != nil {
		t.Fatalf("own file should pass: %v", err)
	}
	if err := AssertOwned(filepath.Join(dir, "absent")); err != nil {
		t.Fatalf("absent path should pass: %v", err)
	}
}

// TestAssertOwnedForeign plants a file owned by another uid and expects
// refusal. It needs root to chown, so it skips otherwise.
func TestAssertOwnedForeign(t *testing.T) {
	if os.Geteuid() != 0 {
		t.Skip("needs root to chown to another user")
	}
	dir := t.TempDir()
	p := filepath.Join(dir, "file")
	if err := os.WriteFile(p, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Chown(p, 1, 1); err != nil { // uid 1 (daemon)
		t.Fatalf("chown: %v", err)
	}
	if err := AssertOwned(p); err == nil {
		t.Fatal("expected refusal for a file owned by another user")
	}
}
