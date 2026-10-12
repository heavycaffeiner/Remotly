Terminal palette sources, retrieved 2026-10-12

Classic: Ghostty 0ba6250388641f52135414b38c4259aa682c489b,
src/terminal/render.zig and src/terminal/color.zig. Native defaults render
#FFFFFF on #000000 with Ghostty's default sixteen ANSI entries.
The Ghostty MIT notice is bundled with the terminal bridge source.

Nord: https://github.com/nordtheme/xresources/blob/develop/src/nord
License: nord-license.txt

Dracula: https://github.com/dracula/xresources/blob/master/Xresources
License: dracula-license.txt
Selection background uses Dracula's #44475A.

Catppuccin Mocha: https://github.com/catppuccin/kitty/blob/main/themes/mocha.conf
License: catppuccin-license.txt

Solarized Dark and Light: https://github.com/altercation/solarized
License: solarized-license.txt
Solarized Light default foreground is #586E75, not canonical #657B83.
The canonical default contrast is approximately 4.13:1 on #FDF6E3.
The adjusted default contrast is approximately 4.99:1. ANSI entries are unchanged.

Only indices 0 through 15 are themed. Indices 16 through 255 retain the
pinned engine's xterm color cube and grayscale ramp. Explicit truecolor
and remote OSC overrides remain controlled by the remote application.
