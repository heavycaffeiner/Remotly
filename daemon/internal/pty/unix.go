//go:build !windows

package pty

import (
	"errors"
	"os"
	"os/exec"

	"path/filepath"
	"strconv"
	"strings"
	"syscall"
)

// closedErr reports whether err is the Unix read error produced when the
// PTY slave side closes (EIO on Linux and macOS).
func closedErr(err error) bool {
	var pe *os.PathError
	if errors.As(err, &pe) {
		return pe.Err == syscall.EIO
	}
	return false
}

// ResolveShell picks the shell for new sessions: an explicit configuration
// value first, then $SHELL, then the OS account entry. The result is
// validated to be an existing executable file.
func ResolveShell(configured string) (path, source string, err error) {
	candidates := [][2]string{}
	if configured != "" {
		candidates = append(candidates, [2]string{configured, "config"})
	}
	if s := os.Getenv("SHELL"); s != "" {
		candidates = append(candidates, [2]string{s, "$SHELL"})
	}
	if shell := accountShell(); shell != "" {
		candidates = append(candidates, [2]string{shell, "account"})
	}
	if len(candidates) == 0 {
		return "", "", errors.New("pty: no shell found ($SHELL unset and no account entry)")
	}
	for _, c := range candidates {
		if c[1] == "config" {
			// A configured shell must work; do not fall back silently.
			if !validShell(c[0]) {
				return "", "", errors.New("pty: configured shell is not an executable file")
			}
			return c[0], c[1], nil
		}
		if validShell(c[0]) {
			return c[0], c[1], nil
		}
	}
	return "", "", errors.New("pty: no usable shell found")
}

func validShell(p string) bool {
	if !filepath.IsAbs(p) || strings.ContainsRune(p, '\x00') {
		return false
	}
	fi, err := os.Stat(p)
	if err != nil || fi.IsDir() || fi.Mode()&0o111 == 0 {
		return false
	}
	return true
}

// ShellArgs builds the shell argument vector. zsh additionally needs -i so
// the interactive rc file loads; a command is passed to the shell's -c form
// as a single structured argument, never concatenated into a string.
func ShellArgs(program, command string) []string {
	base := []string{"-l"}
	if isZsh(program) {
		base = []string{"-i", "-l"}
	}
	if command == "" {
		return base
	}
	return append(base, "-c", command)
}

func isZsh(program string) bool {
	return strings.Contains(filepath.Base(program), "zsh")
}

// ShellFromConfig resolves the configured shell and its arguments for a
// session.
func ShellFromConfig(configured, command string) (program string, args []string, source string, err error) {
	program, source, err = ResolveShell(configured)
	if err != nil {
		return "", nil, "", err
	}
	args = ShellArgs(program, command)
	return program, args, source, nil
}

// accountShell reads the current user's shell from /etc/passwd. os/user
// does not expose the field.
func accountShell() string {
	uid := os.Getuid()
	data, err := os.ReadFile("/etc/passwd")
	if err != nil {
		return ""
	}
	for _, line := range strings.Split(string(data), "\n") {
		fields := strings.Split(line, ":")
		if len(fields) < 7 {
			continue
		}
		if fields[2] == strconv.Itoa(uid) && fields[6] != "" {
			return fields[6]
		}
	}
	return ""
}

// unixBackend starts Unix PTY processes.
type unixBackend struct{}

// New returns the platform PTY backend.
func New() Backend { return unixBackend{} }

func (unixBackend) Start(req StartRequest) (Process, error) {
	if err := Validate(req); err != nil {
		return nil, err
	}
	args := append([]string{}, req.Args...)
	if req.Command != "" {
		args = append(args, "-c", req.Command)
	}
	cmd := exec.Command(req.Program, args...)
	cmd.Dir = req.Cwd
	cmd.Env = req.Env
	// StartWithSize sets the session and controlling terminal and starts
	// the process; it must not be combined with a manual cmd.Start.
	ptmx, err := ptyStartWithSize(cmd, req.Cols, req.Rows)
	if err != nil {
		return nil, err
	}
	return &unixProcess{cmd: cmd, ptmx: ptmx, closed: make(chan struct{})}, nil
}
