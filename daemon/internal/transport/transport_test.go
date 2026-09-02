package transport

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"os"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/flynn/noise"
	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/fsops"
	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
	"github.com/heavycaffeiner/remotly/daemon/internal/transfer"
)

var x25519 = ecdh.X25519()

// fakeProc is a controllable pty.Process. Tests drive output via push and
// termination via terminate; Kill and Close end the stream like the real
// backend.
type fakeProc struct {
	out     chan []byte
	outOnce sync.Once
	pend    []byte

	exitCh    chan pty.ExitStatus
	closeCh   chan struct{}
	closeOnce sync.Once

	mu        sync.Mutex
	inputBuf  [][]byte
	resizeBuf [][2]uint16
	killed    bool
	closed    bool
}

func newFakeProc() *fakeProc {
	return &fakeProc{
		out:     make(chan []byte, 256),
		exitCh:  make(chan pty.ExitStatus, 2),
		closeCh: make(chan struct{}),
	}
}

func (f *fakeProc) push(b []byte) {
	f.out <- append([]byte(nil), b...)
}

func (f *fakeProc) terminate(st pty.ExitStatus) {
	f.outOnce.Do(func() { close(f.out) })
	select {
	case f.exitCh <- st:
	default:
	}
}

func (f *fakeProc) Read(p []byte) (int, error) {
	for len(f.pend) == 0 {
		chunk, ok := <-f.out
		if !ok {
			return 0, io.EOF
		}
		f.pend = chunk
	}
	n := copy(p, f.pend)
	f.pend = f.pend[n:]
	return n, nil
}

func (f *fakeProc) Write(p []byte) (int, error) {
	f.mu.Lock()
	f.inputBuf = append(f.inputBuf, append([]byte(nil), p...))
	f.mu.Unlock()
	return len(p), nil
}

func (f *fakeProc) input() [][]byte {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][]byte(nil), f.inputBuf...)
}

func (f *fakeProc) Resize(c, r uint16) error {
	f.mu.Lock()
	f.resizeBuf = append(f.resizeBuf, [2]uint16{c, r})
	f.mu.Unlock()
	return nil
}

func (f *fakeProc) resizes() [][2]uint16 {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([][2]uint16(nil), f.resizeBuf...)
}

func (f *fakeProc) Signal(_ os.Signal) error { return nil }

func (f *fakeProc) Kill() error {
	f.mu.Lock()
	if f.killed {
		f.mu.Unlock()
		return nil
	}
	f.killed = true
	f.mu.Unlock()
	f.outOnce.Do(func() { close(f.out) })
	select {
	case f.exitCh <- pty.ExitStatus{Exited: true, Code: -1, Signal: "KILL"}:
	default:
	}
	return nil
}

func (f *fakeProc) Wait() pty.ExitStatus {
	st, ok := <-f.exitCh
	if !ok {
		return pty.ExitStatus{Exited: true, Code: -1}
	}
	return st
}

func (f *fakeProc) Close() error {
	f.mu.Lock()
	if f.closed {
		f.mu.Unlock()
		return nil
	}
	f.closed = true
	f.mu.Unlock()
	f.outOnce.Do(func() { close(f.out) })
	f.closeOnce.Do(func() { close(f.closeCh) })
	return nil
}

// fakeBackend hands out fresh fakeProc values.
type fakeBackend struct {
	mu       sync.Mutex
	starts   int
	failNext int
	procs    []*fakeProc
}

func (b *fakeBackend) Start(_ pty.StartRequest) (pty.Process, error) {
	b.mu.Lock()
	b.starts++
	fail := b.failNext > 0
	if fail {
		b.failNext--
	}
	b.mu.Unlock()
	if fail {
		return nil, errors.New("fake: start failed")
	}
	p := newFakeProc()
	b.mu.Lock()
	b.procs = append(b.procs, p)
	b.mu.Unlock()
	return p, nil
}

