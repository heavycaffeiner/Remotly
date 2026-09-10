#!/bin/sh
# Every pane of the focused tab except the first moves to a tab of its own.
#
# The target comes from the server's own focus rather than from the invocation
# context: a call that arrives over the CLI carries whichever tab herdr last
# handed a plugin, which is not the tab the caller is looking at.
set -eu

herdr="${HERDR_BIN_PATH:-herdr}"

# The first match, cut on quotes: the snapshot repeats these keys inside each
# tab's layout, and the ones that matter are the document's own.
field() {
  grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
}

snapshot=$("$herdr" api snapshot)
tab=$(printf '%s' "$snapshot" | field focused_tab_id)
pane=$(printf '%s' "$snapshot" | field focused_pane_id)

if [ -z "$tab" ] || [ -z "$pane" ]; then
  echo "no focused tab" >&2
  exit 1
fi

# A zoomed tab refuses pane moves, so the zoom goes first.
"$herdr" pane zoom --off --pane "$pane" >/dev/null

# The layout lists that tab's panes in place order. `focused_pane_id` does not
# match the pattern, so what is left are the panes themselves.
panes=$(
  "$herdr" pane layout --pane "$pane" |
    grep -o '"pane_id":"[^"]*"' |
    cut -d'"' -f4
)

first=1
moved=0
for p in $panes; do
  if [ "$first" = 1 ]; then
    first=0
    continue
  fi
  # The pane keeps running; only where it is drawn changes. A refusal names
  # its reason in the response, and the response is what says it happened.
  out=$("$herdr" pane move "$p" --new-tab)
  case "$out" in
  *'"changed":true'*) moved=$((moved + 1)) ;;
  *)
    echo "$out" >&2
    exit 1
    ;;
  esac
done

# Back to the tab the panes came from, not the last tab made.
"$herdr" tab focus "$tab" >/dev/null

echo "{\"moved\":$moved,\"tab_id\":\"$tab\"}"
