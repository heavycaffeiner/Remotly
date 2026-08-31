package main

import (
	"fmt"
	"os"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// cmdSessions lists daemon sessions, or kills one with `sessions kill`.
func cmdSessions(args []string) int {
	if len(args) > 0 {
		switch args[0] {
		case "kill":
			return cmdSessionsKill(args[1:])
		case "--help", "-h":
			fmt.Fprintln(os.Stderr, "usage: remotely sessions\n       remotely sessions kill <session-id>")
			return 0
		default:
			fmt.Fprintf(os.Stderr, "remotly: unknown sessions subcommand %q\n", args[0])
			return 2
		}
	}
	resp, ok := callDaemon(localctl.Request{Op: "sessions"})
	if !ok {
		return 1
	}
	if len(resp.Sessions) == 0 {
		fmt.Println("no sessions")
		return 0
	}
	for _, s := range resp.Sessions {
		state := "running"
		if !s.Running {
			state = "exited"
		}
		fmt.Printf("%s  %-6s %-9s %-6s %dx%d  %s\n",
			s.ID, s.Kind, state, s.Title, s.Cols, s.Rows, s.Created.Local().Format(time.RFC3339))
	}
	return 0
}

func cmdSessionsKill(args []string) int {
	if len(args) != 1 {
		fmt.Fprintln(os.Stderr, "usage: remotely sessions kill <session-id>")
		return 2
	}
	resp, ok := callDaemon(localctl.Request{Op: "session_kill", SessionID: args[0]})
	if !ok {
		return 1
	}
	_ = resp
	fmt.Println("killed")
	return 0
}
