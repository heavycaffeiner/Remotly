package server

import (
	"bytes"
	"context"
	"fmt"
	"io"
	"net"
	"net/http"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/relay/relaycfg"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

func TestSlowReaderClosed(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.QueueFrames = 4
		c.Limits.QueueBytes = 4096
	}, func(o *Options) {
		// A slow writer backs the queues up deterministically, without
		// depending on kernel socket buffer sizes or auto-tuning.
		o.AppQueueFrames = 16
		o.AppQueueBytes = 16 * 1024
		o.WriteDelay = 15 * time.Millisecond
	})
	addr := env.srv.Addr()
	did := relayID(0xC3)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)

	// The app never reads and the writer is slow, so the app outbound
	// queue (16 frames) and the pair queue (4 frames) fill; the relay
	// then closes the app with a limit code. The already-queued
	// messages flush before the socket closes.
	for i := 0; i < 30; i++ {
		daemon.send(relayproto.NewStreamFrame(1, mustFrame(t, 1000)))
	}
	var code uint16
	for {
		m := app.readMsg(10 * time.Second)
		if m.Type == relayproto.TypeEnd {
			code = m.Code
			break
		}
		if m.Type != relayproto.TypeFrame {
			t.Fatalf("app got type 0x%02x, want frame or end", m.Type)
		}
	}
	if code != relayproto.CodeLimit {
		t.Fatalf("app end = %d, want %d", code, relayproto.CodeLimit)
	}
	// The daemon connection survives queue pressure from one app: it
	// gets the stream close and keeps answering keepalives.
	for {
		m := daemon.readMsg(5 * time.Second)
		if m.Type == relayproto.TypeStreamClose {
			break
		}
		t.Fatalf("daemon got type 0x%02x, want stream_close", m.Type)
	}
	daemon.send(relayproto.NewKeepalive())
	if ka := daemon.readMsg(5 * time.Second); ka.Type != relayproto.TypeKeepalive {
		t.Fatalf("daemon keepalive after app close: 0x%02x", ka.Type)
	}
}

func TestMalformedAppFrame(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xD4)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)

	// A frame below the minimum Remotly message size.
	_, _ = app.c.Write([]byte{relayproto.TypeFrame, 10, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10})
	if code, _ := app.readEnd(5 * time.Second); code != relayproto.CodeProtocol {
		t.Fatalf("app end = %d, want %d", code, relayproto.CodeProtocol)
	}
	// The daemon registration survives one bad app.
	app2 := dialEndpoint(t, addr)
	if code, _ := app2.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app2 join: %d", code)
	}
	_ = daemon.readMsg(5 * time.Second)
}

func TestConnectionLimit(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.MaxConnections = 16
	}, nil)
	addr := env.srv.Addr()

	// 16 connections fill the budget; the 17th is rejected.
	for i := 0; i < 16; i++ {
		e := dialEndpoint(t, addr)
		if code, _ := e.join(relayproto.RoleDaemon, relayID(byte(0x40+i))); code != 0 {
			t.Fatalf("held join %d: %d", i, code)
		}
	}
	over := dialEndpoint(t, addr)
	if code, _ := over.join(relayproto.RoleDaemon, relayID(0x77)); code != relayproto.CodeLimit {
		t.Fatalf("over limit code = %d, want %d", code, relayproto.CodeLimit)
	}
}

func TestJoinRateLimit(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.JoinRatePerSec = 1
		c.Limits.JoinBurst = 3
	}, nil)
	addr := env.srv.Addr()

	// Burst of 3 joins passes; the 4th from the same address is rejected.
	var codes []uint16
	for i := 0; i < 4; i++ {
		e := dialEndpoint(t, addr)
		code, _ := e.join(relayproto.RoleDaemon, relayID(byte(0x20+i)))
		codes = append(codes, code)
	}
	if codes[0] != 0 || codes[1] != 0 || codes[2] != 0 {
		t.Fatalf("burst codes = %v, want zeros", codes)
	}
	if codes[3] != relayproto.CodeLimit {
		t.Fatalf("4th join code = %d, want %d", codes[3], relayproto.CodeLimit)
	}
}

func TestAppLimitPerRelay(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.MaxAppsPerRelay = 2
	}, nil)
	addr := env.srv.Addr()
	did := relayID(0xA9)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	for i := 0; i < 2; i++ {
		app := dialEndpoint(t, addr)
		if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
			t.Fatalf("app %d join: %d", i, code)
		}
		_ = daemon.readMsg(5 * time.Second)
	}
	over := dialEndpoint(t, addr)
	if code, _ := over.join(relayproto.RoleApp, did); code != relayproto.CodeLimit {
		t.Fatalf("over limit code = %d, want %d", code, relayproto.CodeLimit)
	}
}

