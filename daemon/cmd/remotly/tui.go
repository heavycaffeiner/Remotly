package main

import (
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
	"time"

	"github.com/gdamore/tcell/v2"
	"github.com/rivo/tview"
	"golang.org/x/term"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// How often the list re-reads the daemon, so a session started on the phone
// appears here without the user asking for it.
const tuiRefreshInterval = 2 * time.Second

// cmdTUI browses the daemon's sessions and opens one on this terminal.
//
// The same sessions are visible on the phone at the same time, so the list
// refreshes on a timer rather than only on a keystroke.
func cmdTUI(args []string) int {
	if len(args) > 0 {
		fmt.Fprintln(os.Stderr, "usage: remotly tui")
		return 2
	}
	if !term.IsTerminal(int(os.Stdin.Fd())) {
		fmt.Fprintln(os.Stderr, "remotly: tui requires a terminal")
		return 1
	}
	path := mustLocalPath()
	// Fail before taking over the screen, so "the daemon is not running" is
	// readable rather than flashing past on the way out.
	if _, err := loadSessions(path); err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		fmt.Fprintln(os.Stderr, "remotly: the daemon may not be running; start it with `remotly run`")
		return 1
	}
	return newTUI(path).run()
}

// tui is the session browser: a list on the left, the selected session's
// detail on the right, and a status line underneath.
type tui struct {
	path     string
	app      *tview.Application
	list     *tview.List
	detail   *tview.TextView
	status   *tview.TextView
	pages    *tview.Pages
	sessions []localctl.SessionOut
	// exitCode is what the process returns once the app stops.
	exitCode int
}

func newTUI(path string) *tui {
	t := &tui{path: path, app: tview.NewApplication()}

	t.list = tview.NewList().
		ShowSecondaryText(true).
		SetSelectedFocusOnly(false)
	t.list.SetBorder(true).SetTitle(" sessions ")
	t.list.SetChangedFunc(func(int, string, string, rune) { t.showDetail() })

	t.detail = tview.NewTextView().SetDynamicColors(true).SetWrap(true)
	t.detail.SetBorder(true).SetTitle(" detail ")

	t.status = tview.NewTextView().SetDynamicColors(true)
	t.setStatus("")

	body := tview.NewFlex().
		AddItem(t.list, 0, 3, true).
		AddItem(t.detail, 0, 4, false)

	root := tview.NewFlex().SetDirection(tview.FlexRow).
		AddItem(body, 0, 1, true).
		AddItem(t.status, 1, 0, false)

	t.pages = tview.NewPages().AddPage("main", root, true, true)
	t.app.SetRoot(t.pages, true).EnableMouse(false)
	t.app.SetInputCapture(t.onKey)
	return t
}

func (t *tui) run() int {
	t.reload()
	stop := make(chan struct{})
	go t.refreshLoop(stop)
	err := t.app.Run()
	close(stop)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		return 1
	}
	return t.exitCode
}

// refreshLoop re-reads the session list on a timer. It only redraws when
// something changed, so a still list does not flicker or fight the cursor.
func (t *tui) refreshLoop(stop <-chan struct{}) {
	tick := time.NewTicker(tuiRefreshInterval)
	defer tick.Stop()
	for {
		select {
		case <-stop:
			return
		case <-tick.C:
			sessions, err := loadSessions(t.path)
			if err != nil {
				continue
			}
			// The comparison runs on the UI goroutine because that is the one
			// that owns t.sessions: reading it here would race the redraw
			// that writes it.
			t.app.QueueUpdateDraw(func() {
				if sameSessions(sessions, t.sessions) {
					return
				}
				t.setSessions(sessions)
			})
		}
	}
}

// sameSessions reports whether two listings would draw identically. Comparing
// the fields the list shows is what keeps a periodic reload from stealing the
// selection on every tick.
func sameSessions(a, b []localctl.SessionOut) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i].ID != b[i].ID || a[i].Title != b[i].Title ||
			a[i].Running != b[i].Running || a[i].Cols != b[i].Cols ||
			a[i].Rows != b[i].Rows || !a[i].Active.Equal(b[i].Active) {
			return false
		}
	}
	return true
}

