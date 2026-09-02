// Package localctl is the daemon's local control channel: a per-user,
// loopback-only socket that the CLI uses to manage pairing, devices, and
// sessions. It carries a single JSON request and a single JSON response per
// connection, then closes. It is not a trust boundary against other local
// users on shared machines beyond the socket's file permissions; it is the
// same user's own control plane.
package localctl

import (
	"bytes"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"path/filepath"
	"runtime"
	"sync"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
)

// Timeouts and size bounds for one control exchange.
const (
	dialTimeout      = 2 * time.Second
	connTimeout      = 5 * time.Second
	maxRequestBytes  = 1 << 20
	maxResponseBytes = 1 << 20
)

// Path returns the local control endpoint for the platform: a Unix domain
// socket under the data directory, or a named pipe on Windows.
func Path(dataDir string) string {
	if runtime.GOOS == "windows" {
		return `\\.\pipe\remotly`
	}
	return filepath.Join(dataDir, "remotly.sock")
}

// Request is one localctl operation.
type Request struct {
	// Op selects the operation: "pair", "devices", "revoke", "sessions",
	// "session_kill", "session_create", "attach", "status".
	Op string `json:"op"`
	// Public is the base64url-encoded 32-byte device public key, used by
	// "revoke".
	Public string `json:"public,omitempty"`
	// SessionID is the session id, used by "session_kill" and "attach".
	SessionID string `json:"session_id,omitempty"`
	// Command is the program to run, used by "session_create". Empty starts
	// a shell.
	Command string `json:"command,omitempty"`
	// Title labels a created session in the app and the session list.
	Title string `json:"title,omitempty"`
	// Cols and Rows are the initial PTY size for "session_create".
	Cols uint16 `json:"cols,omitempty"`
	Rows uint16 `json:"rows,omitempty"`
}

// DeviceOut is a paired device as reported to the CLI.
type DeviceOut struct {
	Public   string    `json:"public"`
	Name     string    `json:"name"`
	PairedAt time.Time `json:"paired_at"`
}

// SessionOut is a session as reported to the CLI.
type SessionOut struct {
	ID      string    `json:"id"`
	Title   string    `json:"title"`
	Kind    string    `json:"kind"`
	Command string    `json:"command"`
	Cwd     string    `json:"cwd"`
	Cols    uint16    `json:"cols"`
	Rows    uint16    `json:"rows"`
	Created time.Time `json:"created_at"`
	Active  time.Time `json:"last_activity"`
	Running bool      `json:"running"`
}

// Response is the result of one localctl operation. Fields that do not apply
// to the operation are zero or empty. Err is a short, bounded message that
// never carries key material or secrets.
type Response struct {
	OK  bool   `json:"ok"`
	Err string `json:"error,omitempty"`

	// "pair"
	URI     string `json:"uri,omitempty"`
	Expires int64  `json:"expires,omitempty"`

	// "devices"
	Devices []DeviceOut `json:"devices,omitempty"`

	// "sessions"
	Sessions []SessionOut `json:"sessions,omitempty"`

	// "session_create"
	SessionID string `json:"session_id,omitempty"`

	// "status"
	ActiveTokens  int  `json:"active_tokens,omitempty"`
	PairedDevices int  `json:"paired_devices,omitempty"`
	LANAllowed    bool `json:"lan_allowed,omitempty"`
}

// BuildURIFunc mints a pairing token and returns the pairing URI and the
// token's expiry (unix seconds). The daemonapp supplies it so that hint
// gathering and identity binding stay out of this package.
type BuildURIFunc func() (uri string, expires int64, err error)

// Server is the local control listener. It is safe to start once and close
// once.
type Server struct {
	path     string
	log      interface{ Info(string, ...any) }
	tokens   *pairing.TokenManager
	devices  *pairing.DeviceStore
	sessions *session.Manager
	buildURI BuildURIFunc

	// onDevicesChanged, if set, is called after a successful revoke with the
	// revoked device's public key, so the daemon can drop that device's live
	// connections and re-evaluate the LAN listener gate.
	onDevicesChanged func(pub [32]byte)

	// onTokenIssued, if set, is called after a pairing token is minted. A new
	// token opens the LAN gate, and the app tries to connect as soon as the
	// URI is on screen, so the listener has to open now rather than at the
	// next safety poll.
	onTokenIssued func()

	mu sync.Mutex
	ln net.Listener
}

