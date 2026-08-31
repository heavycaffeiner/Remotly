package relayconn

import (
	"bytes"
	"context"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log/slog"
	"net"
	"os"
	"sync"
	"testing"
	"time"

	"github.com/flynn/noise"

	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
	"github.com/heavycaffeiner/remotly/daemon/internal/transport"
	"github.com/heavycaffeiner/remotly/relay/relaycfg"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
	relayserver "github.com/heavycaffeiner/remotly/relay/server"
)

var testSuite = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2b)
var x25519 = ecdh.X25519()

// echoBackend starts processes that echo input straight back to output, so a
// test can drive terminal I/O without a real shell.
type echoProc struct {
	out       chan []byte
	outOnce   sync.Once
	exitCh    chan pty.ExitStatus
	closeCh   chan struct{}
	closeOnce sync.Once
}

func (p *echoProc) Read(dst []byte) (int, error) {
	chunk, ok := <-p.out
	if !ok {
		return 0, io.EOF
	}
	return copy(dst, chunk), nil
}
func (p *echoProc) Write(b []byte) (int, error) {
	select {
	case p.out <- append([]byte(nil), b...):
	default:
	}
	return len(b), nil
}
func (p *echoProc) Resize(_, _ uint16) error { return nil }
func (p *echoProc) Signal(_ os.Signal) error { return nil }
func (p *echoProc) Kill() error {
	p.outOnce.Do(func() { close(p.out) })
	select {
	case p.exitCh <- pty.ExitStatus{Exited: true, Code: 0}:
	default:
	}
	return nil
}
func (p *echoProc) Wait() pty.ExitStatus {
	st, ok := <-p.exitCh
	if !ok {
		return pty.ExitStatus{Exited: true, Code: 0}
	}
	return st
}
func (p *echoProc) Close() error {
	p.outOnce.Do(func() { close(p.out) })
	p.closeOnce.Do(func() { close(p.closeCh) })
	return nil
}

type echoBackend struct{}

func (echoBackend) Start(_ pty.StartRequest) (pty.Process, error) {
	return &echoProc{
		out:     make(chan []byte, 256),
		exitCh:  make(chan pty.ExitStatus, 1),
		closeCh: make(chan struct{}),
	}, nil
}

// relayApp is one synthetic app: a TCP connection to the relay (app role) that
// completes the Remotly IK handshake and exchanges control frames.
type relayApp struct {
	t        *testing.T
	conn     net.Conn
	cipher   *protocol.ChaCha
	pub      [32]byte
	noiseKey noise.DHKey

	mu      sync.Mutex
	nextID  uint64
	pending map[uint64]chan *protocol.Response
	termOut chan []byte
	dead    chan struct{}
}

func newRelayApp(t *testing.T) *relayApp {
	t.Helper()
	key, err := x25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatalf("app key: %v", err)
	}
	raw := key.PublicKey().Bytes()
	var pub [32]byte
	copy(pub[:], raw)
	return &relayApp{
		t:        t,
		pub:      pub,
		noiseKey: noise.DHKey{Private: key.Bytes(), Public: raw},
		pending:  make(map[uint64]chan *protocol.Response),
		termOut:  make(chan []byte, 256),
		dead:     make(chan struct{}),
	}
}

// startRelayOn brings up an in-process relay on the given addr and registers a
// cleanup that shuts it down.
func startRelayOn(t *testing.T, addr string) *relayserver.Server {
	t.Helper()
	cfg, err := relaycfg.Parse([]byte(`{"listen":"127.0.0.1:1","admin_listen":"127.0.0.1:1"}`))
	if err != nil {
		t.Fatalf("relaycfg: %v", err)
	}
	cfg.Listen = addr
	a, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("relay admin port: %v", err)
	}
	cfg.AdminListen = a.Addr().String()
	_ = a.Close()

	srv, err := relayserver.New(relayserver.Options{Cfg: cfg, Log: slog.New(slog.NewTextHandler(io.Discard, nil))})
	if err != nil {
		t.Fatalf("relay new: %v", err)
	}
	if err := srv.Listen(); err != nil {
		t.Fatalf("relay listen: %v", err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	})
	return srv
}

