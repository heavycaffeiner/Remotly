#!/bin/sh
# Remotly doctor probe (Unix: bash, zsh, and POSIX sh).
#
# It is run by `remotly doctor` inside the target shell through the shell's
# -c form, as a single structured argument. It reports allowlisted facts
# between the BEGIN and END markers; the reader ignores everything outside the
# markers (shell startup noise, motd, profile output).
#
# Trust boundary: this runs in an untrusted shell with an untrusted profile,
# so it prints environment variable NAMES only, never values. A resolved
# command path is not a secret and is reported so PATH differences are
# concrete. It depends on printenv for the name list; if printenv is missing
# the PATH is broken, which is reported as PATHBROKEN rather than fatal.
#
# It must not call exit: the shell is the process, and the probe is the last
# thing it runs before the shell exits.

printf 'REMOTLY-PROBE-BEGIN\n'

# Shell identity.
if [ -n "${BASH_VERSION:-}" ]; then
	printf 'SHELLNAME=bash\nVERSION=%s\n' "$BASH_VERSION"
elif [ -n "${ZSH_VERSION:-}" ]; then
	printf 'SHELLNAME=zsh\nVERSION=%s\n' "$ZSH_VERSION"
else
	printf 'SHELLNAME=sh\nVERSION=unknown\n'
fi

# Login: detect per shell. bash exposes shopt login_shell; zsh exposes the
# login option, readable with [[ -o login ]].
# The leading-dash-in-$0 convention only holds when a terminal names argv[0]
# "-bash", not when -l is passed, so it is not relied on here.
if [ -n "${BASH_VERSION:-}" ]; then
	if shopt -q login_shell 2>/dev/null; then
		printf 'LOGIN=yes\n'
	else
		printf 'LOGIN=no\n'
	fi
elif [ -n "${ZSH_VERSION:-}" ]; then
	if [[ -o login ]]; then
		printf 'LOGIN=yes\n'
	else
		printf 'LOGIN=no\n'
	fi
else
	case "$0" in
	-*) printf 'LOGIN=yes\n' ;;
	*) printf 'LOGIN=no\n' ;;
	esac
fi

# Interactive: bash records it in the $- option string; zsh sets $interactive.
if [ -n "${ZSH_VERSION:-}" ]; then
	if [ -n "${interactive:-}" ]; then
		printf 'INTERACTIVE=yes\n'
	else
		printf 'INTERACTIVE=no\n'
	fi
else
	case "$-" in
	*i*) printf 'INTERACTIVE=yes\n' ;;
	*) printf 'INTERACTIVE=no\n' ;;
	esac
fi

# Controlling terminal on standard input.
if [ -t 0 ]; then
	printf 'TTY=yes\n'
else
	printf 'TTY=no\n'
fi

printf 'CWD=%s\n' "$PWD"
printf 'UMASK=%s\n' "$(umask)"

# Terminal variables the daemon is allowed to override. Their values are not
# secrets (a terminal type and a color flag), so they are reported by value to
# confirm the daemon's overrides.
printf 'TERMVAL=%s\n' "${TERM:-}"
printf 'COLORTERMVAL=%s\n' "${COLORTERM:-}"

# Environment variable names, sorted. Names only, never values.
if command -v printenv >/dev/null 2>&1; then
	printenv |
		while IFS= read -r line; do
			[ -n "$line" ] || continue
			printf 'ENV=%s\n' "${line%%=*}"
		done |
		sort
else
	printf 'PATHBROKEN=yes\n'
fi

# PATH command resolution for a fixed, safe allowlist. A MISSING entry is a
# finding the caller reports, not an error in this probe.
for c in sh bash zsh which git node nvm python3 pyenv go; do
	if p=$(command -v "$c" 2>/dev/null); then
		printf 'CMD=%s=%s\n' "$c" "$p"
	else
		printf 'CMD=%s=MISSING\n' "$c"
	fi
done

# Alias and function names, sorted. Names only.
alias 2>/dev/null |
	while IFS= read -r line; do
		case "$line" in
		*=*) printf 'ALIAS=%s\n' "${line%%=*}" ;;
		esac
	done |
	sort

if [ -n "${ZSH_VERSION:-}" ]; then
	print -l -- "${(k)functions}" 2>/dev/null |
		while IFS= read -r f; do
			[ -n "$f" ] || continue
			printf 'FUNC=%s\n' "$f"
		done |
		sort
else
	compgen -A function 2>/dev/null |
		while IFS= read -r f; do
			[ -n "$f" ] || continue
			printf 'FUNC=%s\n' "$f"
		done |
		sort
fi

printf 'REMOTLY-PROBE-END\n'
