package localctl

import (
	"encoding/binary"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"sync"
	"syscall"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/session"
)

// The local attach stream.
//
// A localctl connection normally carries one JSON request and one JSON
// response, then closes. An attach cannot work that way: it is a live
// bidirectional terminal for as long as the user stays on it. So the "attach"
// op switches the connection into a framed stream after its JSON response,
// and both sides speak frames from then on.
//
// The framing is deliberately trivial: a one-byte kind, a four-byte big-endian
// length, then the payload. It carries terminal bytes between two processes of
// the same user over a 0700 socket, so there is nothing to negotiate and
// nothing to authenticate that the socket permissions have not already
// settled.
const (
	// FrameOutput carries PTY output, daemon to client.
	FrameOutput byte = 1
	// FrameInput carries terminal input, client to daemon.
	FrameInput byte = 2
	// FrameResize carries a four-byte cols/rows pair, client to daemon.
	FrameResize byte = 3
	// FrameExit reports that the session's process ended, daemon to client.
	FrameExit byte = 4
)

// maxFramePayload bounds one frame. Terminal writes are far smaller; the
// bound exists so a corrupt length cannot allocate without limit.
const maxFramePayload = 1 << 20

// resizePayloadLen is the fixed width of a resize frame: two uint16s.
const resizePayloadLen = 4

// ErrStreamClosed reports that the peer ended the stream.
var ErrStreamClosed = errors.New("localctl: stream closed")

// WriteFrame writes one frame. It is not safe for concurrent use on the same
// writer; callers serialize with a mutex.
func WriteFrame(w io.Writer, kind byte, payload []byte) error {
	if len(payload) > maxFramePayload {
		return fmt.Errorf("localctl: frame payload %d exceeds %d", len(payload), maxFramePayload)
	}
	var hdr [5]byte
	hdr[0] = kind
	binary.BigEndian.PutUint32(hdr[1:], uint32(len(payload)))
	if _, err := w.Write(hdr[:]); err != nil {
		return err
	}
	if len(payload) == 0 {
		return nil
	}
	_, err := w.Write(payload)
	return err
}

// ReadFrame reads one frame. The returned slice is freshly allocated and the
// caller owns it.
func ReadFrame(r io.Reader) (byte, []byte, error) {
	var hdr [5]byte
	if _, err := io.ReadFull(r, hdr[:]); err != nil {
		return 0, nil, err
	}
	n := binary.BigEndian.Uint32(hdr[1:])
	if n > maxFramePayload {
		return 0, nil, fmt.Errorf("localctl: frame payload %d exceeds %d", n, maxFramePayload)
	}
	if n == 0 {
		return hdr[0], nil, nil
	}
	buf := make([]byte, n)
	if _, err := io.ReadFull(r, buf); err != nil {
		return 0, nil, err
	}
	return hdr[0], buf, nil
}

// EncodeResize renders a resize payload.
func EncodeResize(cols, rows uint16) []byte {
	b := make([]byte, resizePayloadLen)
	binary.BigEndian.PutUint16(b[0:], cols)
	binary.BigEndian.PutUint16(b[2:], rows)
	return b
}

// DecodeResize parses a resize payload.
func DecodeResize(b []byte) (cols, rows uint16, err error) {
	if len(b) != resizePayloadLen {
		return 0, 0, fmt.Errorf("localctl: resize payload is %d bytes, want %d", len(b), resizePayloadLen)
	}
	return binary.BigEndian.Uint16(b[0:]), binary.BigEndian.Uint16(b[2:]), nil
}

// serveAttach streams a session over conn until either end goes away.
//
// r is the input side: it is not conn itself, because the request decoder may
// have buffered bytes the client already sent. conn stays the write side.
//
// The connection has already carried its JSON response by this point, so the
// read deadline is cleared: an idle terminal is normal and must not time out.
func (s *Server) serveAttach(conn net.Conn, r io.Reader, sess *session.Session) {
	_ = conn.SetReadDeadline(time.Time{})
	_ = conn.SetWriteDeadline(time.Time{})

	at, _, err := sess.Attach()
	if err != nil {
		return
	}
	defer at.Close()

	// Writer ownership is by phase, not by lock. The goroutine below is the
	// only writer until it closes done; the FrameExit write happens after
	// <-done, so the two never overlap. Keep that ordering if this changes.
	done := make(chan struct{})
	go func() {
		defer close(done)
		buf := make([]byte, 32*1024)
		for {
			n, rerr := at.Read(buf)
			if n > 0 {
				if err := WriteFrame(conn, FrameOutput, buf[:n]); err != nil {
					return
				}
			}
			if rerr != nil {
				return
			}
		}
	}()

	// The output stream ending means the session's process exited. Closing the
	// connection wakes the input loop below, which is otherwise blocked on a
	// client that has no reason to send anything: without this the client sat
	// waiting on a session that was already gone, showing a dead screen it
	// could not leave.
	go func() {
		<-done
		_ = conn.Close()
	}()

	// Input runs on this goroutine. A read error means the client is gone,
	// which ends the attach but never the session: the whole point is that
	// the process outlives whoever is looking at it.
input:
	for {
		kind, payload, err := ReadFrame(r)
		if err != nil {
			break
		}
		switch kind {
		case FrameInput:
			// Labelled: a bare break would leave the switch and spin this
			// loop against a session that can no longer take input.
			if _, err := sess.Write(payload); err != nil {
				break input
			}
		case FrameResize:
			cols, rows, err := DecodeResize(payload)
			if err != nil {
				continue
			}
			_ = sess.Resize(cols, rows)
		}
	}

	// Ends the output goroutine so the exit frame is written by this
	// goroutine alone. The deferred Close above is idempotent.
	at.Close()
	<-done
	// Best effort: the client may already be gone, which is the common case,
	// and the connection is closed outright when the session exited.
	_ = WriteFrame(conn, FrameExit, nil)
}