func freeAddr(t *testing.T) string {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatalf("free port: %v", err)
	}
	addr := l.Addr().String()
	_ = l.Close()
	return addr
}

func (a *relayApp) sendRelay(b []byte) error {
	_ = a.conn.SetWriteDeadline(time.Now().Add(5 * time.Second))
	_, err := a.conn.Write(b)
	return err
}

func (a *relayApp) readRelay() (relayproto.Message, error) {
	_ = a.conn.SetReadDeadline(time.Now().Add(15 * time.Second))
	return relayproto.Read(a.conn)
}

// connect dials the relay and completes the app join. It returns an error when
// the relay answers with an end message (for example, no daemon registered).
func (a *relayApp) connect(t *testing.T, relayAddr string, relayID [16]byte) error {
	t.Helper()
	conn, err := net.Dial("tcp", relayAddr)
	if err != nil {
		return err
	}
	a.conn = conn
	join, err := relayproto.Encode(relayproto.NewJoin(relayproto.RoleApp, relayID))
	if err != nil {
		_ = conn.Close()
		return err
	}
	if err := a.sendRelay(join); err != nil {
		_ = conn.Close()
		return err
	}
	msg, err := a.readRelay()
	if err != nil {
		_ = conn.Close()
		return err
	}
	if msg.Type != relayproto.TypeJoinAck {
		_ = conn.Close()
		return errors.New("app: join rejected")
	}
	return nil
}

func (a *relayApp) sendFrame(t *testing.T, raw []byte) error {
	t.Helper()
	buf, err := relayproto.Encode(relayproto.NewFrame(raw))
	if err != nil {
		t.Fatalf("app: encode frame: %v", err)
	}
	return a.sendRelay(buf)
}

func (a *relayApp) readFrame(t *testing.T) ([]byte, error) {
	t.Helper()
	msg, err := a.readRelay()
	if err != nil {
		return nil, err
	}
	if msg.Type != relayproto.TypeFrame {
		return nil, fmt.Errorf("app: expected frame, got type 0x%02x", msg.Type)
	}
	return append([]byte(nil), msg.Data...), nil
}

// handshake runs the IK handshake over relay frames and installs the cipher.
func (a *relayApp) handshake(t *testing.T, daemonPub [32]byte) {
	t.Helper()
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite:   testSuite,
		Pattern:       noise.HandshakeIK,
		Initiator:     true,
		Prologue:      []byte(protocol.Prologue),
		StaticKeypair: a.noiseKey,
		PeerStatic:    daemonPub[:],
	})
	if err != nil {
		t.Fatalf("app: noise: %v", err)
	}
	msg1, _, _, err := hs.WriteMessage(nil, nil)
	if err != nil {
		t.Fatalf("app: msg1: %v", err)
	}
	first := append([]byte{protocol.Version, protocol.ModeIK}, msg1...)
	if err := a.sendFrame(t, first); err != nil {
		t.Fatalf("app: send msg1: %v", err)
	}
	data, err := a.readFrame(t)
	if err != nil {
		t.Fatalf("app: read msg2: %v", err)
	}
	if len(data) < 2 || data[0] != protocol.Version || data[1] != protocol.ModeIK {
		t.Fatalf("app: bad handshake reply")
	}
	_, cs1, cs2, err := hs.ReadMessage(nil, data[2:])
	if err != nil {
		t.Fatalf("app: msg2: %v", err)
	}
	if peer := hs.PeerStatic(); !bytes.Equal(peer, daemonPub[:]) {
		t.Fatalf("app: peer static mismatch")
	}
	a.cipher = protocol.NewChaCha(cs1.UnsafeKey(), cs2.UnsafeKey())
	go a.readLoop()
}

func (a *relayApp) readLoop() {
	defer close(a.dead)
	for {
		msg, err := a.readRelay()
		if err != nil {
			return
		}
		if msg.Type != relayproto.TypeFrame {
			return
		}
		chType, _, payload, err := a.cipher.OpenFrame(append([]byte(nil), msg.Data...))
		if err != nil {
			a.t.Errorf("app: open frame: %v", err)
			return
		}
		switch chType {
		case protocol.ChannelCtrl:
			resp, perr := protocol.ParseResponse(payload)
			if perr == nil {
				a.mu.Lock()
				ch := a.pending[resp.ID]
				delete(a.pending, resp.ID)
				a.mu.Unlock()
				if ch != nil {
					ch <- resp
				}
			}
		case protocol.ChannelTerm:
			select {
			case a.termOut <- payload:
			default:
			}
		}
	}
}

