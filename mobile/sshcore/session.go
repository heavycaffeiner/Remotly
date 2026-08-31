// Package sshcore is the SSH and SFTP client core for the Remotly mobile app.
// It is bound to Android with gomobile (see scripts/build-sshcore.sh) and is
// the engine behind the Kotlin SshEngine / SftpConnection seams.
//
// The design goal is to structure out the failure classes rather than patch
// them: the host-key prompt is gated by its own bound and no dial or auth
// timer races it; key parsing is the Go stdlib so there is no JCE provider
// table to break Ed25519; resize is a real SSH window-change request; and
// close is idempotent with exactly one terminal event.
package sshcore

import (
	"errors"
	"fmt"
	"io"
	"net"
	"strings"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// Listener is implemented by the Kotlin layer (GoSshEngine). Go calls it as
// the connection progresses. OnHostKey blocks the handshake until the caller
// answers through Session.DecideHostKey; the other callbacks are
// fire-and-forget and are all delivered on the session's single worker
// goroutine. Implementations must be thread-safe and must not block.
type Listener interface {
	// OnHostKey reports the server host key before first trust. The handshake
	// blocks until DecideHostKey is called (or the prompt bound expires).
	OnHostKey(algorithm, fingerprint string)
	// OnReady reports that the PTY shell is open and the session is live.
	OnReady()
	// OnData reports terminal output bytes.
	OnData(data []byte)
	// OnClosed reports a terminal close. code is a CloseCode, reason a short
	// human string. Exactly one of OnClosed or OnFailure fires per session.
	OnClosed(code int, reason string)
	// OnFailure reports a terminal failure before the session became live.
	// code is an SshCode string; stage names the operation that failed.
	OnFailure(code, message string)
}

// StageListener is an optional Listener extension. When the Kotlin side
// implements it, the failing stage is reported alongside the public code.
// gomobile cannot bind a type switch on an interface, so this is checked in Go
// and the stage falls back into the message when the listener does not accept
// it.
type StageListener interface {
	OnFailureStage(code, stage, message string)
}

// Timeouts. Each phase is bounded separately so a server that accepts TCP but
// never finishes the version exchange cannot hang the app, while the user
// still gets an unhurried window to decide on a host key.
const (
	defaultDialTimeout = 15 * time.Second
	// Bounds version exchange, key exchange, and auth. The clock is suspended
	// while a host-key prompt is open.
	handshakeTimeout = 45 * time.Second
	// How long a host-key prompt may stay unanswered.
	hostKeyPromptTimeout = 120 * time.Second
	// Bounds session open, PTY request, and shell start.
	sessionSetupTimeout = 20 * time.Second
	// Idle keepalive. Windows firewalls and NAT devices drop an idle SSH
	// connection silently; a periodic global request keeps it observable.
	keepaliveInterval = 30 * time.Second
	// Consecutive keepalive failures treated as connection loss.
	keepaliveMaxFailures = 3
)

// Config is a one-shot connect request. Credentials are consumed per connect
// and zeroed after the handshake attempt; nothing is retained on the Go side.
type Config struct {
	Host string
	User string
	Port int

	// Exactly one of Password or PrivateKey must be set.
	Password   string
	PrivateKey []byte // PEM (PKCS#8 or OpenSSH); Ed25519/ECDSA/RSA
	Passphrase []byte // decrypt passphrase for an encrypted key

	Cols           int
	Rows           int
	ConnectTimeout int // ms; bounds the TCP dial only, not the handshake
}

// Session is one live SSH terminal. Obtain it from Connect; it is created
// before the handshake completes and its outcome is reported through the
// Listener.
type Session struct {
	cfg      *Config
	listener Listener

	decideCh  chan bool
	closeCh   chan struct{}
	closeOnce sync.Once

	terminalOnce sync.Once

	// Set while a host-key prompt is open, so the handshake watchdog can stop
	// counting against a user who is reading a fingerprint.
	promptMu sync.Mutex
	prompted bool

	stdinMu  sync.Mutex
	stdin    io.WriteCloser
	clientMu sync.Mutex
	client   *ssh.Client
	sess     *ssh.Session

	// Serializes stdout and stderr into one ordered output stream.
	emitMu sync.Mutex
	// Closed when the read loops finish, so keepalive stops with them.
	doneCh    chan struct{}
	doneOnce  sync.Once
	readGroup sync.WaitGroup
}

// NewSession creates a session handle without connecting. The listener may
// hold a reference to the session so a host-key challenge can be answered
// through DecideHostKey; call Connect to start.
func NewSession(l Listener) *Session {
	return &Session{
		listener: l,
		decideCh: make(chan bool, 1),
		closeCh:  make(chan struct{}),
		doneCh:   make(chan struct{}),
	}
}

// Connect starts a session. It validates the config, then returns immediately
// and runs the handshake on a worker goroutine; the outcome is reported
// through the listener. A non-nil error means the config was rejected before
// any I/O (no network activity happened).
func (s *Session) Connect(cfg *Config) error {
	if s.cfg != nil {
		return errors.New("session already connected")
	}
	if cfg == nil {
		return errors.New("nil config")
	}
	hasPassword := cfg.Password != ""
	hasKey := len(cfg.PrivateKey) > 0
	if hasPassword == hasKey {
		return errors.New("exactly one of Password or PrivateKey is required")
	}
	s.cfg = cfg
	go s.run()
	return nil
}

// run performs the dial, handshake, PTY request, and read loop. Output
// callbacks are serialized through emit so stdout and stderr interleave in
// arrival order rather than racing.
func (s *Session) run() {
	addr := net.JoinHostPort(s.cfg.Host, fmt.Sprintf("%d", s.cfg.Port))

	timeout := time.Duration(s.cfg.ConnectTimeout) * time.Millisecond
	if timeout <= 0 {
		timeout = defaultDialTimeout
	}
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		s.failStage(CodeConnectFailed, StageDial, err)
		return
	}

	signer, err := s.signer()
	if err != nil {
		conn.Close()
		s.failStage(CodeAuthFailed, StageAuth, err)
		return
	}

	config := &ssh.ClientConfig{
		User:            s.cfg.User,
		Auth:            s.authMethods(signer),
		HostKeyCallback: s.hostKeyCallback,
	}
	zeroCreds(s.cfg)

	// NewClientConn performs the handshake (host-key verification, then auth)
	// on this goroutine. It carries no deadline of its own, so a watchdog
	// closes the raw connection if a phase stalls. The watchdog pauses while a
	// host-key prompt is open, which is what keeps the prompt and the
	// handshake bound from racing.
	stopWatchdog := s.startHandshakeWatchdog(conn)
	clientConn, chans, reqs, err := ssh.NewClientConn(conn, addr, config)
	timedOut := stopWatchdog()
	if err != nil {
		conn.Close()
		if timedOut {
			s.failStage(CodeTimeout, StageTimeout, errors.New("handshake timed out"))
			return
		}
		s.handshakeFail(err)
		return
	}

	client := ssh.NewClient(clientConn, chans, reqs)
	s.clientMu.Lock()
	s.client = client
	s.clientMu.Unlock()

	stdout, stderr, err := s.openShell(client)
	if err != nil {
		return
	}

	s.emitCallback(s.listener.OnReady)
	s.startKeepalive(client)

	// Windows OpenSSH can put useful diagnostics on stderr, and a non-PTY
	// failure writes there exclusively. Both streams feed one ordered output.
	s.readGroup.Add(2)
	go s.pump(stdout)
	go s.pump(stderr)
	s.readGroup.Wait()
	s.doneOnce.Do(func() { close(s.doneCh) })

	s.cleanup()
	s.finish(CloseNormal, "remote closed")
}