func (b *fakeBackend) proc(n int) *fakeProc {
	b.mu.Lock()
	defer b.mu.Unlock()
	return b.procs[n]
}

// appKey is one synthetic app's long-term identity. Its Noise static is the
// key the daemon pins as the device key in hello.
type appKey struct {
	pub      [32]byte
	noiseKey noise.DHKey
}

func newAppKey(t *testing.T) *appKey {
	t.Helper()
	key, err := x25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("app key: %v", err)
	}
	raw := key.PublicKey().Bytes()
	if len(raw) != 32 {
		t.Fatalf("app key: bad public length %d", len(raw))
	}
	var pub [32]byte
	copy(pub[:], raw)
	return &appKey{
		pub:      pub,
		noiseKey: noise.DHKey{Private: key.Bytes(), Public: raw},
	}
}

// envCfg configures one test environment.
type envCfg struct {
	tokenTTL          time.Duration
	maxSessions       int
	scrollbackLines   int
	retainedAfterExit time.Duration
	backend           pty.Backend // nil uses the fake backend
	lanEnabled        bool
	log               *slog.Logger
	presets           []protocol.Preset
	events            *session.Events
}

// env is one daemon instance: identity, tokens, devices, sessions on a fake
// backend, and a running transport server on a free loopback port.
type env struct {
	t        *testing.T
	ident    *pairing.Identity
	tokens   *pairing.TokenManager
	devices  *pairing.DeviceStore
	sessions *session.Manager
	backend  *fakeBackend
	srv      *Server
}

func newEnv(t *testing.T, cfg envCfg) *env {
	t.Helper()
	e := &env{t: t}
	ident, err := pairing.NewIdentity()
	if err != nil {
		t.Fatalf("env identity: %v", err)
	}
	e.ident = ident

	ttl := cfg.tokenTTL
	if ttl <= 0 {
		ttl = pairing.DefaultTokenTTL
	}
	e.tokens = pairing.NewTokenManagerTTL(ttl)

	e.devices, err = pairing.LoadDeviceStore(t.TempDir())
	if err != nil {
		t.Fatalf("env devices: %v", err)
	}
	if cfg.backend == nil {
		e.backend = &fakeBackend{}
	}
	be := pty.Backend(e.backend)
	if cfg.backend != nil {
		be = cfg.backend
	}

	var srvPtr atomic.Pointer[Server]
	sessOpts := session.Options{
		Backend: be,
		OnExit: func(m session.Metadata) {
			if s := srvPtr.Load(); s != nil {
				s.NotifySessionExit(m)
			}
		},
		OnEvent: func(ev session.Event) {
			if s := srvPtr.Load(); s != nil {
				s.NotifySessionEvent(ev)
			}
		},
		Events: cfg.events,
	}
	if cfg.maxSessions > 0 {
		sessOpts.MaxSessions = cfg.maxSessions
	}
	if cfg.scrollbackLines > 0 {
		sessOpts.ScrollbackLines = cfg.scrollbackLines
	}
	if cfg.retainedAfterExit > 0 {
		sessOpts.RetainedAfterExit = cfg.retainedAfterExit
	}
	e.sessions, err = session.New(sessOpts)
	if err != nil {
		t.Fatalf("env sessions: %v", err)
	}
	e.srv = NewServer(Options{
		LoopbackAddr:    "127.0.0.1:0",
		LoopbackEnabled: true,
		LANAddr:         "127.0.0.1:0",
		LANEnabled:      cfg.lanEnabled,
		Identity:        ident,
		Tokens:          e.tokens,
		Devices:         e.devices,
		Sessions:        e.sessions,
		FS:              fsops.New(),
		Transfers:       transfer.NewManager(transfer.Options{FS: fsops.New()}),
		DaemonName:      "test-daemon",
		Log:             cfg.log,
		Presets:         cfg.presets,
	})
	srvPtr.Store(e.srv)
	if err := e.srv.Start(); err != nil {
		t.Fatalf("env start: %v", err)
	}
	// Cleanups run LIFO: the transport closes first, then the sessions.
	t.Cleanup(func() { _ = e.sessions.Shutdown() })
	t.Cleanup(func() { _ = e.srv.Close() })
	return e
}