func (t *tui) reload() {
	sessions, err := loadSessions(t.path)
	if err != nil {
		t.setStatus(fmt.Sprintf("[red]%v", err))
		return
	}
	t.setSessions(sessions)
}

// setSessions repaints the list, keeping the highlighted session under the
// cursor where it still exists.
func (t *tui) setSessions(sessions []localctl.SessionOut) {
	want := t.selectedID()
	t.sessions = sessions
	t.list.Clear()
	for _, s := range sessions {
		state := "running"
		if !s.Running {
			state = "exited"
		}
		title := s.Title
		if title == "" {
			title = s.Kind
		}
		t.list.AddItem(
			title,
			fmt.Sprintf("%s  %s  %dx%d", shortID(s.ID), state, s.Cols, s.Rows),
			0, nil)
	}
	for i, s := range sessions {
		if s.ID == want {
			t.list.SetCurrentItem(i)
			break
		}
	}
	t.showDetail()
	if len(sessions) == 0 {
		t.setStatus("no sessions. [yellow]n[-] new shell   [yellow]q[-] quit")
		return
	}
	t.setStatus("")
}

func (t *tui) setStatus(msg string) {
	if msg == "" {
		msg = "[yellow]enter[-] attach   [yellow]n[-] new   [yellow]r[-] rename   " +
			"[yellow]k[-] kill   [yellow]q[-] quit"
	}
	t.status.SetText(" " + msg)
}

func (t *tui) selected() (localctl.SessionOut, bool) {
	i := t.list.GetCurrentItem()
	if i < 0 || i >= len(t.sessions) {
		return localctl.SessionOut{}, false
	}
	return t.sessions[i], true
}

func (t *tui) selectedID() string {
	s, ok := t.selected()
	if !ok {
		return ""
	}
	return s.ID
}

// showDetail fills the right pane for the highlighted session.
func (t *tui) showDetail() {
	s, ok := t.selected()
	if !ok {
		t.detail.SetText("")
		return
	}
	state := "[green]running"
	if !s.Running {
		state = "[gray]exited"
	}
	title := s.Title
	if title == "" {
		title = s.Kind
	}
	var b strings.Builder
	fmt.Fprintf(&b, "[white::b]%s[-::-]\n\n", tview.Escape(title))
	fmt.Fprintf(&b, "id       %s\n", s.ID)
	fmt.Fprintf(&b, "state    %s[-]\n", state)
	fmt.Fprintf(&b, "kind     %s\n", s.Kind)
	fmt.Fprintf(&b, "size     %dx%d\n", s.Cols, s.Rows)
	fmt.Fprintf(&b, "created  %s\n", s.Created.Local().Format(time.RFC3339))
	fmt.Fprintf(&b, "active   %s\n", s.Active.Local().Format(time.RFC3339))
	if s.Cwd != "" {
		fmt.Fprintf(&b, "cwd      %s\n", tview.Escape(s.Cwd))
	}
	if s.Command != "" {
		fmt.Fprintf(&b, "command  %s\n", tview.Escape(s.Command))
	}
	fmt.Fprintf(&b, "\n%s detaches once attached.\n", detachHint)
	t.detail.SetText(b.String())
}

func (t *tui) onKey(ev *tcell.EventKey) *tcell.EventKey {
	// A dialog is open: let it have the keys.
	if t.pages.GetPageCount() > 1 {
		return ev
	}
	switch ev.Key() {
	case tcell.KeyEnter:
		t.attachSelected()
		return nil
	case tcell.KeyEsc:
		t.app.Stop()
		return nil
	}
	switch ev.Rune() {
	case 'q':
		t.app.Stop()
		return nil
	case 'n':
		t.newShell()
		return nil
	case 'r':
		t.promptRename()
		return nil
	case 'k':
		t.promptKill()
		return nil
	}
	return ev
}

// attachSelected suspends the UI, runs the session on this terminal, and
// restores the UI when the attach ends.
func (t *tui) attachSelected() {
	s, ok := t.selected()
	if !ok {
		return
	}
	t.runOutsideUI(func() {
		exited, err := attachTerminal(t.path, s.ID)
		switch {
		case err != nil:
			t.setStatus(fmt.Sprintf("[red]%v", err))
		case exited:
			t.setStatus(fmt.Sprintf("session %s ended", shortID(s.ID)))
		}
	})
}

