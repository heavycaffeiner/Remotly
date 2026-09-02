package main

import (
	"fmt"
	"io"
	"os"
	"time"

	qrcode "github.com/skip2/go-qrcode"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// cmdPair asks the daemon for a fresh one-time pairing URI and prints it with
// a terminal QR code. The URI is the complete credential the app scans; it is
// safe to show on screen (it is meant to be) but expires in five minutes and
// is single-use.
//
// The QR is printed by default because scanning it is the normal way to pair;
// --no-qr is for piping the URI somewhere.
func cmdPair(args []string) int {
	showQR := true
	for _, a := range args {
		switch a {
		case "--qr", "-qr":
			showQR = true
		case "--no-qr":
			showQR = false
		case "--help", "-h":
			fmt.Fprintln(os.Stderr, "usage: remotly pair [--no-qr]")
			return 0
		default:
			fmt.Fprintf(os.Stderr, "remotly: unknown flag %q (usage: remotly pair [--no-qr])\n", a)
			return 2
		}
	}
	resp, ok := callDaemon(localctl.Request{Op: "pair"})
	if !ok {
		return 1
	}
	if showQR {
		if err := printQR(os.Stdout, resp.URI); err != nil {
			fmt.Fprintf(os.Stderr, "remotly: render QR: %v\n", err)
			return 1
		}
	}
	fmt.Println(resp.URI)
	if resp.Expires > 0 {
		fmt.Fprintf(os.Stderr, "remotly: token expires %s\n", time.Unix(resp.Expires, 0).Local().Format(time.RFC3339))
	}
	return 0
}

// printQR renders uri as a half-block QR code. ToSmallString packs two module
// rows into one text row, so a symbol is one text column per module instead of
// two; the full-size form is twice as wide and wraps well before 80 columns,
// and a wrapped QR cannot be scanned at all.
//
// A pairing URI carrying several LAN hints runs past 400 bytes, which needs a
// large symbol. Error correction is what decides how large: Medium costs 12
// more modules than Low here. Low is tried first so the code still fits an
// 80-column terminal, and Medium is used when the URI is short enough to
// afford it, since more correction scans more forgivingly.
//
// The quiet zone is part of the encoding: without four clear modules around
// the symbol, scanners fail to lock on. ToSmallString includes it, so nothing
// may be trimmed here.
func printQR(w io.Writer, uri string) error {
	const maxCols = 80

	best, err := qrcode.New(uri, qrcode.Medium)
	if err != nil {
		return err
	}
	if len(best.Bitmap()) > maxCols {
		if low, err := qrcode.New(uri, qrcode.Low); err == nil && len(low.Bitmap()) < len(best.Bitmap()) {
			best = low
		}
	}
	_, err = io.WriteString(w, best.ToSmallString(false))
	return err
}