func (e *env) dialURL() string {
	return "ws://" + e.srv.LoopbackAddr() + "/"
}

// termFrame is one decrypted terminal output frame received by a client.
type termFrame struct {
	chID    uint32
	payload []byte
}

// client is one synthetic app peer: a dialed socket, the session cipher, and
// a reader that routes decrypted frames to response and notification queues.
type client struct {
	t          *testing.T
	ws         *websocket.Conn
	cipher     *protocol.ChaCha
	pub        [32]byte
	frameDelay time.Duration // artificial slowness per inbound frame

	mu      sync.Mutex
	nextID  uint64
	pending map[uint64]chan *protocol.Response

	termOut  chan termFrame
	fileOut  chan termFrame
	notifs   chan *protocol.Notification
	dead     chan struct{}
	closeErr *websocket.CloseError
}

type clientOpt func(*client)

// slowFrames makes the client read one frame per delay, modelling an app
// that cannot drain the socket fast enough.
func slowFrames(d time.Duration) clientOpt {
	return func(c *client) { c.frameDelay = d }
}

func (e *env) newClient(t *testing.T, app *appKey, mode byte, token *pairing.Token, opts ...clientOpt) *client {
	t.Helper()
	c := &client{
		t:       t,
		pub:     app.pub,
		pending: make(map[uint64]chan *protocol.Response),
		termOut: make(chan termFrame, 4096),
		fileOut: make(chan termFrame, 4096),
		// Closing a connection emits one channel.close per open channel, and a
		// test may hold the full cap open, so the queue has to absorb a burst
		// of that size plus whatever else is already waiting.
		notifs: make(chan *protocol.Notification, 4*protocol.MaxChannels),
		dead:   make(chan struct{}),
	}
	for _, o := range opts {
		o(c)
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, e.dialURL(), nil)
	if err != nil {
		t.Fatalf("client: dial: %v", err)
	}
	// The daemon sends frames up to maxFrameLen (1 MiB payload plus header and
	// overhead); the library default of 32 KiB would reject them with 1009.
	ws.SetReadLimit(maxFrameLen)
	c.ws = ws

	daemonPub := e.ident.PublicBytes()
	cfg := noise.Config{
		CipherSuite:   suite,
		Pattern:       noise.HandshakeIK,
		Initiator:     true,
		Prologue:      []byte(protocol.Prologue),
		StaticKeypair: app.noiseKey,
		PeerStatic:    daemonPub[:],
	}
	if mode == protocol.ModePair {
		// XXpsk0 has no pre-seeded statics; the token's secret is the PSK.
		cfg.Pattern = noise.HandshakeXX
		cfg.PresharedKey = token.Secret[:]
		cfg.PresharedKeyPlacement = 0
		cfg.PeerStatic = nil
	}
	hs, err := noise.NewHandshakeState(cfg)
	if err != nil {
		t.Fatalf("client: noise state: %v", err)
	}
	msg1, _, _, err := hs.WriteMessage(nil, nil)
	if err != nil {
		t.Fatalf("client: msg1: %v", err)
	}
	first := []byte{protocol.Version, mode}
	if mode == protocol.ModePair {
		first = protocol.AppendVarint(first, uint64(len(token.ID)))
		first = append(first, token.ID[:]...)
	}
	first = append(first, msg1...)
	if err := ws.Write(ctx, websocket.MessageBinary, first); err != nil {
		t.Fatalf("client: send msg1: %v", err)
	}
	typ, data, err := ws.Read(ctx)
	if err != nil {
		t.Fatalf("client: read msg2: %v", err)
	}
	if typ != websocket.MessageBinary || len(data) < 2 || data[0] != protocol.Version || data[1] != mode {
		t.Fatalf("client: bad handshake reply %q", data[:min(2, len(data))])
	}
	var send, recv [32]byte
	if mode == protocol.ModePair {
		if _, _, _, err := hs.ReadMessage(nil, data[2:]); err != nil {
			t.Fatalf("client: msg2: %v", err)
		}
		msg3, cs1, cs2, err := hs.WriteMessage(nil, nil)
		if err != nil {
			t.Fatalf("client: msg3: %v", err)
		}
		send, recv = splitKeys(cs1, cs2)
		if err := ws.Write(ctx, websocket.MessageBinary, msg3); err != nil {
			t.Fatalf("client: send msg3: %v", err)
		}
	} else {
		// IK msg2 carries no static; the responder static is the pre-seeded
		// daemonPub, retrievable via PeerStatic after processing msg2.
		_, cs1, cs2, err := hs.ReadMessage(nil, data[2:])
		if err != nil {
			t.Fatalf("client: msg2: %v", err)
		}
		if peer := hs.PeerStatic(); !bytes.Equal(peer, daemonPub[:]) {
			t.Fatalf("client: peer static key mismatch")
		}
		send, recv = splitKeys(cs1, cs2)
	}
	c.cipher = protocol.NewChaCha(send, recv)
	go c.readLoop()
	return c
}

