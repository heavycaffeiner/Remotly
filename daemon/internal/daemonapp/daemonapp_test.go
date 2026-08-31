package daemonapp

import (
	"testing"

	"github.com/heavycaffeiner/remotly/daemon/internal/config"
	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
)

// hasRelayHint reports whether any hint is a relay hint.
func hasRelayHint(hints []pairing.Hint) bool {
	for _, h := range hints {
		if h.Kind == pairing.HintRelay {
			return true
		}
	}
	return false
}

// TestPairingHintsRelayDisabled verifies that a disabled relay contributes no
// relay hint to the pairing URI, so an unconfigured daemon advertises LAN
// hints only.
func TestPairingHintsRelayDisabled(t *testing.T) {
	a := &App{cfg: config.Config{
		Listen: config.Listen{LAN: false, LANPort: 8443},
		Relay:  config.Relay{Enabled: false},
	}}
	if hasRelayHint(a.pairingHints()) {
		t.Fatalf("disabled relay must not produce a relay hint: %+v", a.pairingHints())
	}
}

// TestPairingHintsRelayEnabled verifies that an enabled relay is advertised as
// the first hint, ahead of any LAN hints.
func TestPairingHintsRelayEnabled(t *testing.T) {
	a := &App{cfg: config.Config{
		Listen: config.Listen{LAN: false, LANPort: 8443},
		Relay:  config.Relay{Enabled: true, Addr: "relay.example.com:443"},
	}}
	hints := a.pairingHints()
	if len(hints) == 0 || hints[0].Kind != pairing.HintRelay {
		t.Fatalf("relay hint must be first: %+v", hints)
	}
	if hints[0].Addr != "relay.example.com" || hints[0].Port != 443 {
		t.Fatalf("relay hint = %+v", hints[0])
	}
}