// SetOnDevicesChanged installs the callback fired after a successful revoke,
// passing the revoked device's public key. It is safe to call before Start.
func (s *Server) SetOnDevicesChanged(f func(pub [32]byte)) {
	s.onDevicesChanged = f
}

// SetOnTokenIssued installs the callback fired after a pairing token is
// minted. It is safe to call before Start.
func (s *Server) SetOnTokenIssued(f func()) {
	s.onTokenIssued = f
}

// NewServer assembles a local control server over the given state. log may be
// nil, in which case logging is suppressed.
func NewServer(path string, log interface{ Info(string, ...any) }, tokens *pairing.TokenManager, devices *pairing.DeviceStore, sessions *session.Manager, buildURI BuildURIFunc) *Server {
	return &Server{path: path, log: log, tokens: tokens, devices: devices, sessions: sessions, buildURI: buildURI}
}

// Start begins listening and serving. It returns once the listener is ready.
// Starting an already-started server is an error.
func (s *Server) Start() error {
	ln, err := listen(s.path)
	if err != nil {
		return fmt.Errorf("localctl: %w", err)
	}
	s.mu.Lock()
	if s.ln != nil {
		s.mu.Unlock()
		_ = ln.Close()
		return errors.New("localctl: already started")
	}
	s.ln = ln
	s.mu.Unlock()
	go s.acceptLoop(ln)
	if s.log != nil {
		s.log.Info("localctl listening", "path", s.path)
	}
	return nil
}

// Close stops the listener and removes the socket file (Unix).
func (s *Server) Close() error {
	s.mu.Lock()
	ln := s.ln
	s.ln = nil
	s.mu.Unlock()
	var err error
	if ln != nil {
		err = ln.Close()
	}
	removeSocket(s.path)
	return err
}

// acceptLoop serves connections until the listener is closed. It holds its own
// reference to the listener rather than reading s.ln, which Close clears
// concurrently.
func (s *Server) acceptLoop(ln net.Listener) {
	for {
		conn, err := ln.Accept()
		if err != nil {
			return
		}
		go s.handleConn(conn)
	}
}

func (s *Server) handleConn(conn net.Conn) {
	defer conn.Close()
	_ = conn.SetReadDeadline(time.Now().Add(connTimeout))
	dec := json.NewDecoder(io.LimitReader(conn, maxRequestBytes))
	var req Request
	if err := dec.Decode(&req); err != nil {
		s.writeResponse(conn, Response{OK: false, Err: "bad request"})
		return
	}
	_ = conn.SetReadDeadline(time.Time{})
	if req.Op == "attach" {
		sess, err := s.attachTarget(req)
		if err != nil {
			s.writeResponse(conn, Response{OK: false, Err: err.Error()})
			return
		}
		s.writeResponse(conn, Response{OK: true})
		s.serveAttach(conn, streamAfter(dec, conn), sess)
		return
	}
	s.writeResponse(conn, s.dispatch(req))
}

// attachTarget resolves the session an attach request names.
func (s *Server) attachTarget(req Request) (*session.Session, error) {
	if req.SessionID == "" {
		return nil, errors.New("missing session id")
	}
	return s.sessions.Get(req.SessionID)
}

// streamAfter returns the frame stream that follows a decoded request.
//
// Decode stops at the closing brace, so the newline json.Encoder writes after
// every document is still buffered. Handing that to the frame reader made it
// take 0x0a as a frame kind and the next four bytes as a length, which blew
// past the payload bound and killed the attach on the client's first frame.
// Leading whitespace is therefore dropped before the stream starts; it can
// only ever be request terminators.
//
// rest is an io.Reader rather than the connection so the framing rule can be
// tested without a socket.
func streamAfter(dec *json.Decoder, rest io.Reader) io.Reader {
	buffered, err := io.ReadAll(dec.Buffered())
	if err != nil {
		return rest
	}
	buffered = bytes.TrimLeft(buffered, " \t\r\n")
	if len(buffered) == 0 {
		return rest
	}
	return io.MultiReader(bytes.NewReader(buffered), rest)
}

