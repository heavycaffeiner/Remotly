package pty

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

func TestValidateStartRequest(t *testing.T) {
	base := StartRequest{
		Program: "/bin/sh",
		Cwd:     "/",
		Env:     []string{"PATH=/usr/bin", "TERM=xterm"},
		Cols:    80,
		Rows:    24,
	}
	if err := Validate(base); err != nil {
		t.Fatal(err)
	}
	cases := []struct {
		name string
		mut  func(*StartRequest)
	}{
		{"relative program", func(r *StartRequest) { r.Program = "sh" }},
		{"program NUL", func(r *StartRequest) { r.Program = "/bin/\x00sh" }},
		{"program long", func(r *StartRequest) { r.Program = "/bin/" + strings.Repeat("a", 5000) }},
		{"command NUL", func(r *StartRequest) { r.Command = "e\x00cho" }},
		{"command long", func(r *StartRequest) { r.Command = strings.Repeat("a", 9000) }},
		{"zero cols", func(r *StartRequest) { r.Cols = 0 }},
		{"huge cols", func(r *StartRequest) { r.Cols = 1001 }},
		{"zero rows", func(r *StartRequest) { r.Rows = 0 }},
		{"huge rows", func(r *StartRequest) { r.Rows = 1001 }},
		{"empty env", func(r *StartRequest) { r.Env = nil }},
		{"env no equals", func(r *StartRequest) { r.Env = []string{"PATH"} }},
		{"env empty key", func(r *StartRequest) { r.Env = []string{"=x"} }},
		{"env NUL key", func(r *StartRequest) { r.Env = []string{"PA\x00TH=/bin"} }},
	}
	for _, c := range cases {
		r := base
		c.mut(&r)
		if err := Validate(r); err == nil {
			t.Errorf("%s: expected error", c.name)
		}
	}
}

func TestValidateCwd(t *testing.T) {
	dir := t.TempDir()
	got, err := ValidateCwd(dir)
	if err != nil {
		t.Fatal(err)
	}
	if got != filepath.Clean(dir) {
		t.Fatalf("got %s", got)
	}
	if _, err := ValidateCwd("relative/path"); err == nil {
		t.Error("relative cwd accepted")
	}
	if _, err := ValidateCwd(filepath.Join(dir, "missing")); err == nil {
		t.Error("missing cwd accepted")
	}
	file := filepath.Join(dir, "f.txt")
	if err := os.WriteFile(file, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := ValidateCwd(file); err == nil {
		t.Error("file cwd accepted")
	}
	// Spaces and unicode in the path.
	weird := filepath.Join(dir, "도스 with space")
	if err := os.Mkdir(weird, 0o755); err != nil {
		t.Fatal(err)
	}
	if _, err := ValidateCwd(weird); err != nil {
		t.Errorf("unicode cwd rejected: %v", err)
	}
}

func TestValidateCwdDefaultHome(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	got, err := ValidateCwd("")
	if err != nil {
		t.Fatal(err)
	}
	if got != filepath.Clean(home) {
		t.Fatalf("got %s want %s", got, home)
	}
}

func TestValidateCwdSymlinkToDir(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("symlink semantics differ")
	}
	dir := t.TempDir()
	target := filepath.Join(dir, "target")
	if err := os.Mkdir(target, 0o755); err != nil {
		t.Fatal(err)
	}
	link := filepath.Join(dir, "link")
	if err := os.Symlink(target, link); err != nil {
		t.Skip("symlink unsupported")
	}
	got, err := ValidateCwd(link)
	if err != nil {
		t.Fatal(err)
	}
	if got != target {
		t.Fatalf("got %s want %s", got, target)
	}
}
