package server

import (
	"bytes"
	"context"
	"fmt"
	"net"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/relay/relaycfg"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

// ---- test harness ----

// fakeClock is a manually advanced clock for idle timeout tests.
type fakeClock struct {
	mu sync.Mutex
	t  time.Time
}

func newFakeClock() *fakeClock { return &fakeClock{t: time.Now()} }
func (c *fakeClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}
func (c *fakeClock) Add(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

// logSink captures relay log lines for privacy assertions.
type logSink struct {
	mu  sync.Mutex
	buf bytes.Buffer
}

func (l *logSink) Info(msg string, kv ...any)  { l.write("INFO", msg, kv) }
func (l *logSink) Warn(msg string, kv ...any)  { l.write("WARN", msg, kv) }
func (l *logSink) Error(msg string, kv ...any) { l.write("ERROR", msg, kv) }

func (l *logSink) write(level, msg string, kv []any) {
	var b bytes.Buffer
	fmt.Fprintf(&b, "%s %s", level, msg)
	for _, v := range kv {
		fmt.Fprintf(&b, " %v", v)
	}
	l.mu.Lock()
	l.buf.Write(b.Bytes())
	l.buf.WriteByte('\n')
	l.mu.Unlock()
}

func (l *logSink) String() string {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.buf.String()
}

type testEnv struct {
	srv *Server
	log *logSink
}

func startTestServer(t *testing.T, mutate func(*relaycfg.Config), opts func(*Options)) *testEnv {
	t.Helper()
	cfg, err := relaycfg.Parse([]byte(`{"listen": "127.0.0.1:0", "admin_listen": "127.0.0.1:1"}`))
	if err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	cfg.AdminListen = ln.Addr().String()
	_ = ln.Close()
	if mutate != nil {
		mutate(&cfg)
	}
	sink := &logSink{}
	o := &Options{Cfg: cfg, Log: sink}
	if opts != nil {
		opts(o)
	}
	// The privacy assertions need the captured sink regardless of any
	// extra logger the test installs.
	if o.Log != sink {
		o.Log = teeLogger{sink: sink, other: o.Log}
	}
	srv, err := New(*o)
	if err != nil {
		t.Fatal(err)
	}
	if err := srv.Listen(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	})
	env := &testEnv{srv: srv, log: sink}
	return env
}

// teeLogger forwards every line to two sinks.
type teeLogger struct {
	sink  *logSink
	other Logger
}

func (l teeLogger) Info(msg string, kv ...any)  { l.sink.Info(msg, kv...); l.other.Info(msg, kv...) }
func (l teeLogger) Warn(msg string, kv ...any)  { l.sink.Warn(msg, kv...); l.other.Warn(msg, kv...) }
func (l teeLogger) Error(msg string, kv ...any) { l.sink.Error(msg, kv...); l.other.Error(msg, kv...) }

// endpoint is a raw relay protocol client for tests.
type endpoint struct {
	t *testing.T
	c net.Conn
}

func dialEndpoint(t *testing.T, addr string) *endpoint {
	t.Helper()
	c, err := net.Dial("tcp", addr)
	if err != nil {
		t.Fatal(err)
	}
	e := &endpoint{t: t, c: c}
	t.Cleanup(func() { _ = c.Close() })
	return e
}

// join sends a join and expects either an ack or an end message.
func (e *endpoint) join(role byte, id [16]byte) (uint16, string) {
	e.t.Helper()
	b, err := relayproto.Encode(relayproto.NewJoin(role, id))
	if err != nil {
		e.t.Fatal(err)
	}
	_ = e.c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := e.c.Write(b); err != nil {
		e.t.Fatal(err)
	}
	got := e.readMsg(5 * time.Second)
	if got.Type == relayproto.TypeJoinAck {
		return 0, ""
	}
	if got.Type == relayproto.TypeEnd {
		return got.Code, got.Reason
	}
	e.t.Fatalf("join answer = type 0x%02x, want ack or end", got.Type)
	return 0, ""
}

func (e *endpoint) readMsg(d time.Duration) relayproto.Message {
	e.t.Helper()
	_ = e.c.SetReadDeadline(time.Now().Add(d))
	m, err := relayproto.Read(e.c)
	_ = e.c.SetReadDeadline(time.Time{})
	if err != nil {
		e.t.Fatalf("read: %v", err)
	}
	return m
}

func (e *endpoint) sendFrame(data []byte) {
	e.t.Helper()
	b, err := relayproto.Encode(relayproto.NewFrame(data))
	if err != nil {
		e.t.Fatal(err)
	}
	_ = e.c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := e.c.Write(b); err != nil {
		e.t.Fatal(err)
	}
}

func (e *endpoint) send(m relayproto.Message) {
	e.t.Helper()
	b, err := relayproto.Encode(m)
	if err != nil {
		e.t.Fatal(err)
	}
	_ = e.c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := e.c.Write(b); err != nil {
		e.t.Fatal(err)
	}
}

func (e *endpoint) readFrame(d time.Duration) []byte {
	m := e.readMsg(d)
	if m.Type != relayproto.TypeFrame {
		e.t.Fatalf("read frame: got type 0x%02x code %d reason %q", m.Type, m.Code, m.Reason)
	}
	return m.Data
}

func (e *endpoint) readEnd(d time.Duration) (uint16, string) {
	m := e.readMsg(d)
	if m.Type != relayproto.TypeEnd {
		e.t.Fatalf("read end: got type 0x%02x", m.Type)
	}
	return m.Code, m.Reason
}

func relayID(b byte) [16]byte {
	var id [16]byte
	for i := range id {
		id[i] = b
	}
	return id
}

func mustFrame(t *testing.T, n int) []byte {
	t.Helper()
	b := make([]byte, n)
	for i := range b {
		b[i] = byte(i % 251)
	}
	return b
}

// ---- core behavior ----

func TestForwardingRoundTrip(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xA1)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("daemon join: %d", code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app join: %d", code)
	}

	open := daemon.readMsg(5 * time.Second)
	if open.Type != relayproto.TypeStreamOpen || open.StreamID != 1 {
		t.Fatalf("daemon got type 0x%02x id %d, want stream_open 1", open.Type, open.StreamID)
	}

	f1 := mustFrame(t, 3000)
	app.sendFrame(f1)
	sf := daemon.readMsg(5 * time.Second)
	if sf.Type != relayproto.TypeStreamFrame || sf.StreamID != 1 || !bytes.Equal(sf.Data, f1) {
		t.Fatalf("daemon stream frame mismatch: type 0x%02x id %d len %d", sf.Type, sf.StreamID, len(sf.Data))
	}

	f2 := mustFrame(t, 70000)
	daemon.send(relayproto.NewStreamFrame(1, f2))
	if got := app.readFrame(5 * time.Second); !bytes.Equal(got, f2) {
		t.Fatalf("app frame mismatch: len %d", len(got))
	}

	fmax := mustFrame(t, relayproto.MaxFrame)
	app.sendFrame(fmax)
	sf = daemon.readMsg(10 * time.Second)
	if !bytes.Equal(sf.Data, fmax) {
		t.Fatal("max frame mismatch app->daemon")
	}
	daemon.send(relayproto.NewStreamFrame(1, fmax))
	if got := app.readFrame(10 * time.Second); !bytes.Equal(got, fmax) {
		t.Fatal("max frame mismatch daemon->app")
	}

	// The app's keepalive is consumed for liveness; the relay does not echo it
	// back, so the next message the app reads is the ping's keepalive.
	app.send(relayproto.NewKeepalive())

	// Stream ping from the daemon is answered via the app keepalive.
	daemon.send(relayproto.NewStreamPing(1))
	if ka := app.readMsg(5 * time.Second); ka.Type != relayproto.TypeKeepalive {
		t.Fatalf("app got 0x%02x, want keepalive for ping", ka.Type)
	}
	app.send(relayproto.NewKeepalive())
	if pong := daemon.readMsg(5 * time.Second); pong.Type != relayproto.TypeStreamPong || pong.StreamID != 1 {
		t.Fatalf("daemon got type 0x%02x id %d, want stream_pong 1", pong.Type, pong.StreamID)
	}

	daemon.send(relayproto.NewStreamClose(1, 4001, "authentication failure"))
	code, reason := app.readEnd(5 * time.Second)
	if code != 4001 || reason != "authentication failure" {
		t.Fatalf("app end = (%d %q), want (4001 authentication failure)", code, reason)
	}
}

