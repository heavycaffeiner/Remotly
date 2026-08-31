package main

import (
	"fmt"
	"os"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// cmdPair asks the daemon for a fresh one-time pairing URI and prints it, with
// an optional terminal QR code. The URI is the complete credential the app
// scans; it is safe to show on screen (it is meant to be) but expires in five
// minutes and is single-use.
func cmdPair(args []string) int {
	showQR := false
	for _, a := range args {
		switch a {
		case "--qr", "-qr":
			showQR = true
		case "--help", "-h":
			fmt.Fprintln(os.Stderr, "usage: remotely pair [--qr]")
			return 0
		default:
			fmt.Fprintf(os.Stderr, "remotly: unknown flag %q (usage: remotely pair [--qr])\n", a)
			return 2
		}
	}
	resp, ok := callDaemon(localctl.Request{Op: "pair"})
	if !ok {
		return 1
	}
	fmt.Println(resp.URI)
	if resp.Expires > 0 {
		fmt.Fprintf(os.Stderr, "remotly: token expires %s\n", time.Unix(resp.Expires, 0).Local().Format(time.RFC3339))
	}
	if showQR {
		qr, err := qrcode.New(resp.URI, qrcode.Medium)
		if err != nil {
			fmt.Fprintf(os.Stderr, "remotly: render QR: %v\n", err)
			return 1
		}
		fmt.Println(qr.ToString(false))
	}
	return 0
}
