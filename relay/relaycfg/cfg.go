// Package relaycfg loads and validates relay configuration.
//
// The relay is deliberately small: a listener address, an admin listener,
// and operational limits. Every limit has a safe default; a zero field
// takes the default, a non-zero field must be within bounds, and unknown
// fields are an error.
package relaycfg

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"strconv"
)

// Defaults.
const (
	DefaultMaxConnections   = 1024
	DefaultMaxRegistrations = 4096
	DefaultMaxAppsPerRelay  = 32
	DefaultQueueFrames      = 256
	DefaultQueueBytes       = 8 << 20
	DefaultBandwidthBPS     = 50 << 20
	DefaultIdleTimeoutSec   = 300
	DefaultJoinRatePerSec   = 10
	DefaultJoinBurst        = 20
)

// Limits are the operational bounds the relay enforces.
type Limits struct {
	// MaxConnections is the total live endpoint connections.
	MaxConnections int `json:"max_connections"`
	// MaxRegistrations is the max concurrent daemon registrations.
	MaxRegistrations int `json:"max_registrations"`
	// MaxAppsPerRelay is the max app connections attached to one relay id.
	MaxAppsPerRelay int `json:"max_apps_per_relay"`
	// QueueFrames bounds each per-pair direction queue by frame count.
	QueueFrames int `json:"queue_frames"`
	// QueueBytes bounds each per-pair direction queue by byte count.
	QueueBytes int `json:"queue_bytes"`
	// BandwidthBPS is the per-pair per-direction sustained rate in bytes
	// per second. The burst allowance equals one second of the rate.
	BandwidthBPS int64 `json:"bandwidth_bps"`
	// IdleTimeoutSec closes a connection silent for this long.
	IdleTimeoutSec int `json:"idle_timeout_sec"`
	// JoinRatePerSec is the join rate limit per source address.
	JoinRatePerSec int `json:"join_rate_per_sec"`
	// JoinBurst is the join rate limit burst per source address.
	JoinBurst int `json:"join_burst"`
}

// Config is one relay deployment.
type Config struct {
	// Listen is the relay data listener, host:port.
	Listen string `json:"listen"`
	// AdminListen is the loopback admin listener (health and metrics).
	AdminListen string `json:"admin_listen"`
	// Limits are operational bounds; zero fields take defaults.
	Limits Limits `json:"limits"`
}

// Load reads and validates the JSON config at path.
func Load(path string) (Config, error) {
	raw, err := os.ReadFile(path)
	if err != nil {
		return Config{}, err
	}
	return Parse(raw)
}

// Parse decodes and validates raw JSON config.
func Parse(raw []byte) (Config, error) {
	var c Config
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&c); err != nil {
		return Config{}, fmt.Errorf("relay config: %w", err)
	}
	if t, err := dec.Token(); err != nil {
		if !errors.Is(err, io.EOF) {
			return Config{}, fmt.Errorf("relay config: trailing data after config object")
		}
	} else if t != nil {
		return Config{}, fmt.Errorf("relay config: trailing data after config object")
	}
	c.applyDefaults()
	if err := c.validate(); err != nil {
		return Config{}, err
	}
	return c, nil
}

func (c *Config) applyDefaults() {
	l := &c.Limits
	if l.MaxConnections == 0 {
		l.MaxConnections = DefaultMaxConnections
	}
	if l.MaxRegistrations == 0 {
		l.MaxRegistrations = DefaultMaxRegistrations
	}
	if l.MaxAppsPerRelay == 0 {
		l.MaxAppsPerRelay = DefaultMaxAppsPerRelay
	}
	if l.QueueFrames == 0 {
		l.QueueFrames = DefaultQueueFrames
	}
	if l.QueueBytes == 0 {
		l.QueueBytes = DefaultQueueBytes
	}
	if l.BandwidthBPS == 0 {
		l.BandwidthBPS = DefaultBandwidthBPS
	}
	if l.IdleTimeoutSec == 0 {
		l.IdleTimeoutSec = DefaultIdleTimeoutSec
	}
	if l.JoinRatePerSec == 0 {
		l.JoinRatePerSec = DefaultJoinRatePerSec
	}
	if l.JoinBurst == 0 {
		l.JoinBurst = DefaultJoinBurst
	}
}

func (c *Config) validate() error {
	if err := checkAddr(c.Listen, "listen"); err != nil {
		return err
	}
	if err := checkAddr(c.AdminListen, "admin_listen"); err != nil {
		return err
	}
	l := c.Limits
	if l.MaxConnections < 16 || l.MaxConnections > 65535 {
		return fmt.Errorf("relay config: limits.max_connections out of range: %d", l.MaxConnections)
	}
	if l.MaxRegistrations < 1 || l.MaxRegistrations > 65535 {
		return fmt.Errorf("relay config: limits.max_registrations out of range: %d", l.MaxRegistrations)
	}
	if l.MaxAppsPerRelay < 1 || l.MaxAppsPerRelay > 1024 {
		return fmt.Errorf("relay config: limits.max_apps_per_relay out of range: %d", l.MaxAppsPerRelay)
	}
	if l.QueueFrames < 1 || l.QueueFrames > 4096 {
		return fmt.Errorf("relay config: limits.queue_frames out of range: %d", l.QueueFrames)
	}
	if l.QueueBytes < 65536 || l.QueueBytes > 64<<20 {
		return fmt.Errorf("relay config: limits.queue_bytes out of range: %d", l.QueueBytes)
	}
	if l.BandwidthBPS < 1<<20 || l.BandwidthBPS > 1<<30 {
		return fmt.Errorf("relay config: limits.bandwidth_bps out of range: %d", l.BandwidthBPS)
	}
	if l.IdleTimeoutSec < 30 || l.IdleTimeoutSec > 3600 {
		return fmt.Errorf("relay config: limits.idle_timeout_sec out of range: %d", l.IdleTimeoutSec)
	}
	if l.JoinRatePerSec < 1 || l.JoinRatePerSec > 1000 {
		return fmt.Errorf("relay config: limits.join_rate_per_sec out of range: %d", l.JoinRatePerSec)
	}
	if l.JoinBurst < 1 || l.JoinBurst > 10000 {
		return fmt.Errorf("relay config: limits.join_burst out of range: %d", l.JoinBurst)
	}
	return nil
}

func checkAddr(s, field string) error {
	if s == "" {
		return fmt.Errorf("relay config: %s is required", field)
	}
	_, port, err := net.SplitHostPort(s)
	if err != nil {
		return fmt.Errorf("relay config: %s: %w", field, err)
	}
	if _, err := strconv.Atoi(port); err != nil {
		return fmt.Errorf("relay config: %s: bad port %q", field, port)
	}
	return nil
}