// openShell opens the channel, requests the PTY, and starts the shell. Each
// step reports its own stage so a Windows console host that refuses a PTY is
// distinguishable from one that refuses the channel.
func (s *Session) openShell(client *ssh.Client) (stdout, stderr io.Reader, err error) {
	done := make(chan struct{})
	// Bounds the whole setup: a server that accepts the channel and never
	// answers the PTY request would otherwise block forever.
	go func() {
		select {
		case <-done:
		case <-s.closeCh:
		case <-time.After(sessionSetupTimeout):
			client.Close()
		}
	}()
	defer close(done)

	sess, err := client.NewSession()
	if err != nil {
		client.Close()
		s.failStage(CodePtyFailed, StageChannel, err)
		return nil, nil, err
	}
	stdin, err := sess.StdinPipe()
	if err != nil {
		sess.Close()
		client.Close()
		s.failStage(CodePtyFailed, StageChannel, err)
		return nil, nil, err
	}
	// The pipes must be set up before the shell starts.
	stdout, err = sess.StdoutPipe()
	if err != nil {
		sess.Close()
		client.Close()
		s.failStage(CodePtyFailed, StageChannel, err)
		return nil, nil, err
	}
	stderr, err = sess.StderrPipe()
	if err != nil {
		sess.Close()
		client.Close()
		s.failStage(CodePtyFailed, StageChannel, err)
		return nil, nil, err
	}

	if err = sess.RequestPty("xterm-256color", s.cfg.Rows, s.cfg.Cols, ptyModes()); err != nil {
		sess.Close()
		client.Close()
		s.failStage(CodePtyFailed, StagePty, err)
		return nil, nil, err
	}
	if err = sess.Shell(); err != nil {
		sess.Close()
		client.Close()
		s.failStage(CodePtyFailed, StageShell, err)
		return nil, nil, err
	}

	s.stdinMu.Lock()
	s.stdin = stdin
	s.stdinMu.Unlock()
	s.clientMu.Lock()
	s.sess = sess
	s.clientMu.Unlock()
	return stdout, stderr, nil
}

