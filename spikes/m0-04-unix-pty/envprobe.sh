#!/usr/bin/env bash
# Allowlisted environment probe. Reports only facts the daemon needs to verify
# environment inheritance, never user secrets. Sources in both a daemon-spawned
# login shell and a directly opened terminal; the two outputs must diff empty.
#
# This file must NOT call exit: it is sourced into the shell under test, and
# exit would terminate that shell.

emit() { printf 'PROBE_%s=%s\n' "$1" "$2"; }

case "$(basename "$BASH")$ZSH_VERSION" in
  bash*|*zsh*) : ;;
  *) echo "PROBE_ERROR=unsupported shell: ${BASH:-}${ZSH_VERSION:-}" >&2; return 1 ;;
esac

if [ -n "$BASH" ]; then
  shell_name=bash
  shell_path="$(readlink -f /proc/$$/exe 2>/dev/null || command -v bash)"
  case "$-" in *i*) interactive=yes ;; *) interactive=no ;; esac
  shopt -q login_shell && login=yes || login=no
elif [ -n "$ZSH_VERSION" ]; then
  shell_name=zsh
  shell_path="$(command -v zsh)"
  case "$-" in *i*) interactive=yes ;; *) interactive=no ;; esac
  [[ -o login ]] && login=yes || login=no
fi

# tty flags are computed with if, not command substitution: $(...) redirects
# stdout to a pipe and would report the shell's own fds incorrectly.
if test -t 0; then stdin_is_tty=yes; else stdin_is_tty=no; fi
if test -t 1; then stdout_is_tty=yes; else stdout_is_tty=no; fi
if test -t 2; then stderr_is_tty=yes; else stderr_is_tty=no; fi

emit shell_name "$shell_name"
emit shell_path "$shell_path"
emit shell_env "$SHELL"
emit interactive "$interactive"
emit login "$login"
emit tty "$(tty 2>/dev/null)"
emit stdin_is_tty "$stdin_is_tty"
emit stdout_is_tty "$stdout_is_tty"
emit stderr_is_tty "$stderr_is_tty"
emit cwd "$PWD"
emit home "$HOME"
emit uid "$(id -u)"
emit path "$PATH"
emit term "$TERM"
emit colorterm "${COLORTERM:-}"
emit remotly_session "${REMOTLY_SESSION:-}"
emit umask "$(umask)"

for v in LANG LC_ALL EDITOR VISUAL PAGER SHELL SSH_AUTH_SOCK; do
  eval "val=\${$v:-}"
  emit "env_$v" "$val"
done

for fn in ll la gs gco gd; do
  if [ -n "$BASH" ]; then
    declare -F "$fn" >/dev/null 2>&1 && emit "func_$fn" yes || emit "func_$fn" no
  else
    (( $+functions[$fn] )) >/dev/null 2>&1 && emit "func_$fn" yes || emit "func_$fn" no
  fi
done

for a in ll la gs; do
  if [ -n "$BASH" ]; then
    alias "$a" >/dev/null 2>&1 && emit "alias_$a" yes || emit "alias_$a" no
  else
    (( $+aliases[$a] )) >/dev/null 2>&1 && emit "alias_$a" yes || emit "alias_$a" no
  fi
done

for vm in nvm pyenv rbenv asdf conda mise fnm; do
  if command -v "$vm" >/dev/null 2>&1; then
    emit "vm_$vm" "$(command -v "$vm")"
  else
    emit "vm_$vm" no
  fi
done

emit stty_size "$(stty size 2>/dev/null | tr ' ' x)"

return 0
