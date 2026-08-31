// Package config loads and validates the single Remotly daemon configuration
// file. The file is untrusted input: it is size-bounded, strictly decoded
// (unknown fields rejected), and field-validated before any use.
package config

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
)

// MaxFileBytes bounds the configuration file. Anything larger is rejected
// before parsing.
const MaxFileBytes = 64 << 10

const (
	DefaultLoopbackPort = 8787
	DefaultLANPort      = 8788
	DefaultTerm         = "xterm-256color"
	DefaultSessionMax   = 64
	DefaultScrollback   = 65536

	minScrollbackLines = 1024
	maxScrollbackLines = 1 << 20
	maxSessionMax      = 1024
	maxShellLen        = 4096

	// M2 bounds: post-exit retention, presets, and output patterns.
	minRetainedAfterExitSec = 0
	maxRetainedAfterExitSec = 3600
	maxPresetCount          = 16
	maxPresetNameLen        = 50
	maxPresetIconLen        = 32
	maxPatternCount         = 32
	maxPatternNameLen       = 50
	maxPatternExprLen       = 256
)

var termPattern = regexp.MustCompile(`^[A-Za-z0-9._-]{1,64}$`)

// Config is the validated configuration. Zero values are never meaningful;
// callers always go through Load, which applies defaults.
type Config struct {
	Version    int        `json:"version"`
	Listen     Listen     `json:"listen"`
	Relay      Relay      `json:"relay"`
	Shell      string     `json:"shell"`
	Term       string     `json:"term"`
	Sessions   Sessions   `json:"sessions"`
	Scrollback Scrollback `json:"scrollback"`
	Presets    []Preset   `json:"presets"`
	Notify     Notify     `json:"notifications"`
}

// Sessions holds session limits. RetainedAfterExit is the post-exit window
// in which an exited session stays listed and attachable for final replay.
type Sessions struct {
	Max               int `json:"max"`
	RetainedAfterExit int `json:"retained_after_exit"`
}

// Listen controls the daemon endpoints. Loopback is the always-on local
// listener. LAN exposure is additionally gated at runtime by the listener
// state rule (paired device or active pairing token).
type Listen struct {
	Loopback     bool `json:"loopback"`
	LoopbackPort int  `json:"loopback_port"`
	LAN          bool `json:"lan"`
	LANPort      int  `json:"lan_port"`
}

type Scrollback struct {
	Lines int `json:"lines"`
}

// Relay configures the daemon's optional outbound relay registration. It is
// disabled by default; an enabled relay requires an address. The relay is
// additive: it never replaces direct LAN service, and a disabled relay
// creates no relay traffic and no relay pairing hint.
type Relay struct {
	Enabled bool   `json:"enabled"`
	Addr    string `json:"addr"`
}

// Preset is one configured agent session preset: a one-tap session creation
// action offered by the app. All fields are bounded plain text; Command is
// the exact agent command line and is never rendered as markup.
type Preset struct {
	Name     string `json:"name"`
	Command  string `json:"command"`
	IconHint string `json:"icon_hint"`
}

// Pattern is one output-pattern event rule: a RE2 expression matched
// against each session's live output decoded as UTF-8 over a bounded
// rolling window.
type Pattern struct {
	Name string `json:"name"`
	Expr string `json:"pattern"`
}

// Notify configures terminal event notifications.
type Notify struct {
	BellEnabled *bool     `json:"bell"`
	Patterns    []Pattern `json:"patterns"`
}

// BellOn reports whether the bell event is enabled; the default is on.
func (n Notify) BellOn() bool {
	if n.BellEnabled == nil {
		return true
	}
	return *n.BellEnabled
}

// Default returns the built-in configuration.
func Default() Config {
	return Config{
		Version: 1,
		Listen: Listen{
			Loopback:     true,
			LoopbackPort: DefaultLoopbackPort,
			LAN:          true,
			LANPort:      DefaultLANPort,
		},
		Term:       DefaultTerm,
		Relay:      Relay{Enabled: false, Addr: ""},
		Sessions:   Sessions{Max: DefaultSessionMax, RetainedAfterExit: 300},
		Scrollback: Scrollback{Lines: DefaultScrollback},
		Notify:     Notify{},
	}
}

