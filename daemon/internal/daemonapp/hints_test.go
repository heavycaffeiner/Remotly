package daemonapp

import (
	"net"
	"testing"
)

// Which addresses are worth putting in a pairing URI.
//
// A phone dials the hints in turn, so a wrong answer here is felt directly: an
// address that goes nowhere costs a timeout, and an address that is left out
// cannot be paired with at all. Both have shipped as bugs.
func TestDialableHint(t *testing.T) {
	cases := []struct {
		name string
		ip   string
		rank int
		keep bool
	}{
		// The tailnet address must be advertised, and first. When the phone is
		// on the same tailnet this is the address that works, and it needs no
		// inbound firewall rule, unlike the LAN address.
		{"tailscale ipv4", "100.64.0.3", rankOverlay, true},
		{"tailscale ipv4 upper", "100.127.255.254", rankOverlay, true},
		{"tailscale ipv6", "fd7a:115c:a1e0::ce35:b365", rankOverlay, true},

		{"wifi lan", "192.168.0.126", rankLAN, true},
		// A routable v4 address is advertised and dialed alongside the LAN
		// address; it is not separated out, since a daemon on a public
		// address is reached the same way.
		{"public v4", "203.0.113.9", rankLAN, true},

		{"loopback", "127.0.0.1", 0, false},
		{"loopback v6", "::1", 0, false},
		{"link local v4", "169.254.1.1", 0, false},
		{"link local v6", "fe80::1", 0, false},
		{"unspecified", "0.0.0.0", 0, false},
		// Unique-local that is not the overlay: a container manager's range.
		{"container ula", "fd42:eba0:d0aa:4d1::1", 0, false},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			ip := net.ParseIP(c.ip)
			if ip == nil {
				t.Fatalf("bad test address %q", c.ip)
			}
			rank, keep := dialableHint(ip)
			if keep != c.keep {
				t.Fatalf("keep = %v, want %v", keep, c.keep)
			}
			if keep && rank != c.rank {
				t.Fatalf("rank = %d, want %d", rank, c.rank)
			}
		})
	}
}

// 100.64.0.0/10 is the whole carrier-grade NAT range. An earlier version
// dropped it as unroutable, which is what broke pairing over a tailnet.
func TestOverlayRangeBoundaries(t *testing.T) {
	in := []string{"100.64.0.0", "100.64.0.1", "100.100.1.1", "100.127.255.255"}
	for _, s := range in {
		if rank, keep := dialableHint(net.ParseIP(s)); !keep || rank != rankOverlay {
			t.Fatalf("%s: rank %d keep %v, want overlay", s, rank, keep)
		}
	}
	// Just outside the range: ordinary public addresses, still advertised but
	// not as an overlay.
	for _, s := range []string{"100.63.255.255", "100.128.0.0"} {
		if rank, keep := dialableHint(net.ParseIP(s)); !keep || rank == rankOverlay {
			t.Fatalf("%s: rank %d keep %v, want non-overlay", s, rank, keep)
		}
	}
}

// Container and VM bridges hold a gateway address on a network that exists
// only on this host. They are identified by interface name because the ranges
// they use are the same private ranges a real LAN uses.
func TestVirtualInterface(t *testing.T) {
	for _, n := range []string{"docker0", "incusbr0", "virbr0", "lxcbr0", "lxdbr0", "br-abc123", "veth1a2b", "vmnet8", "vboxnet0"} {
		if !virtualInterface(n) {
			t.Errorf("%s should be treated as virtual", n)
		}
	}
	for _, n := range []string{"wlp3s0", "eth0", "enp0s31f6", "tailscale0", "wlan0", "en0"} {
		if virtualInterface(n) {
			t.Errorf("%s is a real interface", n)
		}
	}
}