// ptyModes is the terminal mode set requested with the PTY.
//
// Deliberately minimal. The previous set forced ECHO, ICANON, and ISIG on,
// which describes a canonical Unix line discipline. A Windows console host
// does its own echo and line editing, so asserting those modes fights it.
// Leaving the set empty lets each server apply its own defaults, which is what
// the x/crypto examples and OpenSSH's own client effectively do.
func ptyModes() ssh.TerminalModes {
	return ssh.TerminalModes{}
}

// pump forwards one stream into the serialized output callback.
func (s *Session) pump(r io.Reader) {
	defer s.readGroup.Done()
	if r == nil {
		return
	}
	buf := make([]byte, 16*1024)
	for {
		n, rerr := r.Read(buf)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			s.emitData(chunk)
		}
		if rerr != nil {
			return
		}
	}
}

// emitData delivers output under a lock, so the two readers cannot interleave
// within a chunk and the Kotlin listener still observes one ordered stream.
func (s *Session) emitData(data []byte) {
	s.emitMu.Lock()
	defer s.emitMu.Unlock()
	s.listener.OnData(data)
}

func (s *Session) emitCallback(fn func()) {
	s.emitMu.Lock()
	defer s.emitMu.Unlock()
	fn()
}

// startHandshakeWatchdog closes the raw connection if the handshake stalls,
// which is what unblocks ssh.NewClientConn. The returned stop function reports
// whether the watchdog fired.
func (s *Session) startHandshakeWatchdog(conn net.Conn) func() bool {
	stop := make(chan struct{})
	fired := make(chan struct{})
	go func() {
		deadline := time.NewTimer(handshakeTimeout)
		defer deadline.Stop()
		for {
			select {
			case <-stop:
				return
			case <-s.closeCh:
				return
			case <-deadline.C:
				// A host-key prompt is open, so the user is the one taking
				// time. Re-arm and check again rather than killing it.
				if s.promptOpen() {
					deadline.Reset(handshakeTimeout)
					continue
				}
				close(fired)
				conn.Close()
				return
			}
		}
	}()
	return func() bool {
		select {
		case <-fired:
			return true
		default:
		}
		close(stop)
		select {
		case <-fired:
			return true
		default:
			return false
		}
	}
}

// startKeepalive sends an OpenSSH keepalive global request while idle. A
// bounded run of failures is treated as connection loss and closes the client,
// which ends the read loops and produces the single terminal event.
func (s *Session) startKeepalive(client *ssh.Client) {
	go func() {
		ticker := time.NewTicker(keepaliveInterval)
		defer ticker.Stop()
		failures := 0
		for {
			select {
			case <-s.closeCh:
				return
			case <-s.doneCh:
				return
			case <-ticker.C:
				// The reply is discarded: it is a liveness probe, not data, and
				// must never reach the terminal.
				_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
				if err != nil {
					failures++
					if failures >= keepaliveMaxFailures {
						client.Close()
						return
					}
					continue
				}
				failures = 0
			}
		}
	}()
}

// hostKeyCallback blocks the handshake until the app decides. The handshake
// watchdog pauses while this is open, so only the prompt bound applies.
func (s *Session) hostKeyCallback(hostname string, _ net.Addr, key ssh.PublicKey) error {
	alg, fp := hostKeyFingerprint(key)

	s.setPrompted(true)
	defer s.setPrompted(false)

	s.listener.OnHostKey(alg, fp)
	select {
	case accept := <-s.decideCh:
		if accept {
			return nil
		}
		return errors.New("host key rejected")
	case <-s.closeCh:
		return errors.New("session closed")
	case <-time.After(hostKeyPromptTimeout):
		return errors.New("host key prompt timed out")
	}
}

func (s *Session) setPrompted(v bool) {
	s.promptMu.Lock()
	s.prompted = v
	s.promptMu.Unlock()
}

func (s *Session) promptOpen() bool {
	s.promptMu.Lock()
	defer s.promptMu.Unlock()
	return s.prompted
}

// DecideHostKey answers a pending host-key challenge. Safe from any thread; a
// call with no pending challenge is ignored.
func (s *Session) DecideHostKey(accept bool) {
	select {
	case s.decideCh <- accept:
	default:
	}
}

// Write sends terminal input to the remote PTY. A no-op before the session is
// live or after close.
func (s *Session) Write(data []byte) {
	s.stdinMu.Lock()
	defer s.stdinMu.Unlock()
	if s.stdin != nil {
		_, _ = s.stdin.Write(data)
	}
}

