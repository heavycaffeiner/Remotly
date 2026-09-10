#!/bin/sh
# Fills the tab with the focused pane, or restores the tiling.
set -eu

herdr="${HERDR_BIN_PATH:-herdr}"
exec "$herdr" pane zoom --toggle --current
