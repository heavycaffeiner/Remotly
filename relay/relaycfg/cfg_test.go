package relaycfg

import (
	"strings"
	"testing"
)

func TestParseDefaults(t *testing.T) {
	c, err := Parse([]byte(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791"}`))
	if err != nil {
		t.Fatal(err)
	}
	if c.Listen != "0.0.0.0:8789" || c.AdminListen != "127.0.0.1:8791" {
		t.Fatalf("listeners = %q %q", c.Listen, c.AdminListen)
	}
	if c.Limits.MaxConnections != DefaultMaxConnections ||
		c.Limits.MaxRegistrations != DefaultMaxRegistrations ||
		c.Limits.MaxAppsPerRelay != DefaultMaxAppsPerRelay ||
		c.Limits.QueueFrames != DefaultQueueFrames ||
		c.Limits.QueueBytes != DefaultQueueBytes ||
		c.Limits.BandwidthBPS != DefaultBandwidthBPS ||
		c.Limits.IdleTimeoutSec != DefaultIdleTimeoutSec ||
		c.Limits.JoinRatePerSec != DefaultJoinRatePerSec ||
		c.Limits.JoinBurst != DefaultJoinBurst {
		t.Fatalf("limits not defaulted: %+v", c.Limits)
	}
}

func TestParseRejects(t *testing.T) {
	bad := func(body string) {
		t.Run(body, func(t *testing.T) {
			_, err := Parse([]byte(body))
			if err == nil {
				t.Fatal("accepted bad config")
			}
		})
	}
	bad(``)
	bad(`{"listen": "0.0.0.0:8789"}`) // missing admin_listen
	bad(`{"listen": "noport", "admin_listen": "127.0.0.1:8791"}`)
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "bogus": 1}`)
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "limits": {"max_connections": 1}}`)
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "limits": {"max_connections": 70000}}`)
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "limits": {"queue_bytes": 1024}}`)
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "limits": {"bandwidth_bps": 1024}}`)
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "limits": {"idle_timeout_sec": 5}}`)
	// A zero limit takes the default by design; only out-of-range non-zero
	// values are rejected.
	if _, err := Parse([]byte(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791", "limits": {"join_rate_per_sec": 0}}`)); err != nil {
		t.Fatalf("zero limit should take default: %v", err)
	}
	bad(`{"listen": "0.0.0.0:8789", "admin_listen": "127.0.0.1:8791"}` + `garbage`)
}

func TestParseAcceptsIPv6(t *testing.T) {
	_, err := Parse([]byte(`{"listen": "[::]:8789", "admin_listen": "[::1]:8791"}`))
	if err != nil {
		t.Fatal(err)
	}
}

func TestLoadMissing(t *testing.T) {
	_, err := Load("/nonexistent/relay.json")
	if err == nil || !strings.Contains(err.Error(), "open") {
		t.Fatalf("err = %v", err)
	}
}
