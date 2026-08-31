//go:build !windows

package pty

import (
	"path/filepath"
	"testing"
)

func TestResolveShellConfiguredWins(t *testing.T) {
	t.Setenv("SHELL", "/nonexistent/shell")
	p, source, err := ResolveShell("/bin/sh")
	if err != nil {
		t.Fatal(err)
	}
	if p != "/bin/sh" || source != "config" {
		t.Fatalf("got %q %q", p, source)
	}
}

func TestResolveShellConfiguredInvalid(t *testing.T) {
	if _, _, err := ResolveShell("/nonexistent/shell"); err == nil {
		t.Fatal("expected error for invalid configured shell")
	}
	if _, _, err := ResolveShell("relative/shell"); err == nil {
		t.Fatal("expected error for relative configured shell")
	}
}

func TestResolveShellEnvFallback(t *testing.T) {
	t.Setenv("SHELL", "/bin/sh")
	p, source, err := ResolveShell("")
	if err != nil {
		t.Fatal(err)
	}
	if p != "/bin/sh" || source != "$SHELL" {
		t.Fatalf("got %q %q", p, source)
	}
}

func TestResolveShellAccountFallback(t *testing.T) {
	t.Setenv("SHELL", "")
	if accountShell() == "" {
		t.Skip("no account shell on this host")
	}
	p, source, err := ResolveShell("")
	if err != nil {
		t.Fatal(err)
	}
	if source != "account" {
		t.Fatalf("source %q, want account", source)
	}
	if !validShell(p) {
		t.Fatalf("resolved shell %q not usable", p)
	}
}

func TestShellArgsMatrix(t *testing.T) {
	cases := []struct {
		program, command string
		want             []string
	}{
		{"/bin/bash", "", []string{"-l"}},
		{"/bin/bash", "echo hi", []string{"-l", "-c", "echo hi"}},
		{"/bin/zsh", "", []string{"-i", "-l"}},
		{"/usr/local/bin/zsh", "claude", []string{"-i", "-l", "-c", "claude"}},
		{"/bin/dash", "", []string{"-l"}},
	}
	for _, c := range cases {
		got := ShellArgs(c.program, c.command)
		if len(got) != len(c.want) {
			t.Errorf("%s %q: got %v want %v", c.program, c.command, got, c.want)
			continue
		}
		for i := range got {
			if got[i] != c.want[i] {
				t.Errorf("%s %q: got %v want %v", c.program, c.command, got, c.want)
				break
			}
		}
	}
}

func TestStartRejectsInvalidBeforeSpawn(t *testing.T) {
	b := New()
	_, err := b.Start(StartRequest{
		Program: "/bin/sh",
		Cwd:     filepath.Join(t.TempDir(), "missing"),
		Env:     []string{"PATH=/bin"},
		Cols:    80, Rows: 24,
	})
	if err == nil {
		t.Fatal("expected error for missing cwd")
	}
}
