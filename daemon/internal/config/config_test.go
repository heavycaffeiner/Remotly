package config

import (
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

func writeTemp(t *testing.T, content string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(p, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestLoadMissingReturnsDefault(t *testing.T) {
	cfg, ok, err := Load(filepath.Join(t.TempDir(), "nope.json"))
	if err != nil {
		t.Fatal(err)
	}
	if ok {
		t.Fatal("expected ok=false for missing file")
	}
	def := Default()
	if !reflect.DeepEqual(cfg, def) {
		t.Fatalf("got %+v want default %+v", cfg, def)
	}
}

func TestParseEmptyObjectIsDefault(t *testing.T) {
	cfg, err := Parse([]byte("{}"))
	if err != nil {
		t.Fatal(err)
	}
	if !reflect.DeepEqual(cfg, Default()) {
		t.Fatalf("got %+v want default", cfg)
	}
}

func TestParseValidPartial(t *testing.T) {
	in := `{"version":1,"listen":{"lan_port":9999},"sessions":{"max":8},"scrollback":{"lines":2048}}`
	cfg, err := Parse([]byte(in))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Listen.LANPort != 9999 || cfg.Sessions.Max != 8 || cfg.Scrollback.Lines != 2048 {
		t.Fatalf("got %+v", cfg)
	}
	if cfg.Listen.LoopbackPort != DefaultLoopbackPort {
		t.Fatalf("default not preserved: %+v", cfg.Listen)
	}
}

func TestParseRejectsUnknownFields(t *testing.T) {
	cases := []string{
		`{"nope":1}`,
		`{"listen":{"port":1}}`,
		`{"sessions":{"maximum":1}}`,
		`{"scrollback":{"rows":1}}`,
	}
	for _, in := range cases {
		if _, err := Parse([]byte(in)); err == nil {
			t.Errorf("input %s: expected unknown-field error", in)
		}
	}
}

func TestParseRejectsBadValues(t *testing.T) {
	cases := map[string]string{
		`{"version":2}`:                                      "version",
		`{"listen":{"loopback_port":0}}`:                     "port zero",
		`{"listen":{"lan_port":70000}}`:                      "port high",
		`{"listen":{"loopback_port":"x"}}`:                   "port type",
		`{"shell":"bash"}`:                                   "relative shell",
		`{"shell":"/bin/` + strings.Repeat("a", 5000) + `"}`: "shell length",
		`{"shell":"/bin/\u0000sh"}`:                          "shell NUL",
		`{"term":"x;rm"}`:                                    "term chars",
		`{"sessions":{"max":0}}`:                             "session max zero",
		`{"sessions":{"max":100000}}`:                        "session max high",
		`{"scrollback":{"lines":10}}`:                        "scrollback low",
		`{"scrollback":{"lines":99999999}}`:                  "scrollback high",
		`{"listen":{"lan_port":true}}`:                       "port bool",
	}
	for in, name := range cases {
		if _, err := Parse([]byte(in)); err == nil {
			t.Errorf("%s: expected error", name)
		}
	}
}

func TestParseRejectsMalformedAndTrailing(t *testing.T) {
	for _, in := range []string{
		`{"version":1`,
		`not json`,
		`{"version":1} trailing`,
		``,
		`[1,2]`,
	} {
		if _, err := Parse([]byte(in)); err == nil {
			t.Errorf("input %q: expected error", in)
		}
	}
}

func TestParseErrorNamesFieldWithoutContent(t *testing.T) {
	_, err := Parse([]byte(`{"listen":{"lan_port":0}}`))
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "lan_port") {
		t.Fatalf("error should name the field: %v", err)
	}
	if strings.Contains(err.Error(), `{"listen"`) {
		t.Fatalf("error should not echo raw input: %v", err)
	}
}

func TestLoadRejectsSymlink(t *testing.T) {
	dir := t.TempDir()
	target := writeTemp(t, `{}`)
	link := filepath.Join(dir, "config.json")
	if err := os.Symlink(target, link); err != nil {
		t.Skipf("symlink unsupported: %v", err)
	}
	if _, _, err := Load(link); err == nil {
		t.Fatal("expected symlink rejection")
	}
}

func TestLoadRejectsOversized(t *testing.T) {
	p := writeTemp(t, `{"version":1,"shell":"`+strings.Repeat("a", MaxFileBytes)+`"}`)
	// The oversized shell is also invalid by itself; the size check must
	// fire first.
	_, _, err := Load(p)
	if err == nil {
		t.Fatal("expected error")
	}
	if !strings.Contains(err.Error(), "exceeds") {
		t.Fatalf("expected size error, got %v", err)
	}
}

func TestLoadRejectsUnreadable(t *testing.T) {
	p := writeTemp(t, `{}`)
	if err := os.Chmod(p, 0o000); err != nil {
		t.Fatal(err)
	}
	defer os.Chmod(p, 0o600)
	if os.Geteuid() == 0 {
		t.Skip("root bypasses file permissions")
	}
	if _, _, err := Load(p); err == nil {
		t.Fatal("expected permission error")
	}
}

func TestShellOverrideAccepted(t *testing.T) {
	cfg, err := Parse([]byte(`{"shell":"/opt/my shell/bin/zsh"}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Shell != "/opt/my shell/bin/zsh" {
		t.Fatalf("got %q", cfg.Shell)
	}
}

func TestM2Presets(t *testing.T) {
	in := `{"version":1,"presets":[
		{"name":"claude","command":"claude","icon_hint":"spark"},
		{"name":"codex","command":"codex --yolo"}
	]}`
	cfg, err := Parse([]byte(in))
	if err != nil {
		t.Fatal(err)
	}
	if len(cfg.Presets) != 2 || cfg.Presets[0].Name != "claude" || cfg.Presets[1].Command != "codex --yolo" || cfg.Presets[1].IconHint != "" {
		t.Fatalf("presets %+v", cfg.Presets)
	}
}

func TestM2PresetsInvalid(t *testing.T) {
	mk := func(p string) string {
		return `{"version":1,"presets":[` + p + `]}`
	}
	longName := strings.Repeat("n", 51)
	longCmd := strings.Repeat("c", 4097)
	longIcon := strings.Repeat("i", 33)
	dup := mk(`{"name":"a","command":"x"},{"name":"a","command":"y"}`)
	many := `{"version":1,"presets":` + "[" + strings.Repeat(`{"name":"a","command":"x"},`, 16) + `{"name":"b","command":"y"}` + "]}"
	cases := map[string]string{
		"empty name":      mk(`{"name":"","command":"x"}`),
		"long name":       mk(`{"name":"` + longName + `","command":"x"}`),
		"control name":    mk(`{"name":"a\nb","command":"x"}`),
		"empty command":   mk(`{"name":"a","command":""}`),
		"long command":    mk(`{"name":"a","command":"` + longCmd + `"}`),
		"nul command":     mk(`{"name":"a","command":"a\u0000b"}`),
		"long icon":       mk(`{"name":"a","command":"x","icon_hint":"` + longIcon + `"}`),
		"control icon":    mk(`{"name":"a","command":"x","icon_hint":"i\t"}`),
		"duplicate name":  dup,
		"too many":        many,
		"missing command": mk(`{"name":"a"}`),
	}
	for name, in := range cases {
		if _, err := Parse([]byte(in)); err == nil {
			t.Fatalf("%s: accepted", name)
		}
	}
}

func TestM2Notify(t *testing.T) {
	in := `{"version":1,"notifications":{"bell":false,"patterns":[
		{"name":"error","pattern":"(?i)error:"},
		{"name":"done","pattern":"DONE"}
	]}}`
	cfg, err := Parse([]byte(in))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Notify.BellOn() {
		t.Fatal("bell should be off")
	}
	if len(cfg.Notify.Patterns) != 2 || cfg.Notify.Patterns[0].Expr != "(?i)error:" || cfg.Notify.Patterns[1].Name != "done" {
		t.Fatalf("patterns %+v", cfg.Notify.Patterns)
	}

	// Bell defaults to on when the key is absent.
	cfg, err = Parse([]byte(`{"version":1,"notifications":{"patterns":[]}}`))
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.Notify.BellOn() {
		t.Fatal("bell should default on")
	}
}

func TestM2NotifyInvalid(t *testing.T) {
	mk := func(p string) string {
		return `{"version":1,"notifications":{"patterns":[` + p + `]}}`
	}
	longName := strings.Repeat("n", 51)
	longExpr := strings.Repeat("a", 257)
	many := `{"version":1,"notifications":{"patterns":` + "[" + strings.Repeat(`{"name":"a","pattern":"x"},`, 32) + `{"name":"b","pattern":"y"}` + "]}}"
	cases := map[string]string{
		"bad re2":        mk(`{"name":"a","pattern":"("}`),
		"empty expr":     mk(`{"name":"a","pattern":""}`),
		"long expr":      mk(`{"name":"a","pattern":"` + longExpr + `"}`),
		"empty name":     mk(`{"name":"","pattern":"x"}`),
		"long name":      mk(`{"name":"` + longName + `","pattern":"x"}`),
		"control name":   mk(`{"name":"a\rb","pattern":"x"}`),
		"duplicate name": mk(`{"name":"a","pattern":"x"},{"name":"a","pattern":"y"}`),
		"too many":       many,
	}
	for name, in := range cases {
		if _, err := Parse([]byte(in)); err == nil {
			t.Fatalf("%s: accepted", name)
		}
	}
}

func TestM2RetainedAfterExit(t *testing.T) {
	for _, v := range []int{0, 5, 3600} {
		in := `{"version":1,"sessions":{"retained_after_exit":` + itoaCfg(v) + `}}`
		cfg, err := Parse([]byte(in))
		if err != nil {
			t.Fatalf("retained %d: %v", v, err)
		}
		if cfg.Sessions.RetainedAfterExit != v {
			t.Fatalf("retained = %d, want %d", cfg.Sessions.RetainedAfterExit, v)
		}
	}
	for _, v := range []int{-1, 3601} {
		in := `{"version":1,"sessions":{"retained_after_exit":` + itoaCfg(v) + `}}`
		if _, err := Parse([]byte(in)); err == nil {
			t.Fatalf("retained %d: accepted", v)
		}
	}
}

func itoaCfg(v int) string {
	if v < 0 {
		return "-" + strings.TrimPrefix(itoaCfg(-v), "-")
	}
	if v == 0 {
		return "0"
	}
	var b [12]byte
	i := len(b)
	for v > 0 {
		i--
		b[i] = byte('0' + v%10)
		v /= 10
	}
	return string(b[i:])
}

func TestParseRelayDefaultDisabled(t *testing.T) {
	cfg, err := Parse([]byte(`{}`))
	if err != nil {
		t.Fatal(err)
	}
	if cfg.Relay.Enabled || cfg.Relay.Addr != "" {
		t.Fatalf("relay not disabled by default: %+v", cfg.Relay)
	}
}

func TestParseRelayEnabled(t *testing.T) {
	in := `{"relay":{"enabled":true,"addr":"relay.example.com:443"}}`
	cfg, err := Parse([]byte(in))
	if err != nil {
		t.Fatal(err)
	}
	if !cfg.Relay.Enabled || cfg.Relay.Addr != "relay.example.com:443" {
		t.Fatalf("got %+v", cfg.Relay)
	}
}

func TestParseRelayRejects(t *testing.T) {
	cases := map[string]string{
		`{"relay":{"enabled":true}}`:                     "enabled without addr",
		`{"relay":{"enabled":true,"addr":"noport"}}`:     "missing port",
		`{"relay":{"enabled":true,"addr":"host:0"}}`:     "port zero",
		`{"relay":{"enabled":true,"addr":"host:70000"}}`: "port high",
		`{"relay":{"enabled":true,"addr":"host:abc"}}`:   "non-numeric port",
		`{"relay":{"enabled":true,"addr":":443"}}`:       "empty host",
		`{"relay":{"enabled":true,"addr":["x"]}}`:        "wrong type",
	}
	for in, name := range cases {
		if _, err := Parse([]byte(in)); err == nil {
			t.Errorf("%s: input %s expected error", name, in)
		}
	}
}

func TestParseRelayIPv6Addr(t *testing.T) {
	in := `{"relay":{"enabled":true,"addr":"[2001:db8::1]:443"}}`
	cfg, err := Parse([]byte(in))
	if err != nil {
		t.Fatalf("IPv6 relay addr should be accepted: %v", err)
	}
	if cfg.Relay.Addr != "[2001:db8::1]:443" {
		t.Fatalf("got %+v", cfg.Relay)
	}
}
