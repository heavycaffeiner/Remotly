package sshcore

import (
	"errors"
	"net"
	"sync"
	"testing"
	"time"

	"golang.org/x/crypto/ssh"
)

// Interoperability behavior added for Windows OpenSSH hosts: stderr capture,
// keyboard-interactive auth, failure stages, keepalive, and phase timeouts.
// Every server here is in-process, so these run anywhere; the real Windows
// matrix is a separate manual gate.

// --- a listener that records stages ---------------------------------------

type stageListener struct {
	testListener
	mu     sync.Mutex
	stages []string
}

func newStageListener() *stageListener {
	l := &stageListener{testListener: *newTestListener()}
	return l
}

func (l *stageListener) OnFailureStage(code, stage, msg string) {
	l.mu.Lock()
	l.stages = append(l.stages, stage)
	l.mu.Unlock()
	l.testListener.OnFailure(code, msg)
}

func (l *stageListener) lastStage() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	if len(l.stages) == 0 {
		return ""
	}
	return l.stages[len(l.stages)-1]
}

// --- configurable server ---------------------------------------------------

type interopServer struct {
	addr    string
	mu      sync.Mutex
	keepalives int

	// Behavior switches.
	rejectChannel bool
	rejectPty     bool
	rejectShell   bool
	writeStderr   string
	stallHandshake bool
}

func startInteropServer(t *testing.T, cfg *ssh.ServerConfig, s *interopServer) *interopServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	s.addr = ln.Addr().String()
	t.Cleanup(func() { ln.Close() })

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go s.handle(conn, cfg)
		}
	}()
	return s
}

func (s *interopServer) handle(conn net.Conn, cfg *ssh.ServerConfig) {
	if s.stallHandshake {
		// Accept the TCP connection and never send a version string. This is
		// what a firewalled or wedged sshd looks like from the client.
		defer conn.Close()
		time.Sleep(90 * time.Second)
		return
	}
	sconn, chans, reqs, err := ssh.NewServerConn(conn, cfg)
	if err != nil {
		conn.Close()
		return
	}
	defer sconn.Close()

	go func() {
		for req := range reqs {
			if req.Type == "keepalive@openssh.com" {
				s.mu.Lock()
				s.keepalives++
				s.mu.Unlock()
			}
			if req.WantReply {
				_ = req.Reply(true, nil)
			}
		}
	}()

	for newChan := range chans {
		if s.rejectChannel {
			_ = newChan.Reject(ssh.Prohibited, "channel refused")
			continue
		}
		if newChan.ChannelType() != "session" {
			_ = newChan.Reject(ssh.UnknownChannelType, "unknown channel type")
			continue
		}
		ch, requests, err := newChan.Accept()
		if err != nil {
			continue
		}
		go s.handleSession(ch, requests)
	}
}

func (s *interopServer) handleSession(ch ssh.Channel, requests <-chan *ssh.Request) {
	defer ch.Close()
	for req := range requests {
		switch req.Type {
		case "pty-req":
			if req.WantReply {
				_ = req.Reply(!s.rejectPty, nil)
			}
		case "shell":
			if s.rejectShell {
				if req.WantReply {
					_ = req.Reply(false, nil)
				}
				continue
			}
			if req.WantReply {
				_ = req.Reply(true, nil)
			}
			_, _ = ch.Write([]byte("SHELL-READY\n"))
			if s.writeStderr != "" {
				_, _ = ch.Stderr().Write([]byte(s.writeStderr))
			}
		default:
			if req.WantReply {
				_ = req.Reply(false, nil)
			}
		}
	}
}

func (s *interopServer) host() string { h, _, _ := net.SplitHostPort(s.addr); return h }

func (s *interopServer) port() int {
	_, p, _ := net.SplitHostPort(s.addr)
	var port int
	_, _ = fmtSscan(p, &port)
	return port
}

func fmtSscan(s string, v *int) (int, error) {
	n := 0
	for _, c := range s {
		if c < '0' || c > '9' {
			return 0, errors.New("not a number")
		}
		n = n*10 + int(c-'0')
	}
	*v = n
	return 1, nil
}