func (a *relayApp) newID() uint64 {
	a.mu.Lock()
	defer a.mu.Unlock()
	a.nextID++
	return a.nextID
}

func ctrlJSON(id uint64, typ string, fields ...any) []byte {
	m := map[string]any{"id": id, "type": typ}
	for i := 0; i+1 < len(fields); i += 2 {
		m[fields[i].(string)] = fields[i+1]
	}
	data, _ := json.Marshal(m)
	return data
}

func (a *relayApp) seal(chType byte, chID uint32, payload []byte) ([]byte, error) {
	return a.cipher.SealFrame(chType, chID, payload)
}

func (a *relayApp) request(t *testing.T, raw []byte) *protocol.Response {
	t.Helper()
	var id uint64
	_ = json.Unmarshal(raw, &struct {
		ID *uint64 `json:"id"`
	}{ID: &id})
	ch := make(chan *protocol.Response, 1)
	a.mu.Lock()
	a.pending[id] = ch
	a.mu.Unlock()
	wire, err := a.seal(protocol.ChannelCtrl, 0, raw)
	if err != nil {
		t.Fatalf("app: seal: %v", err)
	}
	if err := a.sendFrame(t, wire); err != nil {
		t.Fatalf("app: send: %v", err)
	}
	select {
	case resp := <-ch:
		return resp
	case <-a.dead:
		t.Fatalf("app: closed while waiting for id %d", id)
	case <-time.After(15 * time.Second):
		t.Fatalf("app: timeout for id %d", id)
	}
	return nil
}

func (a *relayApp) hello(t *testing.T, daemonName string) {
	t.Helper()
	resp := a.request(t, ctrlJSON(a.newID(), protocol.TypeHello,
		"device_name", "relay-phone",
		"device_pub", base64.RawURLEncoding.EncodeToString(a.pub[:]),
	))
	if resp.Error != nil {
		t.Fatalf("app: hello: %v", resp.Error)
	}
	if resp.DaemonName != daemonName {
		t.Fatalf("app: daemon_name = %q, want %q", resp.DaemonName, daemonName)
	}
}

