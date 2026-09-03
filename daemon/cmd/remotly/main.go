// Command remotely is the single Remotly binary: daemon and CLI. The daemon
// keeps PTY sessions alive on the development machine; the CLI subcommands
// manage pairing, devices, and sessions from the terminal.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"strings"

	"github.com/heavycaffeiner/remotly/daemon/internal/config"
	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
	"github.com/heavycaffeiner/remotly/daemon/internal/service"
)

// version is overridden at release builds with -ldflags.
var version = "0.1.0-dev"

func main() {
	os.Exit(run(os.Args[1:]))
}

func run(args []string) int {
	if len(args) == 0 {
		usage()
		return 2
	}
	// Everything after -- is a program, never a subcommand. Without it the
	// commands below permanently shadow programs that share their names, and
	// `run` and `status` are real ones.
	if args[0] == "--" {
		if len(args) == 1 {
			fmt.Fprintln(os.Stderr, "remotly: -- needs a program to run")
			return 2
		}
		return cmdExec(args[1:])
	}
	switch args[0] {
	case "version":
		fmt.Printf("remotly %s\n", version)
		return 0
	case "status":
		return cmdStatus()
	case "run":
		return cmdRun(args[1:])
	case "start":
		return cmdStart(args[1:])
	case "stop":
		return cmdStop()
	case "uninstall":
		return cmdUninstall()
	case "wipe":
		return cmdWipe(args[1:])
	case "pair":
		return cmdPair(args[1:])
	case "devices":
		return cmdDevices(args[1:])
	case "sessions":
		return cmdSessions(args[1:])
	case "doctor":
		return cmdDoctor(args[1:])
	case "attach":
		return cmdAttach(args[1:])
	case "tui":
		return cmdTUI(args[1:])
	case "help", "-h", "--help":
		usage()
		return 0
	default:
		// A flag is never a program: it is a mistyped subcommand, and running
		// it would start a session named after the mistake.
		if strings.HasPrefix(args[0], "-") {
			fmt.Fprintf(os.Stderr, "remotly: unknown command %q\n", args[0])
			usage()
			return 2
		}
		// A bare word only runs if it actually exists. Otherwise `remotly
		// statuss` would silently become a session that fails to start,
		// rather than the typo report the user needs.
		if _, err := exec.LookPath(args[0]); err != nil {
			fmt.Fprintf(os.Stderr, "remotly: unknown command %q, and no such program in PATH\n", args[0])
			usage()
			return 2
		}
		return cmdExec(args)
	}
}

func usage() {
	fmt.Fprint(os.Stderr, `usage: remotly <command>
       remotly <program> [args...]
       remotly -- <program> [args...]

Running a program starts it as a daemon session on this terminal. The session
outlives the terminal: detach with Ctrl-b d and the phone can attach to it.

A command name always wins, so use -- to run a program that shares one:
`+"`remotly -- run htop`"+` runs htop rather than the daemon.

commands:
  version     print the binary version
  status      show configuration, state files, and service state
  run         run the daemon in the foreground (prints a pairing QR when unpaired)
  start       install and start the per-user daemon service
  stop        stop the per-user daemon service
  uninstall   remove the service definition (keeps config and data)
  wipe        delete config and data (requires --yes)
  pair        print a one-time pairing QR code and link
  devices     list and revoke paired devices
  sessions    list and kill sessions
  attach      attach to a session by id
  tui         browse sessions and attach to one
  doctor      diagnose PTY session environment inheritance
`)
}

func cmdStatus() int {
	cfg, exists, err := config.Load(mustConfigPath())
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		return 1
	}
	fmt.Printf("remotly %s\n", version)
	if exists {
		fmt.Println("config: loaded")
	} else {
		fmt.Println("config: default (no file)")
	}
	fmt.Printf("loopback: %v port %d\n", cfg.Listen.Loopback, cfg.Listen.LoopbackPort)
	fmt.Printf("lan:      %v port %d\n", cfg.Listen.LAN, cfg.Listen.LANPort)
	fmt.Printf("term:     %s\n", cfg.Term)
	printDaemonState()
	if st, err := service.New().State(); err == nil {
		printServiceState(st)
	}
	return 0
}

// printDaemonState queries the running daemon for its live pairing and device
// state. It is best-effort: if the daemon is not running, it says so.
func printDaemonState() {
	resp, err := localctl.Call(mustLocalPath(), localctl.Request{Op: "status"})
	if err != nil || !resp.OK {
		fmt.Println("daemon:   not running")
		return
	}
	fmt.Printf("daemon:   running (active tokens: %d, paired devices: %d, lan allowed: %v)\n",
		resp.ActiveTokens, resp.PairedDevices, resp.LANAllowed)
}

func mustConfigPath() string {
	p, err := config.FilePath()
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		os.Exit(1)
	}
	return p
}
