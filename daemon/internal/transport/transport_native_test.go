//go:build !windows

package transport

import (
	"testing"
	"time"

	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// TestRealPTY runs the full transport against a real shell in a real PTY:
// live output, input, resize, and exit propagation.
func TestRealPTY(t *testing.T) {
	e := newEnv(t, envCfg{backend: pty.New()})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate,
		"kind", protocol.KindShell, "cols", 80, "rows", 24))
	if resp.Error != nil || resp.Session == nil || !resp.Session.Running {
		t.Fatalf("create: %v %+v", resp.Error, resp.Session)
	}
	id := resp.Session.ID

	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	ch := *att.ChannelID

	// A shell arithmetic expression: proves the input reached a live PTY and
	// the output came back through it.
	c.sendTerm(t, ch, "echo rem-pty-$((6*7))\n")
	c.termUntil(t, ch, "rem-pty-42", 20*time.Second)

	if r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionResize,
		"session_id", id, "cols", 120, "rows", 40)); r.Error != nil {
		t.Fatalf("resize: %v", r.Error)
	}
	lst := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionList))
	if lst.Error != nil || len(lst.Sessions) != 1 {
		t.Fatalf("list: %v %d sessions", lst.Error, len(lst.Sessions))
	}
	m := lst.Sessions[0]
	if m.ID != id || m.Cols != 120 || m.Rows != 40 || !m.Running {
		t.Fatalf("session after resize: %+v", m)
	}

	if k := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionKill, "session_id", id)); k.Error != nil {
		t.Fatalf("kill: %v", k.Error)
	}
	// The exit produces both a channel.close and a session.update, in an
	// order the protocol leaves open.
	nots := c.awaitNotifs(t, 15*time.Second, protocol.TypeChannelClose, protocol.TypeSessionUpdate)
	cc := nots[protocol.TypeChannelClose]
	if cc.ChannelID == nil || *cc.ChannelID != ch {
		t.Fatalf("channel.close id = %v, want %d", cc.ChannelID, ch)
	}
	if cc.Reason == nil || *cc.Reason != protocol.ReasonSessionExited {
		t.Fatalf("channel.close reason = %v, want %q", cc.Reason, protocol.ReasonSessionExited)
	}
	sm := nots[protocol.TypeSessionUpdate].Session
	if sm == nil || sm.ID != id || sm.Running || sm.Exit == nil {
		t.Fatalf("session.update: %+v", sm)
	}
	waitFor(t, 15*time.Second, "session to show exited in the list", func() bool {
		lst := e.sessions.List()
		return len(lst) == 1 && !lst[0].Running
	})
	c.close(t, websocket.StatusNormalClosure, "bye")
}
