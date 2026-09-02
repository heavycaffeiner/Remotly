// Package transport implements the daemon's secure WebSocket endpoint: a
// binary WebSocket, the versioned Noise handshake (IK for paired devices,
// XXpsk0 for first-time pairing), hello authorization, and the protocol
// channel multiplexer bound to live sessions.
//
// Listener lifecycle: the loopback listener runs as configured, from start
// to shutdown. The LAN listener is gated by the listener-state rule and
// listens only while a pairing token is active or at least one device is
// paired; it opens and closes as that state changes, while already
// authenticated connections are left to run out their lifetime.
//
// Origin verification is intentionally skipped: the native client sends no
// Origin header, and the encrypted handshake is the authentication boundary.
// A browser-based attacker gets no further than a WebSocket upgrade it
// cannot complete.
//
// Every connection is bounded: a 10 second handshake deadline, 30 second
// read liveness with a 10 second pong deadline, a 16-connection cap, 64
// channels, 256-frame or 8 MiB channel queues, and 64 KiB control frames.
package transport

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"sync"
	"time"

	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/fsops"
	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
	"github.com/heavycaffeiner/remotly/daemon/internal/transfer"
)

// Tunables. They are variables, not constants, so tests can shorten them.
var (
	// handshakeTimeout bounds the whole encrypted handshake.
	handshakeTimeout = 10 * time.Second
	// readTimeout is the maximum idle time between inbound frames before the
	// daemon pings the peer.
	readTimeout = 30 * time.Second
	// pongDeadline bounds the liveness ping after a read timeout.
	pongDeadline = 10 * time.Second
	// gatePollInterval is the safety-net interval for the LAN gate when no
	// event or token expiry is pending.
	gatePollInterval = 30 * time.Second
	// closeGrace bounds how long Close waits for in-flight connections.
	closeGrace = 2 * time.Second
	// headerTimeout bounds the HTTP header read of a not-yet-upgraded
	// connection.
	headerTimeout = 10 * time.Second
)

// maxFrameLen bounds one inbound transport frame: the channel type byte, two
// 5-byte varints, a 1 MiB payload, and the 16-byte AEAD tag.
const maxFrameLen = 1 + 5 + 5 + protocol.MaxPayloadLen + 16

// Options wires the transport to the daemon's state.
type Options struct {
	// LoopbackAddr is the local listener address, e.g. "127.0.0.1:8787".
	// Port 0 picks a free port.
	LoopbackAddr string
	// LoopbackEnabled reports whether the local listener runs at all.
	LoopbackEnabled bool
	// LANAddr is the LAN listener address, e.g. "0.0.0.0:8788". Port 0 picks
	// a free port. The listener is gated by the listener-state rule.
	LANAddr string
	// LANEnabled reports whether LAN exposure is allowed at all; the gate
	// additionally requires a pairing token or a paired device.
	LANEnabled bool

	Identity *pairing.Identity
	Tokens   *pairing.TokenManager
	Devices  *pairing.DeviceStore
	Sessions *session.Manager
	// FS is the filesystem metadata service backing the fs.* operations.
	FS *fsops.FS
	// Transfers is the resumable file-transfer manager backing the
	// transfer.* operations and the file channel. May be nil to disable.
	Transfers *transfer.Manager
	// Presets is the daemon's configured session presets, reported by
	// preset.list. May be empty.
	Presets []protocol.Preset
	// DaemonName is reported in hello responses.
	DaemonName string
	// Log may be nil, in which case logging is suppressed.
	Log *slog.Logger
}

// Server owns both listeners and all active connections.
type Server struct {
	opts Options
	log  *slog.Logger

	ctx    context.Context
	cancel context.CancelFunc
	closed chan struct{}

	lnMu     sync.Mutex
	loopback net.Listener
	lan      net.Listener

	connMu sync.Mutex
	conns  map[*conn]struct{}
	slots  int // reserved connection slots: handshake in flight plus live

	gateCh   chan struct{}
	gateDone chan struct{}

	wg sync.WaitGroup
}

// NewServer assembles a transport server over the given state. It creates no
// listeners; Start does.
func NewServer(opts Options) *Server {
	ctx, cancel := context.WithCancel(context.Background())
	log := opts.Log
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	return &Server{
		opts:     opts,
		log:      log,
		ctx:      ctx,
		cancel:   cancel,
		closed:   make(chan struct{}),
		conns:    make(map[*conn]struct{}),
		gateCh:   make(chan struct{}, 1),
		gateDone: make(chan struct{}),
	}
}

// Start begins the loopback listener (if enabled), evaluates the LAN gate,
// and starts the gate loop. It returns once the listeners are ready.
func (s *Server) Start() error {
	if s.opts.LoopbackEnabled {
		ln, err := net.Listen("tcp", s.opts.LoopbackAddr)
		if err != nil {
			return fmt.Errorf("transport: loopback listen: %w", err)
		}
		s.lnMu.Lock()
		s.loopback = ln
		s.lnMu.Unlock()
		s.wg.Add(1)
		go s.acceptLoop(ln, false)
		s.log.Info("transport loopback listening", "addr", ln.Addr().String())
	}
	s.refreshGate()
	s.wg.Add(1)
	go func() {
		defer s.wg.Done()
		defer close(s.gateDone)
		s.gateLoop(s.ctx)
	}()
	return nil
}

