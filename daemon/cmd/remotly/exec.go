package main

import (
	"fmt"
	"os"
	"strings"

	"golang.org/x/term"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// cmdExec starts a program as a daemon session and shows it on this terminal.
//
// The session belongs to the daemon, not to this process: detaching leaves it
// running and the phone can attach to the same terminal. That is the whole
// point of running a program this way rather than in the shell directly.
func cmdExec(args []string) int {
	if len(args) == 0 {
		fmt.Fprintln(os.Stderr, "usage: remotly <program> [args...]")
		return 2
	}
	command := strings.Join(args, " ")
	path := mustLocalPath()

	cols, rows := localctl.TerminalSize(os.Stdout, term.GetSize)
	id, err := localctl.CreateLocalSession(path, command, args[0], cols, rows)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		fmt.Fprintln(os.Stderr, "remotly: the daemon may not be running; start it with `remotly run`")
		return 1
	}

	fmt.Fprintf(os.Stderr, "remotly: session %s (%s to detach)\n", shortID(id), detachHint)
	exited, err := attachTerminal(path, id)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		return 1
	}
	if !exited {
		fmt.Fprintf(os.Stderr, "remotly: detached; reattach with `remotly attach %s`\n", shortID(id))
	}
	return 0
}

// cmdAttach reattaches to an existing session by id or unique id prefix.
func cmdAttach(args []string) int {
	if len(args) != 1 || args[0] == "--help" || args[0] == "-h" {
		fmt.Fprintln(os.Stderr, "usage: remotly attach <session-id>")
		return 2
	}
	path := mustLocalPath()
	id, err := resolveSessionID(args[0])
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		return 1
	}
	fmt.Fprintf(os.Stderr, "remotly: attached to %s (%s to detach)\n", shortID(id), detachHint)
	exited, err := attachTerminal(path, id)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		return 1
	}
	if !exited {
		fmt.Fprintln(os.Stderr, "remotly: detached")
	}
	return 0
}

// resolveSessionID expands a unique id prefix to a full session id. Full ids
// are 64 hex characters, which nobody types by hand.
func resolveSessionID(want string) (string, error) {
	resp, err := localctl.Call(mustLocalPath(), localctl.Request{Op: "sessions"})
	if err != nil {
		return "", err
	}
	if !resp.OK {
		return "", fmt.Errorf("%s", resp.Err)
	}
	var found []string
	for _, s := range resp.Sessions {
		if strings.HasPrefix(s.ID, want) {
			found = append(found, s.ID)
		}
	}
	switch len(found) {
	case 0:
		return "", fmt.Errorf("no session matches %q", want)
	case 1:
		return found[0], nil
	default:
		return "", fmt.Errorf("%q matches %d sessions; use more characters", want, len(found))
	}
}

// shortIDLen is how much of a session id is shown and is enough to type back.
const shortIDLen = 12

func shortID(id string) string {
	if len(id) <= shortIDLen {
		return id
	}
	return id[:shortIDLen]
}
