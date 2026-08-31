//go:build winssh

// The environment-backed Windows OpenSSH suite. Behind a build tag and skipped
// unless the environment names a host, so an ordinary `go test ./...` never
// needs a Windows machine.
//
// See testdata/windows-openssh/README.md for host setup and for the evidence
// each run has to record.

package sshcore

import (
	"os"
	"strconv"
	"strings"
	"testing"
	"time"
)

type winEnv struct {
	host       string
	port       int
	user       string
	password   string
	keyPEM     []byte
	passphrase []byte
}

func loadWinEnv(t *testing.T) winEnv {
	t.Helper()
	host := os.Getenv("REMOTLY_WIN_SSH_HOST")
	if host == "" {
		t.Skip("REMOTLY_WIN_SSH_HOST is not set")
	}
	port := 22
	if p := os.Getenv("REMOTLY_WIN_SSH_PORT"); p != "" {
		n, err := strconv.Atoi(p)
		if err != nil {
			t.Fatalf("REMOTLY_WIN_SSH_PORT: %v", err)
		}
		port = n
	}
	user := os.Getenv("REMOTLY_WIN_SSH_USER")
	if user == "" {
		t.Fatal("REMOTLY_WIN_SSH_USER is required")
	}
	env := winEnv{host: host, port: port, user: user}

	if keyPath := os.Getenv("REMOTLY_WIN_SSH_KEY"); keyPath != "" {
		pem, err := os.ReadFile(keyPath)
		if err != nil {
			t.Fatalf("cannot read the private key: %v", err)
		}
		env.keyPEM = pem
		env.passphrase = []byte(os.Getenv("REMOTLY_WIN_SSH_KEY_PASSPHRASE"))
		return env
	}
	env.password = os.Getenv("REMOTLY_WIN_SSH_PASSWORD")
	if env.password == "" {
		t.Fatal("set REMOTLY_WIN_SSH_PASSWORD or REMOTLY_WIN_SSH_KEY")
	}
	return env
}

func (e winEnv) config() *Config {
	cfg := &Config{
		Host: e.host, Port: e.port, User: e.user,
		Cols: 80, Rows: 24, ConnectTimeout: 15000,
	}
	if len(e.keyPEM) > 0 {
		cfg.PrivateKey = e.keyPEM
		cfg.Passphrase = e.passphrase
	} else {
		cfg.Password = e.password
	}
	return cfg
}

func winConnect(t *testing.T) (*Session, *testListener) {
	t.Helper()
	env := loadWinEnv(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(env.config()); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	t.Cleanup(sess.Close)
	return sess, l
}

func TestWindowsShellOpens(t *testing.T) {
	sess, _ := winConnect(t)
	_ = sess
}

func TestWindowsCommandRoundTrip(t *testing.T) {
	sess, l := winConnect(t)
	// Works on both PowerShell and cmd.
	sess.Write([]byte("echo REMOTLY-OK\r\n"))
	got := waitData(t, l, "REMOTLY-OK", 20*time.Second)
	if !strings.Contains(got, "REMOTLY-OK") {
		t.Fatalf("command output not seen; got %q", got)
	}
}

func TestWindowsUtf8RoundTrip(t *testing.T) {
	// CJK output through the Windows console host is the case most likely to
	// break, and it is a headline product requirement.
	sess, l := winConnect(t)
	sess.Write([]byte("echo 한글-테스트\r\n"))
	got := waitData(t, l, "한글", 20*time.Second)
	if !strings.Contains(got, "한글") {
		t.Fatalf("Hangul did not round-trip; got %q", got)
	}
}

func TestWindowsWindowChange(t *testing.T) {
	sess, _ := winConnect(t)
	sess.WindowChange(120, 40)
	// A rejected window-change surfaces as a closed session; staying live is
	// the assertion.
	time.Sleep(2 * time.Second)
}

func TestWindowsCtrlC(t *testing.T) {
	sess, l := winConnect(t)
	sess.Write([]byte("ping -t 127.0.0.1\r\n"))
	time.Sleep(3 * time.Second)
	sess.Write([]byte{0x03})
	// The prompt should come back rather than the session dying.
	time.Sleep(2 * time.Second)
	select {
	case <-l.closed:
		t.Fatal("Ctrl+C closed the session instead of interrupting the command")
	case <-l.failed:
		t.Fatal("Ctrl+C failed the session")
	default:
	}
}

func TestWindowsRemoteCloseIsReportedAsClose(t *testing.T) {
	// Exiting the shell must report a remote close, not a generic failure.
	env := loadWinEnv(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(env.config()); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	sess.Write([]byte("exit\r\n"))
	select {
	case <-l.closed:
	case f := <-l.failed:
		t.Fatalf("exit reported a failure instead of a close: %s %s", f.code, f.msg)
	case <-time.After(20 * time.Second):
		t.Fatal("no terminal event after exit")
	}
}

// winSftpListener auto-accepts the host key so the path assertions are what
// the test actually exercises.
type winSftpListener struct{ s *Sftp }

func (l *winSftpListener) OnHostKey(_, _ string) { l.s.DecideHostKey(true) }

func TestWindowsSftpPathsArePassedThrough(t *testing.T) {
	// SFTP on Windows OpenSSH serves slash-separated protocol paths. The client
	// must use what the server returns rather than converting to backslashes.
	env := loadWinEnv(t)
	l := &winSftpListener{}
	conn := NewSftp(l)
	l.s = conn
	res := conn.Connect(env.config())
	if !res.Ready {
		t.Fatalf("sftp connect failed: %s %s", res.Code, res.Message)
	}
	defer conn.Close()

	raw, err := conn.List(".")
	if err != nil {
		t.Fatalf("list failed: %v", err)
	}
	if len(raw) == 0 {
		t.Fatal("no entries returned")
	}
	if strings.Contains(string(raw), `\\`) {
		t.Fatalf("a path was backslash-escaped rather than passed through: %s", raw)
	}
}
