package main

import (
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// installOnPath makes the running binary runnable as `remotly`.
//
// A user downloads one file from the releases page and runs it from wherever
// it landed, usually ~/Downloads. `remotly start` then installs a service
// pointing at that path, and every later instruction ("run `remotly pair`")
// fails because the name is not on PATH. Installing here is what makes the
// documented commands work without asking the user to move the file.
//
// It returns the installed path, or "" when nothing was done: already on PATH,
// or no writable directory that PATH actually contains.
func installOnPath(self string) (string, error) {
	dir := pathInstallDir()
	if dir == "" {
		return "", nil
	}
	target := filepath.Join(dir, binaryName())
	// Already the file PATH resolves to: nothing to copy, and copying a file
	// onto itself would truncate it.
	if sameFile(self, target) {
		return "", nil
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return "", err
	}
	if err := copyExecutable(self, target); err != nil {
		return "", err
	}
	return target, nil
}

// binaryName is the file name the install uses.
func binaryName() string {
	if runtime.GOOS == "windows" {
		return "remotly.exe"
	}
	return "remotly"
}

// pathInstallDir picks a directory on PATH to install into.
//
// Only per-user directories are considered: writing to /usr/local/bin needs
// root, and a tool that silently asks for it is worse than one that says where
// it put itself. The candidates are the conventional user bin directories, and
// one is used only when PATH already contains it, so the install actually
// takes effect in the shell the user is in.
func pathInstallDir() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	var candidates []string
	if runtime.GOOS == "windows" {
		candidates = []string{filepath.Join(home, "AppData", "Local", "Programs", "remotly")}
	} else {
		candidates = []string{
			filepath.Join(home, ".local", "bin"),
			filepath.Join(home, "bin"),
		}
	}
	onPath := pathEntries()
	for _, c := range candidates {
		if onPath[filepath.Clean(c)] {
			return c
		}
	}
	return ""
}

// pathEntries returns the directories in PATH as a set.
func pathEntries() map[string]bool {
	out := make(map[string]bool)
	for _, p := range filepath.SplitList(os.Getenv("PATH")) {
		if p == "" {
			continue
		}
		out[filepath.Clean(p)] = true
	}
	return out
}

// sameFile reports whether two paths are the same file on disk.
func sameFile(a, b string) bool {
	fa, err := os.Stat(a)
	if err != nil {
		return false
	}
	fb, err := os.Stat(b)
	if err != nil {
		return false
	}
	return os.SameFile(fa, fb)
}

// copyExecutable copies src to dst and makes it executable.
//
// Written to a temporary file beside the target and renamed, so an interrupted
// copy cannot leave a half-written binary in place of a working one. The
// existing file is removed first because a running process holds its inode:
// replacing the name is fine, truncating the file underneath it is not.
func copyExecutable(src, dst string) (err error) {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()

	tmp, err := os.CreateTemp(filepath.Dir(dst), ".remotly-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	defer func() {
		if err != nil {
			_ = os.Remove(tmpName)
		}
	}()

	if _, err = io.Copy(tmp, in); err != nil {
		_ = tmp.Close()
		return err
	}
	if err = tmp.Chmod(0o755); err != nil {
		_ = tmp.Close()
		return err
	}
	if err = tmp.Close(); err != nil {
		return err
	}
	// Windows refuses to rename onto an existing file, and on Unix removing
	// first detaches a running copy from the name rather than rewriting it.
	_ = os.Remove(dst)
	return os.Rename(tmpName, dst)
}

// pathHint returns advice for a user whose PATH has no directory to install
// into, or "" when one was found.
func pathHint() string {
	if pathInstallDir() != "" {
		return ""
	}
	if runtime.GOOS == "windows" {
		return "add %LOCALAPPDATA%\\Programs\\remotly to PATH to run remotly by name"
	}
	home, err := os.UserHomeDir()
	if err != nil {
		return "add a user bin directory to PATH to run remotly by name"
	}
	local := filepath.Join(home, ".local", "bin")
	return fmt.Sprintf("add %s to PATH to run remotly by name (it is not there now)",
		strings.TrimSuffix(local, string(filepath.Separator)))
}
