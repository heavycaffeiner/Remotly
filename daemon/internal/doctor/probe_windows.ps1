# Remotly doctor probe (Windows: pwsh and Windows PowerShell).
#
# Run by `remotly doctor` through `pwsh -Command` as a single structured
# argument. Reports the same allowlisted facts as the Unix probe between the
# BEGIN and END markers; the reader ignores everything outside the markers.
#
# Trust boundary: same as the Unix probe. Environment variable NAMES only,
# never values. A resolved command path is not a secret and is reported so
# PATH differences are concrete.
#
# PowerShell has no login/interactive flags the way Unix shells do; a ConPTY
# session runs the normal $PROFILE and is interactive, so LOGIN is reported as
# "n/a" and INTERACTIVE/TTY reflect whether the console input is redirected.

Write-Output 'REMOTLY-PROBE-BEGIN'

$edition = $PSVersionTable.PSEdition
if ($edition -eq 'Core') { $shellName = 'pwsh' } else { $shellName = 'powershell' }
Write-Output "SHELLNAME=$shellName"
Write-Output "VERSION=$($PSVersionTable.PSVersion.ToString())"
Write-Output 'LOGIN=n/a'

$redirected = [Console]::IsInputRedirected
if ($redirected) { $interactive = 'no' } else { $interactive = 'yes' }
Write-Output "INTERACTIVE=$interactive"
Write-Output "TTY=$interactive"
Write-Output "CWD=$((Get-Location).Path)"
Write-Output "TERMVAL=$($env:TERM)"
Write-Output "COLORTERMVAL=$($env:COLORTERM)"

Get-ChildItem Env: | ForEach-Object { "ENV=$($_.Name)" } | Sort-Object

foreach ($c in @('sh', 'bash', 'zsh', 'which', 'git', 'node', 'nvm', 'python3', 'pyenv', 'go')) {
    $cmd = Get-Command $c -ErrorAction SilentlyContinue
    if ($cmd) { Write-Output "CMD=$c=$($cmd.Source)" } else { Write-Output "CMD=$c=MISSING" }
}

Get-Alias | ForEach-Object { "ALIAS=$($_.Name)" } | Sort-Object
Get-Function | ForEach-Object { "FUNC=$($_.Name)" } | Sort-Object

Write-Output 'REMOTLY-PROBE-END'
