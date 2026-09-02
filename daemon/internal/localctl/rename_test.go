//go:build !windows

package localctl

import (
	"sync"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/session"
)

// Renaming from the CLI or the TUI has to reach the phones as well.
//
// The local control channel and the app's transport are separate paths into
// the same sessions, so a rename made here is invisible to a connected app
// unless it is broadcast. Without that the phone kept showing the old name
// until something else forced a re-list.
func TestRenameNotifiesConnectedApps(t *testing.T) {
	env := startServer(t)

	var mu sync.Mutex
	var got []session.Metadata
	env.srv.SetOnSessionUpdate(func(m session.Metadata) {
		mu.Lock()
		got = append(got, m)
		mu.Unlock()
	})

	resp, err := Call(env.path, Request{
		Op: "session_create", Title: "ctl", Cols: 80, Rows: 24,
	})
	if err != nil || !resp.OK {
		t.Fatalf("session_create: %v %s", err, resp.Err)
	}
	id := resp.SessionID
	t.Cleanup(func() {
		if s, err := env.sessions.Get(id); err == nil {
			_ = s.Kill()
		}
	})

	resp, err = Call(env.path, Request{
		Op: "session_rename", SessionID: id, Title: "build logs",
	})
	if err != nil || !resp.OK {
		t.Fatalf("session_rename: %v %s", err, resp.Err)
	}

	mu.Lock()
	defer mu.Unlock()
	if len(got) != 1 {
		t.Fatalf("broadcast %d updates, want 1", len(got))
	}
	if got[0].ID != id || got[0].Title != "build logs" {
		t.Fatalf("update = %+v", got[0])
	}
	if !got[0].TitlePinned {
		t.Fatal("a rename should pin the title")
	}
}

// A rejected rename must not tell the apps anything changed.
func TestRejectedRenameDoesNotNotify(t *testing.T) {
	env := startServer(t)

	var mu sync.Mutex
	calls := 0
	env.srv.SetOnSessionUpdate(func(session.Metadata) {
		mu.Lock()
		calls++
		mu.Unlock()
	})

	resp, err := Call(env.path, Request{
		Op: "session_create", Title: "ctl", Cols: 80, Rows: 24,
	})
	if err != nil || !resp.OK {
		t.Fatalf("session_create: %v %s", err, resp.Err)
	}
	id := resp.SessionID
	t.Cleanup(func() {
		if s, err := env.sessions.Get(id); err == nil {
			_ = s.Kill()
		}
	})

	resp, err = Call(env.path, Request{
		Op: "session_rename", SessionID: id, Title: "   ",
	})
	if err != nil {
		t.Fatal(err)
	}
	if resp.OK {
		t.Fatal("a blank rename was accepted")
	}

	// Give a stray goroutine a moment to get it wrong.
	time.Sleep(50 * time.Millisecond)
	mu.Lock()
	defer mu.Unlock()
	if calls != 0 {
		t.Fatalf("broadcast %d updates for a rejected rename, want 0", calls)
	}
}
