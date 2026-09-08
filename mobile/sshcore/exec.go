// One-shot command execution over SSH.
//
// A terminal session (Session) keeps a PTY shell open and streams bytes to a
// listener. Herdr control needs the opposite: run a command, collect its
// stdout and stderr, get the exit code, and disconnect. Exec does that in a
// single blocking call. It reuses the credential and host-key flow of the
// terminal and SFTP paths, so a first-use host key prompts exactly once and a
// changed key fails closed. The command is a single already-quoted string from
// the app; it is handed to the remote default shell verbatim.

package sshcore

import (
	"bytes"
	"errors"
	"fmt"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
)

// ExecListener reports a host-key challenge during an exec connect. The
// connect blocks until the caller answers through Exec.DecideHostKey (bounded
// by the 120s prompt), mirroring the terminal and SFTP flows.
type ExecListener interface {
	OnHostKey(algorithm, fingerprint string)
}

// ExecConfig is a one-shot exec request: the connection identity and the
// command to run. Exactly one of Password or PrivateKey must be set.
type ExecConfig struct {
	Host string
	User string
	Port int

	Password   string
	PrivateKey []byte // PEM (PKCS#8 or OpenSSH)
	Passphrase []byte

	// The command line for the remote default shell. The app has already
	// quoted its argument vector into this string; nothing here re-quotes it.
	Command string

	// ConnectTimeout bounds the TCP dial in ms; zero uses the default.
	// CommandTimeout bounds the command in ms; zero uses the default.
	ConnectTimeout int
	CommandTimeout int
}

// ExecResult is the outcome of an exec. When Code is empty the connect
// succeeded and ExitCode, Stdout, and Stderr describe the command. When Code
// is set the connect itself failed (dial, auth, host key, or channel) and
// Stdout/Stderr carry whatever output arrived before the break.
type ExecResult struct {
	ExitCode int
	Stdout   []byte
	Stderr   []byte
	Code     string
	Message  string
}

// Exec is one one-shot command over a fresh SSH connection. Create with
// NewExec, then call Run on a worker thread; it blocks until the command
// finishes or a connect failure is reported. Like the SFTP connection, an Exec
// is single-use: Run once, then discard.
type Exec struct {
	listener ExecListener

	decideCh  chan bool
	closeCh   chan struct{}
	closeOnce sync.Once
}

// NewExec creates a handle for an exec without connecting.
func NewExec(l ExecListener) *Exec {
	return &Exec{
		listener: l,
		decideCh: make(chan bool, 1),
		closeCh:  make(chan struct{}),
	}
}

// DecideHostKey answers a pending host-key challenge. Safe from any thread.
func (e *Exec) DecideHostKey(accept bool) {
	select {
	case e.decideCh <- accept:
	default:
	}
}

// Close cancels a running exec. It closes the connection, which ends the
// command (or a pending host-key prompt) and unblocks Run.
func (e *Exec) Close() {
	e.closeOnce.Do(func() { close(e.closeCh) })
}

// A hung remote command must not hold the bridge thread. Sixty seconds is far
// beyond any herdr control call and short of a terminal session's lifetime.
const defaultCommandTimeout = 60 * time.Second

