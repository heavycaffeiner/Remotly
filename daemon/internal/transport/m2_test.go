package transport

import (
	"bytes"
	"sync"
	"testing"
	"time"

	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
)

// termBytes reads exactly n terminal bytes from channel ch.
func (c *client) termBytes(t *testing.T, ch uint32, n int, timeout time.Duration) []byte {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var got []byte
	for len(got) < n {
		select {
		case f := <-c.termOut:
			if f.chID == ch {
				got = append(got, f.payload...)
			}
		case <-c.dead:
			t.Fatalf("client: closed before %d bytes on channel %d; got %d", n, ch, len(got))
		case <-time.After(time.Until(deadline)):
			t.Fatalf("client: %d of %d bytes on channel %d within timeout", len(got), n, ch)
		}
	}
	return got
}

// termUntilClose reads terminal bytes from ch until its channel.close
// notification arrives, returning everything received.
func (c *client) termUntilClose(t *testing.T, ch uint32, timeout time.Duration) []byte {
	t.Helper()
	deadline := time.Now().Add(timeout)
	var got []byte
	for {
		select {
		case f := <-c.termOut:
			if f.chID == ch {
				got = append(got, f.payload...)
			}
		case n := <-c.notifs:
			if n.Type == protocol.TypeChannelClose && n.ChannelID != nil && *n.ChannelID == ch {
				return got
			}
		case <-c.dead:
			t.Fatalf("client: closed while waiting for channel %d close; got %d bytes", ch, len(got))
		case <-time.After(time.Until(deadline)):
			t.Fatalf("client: channel %d close not seen within timeout; got %d bytes", ch, len(got))
		}
	}
}

// TestM2ReconnectGapless is the M2-03 core scenario: an app attaches,
// consumes a prefix of the stream, the transport drops, the session keeps
// producing, and the app reattaches with a resume cursor. The continuation
// must be byte-exact: no duplicate bytes, no missing bytes.
func TestM2ReconnectGapless(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: false})
	app := newAppKey(t)

	c1 := e.newClientPair(t, app, e.tokens.Create())
	c1.hello(t, e, "phone")
	resp := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	proc := e.backend.proc(0)

	part1 := "first half of the stream\n"
	proc.push([]byte(part1))
	att := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	if att.Continuity == nil || *att.Continuity != protocol.ContinuityFull {
		t.Fatalf("continuity %v, want full", att.Continuity)
	}
	got := c1.termBytes(t, *att.ChannelID, len(part1), 5*time.Second)
	if !bytes.Equal(got, []byte(part1)) {
		t.Fatalf("first attach stream %q, want %q", got, part1)
	}
	cursor := uint64(len(got))

	c1.close(t, websocket.StatusNormalClosure, "bye")

	part2 := "second half\n"
	proc.push([]byte(part2))

	// Reconnect with the device key over IK and resume from the cursor.
	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "phone")
	att2 := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach,
		"session_id", id, "resume_from", cursor))
	if att2.Error != nil || att2.ChannelID == nil {
		t.Fatalf("reattach: %v", att2.Error)
	}
	if att2.Continuity == nil || *att2.Continuity != protocol.ContinuityGapless {
		t.Fatalf("continuity %v, want gapless", att2.Continuity)
	}
	if att2.ReplayedFrom == nil || *att2.ReplayedFrom != cursor {
		t.Fatalf("replayed_from %v, want %d", att2.ReplayedFrom, cursor)
	}
	got2 := c2.termBytes(t, *att2.ChannelID, len(part2), 5*time.Second)
	if !bytes.Equal(got2, []byte(part2)) {
		t.Fatalf("resumed stream %q, want exactly %q", got2, part2)
	}

	proc.terminate(pty.ExitStatus{Exited: true, Code: 0})
	c2.channelClose(t, *att2.ChannelID, protocol.ReasonSessionExited, 5*time.Second)
}