// FilePath returns the location of config.json for the current platform.
func FilePath() (string, error) {
	dir, err := paths.Dir(paths.ConfigKind)
	if err != nil {
		return "", err
	}
	return filepath.Join(dir, "config.json"), nil
}

// Load reads and validates the configuration at path. If the file does not
// exist, the default configuration is returned with ok=false. Errors name the
// offending field and never include file contents.
func Load(path string) (cfg Config, ok bool, err error) {
	def := Default()
	fi, err := os.Lstat(path)
	if errors.Is(err, os.ErrNotExist) {
		return def, false, nil
	}
	if err != nil {
		return def, false, fmt.Errorf("stat config: %w", err)
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		return def, false, fmt.Errorf("config: %s must not be a symlink", path)
	}
	if !fi.Mode().IsRegular() {
		return def, false, fmt.Errorf("config: %s is not a regular file", path)
	}
	if fi.Size() > MaxFileBytes {
		return def, false, fmt.Errorf("config: file exceeds %d bytes", MaxFileBytes)
	}
	raw, err := os.ReadFile(path)
	if err != nil {
		return def, false, fmt.Errorf("read config: %w", err)
	}
	cfg, err = Parse(raw)
	if err != nil {
		return def, false, err
	}
	return cfg, true, nil
}

// Parse validates a configuration byte slice. The input is hostile: unknown
// fields, wrong types, and out-of-range values are all rejected.
func Parse(raw []byte) (Config, error) {
	def := Default()
	var wire struct {
		Version *int `json:"version"`
		Listen  *struct {
			Loopback     *bool `json:"loopback"`
			LoopbackPort *int  `json:"loopback_port"`
			LAN          *bool `json:"lan"`
			LANPort      *int  `json:"lan_port"`
		} `json:"listen"`
		Relay *struct {
			Enabled *bool   `json:"enabled"`
			Addr    *string `json:"addr"`
		} `json:"relay"`
		Shell    *string `json:"shell"`
		Term     *string `json:"term"`
		Sessions *struct {
			Max               *int `json:"max"`
			RetainedAfterExit *int `json:"retained_after_exit"`
		} `json:"sessions"`
		Scrollback *struct {
			Lines *int `json:"lines"`
		} `json:"scrollback"`
		Presets *[]struct {
			Name     string `json:"name"`
			Command  string `json:"command"`
			IconHint string `json:"icon_hint"`
		} `json:"presets"`
		Notify *struct {
			Bell     *bool `json:"bell"`
			Patterns *[]struct {
				Name    string `json:"name"`
				Pattern string `json:"pattern"`
			} `json:"patterns"`
		} `json:"notifications"`
	}
	dec := json.NewDecoder(strings.NewReader(string(raw)))
	dec.DisallowUnknownFields()
	if err := dec.Decode(&wire); err != nil {
		return def, fmt.Errorf("config: %w", err)
	}
	var extra json.RawMessage
	if err := dec.Decode(&extra); !errors.Is(err, io.EOF) {
		return def, errors.New("config: trailing data after JSON object")
	}

	cfg := def
	if wire.Version != nil {
		if *wire.Version != 1 {
			return def, fmt.Errorf("config: unsupported version %d (want 1)", *wire.Version)
		}
	}
	if wire.Listen != nil {
		l := wire.Listen
		if l.Loopback != nil {
			cfg.Listen.Loopback = *l.Loopback
		}
		if l.LoopbackPort != nil {
			if err := validatePort("listen.loopback_port", *l.LoopbackPort); err != nil {
				return def, err
			}
			cfg.Listen.LoopbackPort = *l.LoopbackPort
		}
		if l.LAN != nil {
			cfg.Listen.LAN = *l.LAN
		}
		if l.LANPort != nil {
			if err := validatePort("listen.lan_port", *l.LANPort); err != nil {
				return def, err
			}
			cfg.Listen.LANPort = *l.LANPort
		}
	}
	if wire.Relay != nil {
		r := wire.Relay
		if r.Enabled != nil {
			cfg.Relay.Enabled = *r.Enabled
		}
		if r.Addr != nil {
			if err := validateRelayAddr(*r.Addr); err != nil {
				return def, err
			}
			cfg.Relay.Addr = *r.Addr
		}
		if cfg.Relay.Enabled && cfg.Relay.Addr == "" {
			return def, errors.New("config: relay.addr is required when relay.enabled")
		}
	}
	if wire.Shell != nil {
		if err := validateShell(*wire.Shell); err != nil {
			return def, err
		}
		cfg.Shell = *wire.Shell
	}
	if wire.Term != nil {
		if !termPattern.MatchString(*wire.Term) {
			return def, errors.New("config: term must match [A-Za-z0-9._-]{1,64}")
		}
		cfg.Term = *wire.Term
	}
	if wire.Sessions != nil {
		if wire.Sessions.Max != nil {
			if *wire.Sessions.Max < 1 || *wire.Sessions.Max > maxSessionMax {
				return def, fmt.Errorf("config: sessions.max must be in 1..%d", maxSessionMax)
			}
			cfg.Sessions.Max = *wire.Sessions.Max
		}
		if wire.Sessions.RetainedAfterExit != nil {
			if *wire.Sessions.RetainedAfterExit < minRetainedAfterExitSec || *wire.Sessions.RetainedAfterExit > maxRetainedAfterExitSec {
				return def, fmt.Errorf("config: sessions.retained_after_exit must be in %d..%d seconds", minRetainedAfterExitSec, maxRetainedAfterExitSec)
			}
			cfg.Sessions.RetainedAfterExit = *wire.Sessions.RetainedAfterExit
		}
	}
	if wire.Scrollback != nil && wire.Scrollback.Lines != nil {
		if *wire.Scrollback.Lines < minScrollbackLines || *wire.Scrollback.Lines > maxScrollbackLines {
			return def, fmt.Errorf("config: scrollback.lines must be in %d..%d", minScrollbackLines, maxScrollbackLines)
		}
		cfg.Scrollback.Lines = *wire.Scrollback.Lines
	}
	if wire.Presets != nil {
		if len(*wire.Presets) > maxPresetCount {
			return def, fmt.Errorf("config: presets: at most %d entries", maxPresetCount)
		}
		seen := make(map[string]bool, len(*wire.Presets))
		for i, p := range *wire.Presets {
			if err := validatePreset(fmt.Sprintf("presets[%d]", i), p.Name, p.Command, p.IconHint); err != nil {
				return def, err
			}
			if seen[p.Name] {
				return def, fmt.Errorf("config: presets[%d]: duplicate name %q", i, p.Name)
			}
			seen[p.Name] = true
			cfg.Presets = append(cfg.Presets, Preset{Name: p.Name, Command: p.Command, IconHint: p.IconHint})
		}
	}
	if wire.Notify != nil {
		if wire.Notify.Patterns != nil {
			if len(*wire.Notify.Patterns) > maxPatternCount {
				return def, fmt.Errorf("config: notifications.patterns: at most %d entries", maxPatternCount)
			}
			seen := make(map[string]bool, len(*wire.Notify.Patterns))
			for i, p := range *wire.Notify.Patterns {
				if err := validatePattern(fmt.Sprintf("notifications.patterns[%d]", i), p.Name, p.Pattern); err != nil {
					return def, err
				}
				if seen[p.Name] {
					return def, fmt.Errorf("config: notifications.patterns[%d]: duplicate name %q", i, p.Name)
				}
				seen[p.Name] = true
				cfg.Notify.Patterns = append(cfg.Notify.Patterns, Pattern{Name: p.Name, Expr: p.Pattern})
			}
		}
		cfg.Notify.BellEnabled = wire.Notify.Bell
	}
	return cfg, nil
}

