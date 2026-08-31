package session

import (
	"strings"
	"testing"
)

func TestPreviewPlain(t *testing.T) {
	if got := previewFromTail([]byte("hello")); got != "hello" {
		t.Fatalf("got %q", got)
	}
	if got := previewFromTail(nil); got != "" {
		t.Fatalf("got %q", got)
	}
	if got := previewFromTail([]byte("\n")); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestPreviewLastLine(t *testing.T) {
	if got := previewFromTail([]byte("line1\nline2")); got != "line2" {
		t.Fatalf("got %q", got)
	}
	if got := previewFromTail([]byte("line1\nline2\n")); got != "line2" {
		t.Fatalf("got %q", got)
	}
	if got := previewFromTail([]byte("\nonly")); got != "only" {
		t.Fatalf("got %q", got)
	}
}

func TestPreviewStripsANSI(t *testing.T) {
	cases := map[string]string{
		"\x1b[31mred\x1b[0m":          "red",
		"\x1b[1;32m\x1b[40mhi there":  "hi there",
		"\x1b]0;window title\x07body": "body",
		"\x1b[?25l\x1b[Kcleared":      "cleared",
		"a\x1bmb":                     "ab",
		"\x1b]0;t\x1b\\after":         "after",
		"\x1b[31m\x1b[32m":            "",
	}
	for in, want := range cases {
		if got := sanitizeLine([]byte(in)); got != want {
			t.Fatalf("sanitize %q = %q, want %q", in, got, want)
		}
	}
}

func TestPreviewStripsControls(t *testing.T) {
	cases := map[string]string{
		"a\tb\rc":     "a bc",
		"a\x01b\x7fc": "abc",
		"a\x9bb":      "ab",
		"a\u0085b":    "ab",
		"  spaced  ":  "spaced",
	}
	for in, want := range cases {
		if got := sanitizeLine([]byte(in)); got != want {
			t.Fatalf("sanitize %q = %q, want %q", in, got, want)
		}
	}
}

func TestPreviewMalformedUTF8(t *testing.T) {
	if got := sanitizeLine([]byte("ab\xffcd")); got != "abcd" {
		t.Fatalf("got %q", got)
	}
	if got := sanitizeLine([]byte("\xff")); got != "" {
		t.Fatalf("got %q", got)
	}
}

func TestPreviewTruncatesOnRuneBoundary(t *testing.T) {
	// 130 ASCII bytes -> exactly 120.
	if got := sanitizeLine([]byte(strings.Repeat("a", 130))); len(got) != 120 {
		t.Fatalf("len %d", len(got))
	}
	// 50 CJK runes (150 bytes) -> 40 runes (120 bytes), no split rune.
	cjk := strings.Repeat("한", 50)
	got := sanitizeLine([]byte(cjk))
	if len(got) != 120 {
		t.Fatalf("len %d, want 120", len(got))
	}
	if strings.Count(got, "한") != 40 {
		t.Fatalf("got %d runes", strings.Count(got, "한"))
	}
	// Truncation must not split a rune at the boundary: 39 full CJK runes
	// (117 bytes) plus a 4-byte rune that would cross 120 stays at 117.
	mixed := strings.Repeat("한", 39) + "\U0001F600"
	got = sanitizeLine([]byte(mixed))
	if len(got) != 117 {
		t.Fatalf("len %d, want 117", len(got))
	}
}

func TestPreviewTailScansBoundedRegion(t *testing.T) {
	// A huge earlier line must not leak into the preview.
	big := make([]byte, 4096)
	for i := range big {
		big[i] = 'x'
	}
	big[4095] = '\n'
	tail := append(big, []byte("short tail")...)
	if got := previewFromTail(tail); got != "short tail" {
		t.Fatalf("got %q", got)
	}
}