// Close stops the listeners, fails every connection with 1001 "going away",
// and waits up to closeGrace for the connections to drain. It is idempotent.
func (s *Server) Close() error {
	select {
	case <-s.closed:
		return nil
	default:
		close(s.closed)
	}
	s.cancel()
	<-s.gateDone
	s.lnMu.Lock()
	if s.loopback != nil {
		_ = s.loopback.Close()
		s.loopback = nil
	}
	s.lnMu.Unlock()
	s.connMu.Lock()
	for c := range s.conns {
		c.fail(closeError{code: websocket.StatusCode(1001), reason: "going away"})
	}
	s.connMu.Unlock()
	drained := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(drained)
	}()
	select {
	case <-drained:
	case <-time.After(closeGrace):
	}
	return nil
}

// CloseDevice closes every connection whose authenticated device key equals
// pub, with the device_revoked reason. Revocation must take effect on the
// device's live connection, not only on its next reconnect: a revoked key is
// dropped immediately so it can no longer attach sessions or run transfers.
// Safe to call concurrently and repeatedly; closing an already-closed
// connection is a no-op.
func (s *Server) CloseDevice(pub [32]byte) {
	s.connMu.Lock()
	var victims []*conn
	for c := range s.conns {
		if c.peerStatic == pub {
			victims = append(victims, c)
		}
	}
	s.connMu.Unlock()
	for _, c := range victims {
		c.fail(closeError{code: protocol.CloseAuth, reason: "device_revoked"})
	}
}

// LoopbackAddr returns the actual loopback address, or "" when the loopback
// listener is not running.
func (s *Server) LoopbackAddr() string {
	s.lnMu.Lock()
	defer s.lnMu.Unlock()
	if s.loopback == nil {
		return ""
	}
	return s.loopback.Addr().String()
}

// LANAddr returns the actual LAN address, or "" when the gate is closed.
func (s *Server) LANAddr() string {
	s.lnMu.Lock()
	defer s.lnMu.Unlock()
	if s.lan == nil {
		return ""
	}
	return s.lan.Addr().String()
}

// HandleStream serves one relay sub-stream: it runs the encrypted handshake
// and, on success, the connection loop, exactly as a direct LAN connection is
// served. The relay connector calls it in its own goroutine for each stream
// the relay opens. Relay streams count against the same connection cap as
// direct connections.
func (s *Server) HandleStream(st Stream) {
	if !s.reserve() {
		_ = st.Close(protocol.CloseLimit, "too many connections")
		return
	}
	defer s.release()
	c, err := s.newConn(st, false)
	if err != nil {
		_ = st.Close(uint16(websocket.StatusInternalError), "internal error")
		return
	}
	s.register(c)
	defer s.unregister(c)
	ci, err := s.runHandshake(s.ctx, c)
	if err != nil || ci.code != 0 {
		s.log.Info("transport relay handshake failed", "code", int(ci.code), "reason", ci.reason)
		c.teardown()
		_ = st.Close(uint16(ci.code), ci.reason)
		return
	}
	st.SetReadLimit(maxFrameLen)
	s.log.Info("transport relay connection", "mode", c.mode)
	c.serve(s.ctx)
}

// NotifyGate requests a re-evaluation of the LAN listener gate. It is
// coalesced: at most one evaluation is pending at a time.
func (s *Server) NotifyGate() {
	select {
	case s.gateCh <- struct{}{}:
	default:
	}
}

// NotifySessionUpdate broadcasts a session.update notification to every
// connection. It carries any metadata change: an exit, or a rename. A
// connection whose control queue is full or closed drops the notification;
// the session state is authoritative and a re-list converges.
func (s *Server) NotifySessionUpdate(m session.Metadata) {
	payload := protocol.EncodeSessionUpdate(toMeta(m))
	s.connMu.Lock()
	defer s.connMu.Unlock()
	for c := range s.conns {
		_ = c.mux.Send(protocol.Frame{Type: protocol.ChannelCtrl, Payload: payload})
	}
}

// NotifySessionEvent broadcasts a terminal event (bell or pattern match) to
// every connection. It is the session manager's event hook. Like session
// updates, a full or closed control queue drops the notification: events are
// ephemeral by design and the app dedupes on the per-session seq.
func (s *Server) NotifySessionEvent(e session.Event) {
	payload := protocol.EncodeSessionEvent(protocol.SessionEvent{
		SessionID: e.SessionID,
		Seq:       e.Seq,
		Kind:      e.Kind,
		Pattern:   e.Pattern,
		Text:      e.Text,
		Ts:        e.At.Unix(),
	})
	s.connMu.Lock()
	defer s.connMu.Unlock()
	for c := range s.conns {
		_ = c.mux.Send(protocol.Frame{Type: protocol.ChannelCtrl, Payload: payload})
	}
}