// AttachStream is the client side of a local attach: a live terminal on a
// daemon session over the local control socket.
type AttachStream struct {
	conn net.Conn
	mu   sync.Mutex
}

// DialAttach opens a session's local attach stream. sessionID must name a
// session the daemon already has.
func DialAttach(path, sessionID string) (*AttachStream, error) {
	conn, err := dial(path, dialTimeout)
	if err != nil {
		return nil, err
	}
	if err := writeAttachRequest(conn, Request{Op: "attach", SessionID: sessionID}); err != nil {
		_ = conn.Close()
		return nil, err
	}
	return &AttachStream{conn: conn}, nil
}

// writeAttachRequest sends the opening request and reads the JSON response
// that decides whether the stream follows.
func writeAttachRequest(conn net.Conn, req Request) error {
	_ = conn.SetWriteDeadline(time.Now().Add(connTimeout))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return err
	}
	_ = conn.SetWriteDeadline(time.Time{})
	_ = conn.SetReadDeadline(time.Now().Add(connTimeout))
	// Decoding from the connection directly would let the JSON decoder buffer
	// past the response and swallow the first frames of the stream, so the
	// response is read byte-wise up to its closing newline.
	line, err := readJSONLine(conn)
	if err != nil {
		return err
	}
	_ = conn.SetReadDeadline(time.Time{})
	var resp Response
	if err := json.Unmarshal(line, &resp); err != nil {
		return fmt.Errorf("localctl: bad response: %w", err)
	}
	if !resp.OK {
		return errors.New(resp.Err)
	}
	return nil
}

// readJSONLine reads one newline-terminated JSON document without reading
// past it. json.Encoder terminates every document with a newline, which is
// what makes this safe.
func readJSONLine(r io.Reader) ([]byte, error) {
	buf := make([]byte, 0, 256)
	one := make([]byte, 1)
	for len(buf) < maxResponseBytes {
		if _, err := io.ReadFull(r, one); err != nil {
			return nil, err
		}
		if one[0] == '\n' {
			return buf, nil
		}
		buf = append(buf, one[0])
	}
	return nil, errors.New("localctl: response too large")
}

// Read returns the next output chunk. It reports ErrStreamClosed when the
// session's process has exited.
func (a *AttachStream) Read() ([]byte, error) {
	for {
		kind, payload, err := ReadFrame(a.conn)
		if err != nil {
			// The daemon closes the connection when the session ends, so a
			// reset is the ordinary end of an attach rather than a failure:
			// reporting it verbatim showed the user a socket error where the
			// session had simply finished.
			if errors.Is(err, io.EOF) || errors.Is(err, io.ErrUnexpectedEOF) ||
				errors.Is(err, net.ErrClosed) || errors.Is(err, syscall.ECONNRESET) {
				return nil, ErrStreamClosed
			}
			return nil, err
		}
		switch kind {
		case FrameOutput:
			if len(payload) == 0 {
				continue
			}
			return payload, nil
		case FrameExit:
			return nil, ErrStreamClosed
		}
	}
}

// Write sends terminal input.
func (a *AttachStream) Write(b []byte) error {
	if len(b) == 0 {
		return nil
	}
	a.mu.Lock()
	defer a.mu.Unlock()
	return WriteFrame(a.conn, FrameInput, b)
}

// Resize reports a new terminal size.
func (a *AttachStream) Resize(cols, rows uint16) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	return WriteFrame(a.conn, FrameResize, EncodeResize(cols, rows))
}

// Close ends the attach. The session keeps running.
func (a *AttachStream) Close() error { return a.conn.Close() }

// CreateLocalSession asks the daemon to start a session and returns its id.
// An empty command starts a shell.
func CreateLocalSession(path, command, title string, cols, rows uint16) (string, error) {
	resp, err := Call(path, Request{
		Op:      "session_create",
		Command: command,
		Title:   title,
		Cols:    cols,
		Rows:    rows,
	})
	if err != nil {
		return "", err
	}
	if !resp.OK {
		return "", errors.New(resp.Err)
	}
	if resp.SessionID == "" {
		return "", errors.New("localctl: daemon returned no session id")
	}
	return resp.SessionID, nil
}

// TerminalSize reports the size of f, falling back to the session defaults
// when it is not a terminal.
func TerminalSize(f *os.File, sizeOf func(fd int) (int, int, error)) (uint16, uint16) {
	w, h, err := sizeOf(int(f.Fd()))
	if err != nil || w <= 0 || h <= 0 {
		return session.DefaultCols, session.DefaultRows
	}
	return uint16(w), uint16(h)
}