// newDaemon builds a running transport server plus its supporting state, with
// LAN disabled (the relay path does not use the LAN listener).
func newDaemon(t *testing.T) (*transport.Server, *pairing.Identity, *pairing.DeviceStore) {
	t.Helper()
	ident, err := pairing.NewIdentity()
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	tokens := pairing.NewTokenManagerTTL(pairing.DefaultTokenTTL)
	devices, err := pairing.LoadDeviceStore(t.TempDir())
	if err != nil {
		t.Fatalf("devices: %v", err)
	}
	sessions, err := session.New(session.Options{Backend: pty.Backend(echoBackend{})})
	if err != nil {
		t.Fatalf("sessions: %v", err)
	}
	srv := transport.NewServer(transport.Options{
		LoopbackAddr:    "127.0.0.1:0",
		LoopbackEnabled: true,
		LANAddr:         "127.0.0.1:0",
		LANEnabled:      false,
		Identity:        ident,
		Tokens:          tokens,
		Devices:         devices,
		Sessions:        sessions,
		DaemonName:      "relay-daemon",
		Log:             slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	if err := srv.Start(); err != nil {
		t.Fatalf("daemon start: %v", err)
	}
	t.Cleanup(func() { _ = srv.Close() })
	return srv, ident, devices
}

func relayIDOf(ident *pairing.Identity) [16]byte {
	pub := ident.PublicBytes()
	var out [16]byte
	copy(out[:], pub[:16])
	return out
}

func waitUntil(t *testing.T, timeout time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		if cond() {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("condition not met within %v", timeout)
}

func TestEndToEndOverRelay(t *testing.T) {
	srv, ident, devices := newDaemon(t)
	relay := startRelayOn(t, freeAddr(t))

	client := New(Config{
		Addr:     relay.Addr(),
		RelayID:  relayIDOf(ident),
		OnStream: func(st *Stream) { srv.HandleStream(st) },
		Log:      slog.New(slog.NewTextHandler(io.Discard, nil)),
	})
	client.Start()
	t.Cleanup(func() { _ = client.Close() })

	app := newRelayApp(t)
	if _, err := devices.Pair(app.pub, "relay-phone"); err != nil {
		t.Fatalf("pair: %v", err)
	}

	// The app can only join once the daemon has registered with the relay, so
	// retry the join until the registration lands.
	waitUntil(t, 10*time.Second, func() bool {
		return app.connect(t, relay.Addr(), relayIDOf(ident)) == nil
	})

	app.handshake(t, ident.PublicBytes())
	app.hello(t, "relay-daemon")

	// Create a shell session and attach; the echo backend reflects input to
	// output, proving data frames flow both ways through the relay.
	create := app.request(t, ctrlJSON(app.newID(), protocol.TypeSessionCreate, "kind", "shell"))
	if create.Error != nil {
		t.Fatalf("session.create: %v", create.Error)
	}
	if create.Session == nil {
		t.Fatalf("session.create: no session in response")
	}
	sid := create.Session.ID
	attach := app.request(t, ctrlJSON(app.newID(), protocol.TypeSessionAttach, "session_id", sid))
	if attach.Error != nil {
		t.Fatalf("session.attach: %v", attach.Error)
	}
	if attach.ChannelID == nil {
		t.Fatalf("session.attach: no channel in response")
	}
	chID := *attach.ChannelID

	wire, err := app.seal(protocol.ChannelTerm, chID, []byte("ping\n"))
	if err != nil {
		t.Fatalf("seal term: %v", err)
	}
	if err := app.sendFrame(t, wire); err != nil {
		t.Fatalf("send term: %v", err)
	}

	select {
	case out := <-app.termOut:
		if !bytes.Contains(out, []byte("ping")) {
			t.Fatalf("term output = %q, want to contain %q", out, "ping")
		}
	case <-app.dead:
		t.Fatalf("connection closed before terminal output")
	case <-time.After(10 * time.Second):
		t.Fatalf("no terminal output within 10s")
	}
}

// TestDirectLanUnaffectedByRelayOutage verifies that a daemon whose relay is
// unreachable still serves direct loopback connections.
func TestDirectLanUnaffectedByRelayOutage(t *testing.T) {
	srv, _, _ := newDaemon(t)
	client := New(Config{
		Addr:       "127.0.0.1:1",
		RelayID:    [16]byte{},
		OnStream:   func(st *Stream) { srv.HandleStream(st) },
		Log:        slog.New(slog.NewTextHandler(io.Discard, nil)),
		MinBackoff: 50 * time.Millisecond,
		MaxBackoff: 200 * time.Millisecond,
	})
	client.Start()
	t.Cleanup(func() { _ = client.Close() })

	time.Sleep(150 * time.Millisecond)
	lan := srv.LoopbackAddr()
	if lan == "" {
		t.Fatalf("no loopback address")
	}
	conn, err := net.Dial("tcp", lan)
	if err != nil {
		t.Fatalf("direct loopback dial failed while relay was down: %v", err)
	}
	_ = conn.Close()
}

// TestConnectorReconnectsAfterOutage verifies the connector registers once the
// relay comes up on an address the connector has been retrying.
func TestConnectorReconnectsAfterOutage(t *testing.T) {
	srv, ident, _ := newDaemon(t)
	addr := freeAddr(t)
	client := New(Config{
		Addr:       addr,
		RelayID:    relayIDOf(ident),
		OnStream:   func(st *Stream) { srv.HandleStream(st) },
		Log:        slog.New(slog.NewTextHandler(io.Discard, nil)),
		MinBackoff: 50 * time.Millisecond,
		MaxBackoff: 200 * time.Millisecond,
	})
	client.Start()
	t.Cleanup(func() { _ = client.Close() })
	time.Sleep(100 * time.Millisecond)

	relay := startRelayOn(t, addr)
	_ = relay

	waitUntil(t, 10*time.Second, func() bool {
		app := newRelayApp(t)
		if err := app.connect(t, addr, relayIDOf(ident)); err != nil {
			return false
		}
		_ = app.conn.Close()
		return true
	})
}
