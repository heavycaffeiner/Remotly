package transport

import (
	"context"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/flynn/noise"
	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// rawHandshakeClose dials e, writes one raw first handshake message, and
// returns the close error the daemon answers with. It is for rejection tests
// where a full client would mask the failure.
func (e *env) rawHandshakeClose(t *testing.T, first []byte) *websocket.CloseError {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, e.dialURL(), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = ws.Close(websocket.StatusNormalClosure, "") }()
	if err := ws.Write(ctx, websocket.MessageBinary, first); err != nil {
		t.Fatalf("send first: %v", err)
	}
	_, _, err = ws.Read(ctx)
	if err == nil {
		t.Fatalf("expected the daemon to close, got a handshake reply")
	}
	// The library wraps the close as a CloseError value, so the match
	// target must be a value, not a pointer.
	var cerr websocket.CloseError
	if !errors.As(err, &cerr) {
		t.Fatalf("read: %v", err)
	}
	return &cerr
}

func (e *env) firstMessage(t *testing.T, cfg noise.Config, first []byte) *websocket.CloseError {
	t.Helper()
	hs, err := noise.NewHandshakeState(cfg)
	if err != nil {
		t.Fatalf("noise state: %v", err)
	}
	msg1, _, _, err := hs.WriteMessage(nil, nil)
	if err != nil {
		t.Fatalf("msg1: %v", err)
	}
	return e.rawHandshakeClose(t, append(first, msg1...))
}

// ikFirstMessage runs one IK initiator handshake against e.
func (e *env) ikFirstMessage(t *testing.T, app *appKey) *websocket.CloseError {
	t.Helper()
	daemonPub := e.ident.PublicBytes()
	return e.firstMessage(t, noise.Config{
		CipherSuite:   suite,
		Pattern:       noise.HandshakeIK,
		Initiator:     true,
		Prologue:      []byte(protocol.Prologue),
		StaticKeypair: app.noiseKey,
		PeerStatic:    daemonPub[:],
	}, []byte{protocol.Version, protocol.ModeIK})
}

// pairFirstMessage runs one XXpsk0 initiator handshake with the token.
func (e *env) pairFirstMessage(t *testing.T, app *appKey, token *pairing.Token) *websocket.CloseError {
	t.Helper()
	first := []byte{protocol.Version, protocol.ModePair}
	first = protocol.AppendVarint(first, uint64(len(token.ID)))
	first = append(first, token.ID[:]...)
	return e.firstMessage(t, noise.Config{
		CipherSuite:           suite,
		Pattern:               noise.HandshakeXX,
		Initiator:             true,
		Prologue:              []byte(protocol.Prologue),
		StaticKeypair:         app.noiseKey,
		PresharedKey:          token.Secret[:],
		PresharedKeyPlacement: 0,
	}, first)
}

func helloFrame(id uint64, app *appKey) []byte {
	return ctrlJSON(id, protocol.TypeHello,
		"device_name", "phone",
		"device_pub", base64.RawURLEncoding.EncodeToString(app.pub[:]),
	)
}