// TestM2ReconnectGap forces eviction with a small line cap: the cursor is
// older than the retained window, so the daemon must report a gap and serve
// the retained stream from its start.
func TestM2ReconnectGap(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: false, scrollbackLines: 1024})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	proc := e.backend.proc(0)

	// Attach first, on an empty ring: everything pushed below is live
	// output on this stream. A concurrent reader drains the stream as we
	// push in batches (a never-read reader would overflow and be dropped);
	// reading all 200000 bytes is a drain barrier proving the ring absorbed
	// and evicted the whole stream.
	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	const lines = 2000
	const lineBytes = 100
	var pushed []byte
	for i := 0; i < lines; i++ {
		line := make([]byte, lineBytes)
		for j := range line {
			line[j] = byte('a' + i%26)
		}
		line[lineBytes-1] = '\n'
		pushed = append(pushed, line...)
	}
	// A concurrent reader drains the stream as we push; a never-read reader
	// would overflow its queue and be dropped. The reader collects into a
	// buffer and signals when it has everything.
	var rdMu sync.Mutex
	var rdOut []byte
	rdDone := make(chan struct{})
	go func() {
		defer close(rdDone)
		for {
			rdMu.Lock()
			have := len(rdOut)
			rdMu.Unlock()
			if have >= len(pushed) {
				return
			}
			select {
			case f := <-c.termOut:
				if f.chID == *att.ChannelID {
					rdMu.Lock()
					rdOut = append(rdOut, f.payload...)
					rdMu.Unlock()
				}
			case <-c.dead:
				return
			}
		}
	}()
	// Push in batches, waiting for the reader to catch up between batches so
	// no intermediate queue ever fills.
	for i := 0; i < lines; i++ {
		proc.push(pushed[i*lineBytes : (i+1)*lineBytes])
		if (i+1)%64 == 0 {
			want := (i + 1) * lineBytes
			deadline := time.Now().Add(10 * time.Second)
			for {
				rdMu.Lock()
				have := len(rdOut)
				rdMu.Unlock()
				if have >= want {
					break
				}
				if time.Now().After(deadline) {
					t.Fatalf("reader stalled at %d of %d bytes", have, want)
				}
				time.Sleep(time.Millisecond)
			}
		}
	}
	<-rdDone
	rdMu.Lock()
	got := append([]byte(nil), rdOut...)
	rdMu.Unlock()
	if !bytes.Equal(got, pushed) {
		t.Fatalf("live stream differs from pushed output")
	}

	// The ring kept the last 1024 lines (102400 bytes): the retained
	// stream starts at byte 97600 and is the tail of the pushed output.
	const kept = 1024 * lineBytes
	ringStart := uint64(len(pushed) - kept)
	want := pushed[len(pushed)-kept:]

	c.close(t, websocket.StatusNormalClosure, "bye")

	// A cursor deep inside the evicted region is a gap.
	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "phone")
	att2 := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach,
		"session_id", id, "resume_from", 20000))
	if att2.Error != nil || att2.ChannelID == nil {
		t.Fatalf("reattach: %v", att2.Error)
	}
	if att2.Continuity == nil || *att2.Continuity != protocol.ContinuityGap {
		t.Fatalf("continuity %v, want gap", att2.Continuity)
	}
	if att2.ReplayedFrom == nil || *att2.ReplayedFrom != ringStart {
		t.Fatalf("replayed_from %v, want ring start %d", att2.ReplayedFrom, ringStart)
	}
	gap := c2.termBytes(t, *att2.ChannelID, kept, 10*time.Second)
	if !bytes.Equal(gap, want) {
		t.Fatal("gap replay differs from the retained window")
	}

	// A fresh attach on the same session serves the identical window.
	att3 := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att3.Error != nil || att3.ChannelID == nil {
		t.Fatalf("second attach: %v", att3.Error)
	}
	if att3.Continuity == nil || *att3.Continuity != protocol.ContinuityFull {
		t.Fatalf("continuity %v, want full", att3.Continuity)
	}
	if att3.ReplayedFrom == nil || *att3.ReplayedFrom != ringStart {
		t.Fatalf("replayed_from %v, want ring start %d", att3.ReplayedFrom, ringStart)
	}
	full := c2.termBytes(t, *att3.ChannelID, kept, 10*time.Second)
	if !bytes.Equal(full, want) {
		t.Fatal("full replay differs from the retained window")
	}
}

// TestM2CursorOutOfRange rejects a cursor beyond the session's total output.
func TestM2CursorOutOfRange(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: false})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	e.backend.proc(0).push([]byte("abc"))

	r := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach,
		"session_id", id, "resume_from", 4))
	if r.Error == nil || r.Error.Code != protocol.CodeCursorOutOfRange {
		t.Fatalf("attach: %+v, want cursor_out_of_range", r.Error)
	}
}

// TestM2ExitedSessionReplay: an exited session stays listed and attachable
// for its retention window; the final attach serves the whole stream and
// then closes the channel.
func TestM2ExitedSessionReplay(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: false})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	proc := e.backend.proc(0)
	proc.push([]byte("last words\n"))
	proc.terminate(pty.ExitStatus{Exited: true, Code: 0})

	// Wait for the session.update with the final metadata.
	m := c.sessionUpdate(t, id, 10*time.Second)
	if m.Running || m.Exit == nil {
		t.Fatalf("final meta %+v", m)
	}
	// The exited session is still in the list during the retention window.
	waitFor(t, 5*time.Second, "exited session listed", func() bool {
		lst := e.sessions.List()
		return len(lst) == 1 && !lst[0].Running
	})

	att := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	if att.Continuity == nil || *att.Continuity != protocol.ContinuityFull {
		t.Fatalf("continuity %v, want full", att.Continuity)
	}
	got := c.termUntilClose(t, *att.ChannelID, 5*time.Second)
	if string(got) != "last words\n" {
		t.Fatalf("final replay %q", got)
	}
}