// gateLoop re-evaluates the LAN gate on events, token expiries, and a safety
// poll until the server context is cancelled.
func (s *Server) gateLoop(ctx context.Context) {
	defer s.closeLAN()
	ticker := time.NewTicker(gatePollInterval)
	defer ticker.Stop()
	for {
		next := s.opts.Tokens.NextExpiry()
		var (
			timer *time.Timer
			tch   <-chan time.Time
		)
		if !next.IsZero() && next.After(time.Now()) {
			timer = time.NewTimer(time.Until(next))
			tch = timer.C
		}
		select {
		case <-ctx.Done():
			return
		case <-s.gateCh:
		case <-ticker.C:
		case <-tch:
		}
		if timer != nil {
			timer.Stop()
		}
		s.refreshGate()
	}
}

// refreshGate opens or closes the LAN listener to match the listener-state
// rule: LAN listening only while a pairing token is active or at least one
// device is paired.
func (s *Server) refreshGate() {
	want := s.opts.LANEnabled && (s.opts.Tokens.Active() || s.opts.Devices.ActiveCount() > 0)
	s.lnMu.Lock()
	defer s.lnMu.Unlock()
	if want && s.lan == nil {
		ln, err := net.Listen("tcp", s.opts.LANAddr)
		if err != nil {
			// The listener stays closed; the next event or safety poll
			// retries.
			s.log.Error("transport lan listen failed", "addr", s.opts.LANAddr, "err", err.Error())
			return
		}
		s.lan = ln
		s.log.Info("transport lan listening", "addr", ln.Addr().String())
		s.wg.Add(1)
		go s.acceptLoop(ln, true)
	} else if !want && s.lan != nil {
		_ = s.lan.Close()
		s.lan = nil
		s.log.Info("transport lan closed")
	}
}

// closeLAN removes the LAN listener without touching in-flight connections,
// which are left to run out their lifetime.
func (s *Server) closeLAN() {
	s.lnMu.Lock()
	ln := s.lan
	s.lan = nil
	s.lnMu.Unlock()
	if ln != nil {
		_ = ln.Close()
	}
}

// reserve claims a connection slot before the WebSocket upgrade. It returns
// false when the cap is hit, which the HTTP layer answers with 503.
func (s *Server) reserve() bool {
	s.connMu.Lock()
	defer s.connMu.Unlock()
	if s.slots >= protocol.MaxConnections {
		return false
	}
	s.slots++
	return true
}

func (s *Server) release() {
	s.connMu.Lock()
	s.slots--
	s.connMu.Unlock()
}

// register adds a live connection. The slot was already reserved and is
// released by handleConn's deferred release when the connection ends.
func (s *Server) register(c *conn) {
	s.connMu.Lock()
	s.conns[c] = struct{}{}
	s.connMu.Unlock()
}

func (s *Server) unregister(c *conn) {
	s.connMu.Lock()
	delete(s.conns, c)
	s.connMu.Unlock()
}

// acceptLoop serves one listener until it is closed.
func (s *Server) acceptLoop(ln net.Listener, lan bool) {
	defer s.wg.Done()
	mux := http.NewServeMux()
	mux.Handle("/", http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.reserve() {
			http.Error(w, "too many connections", http.StatusServiceUnavailable)
			return
		}
		s.handleConn(w, r, lan)
	}))
	srv := &http.Server{Handler: mux, ReadHeaderTimeout: headerTimeout}
	_ = srv.Serve(ln)
}

// handleConn upgrades one HTTP request to the Remotly protocol and serves it
// until the connection ends. The caller reserved the slot; it is released
// here.
func (s *Server) handleConn(w http.ResponseWriter, r *http.Request, lan bool) {
	defer s.release()
	ws, err := websocket.Accept(w, r, &websocket.AcceptOptions{InsecureSkipVerify: true})
	if err != nil {
		// Accept already wrote the error response.
		return
	}
	st := &wsStream{ws: ws}
	c, err := s.newConn(st, lan)
	if err != nil {
		_ = st.Close(uint16(websocket.StatusInternalError), "internal error")
		return
	}
	s.register(c)
	defer s.unregister(c)
	ci, err := s.runHandshake(s.ctx, c)
	// Policy rejections (bad version/mode/token) return a non-zero close
	// code with a nil error, so a failure is either a non-nil error or a
	// non-zero close code. On success both are zero.
	if err != nil || ci.code != 0 {
		s.log.Info("transport handshake failed", "remote", r.RemoteAddr, "code", int(ci.code), "reason", ci.reason)
		c.teardown()
		_ = st.Close(uint16(ci.code), ci.reason)
		return
	}
	st.SetReadLimit(maxFrameLen)
	s.log.Info("transport connection", "remote", r.RemoteAddr, "mode", c.mode, "lan", lan)
	c.serve(s.ctx)
}
