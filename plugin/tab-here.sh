#!/bin/sh
# A tab in the focused pane's directory, focused once it exists.
#
# Read from the server's own focus rather than from the invocation context: a
# call that arrives over the CLI carries no pane of the caller's.
set -eu

herdr="${HERDR_BIN_PATH:-herdr}"

# The first match, cut on quotes: the snapshot repeats these keys inside each
# tab's layout, and the ones that matter are the document's own.
field() {
  grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

snapshot=$("$herdr" api snapshot)
workspace=$(printf '%s' "$snapshot" | field focused_workspace_id)
pane=$(printf '%s' "$snapshot" | field focused_pane_id)

# What the shell in that pane is in, not where it was started.
cwd=''
if [ -n "$pane" ]; then
  cwd=$("$herdr" pane get "$pane" | field foreground_cwd)
fi

set -- tab create --focus
[ -n "$workspace" ] && set -- "$@" --workspace "$workspace"
[ -n "$cwd" ] && set -- "$@" --cwd "$cwd"

exec "$herdr" "$@"
