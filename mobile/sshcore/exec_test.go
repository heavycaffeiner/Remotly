package sshcore

import (
	"crypto/ed25519"
	"encoding/binary"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// execListener answers the host-key challenge on a channel. With auto set, it
// accepts immediately; otherwise it rejects, which fails the handshake for the
// reject test.
type execListener struct {
	auto   bool
	decide *Exec
	ch     chan struct{ alg, fp string }
}

func newExecListener(auto bool) *execListener {
	return &execListener{
		auto: auto,
		ch:   make(chan struct{ alg, fp string }, 4),
	}
}

func (l *execListener) OnHostKey(alg, fp string) {
	select {
	case l.ch <- struct{ alg, fp string }{alg, fp}:
	default:
	}
	if l.decide == nil {
		return
	}
	l.decide.DecideHostKey(l.auto)
}

// runExec runs e.Run with an outer deadline so a regression that leaves Wait
// blocked (the original bug this feature fixes) fails the test instead of
// hanging the package.
func runExec(t *testing.T, e *Exec, cfg *ExecConfig) *ExecResult {
	t.Helper()
	done := make(chan *ExecResult, 1)
	go func() {
		res, err := e.Run(cfg)
		if err != nil {
			t.Errorf("Run returned error: %v", err)
		}
		done <- res
	}()
	select {
	case res := <-done:
		return res
	case <-time.After(10 * time.Second):
		t.Fatal("Run did not return within the deadline")
		return nil
	}
}

func execCfg(h string, port int, command string) *ExecConfig {
	return &ExecConfig{
		Host: h, User: "tester", Port: port,
		Password:       testPassword,
		Command:        command,
		ConnectTimeout: 5000,
		CommandTimeout: 3000,
	}
}

// TestExecStdoutStderrAndExitCode drives a real one-shot exec end to end: the
// server writes to stdout and to the stderr stream and reports a non-zero exit
// status, and the client must split the two streams and surface the status.
func TestExecStdoutStderrAndExitCode(t *testing.T) {
	ts := startTestServer(t)
	ln := newExecListener(true)
	e := NewExec(ln)
	ln.decide = e
	cfg := execCfg(ts.host(), ts.port(), "printf hello")

	res := runExec(t, e, cfg)

	if res.Code != "" {
		t.Fatalf("expected a command result, got connect failure %s: %s", res.Code, res.Message)
	}
	if string(res.Stdout) != "hello" {
		t.Errorf("stdout = %q, want %q", res.Stdout, "hello")
	}
	if string(res.Stderr) != "boom" {
		t.Errorf("stderr = %q, want %q", res.Stderr, "boom")
	}
	if res.ExitCode != 3 {
		t.Errorf("exit code = %d, want 3", res.ExitCode)
	}
}

// TestExecHostKeyReject proves a rejected first-use key fails the exec connect
// with the host-key code, matching the terminal and SFTP flows.
func TestExecHostKeyReject(t *testing.T) {
	ts := startTestServer(t)
	ln := newExecListener(false)
	e := NewExec(ln)
	ln.decide = e

	res := runExec(t, e, execCfg(ts.host(), ts.port(), "true"))

	if res.Code != CodeHostKeyRejected {
		t.Fatalf("code = %s, want %s", res.Code, CodeHostKeyRejected)
	}
}

// TestExecAuthFailure proves a wrong password is reported as an auth failure,
// not a connect failure or a hang.
func TestExecAuthFailure(t *testing.T) {
	ts := startTestServer(t)
	ln := newExecListener(true)
	e := NewExec(ln)
	ln.decide = e
	cfg := execCfg(ts.host(), ts.port(), "true")
	cfg.Password = "wrong"

	res := runExec(t, e, cfg)

	if res.Code != CodeAuthFailed {
		t.Fatalf("code = %s, want %s", res.Code, CodeAuthFailed)
	}
}

// TestExecKeyAuth proves a private key authenticates an exec, exercising the
// shared auth builder's key path over a real connection.
func TestExecKeyAuth(t *testing.T) {
	ts := startTestServer(t)
	ln := newExecListener(true)
	e := NewExec(ln)
	ln.decide = e

	_, priv, err := ed25519.GenerateKey(nil)
	if err != nil {
		t.Fatal(err)
	}
	keyPEM := opensshKey(t, priv, nil)
	cfg := execCfg(ts.host(), ts.port(), "id")
	cfg.Password = ""
	cfg.PrivateKey = keyPEM

	res := runExec(t, e, cfg)

	if res.Code != "" {
		t.Fatalf("expected a command result, got %s: %s", res.Code, res.Message)
	}
	if res.ExitCode != 0 {
		t.Errorf("exit code = %d, want 0", res.ExitCode)
	}
}

// TestExecCommandTimeout proves a command that outlives CommandTimeout is
// closed and reported as a timeout, so a hung remote cannot hold the bridge
// thread. The server sleeps past the bound; the client must come back with
// CodeTimeout, not block.
func TestExecCommandTimeout(t *testing.T) {
	ts := startTestServer(t)
	ln := newExecListener(true)
	e := NewExec(ln)
	ln.decide = e
	cfg := execCfg(ts.host(), ts.port(), "sleep")
	cfg.CommandTimeout = 300

	start := time.Now()
	res := runExec(t, e, cfg)
	elapsed := time.Since(start)

	if res.Code != CodeTimeout {
		t.Fatalf("code = %q, want %q (message=%q)", res.Code, CodeTimeout, res.Message)
	}
	if elapsed > 5*time.Second {
		t.Fatalf("Run took %v; the command timeout bound did not fire", elapsed)
	}
}

// serveExec is the test-side answer to an exec request: write to stdout and the
// stderr stream, then send a non-zero exit status.
func serveExec(ch ssh.Channel, payload []byte) {
	var payloadStruct struct{ Command string }
	ssh.Unmarshal(payload, &payloadStruct)

	// A command that never finishes on its own; the client's timeout bound is
	// what ends it.
	if payloadStruct.Command == "sleep" {
		time.Sleep(3 * time.Second)
		return
	}

	exitCode := uint32(0)
	if payloadStruct.Command == "printf hello" {
		_, _ = ch.Write([]byte("hello"))
		_, _ = ch.Stderr().Write([]byte("boom"))
		exitCode = 3
	}
	// exit-status is a notification; the client never replies. Closing the
	// channel ends the client's Wait.
	status := make([]byte, 4)
	binary.BigEndian.PutUint32(status, exitCode)
	_, _ = ch.SendRequest("exit-status", false, status)
	_ = ch.Close()
}
