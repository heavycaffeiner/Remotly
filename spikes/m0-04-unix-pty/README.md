# M0-04 spike: Unix PTY environment inheritance

Disposable Go spike proving that a process started with a minimal, service-like
environment can spawn an interactive login shell in a PTY that reconstructs the
user's normal shell environment. This runs and was verified on Linux; macOS is
covered by the same code path and needs a macOS run before M1-02.

## Build and run

```
go build -o m0-04 .
./m0-04 --minimal --timeout 20s
```

Flags:

- `--shell PATH`: override shell; default `$SHELL` then the passwd entry.
- `--cwd DIR`: working directory; default the user's home.
- `--minimal`: start from a minimal service-like env (default true). Set
  `--minimal=false` to inherit the full process env instead.
- `--probe PATH`: the allowlisted probe script (default `envprobe.sh`).
- `--rows` / `--cols`: initial PTY size.
- `--timeout`: per-step read timeout.

The command prints a JSON report with shell resolution, parent env, the child
probe result, resize before/after, an interrupt check, exit status, and failure
cases.

## What it proves

- Login + interactive: the child runs `shell -l` in a PTY, so it is a login
  shell (`PROBE_login=yes`) and interactive (`PROBE_interactive=yes`).
- Environment reconstruction: from a parent PATH of
  `/usr/local/bin:/usr/bin:/bin`, the child PATH becomes the full user PATH,
  including `~/.nvm/versions/node/.../bin`, pnpm, and `~/.local/bin`. The daemon
  delegates environment setup to the shell; it does not snapshot or replicate.
- Only three values are overridden: `TERM`, `COLORTERM`, `REMOTLY_SESSION`.
  Everything else the login shell sets is preserved.
- Aliases and version managers load (`PROBE_alias_ll=yes`, `PROBE_vm_nvm=nvm`).
- Resize: `stty size` tracks `pty.Setsize` (40x120 to 50x140).
- Interrupt: a foreground `sleep` is interrupted by an ETX byte (SIGINT via the
  tty line discipline), and the shell stays alive.
- Exit status is reported; invalid shell paths and cwd fail clearly without
  falling back to an unsafe directory or command.

## Comparison procedure

The spike captures the daemon-side result. To verify parity against a real
terminal, run the probe in a terminal you opened yourself and diff:

```
source envprobe.sh > /tmp/terminal-probe.txt
diff <(grep '^PROBE_' /tmp/terminal-probe.txt | sort) \
     <(./m0-04 --minimal | jq -r '.probe | to_entries[] | "PROBE_\(.key)=\(.value)"' | sort)
```

A non-empty diff shows exactly which environment fact the daemon path fails to
reconstruct.

## macOS notes

- Run the same binary on macOS. `$SHELL` there is usually zsh; the probe detects
  zsh and reports `login`/`interactive`/functions/aliases accordingly.
- When launched via launchd the daemon env is minimal; the login shell still
  rebuilds the environment, including `path_helper` output. Confirm the child
  PATH includes `/usr/local/bin` and `/opt/homebrew/bin` on Apple Silicon.

## Handoff

- M1-02: the `shell -l` invocation, the three overrides, the passwd fallback, and
  the probe format are the production contract. Spike code is not reused.
- M5-01: `remotly doctor` reuses the probe format for the env diff.

The spike code is disposable and must not be promoted to the daemon backend.
