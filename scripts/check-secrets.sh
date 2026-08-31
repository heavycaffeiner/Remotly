#!/usr/bin/env bash
# Looks for secret material and terminal content reaching logs.
#
# The rule this enforces: a log line may carry a stable error code, an
# operation name, a duration, and a redacted id prefix. It may not carry a
# pairing payload, a credential, or anything the user typed or the remote
# printed.
#
# This is a grep, not a proof. It catches the mistake that is easy to make,
# not every possible one.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if command -v rg >/dev/null 2>&1; then
  search() { rg -n --glob '!node_modules' --glob "!$SELF" "$@"; }
else
  search() {
    local pattern_args=() paths=()
    while [ "$#" -gt 0 ]; do
      case "$1" in
        -e) pattern_args+=(-e "$2"); shift 2 ;;
        --glob) shift 2 ;;
        *) if [ "${#pattern_args[@]}" -eq 0 ]; then pattern_args+=(-e "$1"); else paths+=("$1"); fi; shift ;;
      esac
    done
    grep -rnE --exclude-dir=node_modules --exclude="$(basename "$SELF")" \
      "${pattern_args[@]}" "${paths[@]}"
  }
fi

status=0
SRC=(app/src app/android/app/src/main mobile daemon relay)
SELF='scripts/check-secrets.sh'

report() {
  echo "FAIL: $1" >&2
  status=1
}

scan() {
  local label="$1" pattern="$2"
  local hits
  hits="$(search "$pattern" "${SRC[@]}" 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    report "$label"
    echo "$hits" >&2
  fi
}

# A log call whose argument names a secret. The field-name check in
# src/lib/log.ts redacts these on the JS side, but a native Log call has no
# such protection.
scan "a log call appears to take a password or passphrase" \
  '(Log\.[dviwe]|console\.(log|warn|error|info|debug))\([^)]*\b(password|passphrase|privateKey|private_key|secret|psk)\b'

# The pairing payload is single use, but it is still a credential in transit.
scan "a log call appears to take a pairing URI" \
  '(Log\.[dviwe]|console\.[a-z]+)\([^)]*\b(pairingUri|pairing_uri|remotly://pair)'

# Terminal content. Anything the remote printed or the user typed.
scan "a log call appears to take terminal data" \
  '(Log\.[dviwe]|console\.[a-z]+)\([^)]*\b(terminalData|termData|composingText|preedit|cell\.text)\b'

# Private key markers in shipped source, which would mean a real key was pasted
# into a file. Test files are excluded: a fixture uses the marker as a string to
# assert that an unparseable key is rejected, and it carries no key material.
# The leading dashes need -e so the pattern is not read as options.
hits="$(search -e '-----BEGIN [A-Z ]*PRIVATE KEY-----' "${SRC[@]}" 2>/dev/null |
  grep -vE '(_test\.go|/__tests__/|/test/)' || true)"
if [ -n "$hits" ]; then
  report "a private key appears to be embedded in source"
  echo "$hits" >&2
fi

# Migration-era debug tags.
scan "debug crash logging is still present" 'RemotlyCrash'

# A fixed password in a script that is not obviously a test fixture.
hits="$(search 'pass:[A-Za-z0-9]+' scripts 2>/dev/null || true)"
if [ -n "$hits" ]; then
  report "a literal password appears in a script"
  echo "$hits" >&2
fi

if [ "$status" -eq 0 ]; then
  echo "OK: no secret material found in logs or source"
fi
exit "$status"
