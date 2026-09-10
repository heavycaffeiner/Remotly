package sshcore

// A held SSH connection for control commands, and the streaming twin of Exec.
//
// Exec dials, runs one command, and disconnects. That costs a full handshake
// per call, which is most of the time a herdr control call takes from a phone.
// Control dials once and runs each command as a channel on the same
// connection, and it can also start a command that keeps printing and have its
// lines delivered as they arrive.
//
// The host-key policy is the caller's: the challenge is reported through
// ExecListener and the connect blocks until DecideHostKey answers, exactly as
// Exec does.

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/crypto/ssh"
)

// ControlLines receives the output of a streaming command one line at a time,
// then exactly one OnClosed when the command or the connection ends.
type ControlLines interface {
	OnLine(line string)
	OnClosed(code, message string)
}

// Control is a reusable authenticated connection. Create with NewControl,
// Connect once, then Run or Stream from any thread. Close ends everything
// running on it.
type Control struct {
	listener ExecListener

	decideCh chan bool
	closeCh  chan struct{}

	closeOnce sync.Once

	mu     sync.Mutex
	client *ssh.Client
	// The one running stream, kept so a resubscribe can end the reader the
	// previous attempt left on the host.
	stream *ssh.Session
}

// NewControl creates a handle without connecting.
func NewControl(l ExecListener) *Control {
	return &Control{
		listener: l,
		decideCh: make(chan bool, 1),
		closeCh:  make(chan struct{}),
	}
}

// DecideHostKey answers a pending host-key challenge. Safe from any thread.
func (c *Control) DecideHostKey(accept bool) {
	select {
	case c.decideCh <- accept:
	default:
	}
}

// Connect dials and authenticates. An empty Code means the connection is up
// and Run may be called; anything else is a connect-level failure in the same
// vocabulary Exec reports. Command in cfg is ignored.
func (c *Control) Connect(cfg *ExecConfig) (result *ExecResult) {
	defer func() {
		if r := recover(); r != nil {
			result = &ExecResult{Code: CodeProtocol, Message: fmt.Sprintf("control: internal failure: %v", r)}
		}
	}()

	addr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))

	dialTimeout := time.Duration(cfg.ConnectTimeout) * time.Millisecond
	if dialTimeout <= 0 {
		dialTimeout = defaultDialTimeout
	}
	conn, err := net.DialTimeout("tcp", addr, dialTimeout)
	if err != nil {
		return failCode(CodeConnectFailed, err)
	}

	var signer ssh.Signer
	if len(cfg.PrivateKey) > 0 {
		signer, err = parsePrivateKey(cfg.PrivateKey, cfg.Passphrase)
		if err != nil {
			conn.Close()
			return failCode(CodeAuthFailed, err)
		}
	}

	authMethods := buildAuthMethods(cfg.Password, cfg.PrivateKey, signer)
	zeroExecCreds(cfg)

	clientConn, chans, reqs, err := ssh.NewClientConn(conn, addr, &ssh.ClientConfig{
		User:            cfg.User,
		Auth:            authMethods,
		HostKeyCallback: c.hostKeyCallback,
	})
	if err != nil {
		conn.Close()
		code, _ := handshakeCode(err)
		return failCode(code, err)
	}
	client := ssh.NewClient(clientConn, chans, reqs)

	c.mu.Lock()
	previous := c.client
	c.client = client
	c.mu.Unlock()
	if previous != nil {
		previous.Close()
	}

	// A control connection is idle between calls, which is exactly what a NAT
	// or an idle-timeout drops silently. The probe keeps it answerable and
	// closes it once it stops answering, so the next Run reports a broken
	// connection instead of blocking on a dead socket.
	c.startKeepalive(client)
	return &ExecResult{}
}

// Run executes one command on the held connection. timeoutMs bounds it; zero
// uses the default. A connect-level Code means the connection is unusable and
// the caller should reconnect.
func (c *Control) Run(command string, timeoutMs int) (result *ExecResult) {
	defer func() {
		if r := recover(); r != nil {
			result = &ExecResult{Code: CodeProtocol, Message: fmt.Sprintf("control: internal failure: %v", r)}
		}
	}()

	client := c.held()
	if client == nil {
		return &ExecResult{Code: CodeRemoteClosed, Message: "control: not connected"}
	}

	sess, err := client.NewSession()
	if err != nil {
		return failCode(CodeRemoteClosed, err)
	}
	defer sess.Close()

	var out, errb syncBuffer
	sess.Stdout = &out
	sess.Stderr = &errb

	var timedOut atomic.Bool
	timeout := time.Duration(timeoutMs) * time.Millisecond
	if timeout <= 0 {
		timeout = defaultCommandTimeout
	}
	timer := time.AfterFunc(timeout, func() {
		timedOut.Store(true)
		sess.Close()
	})
	defer timer.Stop()

	if err = sess.Start(command); err != nil {
		return failCode(CodeRemoteClosed, err)
	}
	waitErr := sess.Wait()

	if waitErr == nil {
		return &ExecResult{Stdout: out.bytes(), Stderr: errb.bytes()}
	}
	var sshExit *ssh.ExitError
	if errors.As(waitErr, &sshExit) {
		return &ExecResult{ExitCode: sshExit.ExitStatus(), Stdout: out.bytes(), Stderr: errb.bytes()}
	}
	code := CodeRemoteClosed
	if timedOut.Load() {
		code = CodeTimeout
	}
	return &ExecResult{Code: code, Message: waitErr.Error(), Stdout: out.bytes(), Stderr: errb.bytes()}
}

