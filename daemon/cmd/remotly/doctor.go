package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"os"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/config"
	"github.com/heavycaffeiner/remotly/daemon/internal/doctor"
)

// cmdDoctor runs the environment-inheritance diagnostic. It compares a
// daemon-path PTY session with a directly launched login shell and reports
// differences. It exits nonzero on any failing check.
func cmdDoctor(args []string) int {
	fs := flag.NewFlagSet("doctor", flag.ContinueOnError)
	fs.SetOutput(os.Stderr)
	jsonOut := fs.Bool("json", false, "print a machine-readable JSON report")
	shell := fs.String("shell", "", "shell to probe (default: the daemon's configured shell, then $SHELL, then the account entry)")
	term := fs.String("term", "", "TERM value to treat as the daemon override (default: the daemon's configured term)")
	timeoutSec := fs.Int("timeout", int(doctor.DefaultTimeout.Seconds()), "per-probe timeout in seconds")
	if err := fs.Parse(args); err != nil {
		return 2
	}

	// Fall back to the daemon's configured shell and term so the diagnostic
	// matches what the daemon would actually spawn.
	cfg, _, _ := config.Load(mustConfigPath())
	if *shell == "" {
		*shell = cfg.Shell
	}
	if *term == "" {
		*term = cfg.Term
	}

	rep, err := doctor.Run(context.Background(), doctor.Options{
		ConfiguredShell: *shell,
		Term:            *term,
		Timeout:         time.Duration(*timeoutSec) * time.Second,
	})
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: doctor: %v\n", err)
		return 1
	}

	if *jsonOut {
		enc := json.NewEncoder(os.Stdout)
		enc.SetIndent("", "  ")
		if err := enc.Encode(toReportJSON(rep)); err != nil {
			fmt.Fprintf(os.Stderr, "remotly: doctor: encode: %v\n", err)
			return 1
		}
	} else {
		printReport(rep)
	}

	if rep.HasFailure() {
		return 1
	}
	return 0
}

// reportJSON is the JSON shape of a report. Class is a string for readability.
type reportJSON struct {
	ShellPath   string      `json:"shellPath"`
	ShellSource string      `json:"shellSource"`
	Failure     bool        `json:"failure"`
	Checks      []checkJSON `json:"checks"`
}

type checkJSON struct {
	Name   string `json:"name"`
	Class  string `json:"class"`
	Detail string `json:"detail"`
}

func toReportJSON(rep *doctor.Report) reportJSON {
	r := reportJSON{
		ShellPath:   rep.ShellPath,
		ShellSource: rep.ShellSource,
		Failure:     rep.HasFailure(),
		Checks:      make([]checkJSON, 0, len(rep.Checks)),
	}
	for _, c := range rep.Checks {
		r.Checks = append(r.Checks, checkJSON{Name: c.Name, Class: c.Class.String(), Detail: c.Detail})
	}
	return r
}

// printReport renders the human-readable report. The class is a fixed-width
// tag so columns align; details are wrapped by the terminal.
func printReport(rep *doctor.Report) {
	fmt.Printf("shell: %s (%s)\n\n", rep.ShellPath, rep.ShellSource)
	for _, c := range rep.Checks {
		fmt.Printf("  [%-9s] %-20s %s\n", c.Class.String(), c.Name, c.Detail)
	}
	if rep.HasFailure() {
		fmt.Println("\ndiagnosis: failures found; see the details above for likely causes and next actions.")
	} else {
		fmt.Println("\ndiagnosis: environment inheritance looks healthy.")
	}
}