func TestIdleTimeout(t *testing.T) {
	clock := newFakeClock()
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.IdleTimeoutSec = 30
	}, func(o *Options) {
		o.Now = clock.Now
		o.SweepInterval = 50 * time.Millisecond
	})
	addr := env.srv.Addr()
	did := relayID(0xE5)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)

	// Only the app idles: a keepalive refreshes the daemon timer, then
	// the app (untouched since join) crosses the 30s mark. It is closed
	// with an idle code and the daemon is told its stream closed.
	clock.Add(29 * time.Second)
	daemon.send(relayproto.NewKeepalive())
	_ = daemon.readMsg(5 * time.Second)
	clock.Add(2 * time.Second)
	time.Sleep(150 * time.Millisecond)
	if code, _ := app.readEnd(2 * time.Second); code != relayproto.CodeIdle {
		t.Fatalf("app end = %d, want %d", code, relayproto.CodeIdle)
	}
	sc := daemon.readMsg(2 * time.Second)
	if sc.Type != relayproto.TypeStreamClose || sc.Code != relayproto.CodeIdle {
		t.Fatalf("daemon stream close = type 0x%02x code %d, want 0x08 %d", sc.Type, sc.Code, relayproto.CodeIdle)
	}
	daemon.send(relayproto.NewKeepalive())
	if ka := daemon.readMsg(2 * time.Second); ka.Type != relayproto.TypeKeepalive {
		t.Fatalf("daemon keepalive after app idle: 0x%02x", ka.Type)
	}

	// Now the daemon itself idles out: it is closed with an idle code.
	clock.Add(31 * time.Second)
	time.Sleep(150 * time.Millisecond)
	if code, _ := daemon.readEnd(2 * time.Second); code != relayproto.CodeIdle {
		t.Fatalf("daemon end = %d, want %d", code, relayproto.CodeIdle)
	}
}

func TestGracefulShutdown(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xF6)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := env.srv.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
	// The daemon sees its stream close (the app went away) and then the
	// connection end; the app sees the going-away end directly.
	var code uint16
	for {
		m := daemon.readMsg(5 * time.Second)
		if m.Type == relayproto.TypeEnd {
			code = m.Code
			break
		}
		if m.Type != relayproto.TypeStreamClose {
			t.Fatalf("daemon got type 0x%02x while draining, want stream_close or end", m.Type)
		}
	}
	if code != relayproto.CodeGoingAway {
		t.Fatalf("daemon end = %d, want %d", code, relayproto.CodeGoingAway)
	}
	if code, _ := app.readEnd(5 * time.Second); code != relayproto.CodeGoingAway {
		t.Fatalf("app end = %d, want %d", code, relayproto.CodeGoingAway)
	}
	// Shutdown is idempotent.
	if err := env.srv.Shutdown(ctx); err != nil {
		t.Fatal(err)
	}
}

func TestBadJoinRejected(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()

	// Garbage instead of a join.
	e := dialEndpoint(t, addr)
	_, _ = e.c.Write([]byte{0x7f, 1, 2, 3})
	code, _ := e.readEnd(5 * time.Second)
	if code != relayproto.CodeProtocol {
		t.Fatalf("garbage join code = %d, want %d", code, relayproto.CodeProtocol)
	}

	// Wrong protocol version.
	e2 := dialEndpoint(t, addr)
	b := make([]byte, 19)
	b[0] = relayproto.TypeJoin
	b[1] = 9
	b[2] = relayproto.RoleDaemon
	if _, err := e2.c.Write(b); err != nil {
		t.Fatal(err)
	}
	code, _ = e2.readEnd(5 * time.Second)
	if code != relayproto.CodeProtocol {
		t.Fatalf("bad version code = %d, want %d", code, relayproto.CodeProtocol)
	}
}

func TestAdminEndpoints(t *testing.T) {
	env := startTestServer(t, nil, nil)
	adminAddr := env.srv.AdminAddr()

	resp, err := http.Get("http://" + adminAddr + "/healthz")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("healthz = %d", resp.StatusCode)
	}

	did := relayID(0x61)
	daemon := dialEndpoint(t, env.srv.Addr())
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	text := fetchMetrics(t, adminAddr)
	if !strings.Contains(text, "remotly_relay_registrations 1") {
		t.Fatalf("metrics missing registrations=1:\n%s", text)
	}
	if !strings.Contains(text, "remotly_relay_goroutines ") {
		t.Fatalf("metrics missing goroutines:\n%s", text)
	}
}

func TestHighCardinalityUnknownIDs(t *testing.T) {
	// Raise the join rate so 200 joins from one test address are not
	// rejected for rate reasons; this test is about registry cardinality.
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.JoinRatePerSec = 1000
		c.Limits.JoinBurst = 1000
	}, nil)
	addr := env.srv.Addr()

	const n = 200
	var wg sync.WaitGroup
	codes := make(chan uint16, n)
	for i := 0; i < n; i++ {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			e := dialEndpoint(t, addr)
			var id [16]byte
			id[0] = byte(i)
			id[1] = 0xEE
			code, _ := e.join(relayproto.RoleApp, id)
			codes <- code
		}(i)
	}
	wg.Wait()
	close(codes)
	for code := range codes {
		if code != relayproto.CodeNoDaemon {
			t.Fatalf("unknown id join code = %d, want %d", code, relayproto.CodeNoDaemon)
		}
	}

	// No registrations were created by the failed joins.
	text := fetchMetrics(t, env.srv.AdminAddr())
	if !strings.Contains(text, "remotly_relay_registrations 0") {
		t.Fatalf("registrations should stay 0:\n%s", text)
	}
	if !strings.Contains(text, fmt.Sprintf(`remotly_relay_join_rejections_total{reason="no_daemon"} %d`, n)) {
		t.Fatalf("no_daemon rejections should be %d:\n%s", n, text)
	}
	// Goroutines return to a small steady state.
	goroutines := runtime.NumGoroutine()
	time.Sleep(100 * time.Millisecond)
	if g2 := runtime.NumGoroutine(); g2 > goroutines+4 {
		t.Fatalf("goroutine growth: %d -> %d", goroutines, g2)
	}
}