// TestM2PresetList serves the configured presets to the app.
func TestM2PresetList(t *testing.T) {
	e := newEnv(t, envCfg{
		lanEnabled: false,
		presets: []protocol.Preset{
			{Name: "claude", Command: "claude", IconHint: "spark"},
			{Name: "codex", Command: "codex"},
		},
	})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	r := c.request(t, ctrlJSON(c.newID(), protocol.TypePresetList))
	if r.Error != nil {
		t.Fatalf("preset.list: %v", r.Error)
	}
	if len(r.Presets) != 2 || r.Presets[0].Name != "claude" || r.Presets[0].IconHint != "spark" ||
		r.Presets[1].Command != "codex" || r.Presets[1].IconHint != "" {
		t.Fatalf("presets %+v", r.Presets)
	}
}

// TestM2SessionEvents delivers bell and pattern events as notifications.
// Events are content: the test asserts exact text, and nothing here may
// reach a log.
//
// An agent session, because a bell notifies only for those: an interactive
// shell rings it as ordinary line-editor feedback and notifying on that
// turned normal typing into a stream of notifications.
func TestM2SessionEvents(t *testing.T) {
	e := newEnv(t, envCfg{
		lanEnabled: false,
		events: &session.Events{
			BellEnabled: true,
			Patterns: []session.PatternSpec{
				{Name: "error", Expr: "error: .*"},
			},
		},
	})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")
	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate,
		"kind", protocol.KindAgent, "command", "run-agent"))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	proc := e.backend.proc(0)

	proc.push([]byte("compiling\x07"))
	bell := c.notifUntil(t, protocol.TypeSessionEvent, 5*time.Second)
	if bell.SessionID == nil || *bell.SessionID != id || bell.Seq == nil || *bell.Seq != 1 ||
		bell.Kind == nil || *bell.Kind != protocol.EventBell || bell.Pattern != nil ||
		bell.Text == nil || *bell.Text != "compiling" || bell.Ts == nil || *bell.Ts <= 0 {
		t.Fatalf("bell: %+v", bell)
	}

	proc.push([]byte("error: cannot find symbol\n"))
	pat := c.notifUntil(t, protocol.TypeSessionEvent, 5*time.Second)
	if pat.Seq == nil || *pat.Seq != 2 || pat.Kind == nil || *pat.Kind != protocol.EventPattern ||
		pat.Pattern == nil || *pat.Pattern != "error" || pat.Text == nil ||
		*pat.Text != "error: cannot find symbol" {
		t.Fatalf("pattern: %+v", pat)
	}
}

// TestM2EventBroadcast reaches every authenticated connection: a second
// device attached to the daemon sees the same event.
func TestM2EventBroadcast(t *testing.T) {
	e := newEnv(t, envCfg{
		lanEnabled: false,
		events:     &session.Events{BellEnabled: true},
	})
	app := newAppKey(t)
	c1 := e.newClientPair(t, app, e.tokens.Create())
	c1.hello(t, e, "phone")
	// A second, independent connection from the same app.
	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "tablet")

	resp := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionCreate,
		"kind", protocol.KindAgent, "command", "run-agent"))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	e.backend.proc(0).push([]byte("\x07"))

	n1 := c1.notifUntil(t, protocol.TypeSessionEvent, 5*time.Second)
	n2 := c2.notifUntil(t, protocol.TypeSessionEvent, 5*time.Second)
	if n1.Seq == nil || n2.Seq == nil || *n1.Seq != *n2.Seq || *n1.Seq != 1 {
		t.Fatalf("broadcast seqs: %v %v", n1.Seq, n2.Seq)
	}
	if n1.SessionID == nil || n2.SessionID == nil || *n1.SessionID != *n2.SessionID {
		t.Fatalf("broadcast ids: %v %v", n1.SessionID, n2.SessionID)
	}
}

// waitForReplayComplete drains notifications until the channel's
// replay_complete arrives. The mux serves control frames with priority, so
// the notification may land before the final replay bytes; no ordering is
// assumed.
func (c *client) waitForReplayComplete(t *testing.T, ch uint32, timeout time.Duration) uint64 {
	t.Helper()
	deadline := time.Now().Add(timeout)
	for {
		select {
		case n := <-c.notifs:
			if n.Type == protocol.TypeChannelReplayComplete && n.ChannelID != nil && *n.ChannelID == ch {
				if n.Offset == nil {
					t.Fatalf("replay_complete without offset: %+v", n)
				}
				return *n.Offset
			}
		case <-c.dead:
			t.Fatalf("client closed before replay_complete for channel %d", ch)
		case <-time.After(time.Until(deadline)):
			t.Fatalf("replay_complete for channel %d not seen within timeout", ch)
		}
	}
}