func (s *Server) writeResponse(conn net.Conn, resp Response) {
	_ = conn.SetWriteDeadline(time.Now().Add(connTimeout))
	_ = json.NewEncoder(conn).Encode(resp)
}

func (s *Server) dispatch(req Request) Response {
	switch req.Op {
	case "pair":
		if s.buildURI == nil {
			return Response{OK: false, Err: "pairing not available"}
		}
		uri, expires, err := s.buildURI()
		if err != nil {
			return Response{OK: false, Err: err.Error()}
		}
		if s.onTokenIssued != nil {
			s.onTokenIssued()
		}
		return Response{OK: true, URI: uri, Expires: expires}
	case "devices":
		devs := s.devices.List()
		out := make([]DeviceOut, 0, len(devs))
		for _, d := range devs {
			out = append(out, DeviceOut{
				Public:   base64.RawURLEncoding.EncodeToString(d.Public[:]),
				Name:     d.Name,
				PairedAt: d.PairedAt,
			})
		}
		return Response{OK: true, Devices: out}
	case "revoke":
		var pub [32]byte
		decoded, err := base64.RawURLEncoding.DecodeString(req.Public)
		if err != nil || len(decoded) != 32 {
			return Response{OK: false, Err: "bad public key"}
		}
		copy(pub[:], decoded)
		if err := s.devices.Revoke(pub); err != nil {
			return Response{OK: false, Err: err.Error()}
		}
		if s.onDevicesChanged != nil {
			s.onDevicesChanged(pub)
		}
		return Response{OK: true}
	case "sessions":
		metas := s.sessions.List()
		out := make([]SessionOut, 0, len(metas))
		for _, m := range metas {
			out = append(out, SessionOut{
				ID:      m.ID,
				Title:   m.Title,
				Kind:    string(m.Kind),
				Command: m.Command,
				Cwd:     m.Cwd,
				Cols:    m.Cols,
				Rows:    m.Rows,
				Created: m.CreatedAt,
				Active:  m.LastActivity,
				Running: m.Running,
			})
		}
		return Response{OK: true, Sessions: out}
	case "session_kill":
		if req.SessionID == "" {
			return Response{OK: false, Err: "missing session id"}
		}
		sess, err := s.sessions.Get(req.SessionID)
		if err != nil {
			return Response{OK: false, Err: err.Error()}
		}
		if err := sess.Kill(); err != nil {
			return Response{OK: false, Err: err.Error()}
		}
		return Response{OK: true}
	case "session_create":
		// An empty command is a plain shell; anything else runs as an agent
		// session, which is the kind that carries a command line.
		kind := session.KindShell
		if req.Command != "" {
			kind = session.KindAgent
		}
		sess, err := s.sessions.Create(session.Request{
			Kind:    kind,
			Title:   req.Title,
			Command: req.Command,
			Cols:    req.Cols,
			Rows:    req.Rows,
		})
		if err != nil {
			return Response{OK: false, Err: err.Error()}
		}
		return Response{OK: true, SessionID: sess.ID()}
	case "status":
		at := s.tokens.ActiveCount()
		pd := s.devices.ActiveCount()
		return Response{OK: true, ActiveTokens: at, PairedDevices: pd, LANAllowed: at > 0 || pd > 0}
	default:
		return Response{OK: false, Err: "unknown op"}
	}
}

// Call connects to the local control endpoint, sends one request, and returns
// the response. It is the CLI-side entry point.
func Call(path string, req Request) (Response, error) {
	conn, err := dial(path, dialTimeout)
	if err != nil {
		return Response{}, fmt.Errorf("localctl: connect: %w", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(connTimeout))
	if err := json.NewEncoder(conn).Encode(req); err != nil {
		return Response{}, fmt.Errorf("localctl: send: %w", err)
	}
	var resp Response
	if err := json.NewDecoder(io.LimitReader(conn, maxResponseBytes)).Decode(&resp); err != nil {
		return Response{}, fmt.Errorf("localctl: read: %w", err)
	}
	return resp, nil
}