// SanityCheck cross-validates fields that are individually legal but
// conflicting. Load callers run it before use.
func (c Config) SanityCheck() error {
	if c.Listen.Loopback && c.Listen.LAN &&
		c.Listen.LoopbackPort == c.Listen.LANPort {
		return errors.New("config: listen.loopback_port and listen.lan_port must differ")
	}
	return nil
}

func validatePort(name string, v int) error {
	if v < 1 || v > 65535 {
		return fmt.Errorf("config: %s must be in 1..65535", name)
	}
	return nil
}

// validateRelayAddr checks that a relay address is a non-empty host:port with
// a numeric port in range. The host may be a name, an IPv4 literal, or an
// IPv6 literal (bracketed by SplitHostPort).
func validateRelayAddr(v string) error {
	if v == "" {
		return errors.New("config: relay.addr must be non-empty")
	}
	host, port, err := net.SplitHostPort(v)
	if err != nil {
		return fmt.Errorf("config: relay.addr must be host:port: %w", err)
	}
	if host == "" {
		return errors.New("config: relay.addr host must be non-empty")
	}
	n, err := strconv.Atoi(port)
	if err != nil || n < 1 || n > 65535 {
		return fmt.Errorf("config: relay.addr port out of range: %q", port)
	}
	return nil
}