// Stream starts a command whose stdout is delivered to l line by line, and
// returns as soon as it has started. An empty Code means the command is
// running; l.OnClosed reports how it ended, once.
//
// One stream at a time per connection: starting a second ends the first. A
// stream runs a reader process on the host, and a caller that resubscribes
// (after a reader that never answered, say) must not leave the previous one
// running there.
func (c *Control) Stream(command string, l ControlLines) (result *ExecResult) {
	defer func() {
		if r := recover(); r != nil {
			result = &ExecResult{Code: CodeProtocol, Message: fmt.Sprintf("control: internal failure: %v", r)}
		}
	}()

	client := c.held()
	if client == nil {
		return &ExecResult{Code: CodeRemoteClosed, Message: "control: not connected"}
	}

	c.StopStream()

	sess, err := client.NewSession()
	if err != nil {
		return failCode(CodeRemoteClosed, err)
	}
	stdout, err := sess.StdoutPipe()
	if err != nil {
		sess.Close()
		return failCode(CodeRemoteClosed, err)
	}
	if err = sess.Start(command); err != nil {
		sess.Close()
		return failCode(CodeRemoteClosed, err)
	}

	c.mu.Lock()
	c.stream = sess
	c.mu.Unlock()
	go func() {
		defer sess.Close()
		// Closing the handle has to end a stream that is blocked on a read,
		// which only closing the channel does.
		done := make(chan struct{})
		defer close(done)
		go func() {
			select {
			case <-c.closeCh:
				sess.Close()
			case <-done:
			}
		}()

		reader := bufio.NewReader(stdout)
		for {
			line, err := reader.ReadString('\n')
			if len(line) > 0 {
				l.OnLine(trimNewline(line))
			}
			if err != nil {
				waitErr := sess.Wait()
				if errors.Is(err, io.EOF) && waitErr == nil {
					l.OnClosed("", "")
					return
				}
				l.OnClosed(CodeRemoteClosed, err.Error())
				return
			}
		}
	}()

	return &ExecResult{}
}

// StopStream ends the running stream, if there is one. Its ControlLines gets
// its single OnClosed from the reader goroutine, as it would for any other
// end.
func (c *Control) StopStream() {
	c.mu.Lock()
	sess := c.stream
	c.stream = nil
	c.mu.Unlock()
	if sess != nil {
		sess.Close()
	}
}

// Close ends every command on the connection and drops it.
func (c *Control) Close() {
	c.closeOnce.Do(func() { close(c.closeCh) })
	c.StopStream()
	c.mu.Lock()
	client := c.client
	c.client = nil
	c.mu.Unlock()
	if client != nil {
		client.Close()
	}
}

func (c *Control) held() *ssh.Client {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.client
}

func (c *Control) startKeepalive(client *ssh.Client) {
	go func() {
		ticker := time.NewTicker(keepaliveInterval)
		defer ticker.Stop()
		failures := 0
		for {
			select {
			case <-c.closeCh:
				return
			case <-ticker.C:
				if c.held() != client {
					return
				}
				_, _, err := client.SendRequest("keepalive@openssh.com", true, nil)
				if err == nil {
					failures = 0
					continue
				}
				failures++
				if failures >= keepaliveMaxFailures {
					client.Close()
					return
				}
			}
		}
	}()
}

func (c *Control) hostKeyCallback(_ string, _ net.Addr, key ssh.PublicKey) error {
	alg, fp := hostKeyFingerprint(key)
	c.listener.OnHostKey(alg, fp)
	select {
	case accept := <-c.decideCh:
		if !accept {
			return errors.New("host key rejected")
		}
		return nil
	case <-c.closeCh:
		return errors.New("host key decision cancelled")
	case <-time.After(hostKeyPromptTimeout):
		return errors.New("host key decision timed out")
	}
}

func failCode(code string, err error) *ExecResult {
	return &ExecResult{Code: code, Message: err.Error()}
}

func trimNewline(s string) string {
	for len(s) > 0 && (s[len(s)-1] == '\n' || s[len(s)-1] == '\r') {
		s = s[:len(s)-1]
	}
	return s
}

// The command timer closes the session from another goroutine while Wait is
// still writing what arrived, so the output buffers are guarded.
type syncBuffer struct {
	mu  sync.Mutex
	buf []byte
}

func (b *syncBuffer) Write(p []byte) (int, error) {
	b.mu.Lock()
	defer b.mu.Unlock()
	b.buf = append(b.buf, p...)
	return len(p), nil
}

func (b *syncBuffer) bytes() []byte {
	b.mu.Lock()
	defer b.mu.Unlock()
	out := make([]byte, len(b.buf))
	copy(out, b.buf)
	return out
}