func (e *env) newClientPair(t *testing.T, app *appKey, token *pairing.Token, opts ...clientOpt) *client {
	t.Helper()
	return e.newClient(t, app, protocol.ModePair, token, opts...)
}

func (e *env) newClientIK(t *testing.T, app *appKey, opts ...clientOpt) *client {
	t.Helper()
	return e.newClient(t, app, protocol.ModeIK, nil, opts...)
}

func splitKeys(cs1, cs2 *noise.CipherState) (send, recv [32]byte) {
	return cs1.UnsafeKey(), cs2.UnsafeKey()
}

func (c *client) readLoop() {
	defer close(c.dead)
	for {
		if c.frameDelay > 0 {
			time.Sleep(c.frameDelay)
		}
		typ, data, err := c.ws.Read(context.Background())
		if err != nil {
			// The library wraps the close as a CloseError value, so the
			// match target must be a value, not a pointer.
			var cerr websocket.CloseError
			if errors.As(err, &cerr) {
				c.mu.Lock()
				c.closeErr = &cerr
				c.mu.Unlock()
			}
			return
		}
		if typ != websocket.MessageBinary {
			c.t.Errorf("client: non-binary frame from daemon")
			return
		}
		chType, chID, payload, err := c.cipher.OpenFrame(data)
		if err != nil {
			c.t.Errorf("client: open frame: %v", err)
			return
		}
		switch chType {
		case protocol.ChannelCtrl:
			if resp, perr := protocol.ParseResponse(payload); perr == nil {
				c.mu.Lock()
				ch := c.pending[resp.ID]
				delete(c.pending, resp.ID)
				c.mu.Unlock()
				if ch != nil {
					ch <- resp
				}
				continue
			}
			n, nerr := protocol.ParseNotification(payload)
			if nerr != nil {
				c.t.Errorf("client: control frame is neither response nor notification: %v", nerr)
				continue
			}
			select {
			case c.notifs <- n:
			default:
				c.t.Errorf("client: notification queue full, dropping %s", n.Type)
			}
		case protocol.ChannelTerm:
			select {
			case c.termOut <- termFrame{chID: chID, payload: append([]byte(nil), payload...)}:
			default:
				c.t.Errorf("client: term queue full, dropping frame")
			}
		case protocol.ChannelFile:
			select {
			case c.fileOut <- termFrame{chID: chID, payload: append([]byte(nil), payload...)}:
			default:
				c.t.Errorf("client: file queue full, dropping frame")
			}
		}
	}
}

func (c *client) newID() uint64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.nextID++
	return c.nextID
}

