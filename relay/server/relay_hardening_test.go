package server

import (
	"runtime"
	"strings"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/relay/relaycfg"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

// appendVarint encodes v as an unsigned LEB128 varint. A test helper for
// crafting frames that relayproto.Encode would refuse (an oversized length).
func appendVarint(b []byte, v uint64) []byte {
	for v >= 0x80 {
		b = append(b, byte(v)|0x80)
		v >>= 7
	}
	return append(b, byte(v))
}

// readEndDraining reads app1's connection until an end message arrives,
// discarding any frames delivered first. Returns the end code, or 0 if none
// arrived within the per-read window.
func readEndDraining(e *endpoint, d time.Duration) uint16 {
	for i := 0; i < 256; i++ {
		m := e.readMsg(d)
		if m.Type == relayproto.TypeEnd {
			return m.Code
		}
	}
	return 0
}

// TestFrameLimitEnforced verifies a frame whose declared length exceeds the
// protocol ceiling is rejected with a protocol close, and that the rejection
// closes only the offending app, not the daemon connection.
func TestFrameLimitEnforced(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xD1)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("daemon join: %d", code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app join: %d", code)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen || m.StreamID != 1 {
		t.Fatalf("daemon open = 0x%02x id %d, want stream_open 1", m.Type, m.StreamID)
	}

	// Declare a length one past MaxFrame. The relay rejects it at the length
	// check, before reading the payload, so a stub payload is enough.
	oversized := []byte{relayproto.TypeFrame}
	oversized = appendVarint(oversized, uint64(relayproto.MaxFrame)+1)
	oversized = append(oversized, 0, 0, 0, 0)
	_ = app.c.SetWriteDeadline(time.Now().Add(5 * time.Second))
	if _, err := app.c.Write(oversized); err != nil {
		t.Fatalf("write oversized frame: %v", err)
	}
	if code, reason := app.readEnd(5 * time.Second); code != relayproto.CodeProtocol {
		t.Fatalf("app end = (%d %q), want (%d protocol)", code, reason, relayproto.CodeProtocol)
	}

	// The daemon connection survived the app's oversized frame: a fresh app
	// attaches and exchanges data on it. The daemon first sees stream 1
	// close, then stream 2 open.
	app2 := dialEndpoint(t, addr)
	if code, _ := app2.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app2 join after oversized frame: %d", code)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamClose || m.StreamID != 1 {
		t.Fatalf("daemon = 0x%02x id %d, want stream_close 1", m.Type, m.StreamID)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen || m.StreamID != 2 {
		t.Fatalf("daemon open2 = 0x%02x id %d, want stream_open 2", m.Type, m.StreamID)
	}
	daemon.send(relayproto.NewStreamFrame(2, mustFrame(t, 120)))
	if got := app2.readFrame(5 * time.Second); len(got) != 120 {
		t.Fatalf("app2 frame len = %d, want 120", len(got))
	}
}

// TestRegistrationLimitEnforced verifies the concurrent-registration cap is
// enforced: once the cap is reached, a further daemon join is refused.
func TestRegistrationLimitEnforced(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.MaxRegistrations = 2
		c.Limits.JoinRatePerSec = 1000
		c.Limits.JoinBurst = 1000
	}, nil)
	addr := env.srv.Addr()

	d1 := dialEndpoint(t, addr)
	if code, _ := d1.join(relayproto.RoleDaemon, relayID(0x11)); code != 0 {
		t.Fatalf("d1 join: %d", code)
	}
	d2 := dialEndpoint(t, addr)
	if code, _ := d2.join(relayproto.RoleDaemon, relayID(0x22)); code != 0 {
		t.Fatalf("d2 join: %d", code)
	}
	d3 := dialEndpoint(t, addr)
	if code, reason := d3.join(relayproto.RoleDaemon, relayID(0x33)); code != relayproto.CodeLimit {
		t.Fatalf("d3 join = (%d %q), want (%d registration limit)", code, reason, relayproto.CodeLimit)
	}
}

// TestUnknownStreamDroppedNoTeardown verifies a stream message naming a stream
// the relay does not know is dropped and counted, without tearing down the
// daemon connection.
func TestUnknownStreamDroppedNoTeardown(t *testing.T) {
	env := startTestServer(t, nil, nil)
	addr := env.srv.Addr()
	did := relayID(0xF3)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("daemon join: %d", code)
	}
	// Stream 999 was never allocated; the frame is dropped, not fatal.
	daemon.send(relayproto.NewStreamFrame(999, mustFrame(t, 64)))

	// The daemon connection is still fully usable.
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app join after unknown stream: %d", code)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen || m.StreamID != 1 {
		t.Fatalf("daemon open = 0x%02x id %d, want stream_open 1", m.Type, m.StreamID)
	}
	daemon.send(relayproto.NewStreamFrame(1, mustFrame(t, 200)))
	if got := app.readFrame(5 * time.Second); len(got) != 200 {
		t.Fatalf("app frame len = %d, want 200", len(got))
	}

	text := fetchMetrics(t, env.srv.AdminAddr())
	if !strings.Contains(text, `remotly_relay_drops_total{reason="unknown_stream"} 1`) {
		t.Fatalf("unknown_stream drop not counted:\n%s", text)
	}
}