// TestHappyPath runs the full M1 session lifecycle over the encrypted
// transport: pair, hello, create, attach, live output, input, resize,
// detach, reattach with replay, list, kill.
func TestHappyPath(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: true})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate,
		"kind", protocol.KindShell,
		"cols", 100, "rows", 30,
	))
	if resp.Error != nil || resp.Session == nil || !resp.Session.Running {
		t.Fatalf("create: %v %+v", resp.Error, resp.Session)
	}
	id := resp.Session.ID
	if resp.Session.Cols != 100 || resp.Session.Rows != 30 {
		t.Fatalf("create: dims %dx%d, want 100x30", resp.Session.Cols, resp.Session.Rows)
	}

	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	ch := *att.ChannelID
	proc := e.backend.proc(0)
	proc.push([]byte("rem-live-output\n"))
	c.termUntil(t, ch, "rem-live-output", 10*time.Second)
	c.sendTerm(t, ch, "rem-input\n")
	waitFor(t, 10*time.Second, "input to reach the pty", func() bool {
		for _, in := range proc.input() {
			if string(in) == "rem-input\n" {
				return true
			}
		}
		return false
	})

	if r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionResize,
		"session_id", id, "cols", 120, "rows", 40)); r.Error != nil {
		t.Fatalf("resize: %v", r.Error)
	}
	waitFor(t, 10*time.Second, "resize to reach the pty", func() bool {
		rz := proc.resizes()
		return len(rz) > 0 && rz[len(rz)-1] == [2]uint16{120, 40}
	})

	if d := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionDetach, "channel_id", ch)); d.Error != nil {
		t.Fatalf("detach: %v", d.Error)
	}
	c.channelClose(t, ch, protocol.ReasonDetached, 10*time.Second)
	proc.push([]byte("rem-second\n"))

	att2 := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att2.Error != nil || att2.ChannelID == nil || *att2.ChannelID == ch {
		t.Fatalf("reattach: %v ch=%v", att2.Error, att2.ChannelID)
	}
	ch2 := *att2.ChannelID
	got := c.termUntil(t, ch2, "rem-second", 10*time.Second)
	if !strings.Contains(got, "rem-live-output") {
		t.Fatalf("replay missing earlier output: %q", got)
	}

	lst := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionList))
	if lst.Error != nil || len(lst.Sessions) != 1 || lst.Sessions[0].ID != id {
		t.Fatalf("list: %v %d sessions", lst.Error, len(lst.Sessions))
	}

	if k := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionKill, "session_id", id)); k.Error != nil {
		t.Fatalf("kill: %v", k.Error)
	}
	// The exit produces both a channel.close for the attachment and a
	// session.update broadcast, in an order the protocol leaves open.
	nots := c.awaitNotifs(t, 10*time.Second, protocol.TypeChannelClose, protocol.TypeSessionUpdate)
	cc := nots[protocol.TypeChannelClose]
	if cc.ChannelID == nil || *cc.ChannelID != ch2 {
		t.Fatalf("channel.close id = %v, want %d", cc.ChannelID, ch2)
	}
	if cc.Reason == nil || *cc.Reason != protocol.ReasonSessionExited {
		t.Fatalf("channel.close reason = %v, want %q", cc.Reason, protocol.ReasonSessionExited)
	}
	m := nots[protocol.TypeSessionUpdate].Session
	if m == nil || m.ID != id || m.Running || m.Exit == nil {
		t.Fatalf("session.update: %+v", m)
	}
	// A killed session is dropped rather than retained: retention is for a
	// shell that ended on its own.
	waitFor(t, 10*time.Second, "session to leave the list", func() bool {
		return len(e.sessions.List()) == 0
	})

	c.close(t, websocket.StatusNormalClosure, "bye")
}

// TestIKReconnect pairs once, drops the connection, and reconnects with the
// same device key over IK; the session must survive the drop.
func TestIKReconnect(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)

	c1 := e.newClientPair(t, app, e.tokens.Create())
	c1.hello(t, e, "phone")
	resp := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	c1.close(t, websocket.StatusNormalClosure, "bye")

	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "phone")
	lst := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionList))
	if lst.Error != nil || len(lst.Sessions) != 1 || lst.Sessions[0].ID != id || !lst.Sessions[0].Running {
		t.Fatalf("list after reconnect: %+v", lst.Sessions)
	}
	att := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	e.backend.proc(0).push([]byte("rem-reconnect\n"))
	c2.termUntil(t, *att.ChannelID, "rem-reconnect", 10*time.Second)
	c2.close(t, websocket.StatusNormalClosure, "bye")
}

func TestIKUnknownDevice(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t) // never paired
	c := e.newClientIK(t, app)
	if err := c.sendFrame(protocol.ChannelCtrl, 0, helloFrame(1, app)); err != nil {
		t.Fatalf("send hello: %v", err)
	}
	c.expectClose(t, protocol.CloseAuth, "device_unknown")
}

func TestIKRevokedDevice(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	if _, err := e.devices.Pair(app.pub, "phone"); err != nil {
		t.Fatalf("pair: %v", err)
	}
	if err := e.devices.Revoke(app.pub); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	c := e.newClientIK(t, app)
	if err := c.sendFrame(protocol.ChannelCtrl, 0, helloFrame(1, app)); err != nil {
		t.Fatalf("send hello: %v", err)
	}
	c.expectClose(t, protocol.CloseAuth, "device_revoked")
}

// TestCloseDeviceDropsOnlyThatDevice verifies the revocation path for an
// already-connected device: closing the device drops its live connection with
// the revoked reason, and leaves other devices' connections working. This is
// what makes a revocation take effect immediately rather than on reconnect.
func TestCloseDeviceDropsOnlyThatDevice(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	other := newAppKey(t)
	if _, err := e.devices.Pair(app.pub, "phone"); err != nil {
		t.Fatalf("pair: %v", err)
	}
	if _, err := e.devices.Pair(other.pub, "tablet"); err != nil {
		t.Fatalf("pair: %v", err)
	}
	c1 := e.newClientIK(t, app)
	c1.hello(t, e, "phone")
	c2 := e.newClientIK(t, other)
	c2.hello(t, e, "tablet")

	e.srv.CloseDevice(app.pub)

	c1.expectClose(t, protocol.CloseAuth, "device_revoked")
	// The other device is unaffected and can still issue requests.
	if err := c2.sendFrame(protocol.ChannelCtrl, 0, ctrlJSON(99, protocol.TypeSessionList)); err != nil {
		t.Fatalf("other device send after revoke: %v", err)
	}
}