func TestUnknownRelayID(t *testing.T) {
	env := startTestServer(t, nil, nil)
	app := dialEndpoint(t, env.srv.Addr())
	code, _ := app.join(relayproto.RoleApp, relayID(0x55))
	if code != relayproto.CodeNoDaemon {
		t.Fatalf("code = %d, want %d", code, relayproto.CodeNoDaemon)
	}
}

func TestDuplicateRegistration(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xB2)

	d1 := dialEndpoint(t, addr)
	if code, _ := d1.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("d1 join: %d", code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app join: %d", code)
	}
	_ = d1.readMsg(5 * time.Second)

	d2 := dialEndpoint(t, addr)
	if code, _ := d2.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("d2 join: %d", code)
	}
	if code, _ := d1.readEnd(5 * time.Second); code != relayproto.CodeReplaced {
		t.Fatalf("d1 end = %d, want %d", code, relayproto.CodeReplaced)
	}
	if code, _ := app.readEnd(5 * time.Second); code != relayproto.CodePeerGone {
		t.Fatalf("app end = %d, want %d", code, relayproto.CodePeerGone)
	}

	app2 := dialEndpoint(t, addr)
	if code, _ := app2.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app2 join: %d", code)
	}
	if m := d2.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen {
		t.Fatalf("d2 got 0x%02x, want stream_open", m.Type)
	}
	app2.sendFrame(mustFrame(t, 100))
	if sf := d2.readMsg(5 * time.Second); sf.Type != relayproto.TypeStreamFrame || sf.StreamID != 1 {
		t.Fatalf("d2 stream frame: type 0x%02x id %d", sf.Type, sf.StreamID)
	}
}

func TestDaemonDeathOrphansApps(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xC7)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)

	_ = daemon.c.Close()
	if code, _ := app.readEnd(5 * time.Second); code != relayproto.CodePeerGone {
		t.Fatalf("app end = %d, want %d", code, relayproto.CodePeerGone)
	}
	// The registration is gone: a new app join fails until a daemon
	// re-registers.
	app2 := dialEndpoint(t, addr)
	if code, _ := app2.join(relayproto.RoleApp, did); code != relayproto.CodeNoDaemon {
		t.Fatalf("app2 join = %d, want %d", code, relayproto.CodeNoDaemon)
	}
}

func TestAppEndMessageClosesStream(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xD8)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)

	app.send(relayproto.NewEnd(4002, "protocol error"))
	sc := daemon.readMsg(5 * time.Second)
	if sc.Type != relayproto.TypeStreamClose || sc.Code != 4002 || sc.Reason != "protocol error" {
		t.Fatalf("daemon stream close = %+v", sc)
	}
}
