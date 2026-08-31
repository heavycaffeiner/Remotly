# Windows OpenSSH interoperability harness

The in-process Go tests cover client behavior against a Go SSH server. They
cannot cover what actually differs on Windows: the console host, the default
shell, the server's auth policy, and its SFTP path semantics. This harness runs
the same client against a real Windows OpenSSH Server.

Nothing here contains a real credential. `setup.ps1` generates a throwaway user
and throwaway keys on the machine it runs on.

## Prepare the Windows host

Run in an elevated PowerShell on the Windows test machine:

```powershell
.\setup.ps1 -Scenario Password   # or: -Scenario Key
```

It installs and starts OpenSSH Server if needed, creates a local test account,
configures the scenario's authentication, and prints the host, port, username,
and host key fingerprints. Switch the default shell between runs:

```powershell
.\setup.ps1 -Scenario Password -DefaultShell PowerShell
.\setup.ps1 -Scenario Password -DefaultShell Cmd
```

Remove everything afterwards:

```powershell
.\cleanup.ps1
```

## Run the suite

From a machine that can reach the Windows host:

```sh
cd mobile
REMOTLY_WIN_SSH_HOST=192.168.1.50 \
REMOTLY_WIN_SSH_PORT=22 \
REMOTLY_WIN_SSH_USER=remotly-test \
REMOTLY_WIN_SSH_PASSWORD='<printed by setup.ps1>' \
go test ./sshcore -tags=winssh -run TestWindows -v
```

For the key scenario, point at the generated private key instead:

```sh
REMOTLY_WIN_SSH_KEY=/path/to/remotly_test_ed25519 \
REMOTLY_WIN_SSH_KEY_PASSPHRASE= \
go test ./sshcore -tags=winssh -run TestWindows -v
```

The suite is behind the `winssh` build tag and is skipped when the environment
is not set, so a normal `go test ./...` never needs a Windows host.

## What to record

The ticket asks for evidence, not just a pass. For each run capture:

- Windows build number and `(Get-Item $env:SystemRoot\System32\OpenSSH\sshd.exe).VersionInfo`
- `HKLM\SOFTWARE\OpenSSH\DefaultShell` if set
- The scenario and authentication method
- Server host key algorithms offered
- Whether the account is local, Microsoft, domain, or Entra-backed
- The client's stable error code and stage on failure
- OpenSSH Operational and Admin event log entries for the attempt

Never record passwords, private keys, or terminal content.

## Results

Not yet run. This harness is in place, but the Windows interoperability matrix
has not been executed, so the Windows runtime evidence is still outstanding.