func passwordServerConfig(t *testing.T) *ssh.ServerConfig {
	t.Helper()
	cfg := &ssh.ServerConfig{
		PasswordCallback: func(_ ssh.ConnMetadata, pw []byte) (*ssh.Permissions, error) {
			if string(pw) == testPassword {
				return nil, nil
			}
			return nil, errors.New("bad password")
		},
	}
	cfg.AddHostKey(testHostKey(t))
	return cfg
}

func testHostKey(t *testing.T) ssh.Signer {
	t.Helper()
	ts := startTestServer(t)
	return ts.hostKey
}

// --- stderr ----------------------------------------------------------------

func TestStderrIsDeliveredAsTerminalOutput(t *testing.T) {
	// Windows OpenSSH puts useful diagnostics on stderr, and the previous
	// implementation only ever read stdout, so they were invisible.
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{
		writeStderr: "STDERR-DIAGNOSTIC\n",
	})
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	defer sess.Close()

	if got := waitData(t, l, "STDERR-DIAGNOSTIC", 10*time.Second); !containsBytes([]byte(got), []byte("STDERR-DIAGNOSTIC")) {
		t.Fatalf("stderr was not delivered; got %q", got)
	}
}

func TestStdoutAndStderrBothArrive(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{
		writeStderr: "E-MARK\n",
	})
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	defer sess.Close()

	deadline := time.Now().Add(10 * time.Second)
	var all []byte
	sawOut, sawErr := false, false
	for time.Now().Before(deadline) && (!sawOut || !sawErr) {
		select {
		case b := <-l.data:
			all = append(all, b...)
			sawOut = sawOut || containsBytes(all, []byte("SHELL-READY"))
			sawErr = sawErr || containsBytes(all, []byte("E-MARK"))
		case <-time.After(50 * time.Millisecond):
		}
	}
	if !sawOut || !sawErr {
		t.Fatalf("expected both streams, stdout=%v stderr=%v (%q)", sawOut, sawErr, all)
	}
}

// --- failure stages --------------------------------------------------------

