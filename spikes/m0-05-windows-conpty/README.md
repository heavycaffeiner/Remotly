# M0-05 spike: Windows ConPTY environment inheritance

Disposable Go spike proving a Go process can host PowerShell in a ConPTY with
normal profile and environment behavior, resize, Unicode I/O, Ctrl handling,
exit reporting, and process-tree cleanup. Windows-only; this host cross-compiles
it but cannot run it.

## Build and run (on Windows)

```
go build -o m0-05.exe .
.\m0-05.exe
```

Cross-compile to type-check from a non-Windows host:

```
GOOS=windows GOARCH=amd64 go build -o /dev/null .
```

## What it does

- Detects `pwsh` via `exec.LookPath`, falls back to `powershell.exe`, and fails
  clearly if neither is present. No command-string interpolation: the shell is
  launched with `windows.ComposeCommandLine` from a structured argument list.
- Creates a ConPTY via `CreatePseudoConsole` and attaches it to the child with
  `PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE`, so normal `$PROFILE` scripts run and
  the shell is interactive. Both ConPTY streams are UTF-8: input is written as
  raw UTF-8 bytes (including byte 0x03 for Ctrl-C), output is read as UTF-8.
- Launches from a minimal, service-like environment block (built as a proper
  UTF-16 null-separated block) with the user home as cwd. Only `TERM`,
  `COLORTERM`, and `REMOTLY_SESSION` are overridden on top of the minimal base.
- Runs an allowlisted PowerShell probe reporting shell path, version, profile
  path, cwd, home, PATH, the three overridden vars, window size, and a Unicode
  string. No full environment dump, so no profile secrets enter artifacts.
- Resizes the pseudoconsole and observes the shell's reported window size.
- Sends byte 0x03 to interrupt a foreground `Start-Sleep` and confirms the shell
  is still alive.
- Reports the exit code. `ClosePseudoConsole` itself terminates the attached
  process tree; the spike additionally walks `Toolhelp32Snapshot` as a
  belt-and-braces cleanup for the exit path before the pseudoconsole closes.

## Verification on this host

- `GOOS=windows go vet` and `GOOS=windows go build` both pass, so the ConPTY API
  usage type-checks against `golang.org/x/sys/windows`. `go vet` flags one
  `unsafe.Pointer` warning on the pseudoconsole attribute, a false positive:
  the documented pattern passes the `HPCON` handle value directly, which is what
  the code does.
- Runtime behavior (profiles, Unicode, resize, Ctrl, cleanup) is unverified here
  and must be confirmed on a physical or virtual Windows 11 host, with Windows 10
  covered if it remains in target range.

## WSL feasibility note

WSL is not wired up in this spike. A WSL session would be a separate launch path
(`wsl.exe -- <command>` in its own ConPTY), not a shell inside the same
pseudoconsole. It is deferred and does not change M1 scope.

## Handoff

M1-02 receives the shell-selection order (pwsh then powershell), the
`ComposeCommandLine` quoting rule, the ConPTY attribute pattern, the three env
overrides, and the process-tree cleanup approach. M5-01 receives the probe shape
for `remotly doctor`. The spike code is disposable.