// TestBandwidthLimitEnforced verifies the per-pair sustained-rate cap is
// enforced: with a tiny one-second burst, a single larger frame is refused
// with 3003.
func TestBandwidthLimitEnforced(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.BandwidthBPS = 200
	}, nil)
	addr := env.srv.Addr()
	did := relayID(0xB4)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("daemon join: %d", code)
	}
	app := dialEndpoint(t, addr)
	if code, _ := app.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app join: %d", code)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen || m.StreamID != 1 {
		t.Fatalf("daemon open = 0x%02x id %d, want stream_open 1", m.Type, m.StreamID)
	}

	// 500 bytes exceeds the 200-byte one-second burst, so the first frame is
	// already over the cap.
	app.sendFrame(mustFrame(t, 500))
	if code, reason := app.readEnd(5 * time.Second); code != relayproto.CodeLimit {
		t.Fatalf("app end = (%d %q), want (%d bandwidth limit)", code, reason, relayproto.CodeLimit)
	}
}

// TestQueueOverflowDoesNotStarveOtherStreams verifies that overflowing one
// stream's outbound queue closes that stream only; a sibling stream on the
// same daemon keeps forwarding.
func TestQueueOverflowDoesNotStarveOtherStreams(t *testing.T) {
	// WriteDelay throttles the relay's outbound writers so app1's outbound
	// queue fills deterministically even though the socket buffer would
	// otherwise absorb the small frames.
	env := startTestServer(t, nil, func(o *Options) {
		o.WriteDelay = 20 * time.Millisecond
	})
	addr := env.srv.Addr()
	did := relayID(0xE2)

	daemon := dialEndpoint(t, addr)
	if code, _ := daemon.join(relayproto.RoleDaemon, did); code != 0 {
		t.Fatalf("daemon join: %d", code)
	}
	app1 := dialEndpoint(t, addr)
	if code, _ := app1.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app1 join: %d", code)
	}
	app2 := dialEndpoint(t, addr)
	if code, _ := app2.join(relayproto.RoleApp, did); code != 0 {
		t.Fatalf("app2 join: %d", code)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen || m.StreamID != 1 {
		t.Fatalf("daemon open1 = 0x%02x id %d", m.Type, m.StreamID)
	}
	if m := daemon.readMsg(5 * time.Second); m.Type != relayproto.TypeStreamOpen || m.StreamID != 2 {
		t.Fatalf("daemon open2 = 0x%02x id %d", m.Type, m.StreamID)
	}

	// app1 never reads. Flood stream 1 past the app outbound queue (16
	// frames) so the relay closes stream 1 for queue overflow.
	for i := 0; i < 40; i++ {
		daemon.send(relayproto.NewStreamFrame(1, mustFrame(t, 256)))
	}
	if code := readEndDraining(app1, 5*time.Second); code != relayproto.CodeLimit {
		t.Fatalf("app1 end = %d, want %d (queue overflow)", code, relayproto.CodeLimit)
	}

	// Stream 2 is unaffected: the daemon still reaches app2.
	daemon.send(relayproto.NewStreamFrame(2, mustFrame(t, 900)))
	if got := app2.readFrame(5 * time.Second); len(got) != 900 {
		t.Fatalf("app2 frame len = %d, want 900", len(got))
	}
}

// TestConnectionChurnSoak opens and closes many connections in a loop and
// verifies the relay stays healthy with a stable goroutine count: no leak
// from connection churn. Each join is for an unknown relay id, so it
// exercises the full connect, join, reject, close path.
func TestConnectionChurnSoak(t *testing.T) {
	env := startTestServer(t, func(c *relaycfg.Config) {
		c.Limits.JoinRatePerSec = 100000
		c.Limits.JoinBurst = 100000
	}, nil)
	addr := env.srv.Addr()

	baseline := runtime.NumGoroutine()
	const n = 300
	for i := 0; i < n; i++ {
		e := dialEndpoint(t, addr)
		var id [16]byte
		id[0] = byte(i % 251)
		id[1] = 0xCC
		code, _ := e.join(relayproto.RoleApp, id)
		if code != relayproto.CodeNoDaemon {
			t.Fatalf("churn %d: join code = %d, want %d", i, code, relayproto.CodeNoDaemon)
		}
		_ = e.c.Close()
	}
	time.Sleep(150 * time.Millisecond)
	if g := runtime.NumGoroutine(); g > baseline+8 {
		t.Fatalf("goroutine growth after %d churned connections: %d -> %d", n, baseline, g)
	}
}
