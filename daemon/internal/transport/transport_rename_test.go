package transport

import (
	"strings"
	"testing"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
)

// Renaming a session over the control channel.
//
// The name is what a user picks a session out of a list by, so it has to
// survive on the daemon rather than living only in the client that set it.
func TestSessionRename(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	created := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate,
		"kind", protocol.KindShell))
	if created.Error != nil || created.Session == nil {
		t.Fatalf("session.create: %v", created.Error)
	}
	id := created.Session.ID

	ren := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionRename,
		"session_id", id, "title", "build logs"))
	if ren.Error != nil {
		t.Fatalf("session.rename: %v", ren.Error)
	}

	// The daemon is the source of truth: a fresh list carries the new name.
	list := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionList))
	if list.Error != nil {
		t.Fatalf("session.list: %v", list.Error)
	}
	var found string
	for _, s := range list.Sessions {
		if s.ID == id {
			found = s.Title
		}
	}
	if found != "build logs" {
		t.Fatalf("title = %q, want %q", found, "build logs")
	}
}

// A rename the session cannot accept is reported as an error, not by closing
// the connection.
func TestSessionRenameRejectsBadTitles(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	created := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionCreate,
		"kind", protocol.KindShell))
	if created.Error != nil || created.Session == nil {
		t.Fatalf("session.create: %v", created.Error)
	}
	id := created.Session.ID

	// Whitespace only: there would be nothing to read in the list.
	blank := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionRename,
		"session_id", id, "title", "   "))
	if blank.Error == nil {
		t.Fatal("blank title was accepted")
	}

	// The original name survives a rejected rename.
	list := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionList))
	for _, s := range list.Sessions {
		if s.ID == id && s.Title != "shell" {
			t.Fatalf("title = %q after a rejected rename, want %q", s.Title, "shell")
		}
	}

	// An over-long title is refused by the codec before it reaches the session.
	long := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionRename,
		"session_id", id, "title", strings.Repeat("x", protocol.MaxTitleLen+1)))
	if long.Error == nil {
		t.Fatal("over-long title was accepted")
	}
}

// Renaming a session that does not exist is an error response, not a closed
// connection.
func TestSessionRenameUnknownSession(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	resp := c.request(t, ctrlJSON(c.newID(), protocol.TypeSessionRename,
		"session_id", strings.Repeat("a", 64), "title", "nope"))
	if resp.Error == nil {
		t.Fatal("rename of an unknown session was accepted")
	}
}
