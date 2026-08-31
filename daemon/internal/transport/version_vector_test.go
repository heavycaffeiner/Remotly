package transport

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
)

// TestVectorVersion drives the handshake version gate from the canonical
// version vector so the freeze spec (protocol.md section 1) and the
// implementation stay in lockstep. Rejected versions must close with 4000
// before any Noise or application data; the accepted version must be the
// build's protocol.Version.
func TestVectorVersion(t *testing.T) {
	b, err := os.ReadFile("../protocol/testdata/version.json")
	if err != nil {
		t.Fatalf("read version vector: %v", err)
	}
	var cases []struct {
		Version  int    `json:"version"`
		Accepted bool   `json:"accepted"`
		Close    int    `json:"close"`
		Reason   string `json:"reason"`
	}
	if err := json.Unmarshal(b, &cases); err != nil {
		t.Fatal(err)
	}
	if len(cases) == 0 {
		t.Fatal("no version vector cases")
	}
	e := newEnv(t, envCfg{})
	for _, c := range cases {
		if c.Accepted {
			if protocol.Version != c.Version {
				t.Fatalf("protocol.Version = %d, vector accepts %d", protocol.Version, c.Version)
			}
			continue
		}
		cerr := e.rawHandshakeClose(t, []byte{byte(c.Version), protocol.ModeIK})
		if int(cerr.Code) != c.Close {
			t.Fatalf("version %d: close = %d, want %d", c.Version, cerr.Code, c.Close)
		}
		if cerr.Reason != c.Reason {
			t.Fatalf("version %d: reason = %q, want %q", c.Version, cerr.Reason, c.Reason)
		}
	}
}