func TestPrivacyLogsAndMetrics(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()

	// A distinctive relay id and payload that must never appear in logs
	// or metrics.
	var did [16]byte
	for i := range did {
		did[i] = byte(0x5A + i)
	}
	const secret = "SECRET-PAYLOAD-XYZZY"

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatal(code)
	}
	_ = daemon.readMsg(5 * time.Second)
	app.sendFrame([]byte(secret))
	_ = daemon.readMsg(5 * time.Second)

	logs := env.log.String()
	metrics := fetchMetrics(t, env.srv.AdminAddr())
	for _, blob := range []string{logs, metrics} {
		if strings.Contains(blob, secret) {
			t.Fatalf("payload leaked:\n%s", blob)
		}
	}
	if strings.Contains(logs, fmt.Sprintf("%x", did[:])) || strings.Contains(logs, fmt.Sprintf("%v", did)) {
		t.Fatalf("relay id leaked in logs:\n%s", logs)
	}
	// Peer source addresses must not leak into logs or metrics either. The
	// high ephemeral source port distinguishes a peer from the relay's own
	// configured listen address.
	for _, peer := range []string{daemon.c.LocalAddr().String(), app.c.LocalAddr().String()} {
		for _, blob := range []string{logs, metrics} {
			if strings.Contains(blob, peer) {
				t.Fatalf("peer address %q leaked:\n%s", peer, blob)
			}
		}
	}
}

func TestLoadMultipleApps(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0x77)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatal(code)
	}

	const (
		apps     = 6
		frames   = 50
		frameLen = 32 << 10
	)

	type appSide struct {
		ep     *endpoint
		stream uint32
	}
	sides := make([]appSide, 0, apps)
	for i := 0; i < apps; i++ {
		a := dialEndpoint(t, addr)
		if code, _ := a.join(relayproto.RoleApp, did); code != 0 {
			t.Fatalf("app %d join: %d", i, code)
		}
		m := daemon.readMsg(5 * time.Second)
		if m.Type != relayproto.TypeStreamOpen {
			t.Fatalf("app %d: daemon got 0x%02x", i, m.Type)
		}
		sides = append(sides, appSide{ep: a, stream: m.StreamID})
	}

	expected := mustFrame(t, frameLen)
	// Each app sends frames; the daemon must receive them all on its
	// stream, byte identical.
	received := make(chan struct {
		stream uint32
		data   []byte
	}, apps*frames)
	go func() {
		defer close(received)
		for {
			m, err := readMsgTimeout(daemon.c, 15*time.Second)
			if err != nil {
				return
			}
			if m.Type != relayproto.TypeStreamFrame {
				continue
			}
			received <- struct {
				stream uint32
				data   []byte
			}{m.StreamID, m.Data}
		}
	}()

	var wg sync.WaitGroup
	for i, s := range sides {
		wg.Add(1)
		go func(i int, s appSide) {
			defer wg.Done()
			for f := 0; f < frames; f++ {
				s.ep.sendFrame(mustFrame(t, frameLen))
			}
		}(i, s)
	}
	wg.Wait()

	perStream := map[uint32]int{}
	deadline := time.After(15 * time.Second)
	for count := 0; count < apps*frames; count++ {
		select {
		case got := <-received:
			perStream[got.stream]++
			if !bytes.Equal(got.data, expected) {
				t.Fatalf("stream %d frame content mismatch (len %d)", got.stream, len(got.data))
			}
		case <-deadline:
			t.Fatalf("only %d frames received, want %d (per stream %v)", count, apps*frames, perStream)
		}
	}
	for _, s := range sides {
		if perStream[s.stream] != frames {
			t.Fatalf("stream %d got %d frames, want %d", s.stream, perStream[s.stream], frames)
		}
	}
}

// fetchMetrics pulls the admin metrics endpoint.
func fetchMetrics(t *testing.T, adminAddr string) string {
	t.Helper()
	resp, err := http.Get("http://" + adminAddr + "/metrics")
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return string(b)
}

// readMsgTimeout reads one relay message from a raw connection.
func readMsgTimeout(c net.Conn, d time.Duration) (relayproto.Message, error) {
	_ = c.SetReadDeadline(time.Now().Add(d))
	defer c.SetReadDeadline(time.Time{})
	return relayproto.Read(c)
}
