package pairing

import (
	"os"
	"path/filepath"
	"testing"
)

func TestLoadOrCreateIdentityGeneratesAndPersists(t *testing.T) {
	dir := t.TempDir()
	id1, err := LoadOrCreateIdentity(dir)
	if err != nil {
		t.Fatalf("LoadOrCreateIdentity: %v", err)
	}
	if id1.PublicBytes() == [32]byte{} {
		t.Fatal("public key is all zero")
	}

	fi, err := os.Stat(filepath.Join(dir, "identity.json"))
	if err != nil {
		t.Fatalf("stat identity.json: %v", err)
	}
	if perm := fi.Mode().Perm(); perm != stateFileMode {
		t.Fatalf("identity.json mode = %o, want %o", perm, stateFileMode)
	}

	// A second load (simulating a daemon restart) returns the same identity.
	id2, err := LoadOrCreateIdentity(dir)
	if err != nil {
		t.Fatalf("second LoadOrCreateIdentity: %v", err)
	}
	if id1.PublicBytes() != id2.PublicBytes() {
		t.Fatal("identity changed across restarts")
	}
}

func TestLoadOrCreateIdentityCorruptFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.json")
	corrupt := []byte("this is not json\n")
	if err := os.WriteFile(path, corrupt, 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadOrCreateIdentity(dir); err == nil {
		t.Fatal("expected error for corrupt identity file")
	}
	// The corrupt file must be left in place, never regenerated.
	after, _ := os.ReadFile(path)
	if string(after) != string(corrupt) {
		t.Fatal("corrupt identity file was modified")
	}
}

func TestLoadOrCreateIdentityKeyMismatch(t *testing.T) {
	a, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	b, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.json")
	f := identityFile{
		Version: 1,
		Public:  encodeB64(a.public[:]),
		Private: encodeB64(b.private[:]),
	}
	if err := writeJSONAtomic(path, f); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadOrCreateIdentity(dir); err == nil {
		t.Fatal("expected error for private/public mismatch")
	}
}

func TestLoadOrCreateIdentityBadVersion(t *testing.T) {
	id, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	path := filepath.Join(dir, "identity.json")
	f := identityFile{
		Version: 2,
		Public:  encodeB64(id.public[:]),
		Private: encodeB64(id.private[:]),
	}
	if err := writeJSONAtomic(path, f); err != nil {
		t.Fatal(err)
	}
	if _, err := LoadOrCreateIdentity(dir); err == nil {
		t.Fatal("expected error for unsupported version")
	}
}

func TestLoadOrCreateIdentityRejectsSymlink(t *testing.T) {
	id, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	dir := t.TempDir()
	real := filepath.Join(dir, "real.json")
	f := identityFile{Version: 1, Public: encodeB64(id.public[:]), Private: encodeB64(id.private[:])}
	if err := writeJSONAtomic(real, f); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(real, filepath.Join(dir, "identity.json")); err != nil {
		t.Skipf("cannot create symlink: %v", err)
	}
	if _, err := LoadOrCreateIdentity(dir); err == nil {
		t.Fatal("expected error for symlinked identity file")
	}
}

func TestIdentitySaveLoadRoundTrip(t *testing.T) {
	id, err := NewIdentity()
	if err != nil {
		t.Fatal(err)
	}
	path := filepath.Join(t.TempDir(), "identity.json")
	if err := id.save(path); err != nil {
		t.Fatal(err)
	}
	back, err := loadIdentity(path)
	if err != nil {
		t.Fatalf("loadIdentity: %v", err)
	}
	if back.public != id.public || back.private != id.private {
		t.Fatal("identity did not round-trip")
	}
}
