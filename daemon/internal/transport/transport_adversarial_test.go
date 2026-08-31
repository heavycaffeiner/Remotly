package transport

import (
	"context"
	"log/slog"
	"os"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
)

// authedClient brings up one paired, helloed client over a fresh env.
func authedClient(t *testing.T) (*env, *client) {
	t.Helper()
	e := newEnv(t, envCfg{log: slog.New(slog.NewTextHandler(os.Stderr, nil))})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	return e, c
}

// TestMalformedControl feeds the daemon protocol violations and asserts each
// one is handled per spec: fatal ones close with the documented code and
// reason, recoverable ones answer an error response and leave the connection
// up.
func TestMalformedControl(t *testing.T) {
	t.Run("bad json", func(t *testing.T) {
		_, c := authedClient(t)
		if err := c.sendFrame(protocol.ChannelCtrl, 0, []byte("{invalid")); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "bad json")
	})

	t.Run("trailing data", func(t *testing.T) {
		_, c := authedClient(t)
		if err := c.sendFrame(protocol.ChannelCtrl, 0, []byte(`{"id":1,"type":"session.list"}{}`)); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "bad json")
	})

	t.Run("unknown channel", func(t *testing.T) {
		_, c := authedClient(t)
		if err := c.sendFrame(protocol.ChannelTerm, 99, []byte("x")); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "unknown channel")
	})

	// The file channel type is enabled in M4, but only for channels the daemon
	// opened for a transfer. A file frame on an unopened channel id is
	// rejected as an unknown channel.
	t.Run("unopened file channel", func(t *testing.T) {
		_, c := authedClient(t)
		if err := c.sendFrame(protocol.ChannelFile, 1, []byte("x")); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "unknown channel")
	})

	t.Run("corrupt frame tag", func(t *testing.T) {
		_, c := authedClient(t)
		wire, err := c.cipher.SealFrame(protocol.ChannelCtrl, 0, ctrlJSON(1, protocol.TypeSessionList))
		if err != nil {
			t.Fatalf("seal: %v", err)
		}
		wire[len(wire)-1] ^= 0xFF
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := c.ws.Write(ctx, websocket.MessageBinary, wire); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "frame auth failed")
	})

	t.Run("text frame", func(t *testing.T) {
		_, c := authedClient(t)
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		if err := c.ws.Write(ctx, websocket.MessageText, []byte("hello")); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "text frame")
	})

	t.Run("control frame too large", func(t *testing.T) {
		_, c := authedClient(t)
		if err := c.sendFrame(protocol.ChannelCtrl, 0, make([]byte, protocol.MaxControlLen+1)); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "control frame too large")
	})

	t.Run("duplicate request id", func(t *testing.T) {
		_, c := authedClient(t)
		if r := c.request(t, ctrlJSON(7, protocol.TypeSessionList)); r.Error != nil {
			t.Fatalf("first list: %v", r.Error)
		}
		if err := c.sendFrame(protocol.ChannelCtrl, 0, ctrlJSON(7, protocol.TypeSessionList)); err != nil {
			t.Fatalf("send: %v", err)
		}
		c.expectClose(t, protocol.CloseProtocol, "duplicate request id")
	})

	t.Run("recoverable errors", func(t *testing.T) {
		_, c := authedClient(t)
		cases := []struct {
			name string
			req  []byte
			code string
		}{
			{"unknown type", ctrlJSON(1, "nope.nope"), protocol.CodeUnknownType},
			{"create missing kind", ctrlJSON(2, protocol.TypeSessionCreate), protocol.CodeInvalidRequest},
			{"unknown field", ctrlJSON(3, protocol.TypeSessionList, "bogus", 1), protocol.CodeInvalidRequest},
		}
		for _, tc := range cases {
			r := c.request(t, tc.req)
			if r.Error == nil || r.Error.Code != tc.code {
				t.Fatalf("%s: error = %+v, want %s", tc.name, r.Error, tc.code)
			}
		}
		// The connection survived all three.
		if r := c.request(t, ctrlJSON(4, protocol.TypeSessionList)); r.Error != nil {
			t.Fatalf("list after recoverable errors: %v", r.Error)
		}
		c.close(t, websocket.StatusNormalClosure, "bye")
	})
}

// TestOversizedFrame sends one raw frame past the transport read limit. The
// WebSocket library writes a 1009 close the moment the limit is hit, which is
// what the peer observes.
func TestOversizedFrame(t *testing.T) {
	_, c := authedClient(t)
	big := make([]byte, protocol.MaxPayloadLen+64<<10)
	go func() {
		ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		_ = c.ws.Write(ctx, websocket.MessageBinary, big)
	}()
	c.expectCloseCode(t, websocket.StatusMessageTooBig)
}

// TestSlowReaderOverflow floods one attached channel while its client reads
// slowly. The daemon must drop the slow attachment with reason overflow, keep
// the session running, and replay the retained scrollback to a fresh reader.
func TestSlowReaderOverflow(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create(), slowFrames(2*time.Millisecond))
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

	// 12.8 MiB of newline-free output plus a marker line. The ring's byte
	// cap keeps the tail, so the marker survives eviction.
	chunk := make([]byte, 8<<10)
	pushDone := make(chan struct{})
	go func() {
		defer close(pushDone)
		for i := 0; i < 1600; i++ {
			e.backend.proc(0).push(chunk)
		}
		e.backend.proc(0).push([]byte("\nrem-overflow-marker\n"))
	}()

	c.channelClose(t, ch, protocol.ReasonOverflow, 60*time.Second)
	<-pushDone

	got := e.sessions.List()
	if len(got) != 1 || !got[0].Running || got[0].ID != id {
		t.Fatalf("session did not survive the overflow: %+v", got)
	}

	// A fresh, fast reader gets the retained scrollback including the marker.
	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "phone")
	att2 := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att2.Error != nil || att2.ChannelID == nil {
		t.Fatalf("reattach: %v", att2.Error)
	}
	c2.termUntil(t, *att2.ChannelID, "rem-overflow-marker", 60*time.Second)
	c2.close(t, websocket.StatusNormalClosure, "bye")
	_ = c.ws.CloseNow()
}
