package transport

import (
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/flynn/noise"
	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// TestChannelFlood exhausts the per-connection channel cap. The control
// channel counts toward the 64, so the 64th term channel is refused with a
// limit close.
func TestChannelFlood(t *testing.T) {
	e := newEnv(t, envCfg{maxSessions: 8})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	// Four sessions, attachments round-robin, so no session hits its own
	// attachment cap before the connection hits the channel cap.
	ids := make([]string, 4)
	for i := range ids {
		resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
		if resp.Error != nil || resp.Session == nil {
			t.Fatalf("create %d: %v", i, resp.Error)
		}
		ids[i] = resp.Session.ID
	}
	for i := 0; i < protocol.MaxChannels-1; i++ {
		r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", ids[i%4]))
		if r.Error != nil || r.ChannelID == nil {
			t.Fatalf("attach %d: %v", i, r.Error)
		}
	}
	_ = c.sendFrame(protocol.ChannelCtrl, 0, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", ids[3]))
	c.expectClose(t, protocol.CloseLimit, "channel limit")
}

// TestConnectionCap fills the 16 connection slots, verifies the 17th dial is
// refused, and that a hard-dropped slot becomes reusable.
func TestConnectionCap(t *testing.T) {
	e := newEnv(t, envCfg{})
	clients := make([]*client, protocol.MaxConnections)
	for i := range clients {
		app := newAppKey(t)
		clients[i] = e.newClientPair(t, app, e.tokens.Create())
		clients[i].hello(t, e, fmt.Sprintf("phone-%d", i))
	}

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if _, _, err := websocket.Dial(ctx, e.dialURL(), nil); err == nil {
		t.Fatalf("dial succeeded at the %d connection cap", protocol.MaxConnections)
	}

	_ = clients[0].ws.CloseNow()
	waitFor(t, 10*time.Second, "a connection slot to free", func() bool {
		e.srv.connMu.Lock()
		defer e.srv.connMu.Unlock()
		return e.srv.slots < protocol.MaxConnections
	})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone-16")
	c.close(t, websocket.StatusNormalClosure, "bye")
}

// TestListenerGate walks the listener-state rule: closed with no token and no
// device, open while a token is active, closed on token expiry, open with a
// paired device, closed after revocation.
func TestListenerGate(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: true, tokenTTL: 300 * time.Millisecond})
	if e.srv.LANAddr() != "" {
		t.Fatalf("lan open with no token or device: %s", e.srv.LANAddr())
	}

	e.tokens.Create() // active until its TTL runs out
	e.srv.NotifyGate()
	waitFor(t, 5*time.Second, "lan to open with an active token", func() bool {
		return e.srv.LANAddr() != ""
	})

	waitFor(t, 5*time.Second, "lan to close on token expiry", func() bool {
		return e.srv.LANAddr() == ""
	})

	app := newAppKey(t)
	if _, err := e.devices.Pair(app.pub, "phone"); err != nil {
		t.Fatalf("pair: %v", err)
	}
	e.srv.NotifyGate()
	waitFor(t, 5*time.Second, "lan to reopen with a paired device", func() bool {
		return e.srv.LANAddr() != ""
	})
	if err := e.devices.Revoke(app.pub); err != nil {
		t.Fatalf("revoke: %v", err)
	}
	e.srv.NotifyGate()
	waitFor(t, 5*time.Second, "lan to close after revocation", func() bool {
		return e.srv.LANAddr() == ""
	})
}

// TestLogRedaction runs a full session against a log sink and asserts that
// neither terminal content nor key material appears in the logs.
func TestLogRedaction(t *testing.T) {
	var buf syncBuffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	e := newEnv(t, envCfg{log: log})
	app := newAppKey(t)
	tok := e.tokens.Create()
	c := e.newClientPair(t, app, tok)
	c.hello(t, e, "phone")

	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", resp.Session.ID))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	ch := *att.ChannelID
	proc := e.backend.proc(0)
	proc.push([]byte("rem-log-secret-output\n"))
	c.termUntil(t, ch, "rem-log-secret-output", 10*time.Second)
	c.sendTerm(t, ch, "rem-log-secret-input\n")
	waitFor(t, 10*time.Second, "input to reach the pty", func() bool {
		for _, in := range proc.input() {
			if string(in) == "rem-log-secret-input\n" {
				return true
			}
		}
		return false
	})
	proc.terminate(pty.ExitStatus{Exited: true, Code: 0, Signal: ""})
	c.channelClose(t, ch, protocol.ReasonSessionExited, 10*time.Second)
	c.close(t, websocket.StatusNormalClosure, "bye")

	priv, _ := e.ident.KeyPair()
	forbidden := []string{
		"rem-log-secret-output",
		"rem-log-secret-input",
		hex.EncodeToString(tok.Secret[:]),
		hex.EncodeToString(priv[:]),
		base64.RawURLEncoding.EncodeToString(priv[:]),
	}
	got := buf.String()
	for _, f := range forbidden {
		if strings.Contains(got, f) {
			t.Fatalf("log output leaks %q", f)
		}
	}
}

// TestLivenessDeadPeer completes a handshake and then never reads again. The
// daemon must ping, see no pong, and close with 1001 "no pong".
func TestLivenessDeadPeer(t *testing.T) {
	withShortLiveness(t)
	e := newEnv(t, envCfg{})
	app := newAppKey(t)

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	ws, _, err := websocket.Dial(ctx, e.dialURL(), nil)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer func() { _ = ws.Close(websocket.StatusNormalClosure, "") }()

	daemonPub := e.ident.PublicBytes()
	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite:   suite,
		Pattern:       noise.HandshakeIK,
		Initiator:     true,
		Prologue:      []byte(protocol.Prologue),
		StaticKeypair: app.noiseKey,
		PeerStatic:    daemonPub[:],
	})
	if err != nil {
		t.Fatalf("noise state: %v", err)
	}
	msg1, _, _, err := hs.WriteMessage(nil, nil)
	if err != nil {
		t.Fatalf("msg1: %v", err)
	}
	first := append([]byte{protocol.Version, protocol.ModeIK}, msg1...)
	if err := ws.Write(ctx, websocket.MessageBinary, first); err != nil {
		t.Fatalf("send msg1: %v", err)
	}
	typ, data, err := ws.Read(ctx)
	if err != nil {
		t.Fatalf("read msg2: %v", err)
	}
	if typ != websocket.MessageBinary || len(data) < 2 {
		t.Fatalf("bad handshake reply")
	}
	if _, _, _, err := hs.ReadMessage(nil, data[2:]); err != nil {
		t.Fatalf("msg2: %v", err)
	}

	// readTimeout plus pongDeadline, with margin.
	time.Sleep(1500 * time.Millisecond)
	_, _, err = ws.Read(ctx)
	// The library wraps the close as a CloseError value, so the match
	// target must be a value, not a pointer.
	var cerr websocket.CloseError
	if !errors.As(err, &cerr) {
		t.Fatalf("read: %v", err)
	}
	if cerr.Code != websocket.StatusGoingAway || cerr.Reason != "no pong" {
		t.Fatalf("close = %v, want 1001 no pong", cerr)
	}
}

// TestLivenessHealthyPeer idles well past the read timeout while its reader
// answers pings, and must stay connected.
func TestLivenessHealthyPeer(t *testing.T) {
	withShortLiveness(t)
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	time.Sleep(1500 * time.Millisecond)
	if r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionList)); r.Error != nil {
		t.Fatalf("list after idle: %v", r.Error)
	}
	c.close(t, websocket.StatusNormalClosure, "bye")
}
