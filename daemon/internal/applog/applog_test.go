package applog

import (
	"bytes"
	"encoding/json"
	"log/slog"
	"strings"
	"testing"
)

func TestRedaction(t *testing.T) {
	var buf bytes.Buffer
	log := New(&buf, slog.LevelDebug)
	log.Info("event",
		"pairing_secret", "topsecretvalue",
		"private_key", "abc",
		"psk", "xyz",
		"device_name", "phone",
		"public_key", "visible0001",
	)
	var rec map[string]any
	if err := json.Unmarshal(buf.Bytes(), &rec); err != nil {
		t.Fatal(err)
	}
	if rec["pairing_secret"] != redacted || rec["private_key"] != redacted || rec["psk"] != redacted {
		t.Fatalf("secrets not redacted: %v", rec)
	}
	if rec["device_name"] != "phone" || rec["public_key"] != "visible0001" {
		t.Fatalf("non-secrets altered: %v", rec)
	}
}

func TestRedactionWithGroupAndAttrs(t *testing.T) {
	var buf bytes.Buffer
	log := New(&buf, slog.LevelDebug).With("session", "s1").WithGroup("pair")
	log.Info("event", "token", "t", "plain", "ok")
	out := buf.String()
	if !strings.Contains(out, `"token":"[redacted]"`) {
		t.Fatalf("grouped secret not redacted: %s", out)
	}
	if !strings.Contains(out, `"plain":"ok"`) {
		t.Fatalf("plain value lost: %s", out)
	}
}