func TestDialFailureReportsDialStage(t *testing.T) {
	l := newStageListener()
	sess := NewSession(l)
	l.sess = sess
	// Port 1 on loopback refuses immediately.
	cfg := testConfig("127.0.0.1", 1)
	cfg.ConnectTimeout = 2000
	if err := sess.Connect(cfg); err != nil {
		t.Fatal(err)
	}
	select {
	case f := <-l.failed:
		if f.code != CodeConnectFailed {
			t.Fatalf("code = %s, want %s", f.code, CodeConnectFailed)
		}
		if l.lastStage() != StageDial {
			t.Fatalf("stage = %s, want %s", l.lastStage(), StageDial)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for the dial failure")
	}
}

func TestAuthFailureReportsAuthStage(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	l := newStageListener()
	sess := NewSession(l)
	l.sess = sess
	cfg := testConfig(s.host(), s.port())
	cfg.Password = "wrong-password"
	if err := sess.Connect(cfg); err != nil {
		t.Fatal(err)
	}
	select {
	case f := <-l.failed:
		if f.code != CodeAuthFailed {
			t.Fatalf("code = %s, want %s", f.code, CodeAuthFailed)
		}
		if l.lastStage() != StageAuth {
			t.Fatalf("stage = %s, want %s", l.lastStage(), StageAuth)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for the auth failure")
	}
}

func TestChannelRejectionReportsChannelStage(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{rejectChannel: true})
	l := newStageListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	select {
	case <-l.failed:
		if l.lastStage() != StageChannel {
			t.Fatalf("stage = %s, want %s", l.lastStage(), StageChannel)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for the channel failure")
	}
}

func TestPtyRejectionReportsPtyStage(t *testing.T) {
	// A Windows console host that refuses a PTY must be distinguishable from
	// one that refuses the channel.
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{rejectPty: true})
	l := newStageListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	select {
	case <-l.failed:
		if l.lastStage() != StagePty {
			t.Fatalf("stage = %s, want %s", l.lastStage(), StagePty)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for the pty failure")
	}
}

func TestShellRejectionReportsShellStage(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{rejectShell: true})
	l := newStageListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	select {
	case <-l.failed:
		if l.lastStage() != StageShell {
			t.Fatalf("stage = %s, want %s", l.lastStage(), StageShell)
		}
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for the shell failure")
	}
}

func TestFailureFallsBackWhenListenerHasNoStage(t *testing.T) {
	// A listener that does not implement StageListener still gets the public
	// code, so the binding stays backward compatible.
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	cfg := testConfig("127.0.0.1", 1)
	cfg.ConnectTimeout = 2000
	if err := sess.Connect(cfg); err != nil {
		t.Fatal(err)
	}
	select {
	case f := <-l.failed:
		if f.code != CodeConnectFailed {
			t.Fatalf("code = %s, want %s", f.code, CodeConnectFailed)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for the failure")
	}
}

// --- keyboard-interactive --------------------------------------------------

func TestKeyboardInteractiveAuthSucceeds(t *testing.T) {
	// Windows OpenSSH commonly ships with PasswordAuthentication off and
	// KbdInteractiveAuthentication on, which a password-only client cannot
	// satisfy.
	cfg := &ssh.ServerConfig{
		KeyboardInteractiveCallback: func(
			_ ssh.ConnMetadata,
			challenge ssh.KeyboardInteractiveChallenge,
		) (*ssh.Permissions, error) {
			answers, err := challenge("", "", []string{"Password: "}, []bool{false})
			if err != nil {
				return nil, err
			}
			if len(answers) != 1 || answers[0] != testPassword {
				return nil, errors.New("bad password")
			}
			return nil, nil
		},
	}
	cfg.AddHostKey(testHostKey(t))

	s := startInteropServer(t, cfg, &interopServer{})
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	sess.Close()
}

func TestKeyboardInteractiveIgnoresANonPasswordPrompt(t *testing.T) {
	// The secret is only ever sent in reply to a prompt that asks for it, so a
	// server cannot phrase a question that extracts it for another purpose.
	answered := make(chan []string, 1)
	cfg := &ssh.ServerConfig{
		KeyboardInteractiveCallback: func(
			_ ssh.ConnMetadata,
			challenge ssh.KeyboardInteractiveChallenge,
		) (*ssh.Permissions, error) {
			answers, err := challenge("", "", []string{"Favourite colour: "}, []bool{false})
			if err != nil {
				return nil, err
			}
			answered <- answers
			return nil, errors.New("denied")
		},
	}
	cfg.AddHostKey(testHostKey(t))

	s := startInteropServer(t, cfg, &interopServer{})
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	select {
	case answers := <-answered:
		for _, a := range answers {
			if a == testPassword {
				t.Fatal("the password was sent in reply to a non-password prompt")
			}
		}
	case <-time.After(15 * time.Second):
		t.Fatal("timed out waiting for the challenge")
	}
	sess.Close()
}

func TestPasswordChallengeRules(t *testing.T) {
	challenge := passwordChallenge("s3cret")

	t.Run("answers a password prompt", func(t *testing.T) {
		got, err := challenge("", "", []string{"Password: "}, []bool{false})
		if err != nil {
			t.Fatal(err)
		}
		if len(got) != 1 || got[0] != "s3cret" {
			t.Fatalf("got %q", got)
		}
	})

	t.Run("leaves an unrelated prompt blank", func(t *testing.T) {
		got, err := challenge("", "", []string{"Enter token: "}, []bool{false})
		if err != nil {
			t.Fatal(err)
		}
		if len(got) != 1 || got[0] != "" {
			t.Fatalf("got %q", got)
		}
	})

	t.Run("never answers an echoed prompt", func(t *testing.T) {
		// An echoed field is displayed as the user types, so it is not a
		// secret field and must never receive the password.
		got, err := challenge("", "", []string{"Password: "}, []bool{true})
		if err != nil {
			t.Fatal(err)
		}
		if got[0] != "" {
			t.Fatal("the password was sent to an echoed prompt")
		}
	})

	t.Run("accepts an informational message with no questions", func(t *testing.T) {
		got, err := challenge("", "Welcome", nil, nil)
		if err != nil {
			t.Fatal(err)
		}
		if len(got) != 0 {
			t.Fatalf("got %q", got)
		}
	})

	t.Run("refuses an unbounded question list", func(t *testing.T) {
		many := make([]string, maxChallengeQuestions+1)
		echos := make([]bool, len(many))
		for i := range many {
			many[i] = "Password: "
		}
		if _, err := challenge("", "", many, echos); err == nil {
			t.Fatal("expected a bounded question list to be enforced")
		}
	})
}

// --- keepalive -------------------------------------------------------------

func TestKeepaliveStopsAfterClose(t *testing.T) {
	// The loop must not outlive the session; a leaked ticker would keep a
	// closed connection referenced forever.
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	sess.Close()

	s.mu.Lock()
	before := s.keepalives
	s.mu.Unlock()
	time.Sleep(200 * time.Millisecond)
	s.mu.Lock()
	after := s.keepalives
	s.mu.Unlock()
	if after != before {
		t.Fatalf("keepalive kept running after close: %d -> %d", before, after)
	}
}

// --- timeouts --------------------------------------------------------------

func TestHandshakeStallIsBounded(t *testing.T) {
	// A server that accepts TCP and never completes the version exchange used
	// to hang the client indefinitely, because the handshake carried no
	// deadline of its own.
	if testing.Short() {
		t.Skip("takes longer than the handshake bound")
	}
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{stallHandshake: true})
	l := newStageListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	select {
	case f := <-l.failed:
		if f.code != CodeTimeout {
			t.Fatalf("code = %s, want %s", f.code, CodeTimeout)
		}
	case <-time.After(handshakeTimeout + 20*time.Second):
		t.Fatal("the handshake was not bounded")
	}
}

func TestCloseDuringHandshakeIsReportedOnce(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{stallHandshake: true})
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(s.host(), s.port())); err != nil {
		t.Fatal(err)
	}
	time.Sleep(200 * time.Millisecond)
	sess.Close()

	// Exactly one terminal event, and closing twice adds nothing.
	select {
	case <-l.closed:
	case <-l.failed:
	case <-time.After(10 * time.Second):
		t.Fatal("no terminal event after close during handshake")
	}
	sess.Close()
	select {
	case <-l.closed:
		t.Fatal("a second terminal event was emitted")
	case <-l.failed:
		t.Fatal("a second terminal event was emitted")
	case <-time.After(300 * time.Millisecond):
	}
}