// Run connects, runs the command on the remote default shell, and returns the
// result. It blocks for the connect plus the command.
func (e *Exec) Run(cfg *ExecConfig) (result *ExecResult, err error) {
	// A panic crossing the JNI boundary aborts the process; convert it to a
	// failure result the caller can surface.
	defer func() {
		if r := recover(); r != nil {
			result = &ExecResult{Code: CodeProtocol, Message: fmt.Sprintf("exec: internal failure: %v", r)}
			err = nil
		}
	}()

	addr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))

	dialTimeout := time.Duration(cfg.ConnectTimeout) * time.Millisecond
	if dialTimeout <= 0 {
		dialTimeout = defaultDialTimeout
	}
	conn, err := net.DialTimeout("tcp", addr, dialTimeout)
	if err != nil {
		return failResult(CodeConnectFailed, err)
	}

	var signer ssh.Signer
	if len(cfg.PrivateKey) > 0 {
		signer, err = parsePrivateKey(cfg.PrivateKey, cfg.Passphrase)
		if err != nil {
			conn.Close()
			return failResult(CodeAuthFailed, err)
		}
	}

	// Build the auth methods (which capture the credential values) before
	// zeroing the per-run buffers, then zero so nothing is retained. The
	// shared builder offers the keyboard-interactive fallback a password-only
	// Windows host needs.
	authMethods := buildAuthMethods(cfg.Password, cfg.PrivateKey, signer)
	zeroExecCreds(cfg)

	clientConfig := &ssh.ClientConfig{
		User:            cfg.User,
		Auth:            authMethods,
		HostKeyCallback: e.hostKeyCallback,
	}

	clientConn, chans, reqs, err := ssh.NewClientConn(conn, addr, clientConfig)
	if err != nil {
		conn.Close()
		code, _ := handshakeCode(err)
		return failResult(code, err)
	}
	client := ssh.NewClient(clientConn, chans, reqs)
	defer client.Close()

	sess, err := client.NewSession()
	if err != nil {
		return failResult(CodePtyFailed, err)
	}
	defer sess.Close()

	var out, errb bytes.Buffer
	sess.Stdout = &out
	sess.Stderr = &errb

	// Bound the command. The timer closes the session, which closes the
	// channel and makes Wait return; it also flags the outcome as a timeout so
	// it is not mistaken for a command that exited non-zero. Stopped on every
	// return so a fast command does not leak a timer that later fires on a
	// dead session.
	var timedOut atomic.Bool
	cmdTimeout := time.Duration(cfg.CommandTimeout) * time.Millisecond
	if cmdTimeout <= 0 {
		cmdTimeout = defaultCommandTimeout
	}
	timer := time.AfterFunc(cmdTimeout, func() {
		timedOut.Store(true)
		sess.Close()
	})
	defer timer.Stop()

	if err = sess.Start(cfg.Command); err != nil {
		return failResult(CodePtyFailed, err)
	}
	waitErr := sess.Wait()

	// A clean finish: Wait returns nil, the exit code comes from the session.
	if waitErr == nil {
		return &ExecResult{ExitCode: 0, Stdout: out.Bytes(), Stderr: errb.Bytes()}, nil
	}
	var sshExit *ssh.ExitError
	if errors.As(waitErr, &sshExit) {
		return &ExecResult{ExitCode: sshExit.ExitStatus(), Stdout: out.Bytes(), Stderr: errb.Bytes()}, nil
	}
	// A non-ExitError means the channel broke: our timeout fired, the app
	// closed the handle, or the remote dropped. Keep whatever output arrived;
	// the code lets the caller tell each apart from a non-zero exit.
	code := CodeRemoteClosed
	if timedOut.Load() {
		code = CodeTimeout
	}
	return &ExecResult{Code: code, Message: waitErr.Error(), Stdout: out.Bytes(), Stderr: errb.Bytes()}, nil
}

// hostKeyCallback blocks the handshake until the app decides, mirroring the
// terminal and SFTP flows.
func (e *Exec) hostKeyCallback(_ string, _ net.Addr, key ssh.PublicKey) error {
	alg, fp := hostKeyFingerprint(key)
	e.listener.OnHostKey(alg, fp)
	select {
	case accept := <-e.decideCh:
		if accept {
			return nil
		}
		return errors.New("host key rejected")
	case <-e.closeCh:
		return errors.New("session closed")
	case <-time.After(hostKeyPromptTimeout):
		return errors.New("host key prompt timed out")
	}
}

// zeroExecCreds clears the per-run credential buffers after the auth material
// is built, matching the app's consume-per-connect contract.
func zeroExecCreds(cfg *ExecConfig) {
	zeroByteSlice(cfg.PrivateKey)
	zeroByteSlice(cfg.Passphrase)
}


// failResult builds a connect-failure result with no command output. The error
// return is nil: the failure is in the result's Code, not a Go error, so the
// bound caller reads it through one shape.
func failResult(code string, err error) (*ExecResult, error) {
	return &ExecResult{Code: code, Message: err.Error()}, nil
}
