// Package paths resolves the Remotly per-user directories on each supported
// platform. All Remotly state is per OS user; there is no multi-user layout.
package paths

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

// Dir kinds. Config holds the user-edited config.json. Data holds identity
// key material and device records.
type Kind string

const (
	ConfigKind Kind = "config"
	DataKind   Kind = "data"
)

// Dir returns the absolute path of the requested Remotly directory. The
// directory is not created; callers create it with Ensure.
func Dir(kind Kind) (string, error) {
	switch runtime.GOOS {
	case "windows":
		base, err := os.UserConfigDir()
		if err != nil {
			return "", err
		}
		if kind == DataKind {
			if local := os.Getenv("LOCALAPPDATA"); local != "" {
				base = local
			}
		}
		return filepath.Join(base, "remotly"), nil
	case "darwin":
		base, err := os.UserConfigDir()
		if err != nil {
			return "", err
		}
		return filepath.Join(base, "remotly"), nil
	default:
		if kind == ConfigKind {
			if xdg := os.Getenv("XDG_CONFIG_HOME"); xdg != "" {
				return filepath.Join(xdg, "remotly"), nil
			}
		} else {
			if xdg := os.Getenv("XDG_DATA_HOME"); xdg != "" {
				return filepath.Join(xdg, "remotly"), nil
			}
		}
		home, err := os.UserHomeDir()
		if err != nil {
			return "", err
		}
		if kind == ConfigKind {
			return filepath.Join(home, ".config", "remotly"), nil
		}
		return filepath.Join(home, ".local", "share", "remotly"), nil
	}
}

// Ensure creates the directory with user-only permissions and returns its
// path. Parent directories are created with the default umask; only the
// Remotly leaf is forced to 0700 so sibling apps sharing ~/.config keep
// working. A symlink at the leaf is rejected: state must never be redirected
// through a link the daemon does not own.
func Ensure(kind Kind) (string, error) {
	dir, err := Dir(kind)
	if err != nil {
		return "", err
	}
	if fi, err := os.Lstat(dir); err == nil {
		if fi.Mode()&os.ModeSymlink != 0 {
			return "", &os.PathError{Op: "ensure", Path: dir, Err: os.ErrInvalid}
		}
		if err := AssertOwned(dir); err != nil {
			return "", err
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return "", err
	}
	if err := os.Chmod(dir, 0o700); err != nil {
		return "", err
	}
	return dir, nil
}

// ForTest isolates resolution to a fake home for tests.
func ForTest(t *testing.T, home string) {
	t.Helper()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home)
	t.Setenv("XDG_CONFIG_HOME", "")
	t.Setenv("XDG_DATA_HOME", "")
	t.Setenv("APPDATA", "")
	t.Setenv("LOCALAPPDATA", "")
}