// --- windows username forms ------------------------------------------------

func TestWindowsUsernameFormsArePassedThroughUnchanged(t *testing.T) {
	// Windows accounts appear as MACHINE\user, DOMAIN\user, or user@domain.
	// The client must send them verbatim, never rewritten.
	for _, name := range []string{
		"tester",
		"MACHINE\\tester",
		"DOMAIN\\tester",
		"tester@domain.example",
	} {
		got := make(chan string, 1)
		cfg := &ssh.ServerConfig{
			PasswordCallback: func(meta ssh.ConnMetadata, pw []byte) (*ssh.Permissions, error) {
				select {
				case got <- meta.User():
				default:
				}
				if string(pw) == testPassword {
					return nil, nil
				}
				return nil, errors.New("bad password")
			},
		}
		cfg.AddHostKey(testHostKey(t))
		s := startInteropServer(t, cfg, &interopServer{})

		l := newTestListener()
		sess := NewSession(l)
		l.sess = sess
		c := testConfig(s.host(), s.port())
		c.User = name
		if err := sess.Connect(c); err != nil {
			t.Fatal(err)
		}
		select {
		case seen := <-got:
			if seen != name {
				t.Fatalf("username = %q, want %q", seen, name)
			}
		case <-time.After(15 * time.Second):
			t.Fatalf("timed out waiting for the auth attempt for %q", name)
		}
		sess.Close()
	}
}

// --- pty modes -------------------------------------------------------------

func TestPtyModesAreLeftToTheServer(t *testing.T) {
	// The previous mode set forced ECHO, ICANON, and ISIG, which describes a
	// Unix line discipline and fights a Windows console host.
	if len(ptyModes()) != 0 {
		t.Fatalf("expected no forced terminal modes, got %v", ptyModes())
	}
}