func validatePreset(path, name, command, icon string) error {
	if len(name) < 1 || len(name) > maxPresetNameLen {
		return fmt.Errorf("config: %s.name must be 1..%d bytes", path, maxPresetNameLen)
	}
	if !utf8ValidNoControl(name) {
		return fmt.Errorf("config: %s.name must be printable UTF-8", path)
	}
	if len(command) < 1 || len(command) > maxCommandBytes {
		return fmt.Errorf("config: %s.command must be 1..%d bytes", path, maxCommandBytes)
	}
	if !utf8.ValidString(command) || strings.ContainsRune(command, '\x00') {
		return fmt.Errorf("config: %s.command must be valid UTF-8 without NUL", path)
	}
	if len(icon) > maxPresetIconLen {
		return fmt.Errorf("config: %s.icon_hint must be at most %d bytes", path, maxPresetIconLen)
	}
	if !utf8ValidNoControl(icon) {
		return fmt.Errorf("config: %s.icon_hint must be printable UTF-8", path)
	}
	return nil
}

func validatePattern(path, name, expr string) error {
	if len(name) < 1 || len(name) > maxPatternNameLen {
		return fmt.Errorf("config: %s.name must be 1..%d bytes", path, maxPatternNameLen)
	}
	if !utf8ValidNoControl(name) {
		return fmt.Errorf("config: %s.name must be printable UTF-8", path)
	}
	if len(expr) < 1 || len(expr) > maxPatternExprLen {
		return fmt.Errorf("config: %s.pattern must be 1..%d bytes", path, maxPatternExprLen)
	}
	if _, err := regexp.Compile(expr); err != nil {
		return fmt.Errorf("config: %s.pattern is not a valid RE2 expression: %v", path, err)
	}
	return nil
}

const maxCommandBytes = 4096

// utf8ValidNoControl reports whether s is valid UTF-8 without control
// characters (C0/C1, except that names and icons must not contain any at
// all: they are display text).
func utf8ValidNoControl(s string) bool {
	if !utf8.ValidString(s) {
		return false
	}
	for _, r := range s {
		if r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f) {
			return false
		}
	}
	return true
}

func validateShell(v string) error {
	if v == "" {
		return errors.New("config: shell must be a non-empty absolute path")
	}
	if len(v) > maxShellLen {
		return fmt.Errorf("config: shell exceeds %d bytes", maxShellLen)
	}
	if strings.ContainsRune(v, '\x00') {
		return errors.New("config: shell must not contain NUL")
	}
	if !filepath.IsAbs(v) {
		return errors.New("config: shell must be an absolute path")
	}
	return nil
}
