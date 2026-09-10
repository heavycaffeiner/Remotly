# Remotly Bridge

A herdr plugin that carries the workspace and tab moves Remotly cannot express
over a one-shot command from a phone.

Remotly already drives `herdr workspace`, `herdr tab`, and `herdr pane` itself,
one SSH exec per call. What it cannot do that way is act on where the focused
pane is, or restructure a tab in one step: each of those needs several calls
that have to agree on the same target, and a phone that asks between them can
be told a different answer each time. This plugin does that work on the host.

## Install

```
herdr plugin install heavycaffeiner/Remotly/plugin
herdr plugin action list --plugin remotly.bridge
```

For local development, link a checkout instead:

```
herdr plugin link /path/to/Remotly/plugin
```

Needs herdr 0.9.0 or later, a running `herdr server`, and a POSIX shell. Linux
and macOS.

## Actions

| Action | What it does |
| --- | --- |
| `remotly.bridge.tab-here` | A tab in the directory the focused pane's shell is in, focused. |
| `remotly.bridge.panes-to-tabs` | Every pane of the focused tab except the first moves to a tab of its own. A tiled layout is unreadable on a phone; one pane per tab is what a tab strip can drive. |
| `remotly.bridge.zoom` | Fills the tab with the focused pane, or restores the tiling. |

Each action reads its target from `herdr api snapshot` rather than from the
invocation context, because a call arriving over the CLI carries no pane or tab
of the caller's.

Remotly's workspace screen invokes these from its terminal menu. They are
ordinary actions, so a keybinding reaches them too:

```toml
[[keys.command]]
key = "prefix+t"
type = "plugin_action"
command = "remotly.bridge.tab-here"
description = "new tab here"
```

## Output

`plugin action invoke` answers that the action started; what it printed is in
`herdr plugin log list --plugin remotly.bridge`. Remotly reads the state the
action changed instead of waiting for output.
