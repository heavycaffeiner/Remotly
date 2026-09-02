package main

import (
	"os/exec"
	"testing"
)

// A mistyped subcommand must be reported, not executed.
//
// The default branch runs a bare word as a program, so without a PATH check
// `remotly statuss` would start a session named after the typo and fail
// somewhere the user cannot see, instead of saying the command is unknown.
func TestUnknownCommandIsNotRun(t *testing.T) {
	if _, err := exec.LookPath("statuss-not-a-real-program"); err == nil {
		t.Skip("a program by that name exists on this machine")
	}
	if code := run([]string{"statuss-not-a-real-program"}); code != 2 {
		t.Fatalf("exit = %d, want 2 (usage error)", code)
	}
}

// A flag is never a program: it is a mistyped subcommand.
func TestUnknownFlagIsAUsageError(t *testing.T) {
	if code := run([]string{"--nope"}); code != 2 {
		t.Fatalf("exit = %d, want 2", code)
	}
}

func TestBareDashDashIsAUsageError(t *testing.T) {
	if code := run([]string{"--"}); code != 2 {
		t.Fatalf("exit = %d, want 2", code)
	}
}

// version resolves as a command even though a `version` binary may exist.
func TestCommandsWinOverPrograms(t *testing.T) {
	if code := run([]string{"version"}); code != 0 {
		t.Fatalf("exit = %d, want 0", code)
	}
}