// ctrlJSON renders one control request. Fields are key, value pairs.
func ctrlJSON(id uint64, typ string, fields ...any) []byte {
	m := map[string]any{"id": id, "type": typ}
	for i := 0; i+1 < len(fields); i += 2 {
		m[fields[i].(string)] = fields[i+1]
	}
	data, err := json.Marshal(m)
	if err != nil {
		panic(err)
	}
	return data
}

// sendFrame seals and writes one frame.
func (c *client) sendFrame(chType byte, chID uint32, payload []byte) error {
	wire, err := c.cipher.SealFrame(chType, chID, payload)
	if err != nil {
		return err
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	return c.ws.Write(ctx, websocket.MessageBinary, wire)
}

// sendTerm sends terminal input on an attached channel.
func (c *client) sendTerm(t *testing.T, ch uint32, data string) {
	t.Helper()
	if err := c.sendFrame(protocol.ChannelTerm, ch, []byte(data)); err != nil {
		t.Fatalf("client: term input: %v", err)
	}
}

// request sends one control request and waits for its response.
func (c *client) request(t *testing.T, raw []byte) *protocol.Response {
	t.Helper()
	var id uint64
	if err := json.Unmarshal(raw, &struct {
		ID *uint64 `json:"id"`
	}{ID: &id}); err != nil {
		t.Fatalf("client: request id: %v", err)
	}
	ch := make(chan *protocol.Response, 1)
	c.mu.Lock()
	c.pending[id] = ch
	c.mu.Unlock()
	if err := c.sendFrame(protocol.ChannelCtrl, 0, raw); err != nil {
		t.Fatalf("client: send: %v", err)
	}
	select {
	case resp := <-ch:
		return resp
	case <-c.dead:
		t.Fatalf("client: connection closed while waiting for id %d: %v", id, c.closeError())
	case <-time.After(15 * time.Second):
		t.Fatalf("client: timeout waiting for id %d", id)
	}
	return nil
}

// hello authenticates the client and verifies the hello response.
func (c *client) hello(t *testing.T, e *env, name string) {
	t.Helper()
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeHello,
		"device_name", name,
		"device_pub", base64.RawURLEncoding.EncodeToString(c.pub[:]),
	))
	if resp.Error != nil {
		t.Fatalf("client: hello: %v", resp.Error)
	}
	if resp.DaemonName != "test-daemon" {
		t.Fatalf("client: hello daemon_name = %q", resp.DaemonName)
	}
	daemonPub := e.ident.PublicBytes()
	wantPub := base64.RawURLEncoding.EncodeToString(daemonPub[:])
	if resp.DaemonPub != wantPub {
		t.Fatalf("client: hello daemon_pub mismatch")
	}
}

// close sends a clean close frame and waits for the echo.
func (c *client) close(t *testing.T, code websocket.StatusCode, reason string) {
	t.Helper()
	if err := c.ws.Close(code, reason); err != nil {
		t.Fatalf("client: close: %v", err)
	}
}

func (c *client) closeError() *websocket.CloseError {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.closeErr
}

// expectCloseCode waits for the connection to die and asserts the close code.
func (c *client) expectCloseCode(t *testing.T, code websocket.StatusCode) {
	t.Helper()
	select {
	case <-c.dead:
	case <-time.After(15 * time.Second):
		t.Fatalf("client: connection did not close (want %d)", int(code))
	}
	cerr := c.closeError()
	if cerr == nil || cerr.Code != code {
		t.Fatalf("client: close = %v, want %d", cerr, int(code))
	}
}

// expectClose waits for the connection to die and asserts the full close
// frame.
func (c *client) expectClose(t *testing.T, code websocket.StatusCode, reason string) {
	t.Helper()
	c.expectCloseCode(t, code)
	cerr := c.closeError()
	if cerr.Reason != reason {
		t.Fatalf("client: close reason = %q, want %q", cerr.Reason, reason)
	}
}

