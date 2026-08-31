# Removes what setup.ps1 created: the throwaway account, its profile keys, the
# generated key pair, and the DefaultShell override. The sshd_config auth policy
# is left as it stands, because this script does not know what it was before.
[CmdletBinding()]
param(
    [string]$UserName = 'remotly-test'
)

$ErrorActionPreference = 'Continue'

if (Get-LocalUser -Name $UserName -ErrorAction SilentlyContinue) {
    Remove-LocalUser -Name $UserName
    Write-Host ">> removed local user $UserName"
}

$profileDir = "C:\Users\$UserName"
if (Test-Path $profileDir) {
    Remove-Item $profileDir -Recurse -Force -ErrorAction SilentlyContinue
    Write-Host ">> removed $profileDir"
}

Get-ChildItem -Path $PSScriptRoot -Filter 'remotly_test_ed25519*' -ErrorAction SilentlyContinue |
    ForEach-Object {
        Remove-Item $_.FullName -Force
        Write-Host ">> removed $($_.Name)"
    }

Remove-ItemProperty -Path 'HKLM:\SOFTWARE\OpenSSH' -Name DefaultShell -ErrorAction SilentlyContinue
Write-Host '>> cleared the DefaultShell override'
Write-Host ''
Write-Host 'sshd_config was not reverted. Check PasswordAuthentication and PubkeyAuthentication.'
