package main

import (
	"errors"
	"fmt"
	"io"
	"os"
	"sort"
	"strings"
	"time"

	"golang.org/x/term"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// cmdTUI browses the daemon's sessions and opens one on this terminal.
//
// It is a full-screen list rather than a scrolling prompt: the same session
// can be open on the phone at the same time, so the list has to stay honest
// about what is running, and redrawing in place is what makes that readable.
func cmdTUI(args []string) int {
	if len(args) > 0 {
		fmt.Fprintln(os.Stderr, "usage: remotly tui")
		return 2
	}
	path := mustLocalPath()
	for {
		sessions, err := loadSessions(path)
		if err != nil {
			fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
			fmt.Fprintln(os.Stderr, "remotly: the daemon may not be running; start it with `remotly run`")
			return 1
		}
		choice, err := pickSession(sessions)
		if err != nil {
			fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
			return 1
		}
		switch {
		case choice.quit:
			return 0
		case choice.newShell:
			cols, rows := localctl.TerminalSize(os.Stdout, term.GetSize)
			id, err := localctl.CreateLocalSession(path, "", "shell", cols, rows)
			if err != nil {
				fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
				return 1
			}
			runAttached(path, id)
		case choice.kill != "":
			if _, err := localctl.Call(path, localctl.Request{Op: "session_kill", SessionID: choice.kill}); err != nil {
				fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
			}
		case choice.attach != "":
			runAttached(path, choice.attach)
		}
	}
}

// runAttached attaches and reports how the attach ended, then returns to the
// list.
func runAttached(path, id string) {
	exited, err := attachTerminal(path, id)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		return
	}
	if exited {
		fmt.Fprintf(os.Stderr, "remotly: session %s ended\n", shortID(id))
	}
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

// choice is what the user picked from the list.
type choice struct {
	attach   string
	kill     string
	newShell bool
	quit     bool
}

// pickSession draws the list and reads one keystroke at a time until the user
// chooses. It runs in raw mode so a single key acts immediately.
func pickSession(sessions []localctl.SessionOut) (choice, error) {
	stdin := int(os.Stdin.Fd())
	if !term.IsTerminal(stdin) {
		return choice{}, errors.New("tui requires a terminal")
	}
	state, err := term.MakeRaw(stdin)
	if err != nil {
		return choice{}, fmt.Errorf("raw mode: %w", err)
	}
	defer func() { _ = term.Restore(stdin, state) }()

	cursor := 0
	killPending := false
	for {
		if cursor >= len(sessions) {
			cursor = maxInt(0, len(sessions)-1)
		}
		drawSessions(sessions, cursor, killPending)

		var b [1]byte
		if _, err := os.Stdin.Read(b[:]); err != nil {
			if errors.Is(err, io.EOF) {
				return choice{quit: true}, nil
			}
			return choice{}, err
		}
		key := b[0]
		if killPending {
			killPending = false
			if key == 'y' && cursor < len(sessions) {
				clearScreen()
				return choice{kill: sessions[cursor].ID}, nil
			}
			continue
		}
		switch key {
		case 'q', 0x03: // q or Ctrl-C
			clearScreen()
			return choice{quit: true}, nil
		case 'n':
			clearScreen()
			return choice{newShell: true}, nil
		case 'k':
			if cursor < len(sessions) {
				killPending = true
			}
		case 'j':
			if cursor+1 < len(sessions) {
				cursor++
			}
		case 0x0d, 0x0a: // Enter
			if cursor < len(sessions) {
				clearScreen()
				return choice{attach: sessions[cursor].ID}, nil
			}
		case 0x1b: // an arrow key arrives as ESC [ A/B
			var rest [2]byte
			if _, err := io.ReadFull(os.Stdin, rest[:]); err != nil {
				continue
			}
			if rest[0] != '[' {
				continue
			}
			switch rest[1] {
			case 'A':
				if cursor > 0 {
					cursor--
				}
			case 'B':
				if cursor+1 < len(sessions) {
					cursor++
				}
			}
		}
		if key == 'u' && cursor > 0 {
			cursor--
		}
	}
}

func clearScreen() {
	fmt.Print("\x1b[2J\x1b[H")
}

// drawSessions repaints the list. Raw mode means a bare \n does not return
// the cursor to column 0, so every line ends with \r\n.
func drawSessions(sessions []localctl.SessionOut, cursor int, killPending bool) {
	var b strings.Builder
	b.WriteString("\x1b[2J\x1b[H")
	b.WriteString("remotly sessions\r\n")
	b.WriteString(strings.Repeat("-", 60) + "\r\n")
	if len(sessions) == 0 {
		b.WriteString("no sessions\r\n")
	}
	for i, s := range sessions {
		marker := "  "
		if i == cursor {
			marker = "> "
		}
		state := "running"
		if !s.Running {
			state = "exited"
		}
		title := s.Title
		if title == "" {
			title = s.Kind
		}
		b.WriteString(fmt.Sprintf("%s%s  %-8s %-20s %dx%d  %s\r\n",
			marker, shortID(s.ID), state, truncate(title, 20), s.Cols, s.Rows,
			s.Active.Local().Format(time.Kitchen)))
	}
	b.WriteString(strings.Repeat("-", 60) + "\r\n")
	if killPending {
		b.WriteString("kill this session? y/n\r\n")
	} else {
		b.WriteString("enter attach   n new shell   k kill   j/u or arrows move   q quit\r\n")
		b.WriteString(fmt.Sprintf("inside a session, %s detaches\r\n", detachHint))
	}
	fmt.Print(b.String())
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	if n <= 1 {
		return s[:n]
	}
	return s[:n-1] + "…"
}

func maxInt(a, b int) int {
	if a > b {
		return a
	}
	return b
}
