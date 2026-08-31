#!/usr/bin/env bash
# Fails when a generated artifact would be committed to the source tree.
#
# These files are large, machine-specific, or rebuildable, and one of them
# landing in a commit is easy to miss in review.
#
# Anything .gitignore already excludes is fine on disk: a build directory and a
# local release keystore are expected to exist during development. This checks
# what version control would actually take.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

status=0

report() {
  echo "FAIL: $1" >&2
  status=1
}

# In a git checkout, ask git what is tracked or untracked-but-not-ignored.
# Without one, fall back to scanning and rely on the ignore rules being
# documented rather than enforced.
have_git=0
if command -v git >/dev/null 2>&1 && git rev-parse --git-dir >/dev/null 2>&1; then
  have_git=1
fi

# Lists candidate files: everything git would include, or a plain find.
candidates() {
  if [ "$have_git" -eq 1 ]; then
    # Tracked plus untracked, with ignored files excluded.
    { git ls-files; git ls-files --others --exclude-standard; } | sort -u
  else
    find . -type f -not -path '*/node_modules/*' -not -path '*/.git/*' \
      -printf '%P\n' 2>/dev/null
  fi
}

# Paths that .gitignore covers, so a non-git scan does not report them.
ignored() {
  case "$1" in
    */node_modules/*|node_modules/*) return 0 ;;
    */build/*|build/*) return 0 ;;
    */.gradle/*|.gradle/*) return 0 ;;
    */.cxx/*|.cxx/*) return 0 ;;
    dist/*) return 0 ;;
    *.keystore)
      # The debug keystore is intentionally versioned so a debug build is
      # reproducible. A release keystore never is.
      [ "$1" = "app/android/app/debug.keystore" ] && return 1
      return 0
      ;;
  esac
  return 1
}

while IFS= read -r f; do
  [ -n "$f" ] || continue
  if [ "$have_git" -eq 0 ] && ignored "$f"; then
    continue
  fi
  case "$f" in
    *.apk|*.aab)
      report "build output would be committed: $f" ;;
    *.jks)
      report "keystore would be committed: $f" ;;
    *.keystore)
      if [ "$f" != "app/android/app/debug.keystore" ]; then
        report "keystore would be committed: $f"
      fi
      ;;
    *libghostty-vt.a)
      report "staged static library would be committed: $f" ;;
    */build-host/*)
      report "compiled host test would be committed: $f" ;;
  esac
done < <(candidates)

# Absolute developer paths, which break on any other machine.
if command -v rg >/dev/null 2>&1; then
  hits="$(rg -n --glob '!node_modules' --glob '!*.md' --glob '!scripts/check-artifacts.sh' \
    '/Tokyo/Projects' app/src app/android/app/src mobile daemon relay scripts 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    report "absolute developer path in source:"
    echo "$hits" >&2
  fi

  # Debug crash tags from the migration.
  hits="$(rg -n --glob '!node_modules' --glob '!scripts/check-artifacts.sh' \
    'RemotlyCrash' app/src app/android/app/src 2>/dev/null || true)"
  if [ -n "$hits" ]; then
    report "debug crash logging left in source:"
    echo "$hits" >&2
  fi
fi

if [ "$status" -eq 0 ]; then
  echo "OK: no generated artifacts would be committed"
fi
exit "$status"