// TestCloseDeviceUnknownKeyIsNoOp confirms closing a device with no live
// connections is safe (the localctl revoke callback fires even when the
// device is not connected).
func TestCloseDeviceUnknownKeyIsNoOp(t *testing.T) {
	e := newEnv(t, envCfg{})
	var none [32]byte
	e.srv.CloseDevice(none)
}

// A phone that is already paired can pair again with a fresh token. This is
// the recovery path after an app reinstall: same device key, new one-time
// token, and it must connect rather than be refused.
func TestPairExistingDevice(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)

	c1 := e.newClientPair(t, app, e.tokens.Create())
	c1.hello(t, e, "phone")
	c1.close(t, websocket.StatusNormalClosure, "bye")

	c2 := e.newClientPair(t, app, e.tokens.Create())
	c2.hello(t, e, "phone")
	c2.close(t, websocket.StatusNormalClosure, "bye")
}

func TestBadHello(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	other := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	frame := ctrlJSON(1, protocol.TypeHello,
		"device_name", "phone",
		"device_pub", base64.RawURLEncoding.EncodeToString(other.pub[:]),
	)
	if err := c.sendFrame(protocol.ChannelCtrl, 0, frame); err != nil {
		t.Fatalf("send hello: %v", err)
	}
	c.expectClose(t, protocol.CloseAuth, "bad hello")
}

// TestPreHelloGate verifies session operations are refused before hello.
func TestPreHelloGate(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	if err := c.sendFrame(protocol.ChannelCtrl, 0, ctrlJSON(1, protocol.TypeSessionList)); err != nil {
		t.Fatalf("send: %v", err)
	}
	c.expectClose(t, protocol.CloseProtocol, "hello required")
}

func TestHandshakeRejections(t *testing.T) {
	e := newEnv(t, envCfg{})

	t.Run("bad version", func(t *testing.T) {
		cerr := e.rawHandshakeClose(t, []byte{2, protocol.ModeIK})
		if cerr.Code != protocol.CloseVersion {
			t.Fatalf("close = %v, want %d", cerr, int(protocol.CloseVersion))
		}
	})

	t.Run("bad mode", func(t *testing.T) {
		cerr := e.rawHandshakeClose(t, []byte{protocol.Version, 7})
		if cerr.Code != protocol.CloseProtocol || cerr.Reason != "bad mode" {
			t.Fatalf("close = %v, want 4002 bad mode", cerr)
		}
	})

	t.Run("garbage noise", func(t *testing.T) {
		junk := make([]byte, 40)
		if _, err := rand.Read(junk); err != nil {
			t.Fatalf("rand: %v", err)
		}
		cerr := e.rawHandshakeClose(t, append([]byte{protocol.Version, protocol.ModeIK}, junk...))
		if cerr.Code != protocol.CloseProtocol || cerr.Reason != "handshake failed" {
			t.Fatalf("close = %v, want 4002 handshake failed", cerr)
		}
	})
}

func TestTokenRejections(t *testing.T) {
	t.Run("unknown", func(t *testing.T) {
		e := newEnv(t, envCfg{})
		app := newAppKey(t)
		var tok pairing.Token
		if _, err := rand.Read(tok.ID[:]); err != nil {
			t.Fatalf("rand: %v", err)
		}
		cerr := e.pairFirstMessage(t, app, &tok)
		if cerr.Code != protocol.CloseToken || cerr.Reason != "token_unknown" {
			t.Fatalf("close = %v, want 4003 token_unknown", cerr)
		}
	})

	t.Run("expired", func(t *testing.T) {
		e := newEnv(t, envCfg{tokenTTL: 100 * time.Millisecond})
		app := newAppKey(t)
		tok := e.tokens.Create()
		time.Sleep(150 * time.Millisecond)
		cerr := e.pairFirstMessage(t, app, tok)
		if cerr.Code != protocol.CloseToken || cerr.Reason != "token_expired" {
			t.Fatalf("close = %v, want 4003 token_expired", cerr)
		}
	})

	t.Run("used", func(t *testing.T) {
		e := newEnv(t, envCfg{})
		app := newAppKey(t)
		tok := e.tokens.Create()
		c := e.newClientPair(t, app, tok)
		c.hello(t, e, "phone")
		c.close(t, websocket.StatusNormalClosure, "bye")
		cerr := e.pairFirstMessage(t, app, tok)
		if cerr.Code != protocol.CloseToken || cerr.Reason != "token_used" {
			t.Fatalf("close = %v, want 4003 token_used", cerr)
		}
	})
}