// WindowChange sends a real SSH window-change request. cols/rows are the new
// dimensions; the underlying ssh API takes (height, width), so they are
// swapped here.
func (s *Session) WindowChange(cols, rows int) {
	s.clientMu.Lock()
	defer s.clientMu.Unlock()
	if s.sess != nil {
		_ = s.sess.WindowChange(rows, cols)
	}
}

// Close tears down the session. Idempotent; it emits exactly one terminal
// event via the read loop or the close path.
func (s *Session) Close() {
	s.closeOnce.Do(func() {
		close(s.closeCh)
		s.cleanup()
		s.finish(CloseGoingAway, "user close")
	})
}

func (s *Session) cleanup() {
	s.clientMu.Lock()
	if s.sess != nil {
		_ = s.sess.Close()
		s.sess = nil
	}
	if s.client != nil {
		_ = s.client.Close()
		s.client = nil
	}
	s.clientMu.Unlock()
	s.stdinMu.Lock()
	s.stdin = nil
	s.stdinMu.Unlock()
}

// finish reports the single terminal close event.
func (s *Session) finish(code int, reason string) {
	s.terminalOnce.Do(func() {
		s.listener.OnClosed(code, reason)
	})
}

// failStage reports a terminal pre-live failure with the stage that failed.
func (s *Session) failStage(code, stage string, err error) {
	msg := ""
	if err != nil {
		msg = err.Error()
	}
	s.terminalOnce.Do(func() {
		if sl, ok := s.listener.(StageListener); ok {
			sl.OnFailureStage(code, stage, msg)
			return
		}
		s.listener.OnFailure(code, msg)
	})
}

// handshakeFail maps a handshake error to an SshCode and a stage.
func (s *Session) handshakeFail(err error) {
	msg := err.Error()
	lower := strings.ToLower(msg)
	code := CodeConnectFailed
	stage := StageHandshake
	switch {
	case strings.Contains(lower, "host key rejected"):
		code, stage = CodeHostKeyRejected, StageHostKey
	case strings.Contains(lower, "host key prompt timed out"):
		code, stage = CodeTimeout, StageTimeout
	case strings.Contains(lower, "session closed"):
		code, stage = CodeCancelled, StageCancelled
	case isAuthError(err):
		code, stage = CodeAuthFailed, StageAuth
	case strings.Contains(lower, "handshake failed"):
		code, stage = CodeAuthFailed, StageHandshake
	}
	s.failStage(code, stage, err)
}

func (s *Session) signer() (ssh.Signer, error) {
	if len(s.cfg.PrivateKey) > 0 {
		return parsePrivateKey(s.cfg.PrivateKey, s.cfg.Passphrase)
	}
	return nil, nil
}

// authMethods builds the offered methods.
//
// Windows OpenSSH is frequently configured with PasswordAuthentication off and
// KbdInteractiveAuthentication on, which rejects a password-only client. The
// keyboard-interactive method answers the server's password prompt with the
// same secret, so a Windows host with the default policy authenticates.
func (s *Session) authMethods(signer ssh.Signer) []ssh.AuthMethod {
	var methods []ssh.AuthMethod
	if len(s.cfg.PrivateKey) > 0 && signer != nil {
		methods = append(methods, ssh.PublicKeys(signer))
	}
	if s.cfg.Password != "" {
		password := s.cfg.Password
		methods = append(methods, ssh.Password(password))
		methods = append(methods, ssh.KeyboardInteractive(passwordChallenge(password)))
	}
	return methods
}

// passwordChallenge answers a keyboard-interactive password prompt.
//
// It only ever answers a prompt that asks for a password, and only a bounded
// number of them. An unrecognized challenge is left blank rather than answered
// with the password, so a server cannot phrase a question that extracts the
// secret for something other than authentication.
func passwordChallenge(password string) ssh.KeyboardInteractiveChallenge {
	return func(name, instruction string, questions []string, echos []bool) ([]string, error) {
		if len(questions) == 0 {
			// A bare informational message. Answering nothing is correct.
			return nil, nil
		}
		if len(questions) > maxChallengeQuestions {
			return nil, errors.New("too many authentication questions")
		}
		answers := make([]string, len(questions))
		for i, q := range questions {
			// An echoed prompt is not a secret prompt, so the password is never
			// sent in reply to one.
			if i < len(echos) && echos[i] {
				continue
			}
			if isPasswordPrompt(q) {
				answers[i] = password
			}
		}
		return answers, nil
	}
}

const maxChallengeQuestions = 4

func isPasswordPrompt(q string) bool {
	lower := strings.ToLower(q)
	return strings.Contains(lower, "password") || strings.Contains(lower, "passcode")
}
