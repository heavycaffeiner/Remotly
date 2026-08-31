package main

import (
	"encoding/base64"
	"fmt"
	"os"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
)

// cmdDevices lists paired devices, or revokes one with `devices revoke`.
func cmdDevices(args []string) int {
	if len(args) > 0 {
		switch args[0] {
		case "revoke":
			return cmdDevicesRevoke(args[1:])
		case "--help", "-h":
			devicesUsage()
			return 0
		default:
			fmt.Fprintf(os.Stderr, "remotly: unknown devices subcommand %q\n", args[0])
			devicesUsage()
			return 2
		}
	}
	resp, ok := callDaemon(localctl.Request{Op: "devices"})
	if !ok {
		return 1
	}
	if len(resp.Devices) == 0 {
		fmt.Println("no paired devices")
		return 0
	}
	for _, d := range resp.Devices {
		fmt.Printf("%s  %s  (paired %s)\n", d.Public, d.Name, d.PairedAt.Local().Format(time.RFC3339))
	}
	return 0
}

func cmdDevicesRevoke(args []string) int {
	if len(args) != 1 {
		fmt.Fprintln(os.Stderr, "usage: remotely devices revoke <public-key>")
		return 2
	}
	key, err := base64.RawURLEncoding.DecodeString(args[0])
	if err != nil || len(key) != 32 {
		fmt.Fprintln(os.Stderr, "remotly: public key must be 32 base64url bytes")
		return 2
	}
	resp, ok := callDaemon(localctl.Request{Op: "revoke", Public: args[0]})
	if !ok {
		return 1
	}
	_ = resp
	fmt.Println("revoked")
	return 0
}

func devicesUsage() {
	fmt.Fprint(os.Stderr, `usage: remotely devices
       remotely devices revoke <public-key>

List paired devices, or revoke one by its public key (base64url).
`)
}
