package session

import "testing"

// A program repaints its terminal title constantly: a shell does it on every
// prompt, usually with the working directory. The session has to keep
// following that, not freeze on the first one it sees.
func TestTerminalTitleKeepsTracking(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})

	for _, want := range []string{"~", "~/src", "~/src/remotly"} {
		if _, applied, err := s.SetTerminalTitle(want); err != nil || !applied {
			t.Fatalf("SetTerminalTitle(%q) applied=%v err=%v", want, applied, err)
		}
		if got := s.Meta().Title; got != want {
			t.Fatalf("title = %q, want %q", got, want)
		}
	}
}

// Once the user names a session, that name stands: the shell's next prompt
// must not take it back.
func TestRenamePinsTheTitle(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})

	if _, _, err := s.SetTerminalTitle("~/src"); err != nil {
		t.Fatal(err)
	}
	if _, err := s.SetTitle("build logs"); err != nil {
		t.Fatal(err)
	}
	if !s.Meta().TitlePinned {
		t.Fatal("a rename should pin the title")
	}

	m2, applied, err := s.SetTerminalTitle("~/other")
	if err != nil {
		t.Fatal(err)
	}
	if applied {
		t.Fatalf("terminal title overrode a user rename: %+v", m2)
	}
	if got := s.Meta().Title; got != "build logs" {
		t.Fatalf("title = %q, want the user's name", got)
	}
}

// A terminal title that is only whitespace is refused rather than blanking
// the session's label.
func TestTerminalTitleRejectsBlank(t *testing.T) {
	m, _ := newTestManager(t, Options{})
	s, _ := mustCreate(t, m, Request{Kind: KindShell})
	before := s.Meta().Title
	if _, _, err := s.SetTerminalTitle("   "); err == nil {
		t.Fatal("blank terminal title was accepted")
	}
	if got := s.Meta().Title; got != before {
		t.Fatalf("title = %q, want it unchanged at %q", got, before)
	}
}
