module github.com/heavycaffeiner/remotly/daemon

go 1.26

require (
	github.com/creack/pty v1.1.24
	github.com/flynn/noise v1.1.0
	github.com/gdamore/tcell/v2 v2.8.1
	github.com/heavycaffeiner/remotly/relay v0.0.0
	github.com/rivo/tview v0.42.0
	github.com/skip2/go-qrcode v0.0.0-20200617195104-da1b6568686e
	golang.org/x/crypto v0.55.0
	golang.org/x/sys v0.47.0
	golang.org/x/term v0.45.0
	nhooyr.io/websocket v1.8.17
)

require (
	github.com/gdamore/encoding v1.0.1 // indirect
	github.com/lucasb-eyer/go-colorful v1.2.0 // indirect
	github.com/mattn/go-runewidth v0.0.16 // indirect
	github.com/rivo/uniseg v0.4.7 // indirect
	golang.org/x/text v0.41.0 // indirect
)

replace github.com/heavycaffeiner/remotly/relay => ../relay