// termUntil drains terminal frames on ch until want appears, returning the
// accumulated bytes.
func (c *client) termUntil(t *testing.T, ch uint32, want string, timeout time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var got []byte
	for {
		select {
		case f := <-c.termOut:
			if f.chID == ch {
				got = append(got, f.payload...)
				if strings.Contains(string(got), want) {
					return string(got)
				}
			}
		case <-c.dead:
			t.Fatalf("client: closed before %q on channel %d; got %q", want, ch, string(got))
		case <-time.After(time.Until(deadline)):
			t.Fatalf("client: %q not seen on channel %d; got %q", want, ch, string(got))
		}
	}
}

// notifUntil waits for one notification of the given type.
func (c *client) notifUntil(t *testing.T, typ string, timeout time.Duration) *protocol.Notification {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		select {
		case n := <-c.notifs:
			if n.Type == typ {
				return n
			}
		case <-c.dead:
			t.Fatalf("client: closed before %s notification", typ)
		case <-time.After(time.Until(deadline)):
			t.Fatalf("client: no %s notification within %v", typ, timeout)
		}
	}
}

// awaitNotifs waits until one notification of each type in types has arrived, in
// any order, and returns them keyed by type. The protocol leaves the order
// of independent notifications (e.g. channel.close and session.update on
// exit) open, so tests must not assume a sequence.
func (c *client) awaitNotifs(t *testing.T, timeout time.Duration, types ...string) map[string]*protocol.Notification {
	t.Helper()
	want := make(map[string]bool, len(types))
	for _, typ := range types {
		want[typ] = true
	}
	got := make(map[string]*protocol.Notification, len(types))
	deadline := time.Now().Add(timeout)
	for len(got) < len(types) {
		select {
		case n := <-c.notifs:
			if want[n.Type] && got[n.Type] == nil {
				got[n.Type] = n
			}
		case <-c.dead:
			t.Fatalf("client: closed before notifications %v arrived; got %v", types, got)
		case <-time.After(time.Until(deadline)):
			t.Fatalf("client: notifications %v not all seen within %v; got %v", types, timeout, got)
		}
	}
	return got
}

// channelClose asserts the channel.close notification for ch and reason.
func (c *client) channelClose(t *testing.T, ch uint32, reason string, timeout time.Duration) {
	t.Helper()
	n := c.notifUntil(t, protocol.TypeChannelClose, timeout)
	if n.ChannelID == nil || *n.ChannelID != ch {
		t.Fatalf("client: channel.close id = %v, want %d", n.ChannelID, ch)
	}
	if n.Reason == nil || *n.Reason != reason {
		t.Fatalf("client: channel.close reason = %v, want %q", n.Reason, reason)
	}
}

// sessionUpdate asserts a session.update notification for the session id and
// returns its metadata.
func (c *client) sessionUpdate(t *testing.T, id string, timeout time.Duration) *protocol.Meta {
	t.Helper()
	n := c.notifUntil(t, protocol.TypeSessionUpdate, timeout)
	if n.Session == nil || n.Session.ID != id {
		t.Fatalf("client: session.update id = %v, want %s", n.Session, id)
	}
	return n.Session
}

// waitFor polls cond until it holds or the timeout elapses.
func waitFor(t *testing.T, timeout time.Duration, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(10 * time.Millisecond)
	}
	t.Fatalf("timed out after %v waiting for %s", timeout, what)
}

// withShortLiveness shortens the liveness tunables so dead-peer tests finish
// in under a second.
func withShortLiveness(t *testing.T) {
	t.Helper()
	oldRT, oldPD, oldLI := readTimeout, pongDeadline, livenessPollInterval
	readTimeout = 300 * time.Millisecond
	pongDeadline = 400 * time.Millisecond
	livenessPollInterval = 50 * time.Millisecond
	t.Cleanup(func() {
		readTimeout, pongDeadline, livenessPollInterval = oldRT, oldPD, oldLI
	})
}

// syncBuffer is a concurrency-safe bytes.Buffer for log capture.
type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}
