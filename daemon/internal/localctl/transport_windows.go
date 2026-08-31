//go:build windows

package localctl

import (
	"errors"
	"fmt"
	"io"
	"net"
	"sync"
	"time"

	"golang.org/x/sys/windows"
)

const (
	pipeTimeoutMs = 5000
	pipeBufSize   = 64 << 10
)

// pipeListener is a minimal net.Listener over a Windows named pipe. Each
// Accept creates a fresh pipe instance and blocks until a client connects, so
// connections are served one at a time. That matches the CLI's short-lived,
// single-request usage.
type pipeListener struct {
	path string

	mu     sync.Mutex
	closed bool
}

func listen(path string) (net.Listener, error) {
	return &pipeListener{path: path}, nil
}

func (l *pipeListener) Accept() (net.Conn, error) {
	l.mu.Lock()
	if l.closed {
		l.mu.Unlock()
		return nil, net.ErrClosed
	}
	l.mu.Unlock()

	h, err := windows.CreateNamedPipe(
		windows.StringToUTF16Ptr(l.path),
		windows.PIPE_ACCESS_DUPLEX,
		windows.PIPE_TYPE_BYTE|windows.PIPE_READMODE_BYTE|windows.PIPE_WAIT,
		1, // max instances
		pipeBufSize,
		pipeBufSize,
		pipeTimeoutMs,
		nil,
	)
	if err != nil {
		return nil, fmt.Errorf("create pipe: %w", err)
	}
	err = windows.ConnectNamedPipe(h, nil)
	if err != nil && !errors.Is(err, windows.ERROR_PIPE_CONNECTED) {
		_ = windows.CloseHandle(h)
		return nil, fmt.Errorf("connect pipe: %w", err)
	}
	return &pipeConn{h: h}, nil
}

func (l *pipeListener) Close() error {
	l.mu.Lock()
	l.closed = true
	l.mu.Unlock()
	return nil
}

func (l *pipeListener) Addr() net.Addr { return pipeAddr(l.path) }

type pipeAddr string

func (a pipeAddr) Network() string { return "namedpipe" }
func (a pipeAddr) String() string  { return string(a) }

// pipeConn is a net.Conn over one connected named pipe handle.
type pipeConn struct {
	h windows.Handle
}

func (c *pipeConn) Read(b []byte) (int, error) {
	var n uint32
	err := windows.ReadFile(c.h, b, &n, nil)
	if err != nil {
		if errors.Is(err, windows.ERROR_BROKEN_PIPE) {
			return int(n), io.EOF
		}
		return int(n), err
	}
	return int(n), nil
}

func (c *pipeConn) Write(b []byte) (int, error) {
	var n uint32
	err := windows.WriteFile(c.h, b, &n, nil)
	if err != nil {
		return int(n), err
	}
	return int(n), nil
}

func (c *pipeConn) Close() error { return windows.CloseHandle(c.h) }

func (c *pipeConn) LocalAddr() net.Addr  { return pipeAddr("") }
func (c *pipeConn) RemoteAddr() net.Addr { return pipeAddr("") }

// Deadlines are bounded by the pipe's default I/O timeout set at creation;
// explicit deadlines are not supported.
func (c *pipeConn) SetDeadline(time.Time) error      { return nil }
func (c *pipeConn) SetReadDeadline(time.Time) error  { return nil }
func (c *pipeConn) SetWriteDeadline(time.Time) error { return nil }

func dial(path string, _ time.Duration) (net.Conn, error) {
	h, err := windows.CreateFile(
		windows.StringToUTF16Ptr(path),
		windows.GENERIC_READ|windows.GENERIC_WRITE,
		0,
		nil,
		windows.OPEN_EXISTING,
		0,
		0,
	)
	if err != nil {
		return nil, fmt.Errorf("open pipe: %w", err)
	}
	return &pipeConn{h: h}, nil
}

func removeSocket(string) {}
