package paths

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestDirLinux(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux only")
	}
	home := t.TempDir()
	ForTest(t, home)
	cfg, err := Dir(ConfigKind)
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(home, ".config", "remotly"); cfg != want {
		t.Fatalf("got %s want %s", cfg, want)
	}
	data, err := Dir(DataKind)
	if err != nil {
		t.Fatal(err)
	}
	if want := filepath.Join(home, ".local", "share", "remotly"); data != want {
		t.Fatalf("got %s want %s", data, want)
	}
}

func TestDirXDGOverride(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("linux only")
	}
	home := t.TempDir()
	ForTest(t, home)
	xdg := filepath.Join(t.TempDir(), "xdg")
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(xdg, "config"))
	t.Setenv("XDG_DATA_HOME", filepath.Join(xdg, "data"))
	if cfg, _ := Dir(ConfigKind); cfg != filepath.Join(xdg, "config", "remotly") {
		t.Fatalf("config: %s", cfg)
	}
	if data, _ := Dir(DataKind); data != filepath.Join(xdg, "data", "remotly") {
		t.Fatalf("data: %s", data)
	}
}

func TestEnsureCreatesWith0700(t *testing.T) {
	home := t.TempDir()
	ForTest(t, home)
	got, err := Ensure(DataKind)
	if err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(got)
	if err != nil {
		t.Fatal(err)
	}
	if mode := fi.Mode().Perm(); mode != 0o700 {
		t.Fatalf("mode %o want 700", mode)
	}
}

func TestEnsureRejectsSymlinkLeaf(t *testing.T) {
	home := t.TempDir()
	ForTest(t, home)
	dir, err := Dir(DataKind)
	if err != nil {
		t.Fatal(err)
	}
	parent := filepath.Dir(dir)
	if err := os.MkdirAll(parent, 0o700); err != nil {
		t.Fatal(err)
	}
	target := t.TempDir()
	if err := os.Symlink(target, dir); err != nil {
		t.Skip("symlink unsupported")
	}
	_, err = Ensure(DataKind)
	if err == nil {
		t.Fatal("expected symlink rejection")
	}
	if !strings.Contains(err.Error(), "ensure") {
		t.Fatalf("unexpected error %v", err)
	}
}