func (t *tui) newShell() {
	cols, rows := localctl.TerminalSize(os.Stdout, term.GetSize)
	id, err := localctl.CreateLocalSession(t.path, "", "shell", cols, rows)
	if err != nil {
		t.setStatus(fmt.Sprintf("[red]%v", err))
		return
	}
	t.runOutsideUI(func() {
		if exited, err := attachTerminal(t.path, id); err != nil {
			t.setStatus(fmt.Sprintf("[red]%v", err))
		} else if exited {
			t.setStatus(fmt.Sprintf("session %s ended", shortID(id)))
		}
	})
}

// runOutsideUI stops the tview screen, runs fn with the terminal to itself,
// then restarts the UI. An attach drives the raw terminal directly, so the
// two cannot be on screen at once.
func (t *tui) runOutsideUI(fn func()) {
	t.app.Suspend(fn)
	t.reload()
	t.app.Draw()
}

func (t *tui) promptRename() {
	s, ok := t.selected()
	if !ok {
		return
	}
	input := tview.NewInputField().
		SetLabel("name  ").
		SetText(s.Title).
		SetFieldWidth(40)
	form := tview.NewForm().
		AddFormItem(input).
		AddButton("Rename", func() {
			t.closeDialog()
			t.rename(s.ID, input.GetText())
		}).
		AddButton("Cancel", func() { t.closeDialog() })
	form.SetBorder(true).SetTitle(" rename session ")
	form.SetCancelFunc(func() { t.closeDialog() })
	t.openDialog(form, 60, 7)
}

func (t *tui) rename(id, title string) {
	if strings.TrimSpace(title) == "" {
		t.setStatus("[red]a session needs a name")
		return
	}
	resp, err := localctl.Call(t.path, localctl.Request{
		Op: "session_rename", SessionID: id, Title: title,
	})
	if err != nil {
		t.setStatus(fmt.Sprintf("[red]%v", err))
		return
	}
	if !resp.OK {
		t.setStatus("[red]" + resp.Err)
		return
	}
	t.reload()
}

func (t *tui) promptKill() {
	s, ok := t.selected()
	if !ok {
		return
	}
	title := s.Title
	if title == "" {
		title = s.Kind
	}
	modal := tview.NewModal().
		SetText(fmt.Sprintf("Kill %q?\n\nIts processes are terminated.", title)).
		AddButtons([]string{"Kill", "Cancel"}).
		SetDoneFunc(func(_ int, label string) {
			t.closeDialog()
			if label == "Kill" {
				t.kill(s.ID)
			}
		})
	t.pages.AddPage("dialog", modal, true, true)
}

func (t *tui) kill(id string) {
	resp, err := localctl.Call(t.path, localctl.Request{
		Op: "session_kill", SessionID: id,
	})
	if err != nil {
		t.setStatus(fmt.Sprintf("[red]%v", err))
		return
	}
	if !resp.OK {
		t.setStatus("[red]" + resp.Err)
		return
	}
	t.reload()
}

// openDialog centers a form over the list.
func (t *tui) openDialog(p tview.Primitive, width, height int) {
	wrap := tview.NewFlex().
		AddItem(nil, 0, 1, false).
		AddItem(tview.NewFlex().SetDirection(tview.FlexRow).
			AddItem(nil, 0, 1, false).
			AddItem(p, height, 0, true).
			AddItem(nil, 0, 1, false), width, 0, true).
		AddItem(nil, 0, 1, false)
	t.pages.AddPage("dialog", wrap, true, true)
}

func (t *tui) closeDialog() {
	t.pages.RemovePage("dialog")
	t.app.SetFocus(t.list)
}

func loadSessions(path string) ([]localctl.SessionOut, error) {
	resp, err := localctl.Call(path, localctl.Request{Op: "sessions"})
	if err != nil {
		return nil, err
	}
	if !resp.OK {
		return nil, errors.New(resp.Err)
	}
	out := resp.Sessions
	sort.Slice(out, func(i, j int) bool { return out[i].Created.Before(out[j].Created) })
	return out, nil
}