// TestM2ReplayComplete checks the replay/live boundary notification and the
// client cursor rule it supports: resume cursor is replayed_from plus the
// term bytes received on the channel. The cursor must let a reconnect
// replay neither duplicate nor missing output.
func TestM2ReplayComplete(t *testing.T) {
	e := newEnv(t, envCfg{lanEnabled: false})
	app := newAppKey(t)
	c1 := e.newClientPair(t, app, e.tokens.Create())
	c1.hello(t, e, "phone")
	resp := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	proc := e.backend.proc(0)

	// Attach on a session that already has 4 bytes: the whole stream is
	// replay (replayed_from 0), so exactly one replay_complete must carry
	// offset 4.
	proc.push([]byte("ABCD"))
	att := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("attach: %v", att.Error)
	}
	if att.ReplayedFrom == nil || *att.ReplayedFrom != 0 {
		t.Fatalf("replayed_from %v, want 0", att.ReplayedFrom)
	}
	c1.termBytes(t, *att.ChannelID, 4, 5*time.Second)
	if off := c1.waitForReplayComplete(t, *att.ChannelID, 5*time.Second); off != 4 {
		t.Fatalf("initial replay_complete offset %d, want 4", off)
	}
	// Client cursor rule: replayed_from + bytes received = 4.
	cursor := *att.ReplayedFrom + uint64(4)

	// Live output after the boundary advances the cursor by the bytes
	// received.
	proc.push([]byte("EF"))
	c1.termBytes(t, *att.ChannelID, 2, 5*time.Second)
	cursor += uint64(2) // 6

	// Drop the transport, let the session advance, and resume from the
	// client cursor: the replay is exactly EF (no duplicate, no gap) and
	// the notification reports the new boundary 6.
	c1.close(t, websocket.StatusNormalClosure, "bye")
	proc.push([]byte("GH"))
	c2 := e.newClientIK(t, app)
	c2.hello(t, e, "phone")
	att2 := c2.request(t, ctrlJSON(c2.newID(), protocol.TypeSessionAttach,
		"session_id", id, "resume_from", cursor))
	if att2.Error != nil || att2.ChannelID == nil {
		t.Fatalf("reattach: %v", att2.Error)
	}
	if att2.ReplayedFrom == nil || *att2.ReplayedFrom != cursor {
		t.Fatalf("replayed_from %v, want %d", att2.ReplayedFrom, cursor)
	}
	// EF was already delivered to c1 before it closed, so the replay is
	// exactly GH: no duplicate, no gap.
	if got := c2.termBytes(t, *att2.ChannelID, 2, 5*time.Second); string(got) != "GH" {
		t.Fatalf("resumed stream %q, want exactly GH", got)
	}
	if off := c2.waitForReplayComplete(t, *att2.ChannelID, 5*time.Second); off != 8 {
		t.Fatalf("resume replay_complete offset %d, want 8", off)
	}
}

// TestM2EventNoDuplicateOnReplay: replayed output must not re-fire events.
// The matcher is fed by the live drain path only; a reattach replays the
// ring without notifications.
func TestM2EventNoDuplicateOnReplay(t *testing.T) {
	e := newEnv(t, envCfg{
		lanEnabled: false,
		events:     &session.Events{Patterns: []session.PatternSpec{{Name: "hit", Expr: "needle"}}},
	})
	app := newAppKey(t)
	c1 := e.newClientPair(t, app, e.tokens.Create())
	c1.hello(t, e, "phone")
	resp := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionCreate, "kind", protocol.KindShell))
	if resp.Error != nil || resp.Session == nil {
		t.Fatalf("create: %v", resp.Error)
	}
	id := resp.Session.ID
	proc := e.backend.proc(0)

	proc.push([]byte("the needle is here\n"))
	n := c1.notifUntil(t, protocol.TypeSessionEvent, 5*time.Second)
	if n.Seq == nil || *n.Seq != 1 {
		t.Fatalf("first event seq %v", n.Seq)
	}

	// Reattach from the start: the replay contains "needle" again, but no
	// new event may fire.
	att := c1.request(t, ctrlJSON(c1.newID(), protocol.TypeSessionAttach, "session_id", id))
	if att.Error != nil || att.ChannelID == nil {
		t.Fatalf("reattach: %v", att.Error)
	}
	got := c1.termBytes(t, *att.ChannelID, len("the needle is here\n"), 5*time.Second)
	if string(got) != "the needle is here\n" {
		t.Fatalf("replay %q", got)
	}
	select {
	case ev := <-c1.notifs:
		if ev.Type == protocol.TypeSessionEvent {
			t.Fatalf("replay re-fired event: %+v", ev)
		}
	case <-time.After(200 * time.Millisecond):
	}
}