// TestConnectionDropLeavesSession hard-drops a connected client and verifies
// the session survives, the connection slot is released, and a reconnect can
// attach to the same session.
func TestConnectionDropLeavesSession(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	e.backend.proc(0).push([]byte("rem-before-drop\n"))
	c.termUntil(t, *att.ChannelID, "rem-before-drop", 10*time.Second)

	// Hard drop: the TCP connection dies without a close frame.
	_ = c.ws.CloseNow()
	waitFor(t, 10*time.Second, "connection slot to be released", func() bool {
		e.srv.connMu.Lock()
		defer e.srv.connMu.Unlock()
		return e.srv.slots == 0
	})
	got := e.sessions.List()
	if len(got) != 1 || !got[0].Running || got[0].ID != id {
		t.Fatalf("session did not survive the drop: %+v", got)
	}

	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "phone")
	att2 := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att2.Error != nil || att2.ChannelID == nil {
		t.Fatalf("reattach: %v", att2.Error)
	}
	c2.termUntil(t, *att2.ChannelID, "rem-before-drop", 10*time.Second)
	c2.close(t, websocket.StatusNormalClosure, "bye")
}

// TestSessionErrorResponses verifies recoverable failures answer an error
// response without dropping the connection.
func TestSessionErrorResponses(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	resp := c.request(t, ctrlJSON(100, protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	unknownID := strings.Repeat("ab", 32)

	cases := []struct {
		name string
		req  []byte
		code string
	}{
		{"attach unknown", ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", unknownID), protocol.CodeUnknownSession},
		{"attach bad id", ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", "nope"), protocol.CodeInvalidRequest},
		{"kill unknown", ctrlJSON(c.newID(), protocol.TypeSessionKill, "session_id", unknownID), protocol.CodeUnknownSession},
		{"resize unknown", ctrlJSON(c.newID(), protocol.TypeSessionResize, "session_id", unknownID, "cols", 80, "rows", 24), protocol.CodeUnknownSession},
		{"shell with command", ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell, "command", "ls"), protocol.CodeInvalidRequest},
		{"agent without command", ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindAgent), protocol.CodeInvalidRequest},
		{"bad cwd", ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell, "cwd", "relative"), protocol.CodeInvalidRequest},
		{"resize out of range", ctrlJSON(c.newID(), protocol.TypeSessionResize, "session_id", id, "cols", 0, "rows", 24), protocol.CodeInvalidRequest},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			r := c.request(t, tc.req)
			if r.Error == nil || r.Error.Code != tc.code {
				t.Fatalf("error = %+v, want %s", r.Error, tc.code)
			}
		})
	}
	c.close(t, websocket.StatusNormalClosure, "bye")
}

func TestSpawnFailed(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	e.backend.failNext = 1
	r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if r.Error == nil || r.Error.Code != protocol.CodeSpawnFailed {
		t.Fatalf("error = %+v, want spawn_failed", r.Error)
	}
	c.close(t, websocket.StatusNormalClosure, "bye")
}

func TestSessionCapacity(t *testing.T) {
	e := newEnv(t, envCfg{maxSessions: 2})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	for i := 1; i <= 2; i++ {
		if r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell)); r.Error != nil {
			t.Fatalf("create %d: %v", i, r.Error)
		}
	}
	r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if r.Error == nil || r.Error.Code != protocol.CodeCapacity {
		t.Fatalf("error = %+v, want capacity", r.Error)
	}
	c.close(t, websocket.StatusNormalClosure, "bye")
}

// TestSessionExitBroadcast verifies the exit path: the channel closes with
// session_exited, a session.update is broadcast, and the session leaves the
// list.
func TestSessionExitBroadcast(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	ch := *att.ChannelID

	e.backend.proc(0).terminate(pty.ExitStatus{Exited: true, Code: 7, Signal: ""})
	// The exit produces both a channel.close and a session.update, in an
	// order the protocol leaves open.
	nots := c.awaitNotifs(t, 10*time.Second, protocol.TypeChannelClose, protocol.TypeSessionUpdate)
	cc := nots[protocol.TypeChannelClose]
	if cc.ChannelID == nil || *cc.ChannelID != ch {
		t.Fatalf("channel.close id = %v, want %d", cc.ChannelID, ch)
	}
	if cc.Reason == nil || *cc.Reason != protocol.ReasonSessionExited {
		t.Fatalf("channel.close reason = %v, want %q", cc.Reason, protocol.ReasonSessionExited)
	}
	m := nots[protocol.TypeSessionUpdate].Session
	if m == nil || m.ID != id || m.Running || m.Exit == nil || m.Exit.Code != 7 {
		t.Fatalf("session.update: %+v", m)
	}
	waitFor(t, 10*time.Second, "session to show exited in the list", func() bool {
		lst := e.sessions.List()
		return len(lst) == 1 && !lst[0].Running
	})
	c.close(t, websocket.StatusNormalClosure, "bye")
}
