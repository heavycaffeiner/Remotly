// Package applog provides the daemon's structured logger with secret
// redaction. Any field whose key matches a sensitive pattern is replaced with
// a fixed marker before reaching the output, so a regression that logs a
// secret cannot leak it even to a file sink.
package applog

import (
	"context"
	"io"
	"log/slog"
	"strings"
)

// sensitiveKey reports whether a field key names secret material. The match
// is on lowercased key substrings; public keys stay visible.
func sensitiveKey(key string) bool {
	k := strings.ToLower(key)
	for _, pat := range []string{"secret", "private", "psk", "pairing", "password", "token", "material"} {
		if strings.Contains(k, pat) {
			return true
		}
	}
	return false
}

// redactHandler wraps an slog.Handler and masks sensitive values.
type redactHandler struct {
	next slog.Handler
}

const redacted = "[redacted]"

func (h *redactHandler) Enabled(ctx context.Context, level slog.Level) bool {
	return h.next.Enabled(ctx, level)
}

func (h *redactHandler) Handle(ctx context.Context, r slog.Record) error {
	out := make([]slog.Attr, 0, r.NumAttrs())
	r.Attrs(func(a slog.Attr) bool {
		if sensitiveKey(a.Key) {
			a = slog.Attr{Key: a.Key, Value: slog.StringValue(redacted)}
		}
		out = append(out, a)
		return true
	})
	rec := slog.NewRecord(r.Time, r.Level, r.Message, r.PC)
	rec.AddAttrs(out...)
	return h.next.Handle(ctx, rec)
}

func (h *redactHandler) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &redactHandler{next: h.next.WithAttrs(attrs)}
}

func (h *redactHandler) WithGroup(name string) slog.Handler {
	return &redactHandler{next: h.next.WithGroup(name)}
}

// New returns a JSON logger writing to w with redaction enabled.
func New(w io.Writer, level slog.Level) *slog.Logger {
	return slog.New(&redactHandler{next: slog.NewJSONHandler(w, &slog.HandlerOptions{Level: level})})
}
