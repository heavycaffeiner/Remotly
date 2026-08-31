# Prepares a Windows OpenSSH Server for the Remotly interoperability suite.
#
# Creates a throwaway local account and throwaway keys on this machine. It
# contains no real credential and must not be pointed at a production host.
#
# Run elevated. Undo with cleanup.ps1.
[CmdletBinding()]
param(
    [ValidateSet('Password', 'Key')]
    [string]$Scenario = 'Password',

    [ValidateSet('Default', 'PowerShell', 'Cmd')]
    [string]$DefaultShell = 'Default',

    [string]$UserName = 'remotly-test',

    [int]$Port = 22
)

$ErrorActionPreference = 'Stop'

function Assert-Elevated {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = New-Object Security.Principal.WindowsPrincipal($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this script from an elevated PowerShell.'
    }
}

function Install-OpenSSHServer {
    $capability = Get-WindowsCapability -Online -Name 'OpenSSH.Server*'
    if ($capability.State -ne 'Installed') {
        Write-Host '>> installing OpenSSH Server'
        Add-WindowsCapability -Online -Name $capability.Name | Out-Null
    }
    Set-Service -Name sshd -StartupType Automatic
    Start-Service sshd
    Write-Host ">> sshd is running"
}

function New-TestUser {
    param([string]$Name)

    # A random throwaway password. It is printed once for the test run and is
    # never stored anywhere by this script.
    Add-Type -AssemblyName 'System.Web'
    $plain = [System.Web.Security.Membership]::GeneratePassword(24, 6)
    $secure = ConvertTo-SecureString $plain -AsPlainText -Force

    if (Get-LocalUser -Name $Name -ErrorAction SilentlyContinue) {
        Set-LocalUser -Name $Name -Password $secure
    } else {
        New-LocalUser -Name $Name -Password $secure -AccountNeverExpires:$true `
            -PasswordNeverExpires:$true -Description 'Remotly interop test account' | Out-Null
        Add-LocalGroupMember -Group 'Users' -Member $Name
    }
    return $plain
}

function Set-AuthPolicy {
    param([string]$Scenario)

    $config = "$env:ProgramData\ssh\sshd_config"
    $text = Get-Content $config -Raw

    switch ($Scenario) {
        'Password' {
            $text = $text -replace '(?m)^#?\s*PasswordAuthentication.*$', 'PasswordAuthentication yes'
            $text = $text -replace '(?m)^#?\s*PubkeyAuthentication.*$', 'PubkeyAuthentication no'
        }
        'Key' {
            $text = $text -replace '(?m)^#?\s*PasswordAuthentication.*$', 'PasswordAuthentication no'
            $text = $text -replace '(?m)^#?\s*PubkeyAuthentication.*$', 'PubkeyAuthentication yes'
        }
    }
    Set-Content -Path $config -Value $text -Encoding UTF8
    Restart-Service sshd
    Write-Host ">> auth policy set for scenario $Scenario"
}

function Install-TestKey {
    param([string]$Name)

    $keyPath = Join-Path $PSScriptRoot 'remotly_test_ed25519'
    if (Test-Path $keyPath) { Remove-Item "$keyPath*" -Force }
    ssh-keygen -t ed25519 -N '""' -C 'remotly-interop-test' -f $keyPath | Out-Null

    # A non-admin account reads its own authorized_keys from the profile.
    $profileDir = "C:\Users\$Name\.ssh"
    New-Item -ItemType Directory -Force -Path $profileDir | Out-Null
    Copy-Item "$keyPath.pub" (Join-Path $profileDir 'authorized_keys') -Force

    icacls $profileDir /inheritance:r | Out-Null
    icacls $profileDir /grant "${Name}:(F)" | Out-Null
    icacls (Join-Path $profileDir 'authorized_keys') /inheritance:r | Out-Null
    icacls (Join-Path $profileDir 'authorized_keys') /grant "${Name}:(F)" | Out-Null

    Write-Host ">> private key written to $keyPath"
    return $keyPath
}

function Set-DefaultShell {
    param([string]$Which)

    $key = 'HKLM:\SOFTWARE\OpenSSH'
    switch ($Which) {
        'Default' {
            Remove-ItemProperty -Path $key -Name DefaultShell -ErrorAction SilentlyContinue
            Write-Host '>> default shell: server default'
        }
        'PowerShell' {
            $exe = (Get-Command powershell.exe).Source
            New-ItemProperty -Path $key -Name DefaultShell -Value $exe -PropertyType String -Force | Out-Null
            Write-Host ">> default shell: $exe"
        }
        'Cmd' {
            $exe = (Get-Command cmd.exe).Source
            New-ItemProperty -Path $key -Name DefaultShell -Value $exe -PropertyType String -Force | Out-Null
            Write-Host ">> default shell: $exe"
        }
    }
}

Assert-Elevated
Install-OpenSSHServer
Set-DefaultShell -Which $DefaultShell
Set-AuthPolicy -Scenario $Scenario

$password = New-TestUser -Name $UserName
$keyPath = $null
if ($Scenario -eq 'Key') {
    $keyPath = Install-TestKey -Name $UserName
}

Write-Host ''
Write-Host '>> host key fingerprints'
Get-ChildItem "$env:ProgramData\ssh\ssh_host_*_key.pub" | ForEach-Object {
    ssh-keygen -l -f $_.FullName
}

$addresses = (Get-NetIPAddress -AddressFamily IPv4 |
    Where-Object { $_.IPAddress -ne '127.0.0.1' }).IPAddress -join ', '

Write-Host ''
Write-Host '>> test environment'
Write-Host "REMOTLY_WIN_SSH_HOST=$addresses"
Write-Host "REMOTLY_WIN_SSH_PORT=$Port"
Write-Host "REMOTLY_WIN_SSH_USER=$UserName"
if ($Scenario -eq 'Password') {
    Write-Host "REMOTLY_WIN_SSH_PASSWORD=$password"
} else {
    Write-Host "REMOTLY_WIN_SSH_KEY=$keyPath"
}
Write-Host ''
Write-Host 'This is a throwaway account. Run cleanup.ps1 when finished.'
