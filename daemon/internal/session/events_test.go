package session

import (
	"fmt"
	"strings"
	"sync"
	"testing"
)

type eventCollector struct {
	mu     sync.Mutex
	events []Event
}

func (c *eventCollector) add(e Event) {
	c.mu.Lock()
	c.events = append(c.events, e)
	c.mu.Unlock()
}

func (c *eventCollector) take() []Event {
	c.mu.Lock()
	defer c.mu.Unlock()
	out := append([]Event(nil), c.events...)
	c.events = nil
	return out
}

func newTestEvents(t *testing.T, ev *Events) (*eventMatcher, *eventCollector) {
	t.Helper()
	col := &eventCollector{}
	template, err := compileEvents(ev)
	if err != nil {
		t.Fatalf("compile: %v", err)
	}
	if template == nil {
		t.Fatal("nil matcher")
	}
	template.setSink(col.add)
	m := template.newMatcher()
	return m, col
}

func TestEventBell(t *testing.T) {
	m, col := newTestEvents(t, &Events{BellEnabled: true})
	m.feed("s1", []byte("ready\x07"))
	evs := col.take()
	if len(evs) != 1 || evs[0].Kind != "bell" || evs[0].Text != "ready" {
		t.Fatalf("events %+v", evs)
	}
	if evs[0].Seq != 1 || evs[0].SessionID != "s1" || evs[0].Pattern != "" {
		t.Fatalf("event %+v", evs[0])
	}
	if evs[0].At.IsZero() {
		t.Fatal("event has no timestamp")
	}
}

func TestEventBellCooldown(t *testing.T) {
	m, col := newTestEvents(t, &Events{BellEnabled: true})
	m.feed("s1", []byte("\x07"))
	m.feed("s1", []byte("x\x07"))
	evs := col.take()
	if len(evs) != 1 {
		t.Fatalf("cooldown ignored, got %+v", evs)
	}
}

func TestEventBellDisabled(t *testing.T) {
	m, col := newTestEvents(t, &Events{BellEnabled: false})
	m.feed("s1", []byte("x\x07"))
	if len(col.take()) != 0 {
		t.Fatalf("bell reported while disabled")
	}
}

func TestEventPatternAcrossChunks(t *testing.T) {
	m, col := newTestEvents(t, &Events{Patterns: []PatternSpec{{Name: "done", Expr: "foo bar"}}})
	m.feed("s1", []byte("foo"))
	if len(col.take()) != 0 {
		t.Fatalf("early match: %+v", col.take())
	}
	m.feed("s1", []byte(" bar\n"))
	evs := col.take()
	if len(evs) != 1 || evs[0].Kind != "pattern" || evs[0].Pattern != "done" || evs[0].Text != "foo bar" {
		t.Fatalf("events %+v", evs)
	}
}

func TestEventPatternNotReReported(t *testing.T) {
	m, col := newTestEvents(t, &Events{Patterns: []PatternSpec{{Name: "hello", Expr: "hello"}}})
	m.feed("s1", []byte("hello\n"))
	if len(col.take()) != 1 {
		t.Fatalf("first match missing: %+v", col.take())
	}
	// More output that only extends the window: the old match must not
	// fire again.
	m.feed("s1", []byte("world\n"))
	if evs := col.take(); len(evs) != 0 {
		t.Fatalf("stale match re-reported: %+v", evs)
	}
}

func TestEventPatternWindowBounded(t *testing.T) {
	// A match spanning more than the 16 KiB window cannot fire: the
	// window evicted its start.
	expr := "^A" + strings.Repeat("x", EventWindowBytes+100) + "B$"
	m, col := newTestEvents(t, &Events{Patterns: []PatternSpec{{Name: "big", Expr: expr}}})
	m.feed("s1", []byte("A"+strings.Repeat("x", EventWindowBytes+100)+"B\n"))
	if evs := col.take(); len(evs) != 0 {
		t.Fatalf("window not bounded: %+v", evs)
	}
}

func TestEventMalformedUTF8(t *testing.T) {
	m, col := newTestEvents(t, &Events{Patterns: []PatternSpec{{Name: "bad", Expr: "\uFFFD"}}})
	m.feed("s1", []byte("a\xffb\n"))
	evs := col.take()
	if len(evs) != 1 || evs[0].Pattern != "bad" {
		t.Fatalf("events %+v", evs)
	}
}

func TestEventCJK(t *testing.T) {
	m, col := newTestEvents(t, &Events{Patterns: []PatternSpec{{Name: "ok", Expr: "확인"}}})
	m.feed("s1", []byte("작업 확인 완료\n"))
	evs := col.take()
	if len(evs) != 1 || evs[0].Text != "확인" {
		t.Fatalf("events %+v", evs)
	}
}

func TestEventOnePerRulePerChunk(t *testing.T) {
	m, col := newTestEvents(t, &Events{Patterns: []PatternSpec{{Name: "a", Expr: "a+"}}})
	m.feed("s1", []byte("aaaa\n"))
	evs := col.take()
	if len(evs) != 1 {
		t.Fatalf("one per rule per chunk violated: %+v", evs)
	}
}

func TestEventSeqIncreasing(t *testing.T) {
	m, col := newTestEvents(t, &Events{
		BellEnabled: true,
		Patterns: []PatternSpec{
			{Name: "p1", Expr: "one"},
			{Name: "p2", Expr: "two"},
		},
	})
	m.feed("s1", []byte("one two\x07\n"))
	evs := col.take()
	if len(evs) != 3 {
		t.Fatalf("want 3 events, got %+v", evs)
	}
	for i, e := range evs {
		if e.Seq != uint64(i+1) {
			t.Fatalf("seq %d = %d", i, e.Seq)
		}
	}
}

func TestEventRateLimit(t *testing.T) {
	// Thirty rules, one fresh match each in a single chunk: 30 events
	// requested at once. The token bucket (burst 10, 10 per 6s) must let
	// some through and drop the rest.
	specs := make([]PatternSpec, 30)
	chunk := []byte("abcdefghijklmnopqrstuvwxyz0123")
	for i := range specs {
		specs[i] = PatternSpec{Name: fmt.Sprintf("r%d", i), Expr: string(chunk[i])}
	}
	m, col := newTestEvents(t, &Events{Patterns: specs})
	m.feed("s1", chunk)
	evs := col.take()
	if len(evs) >= 30 {
		t.Fatalf("rate limit not applied: %d events", len(evs))
	}
	if len(evs) < 5 {
		t.Fatalf("too few events: %d", len(evs))
	}
	if m.Dropped() == 0 {
		t.Fatalf("dropped = 0 with %d events", len(evs))
	}
}

func TestEventPerSessionIsolation(t *testing.T) {
	template, err := compileEvents(&Events{Patterns: []PatternSpec{{Name: "done", Expr: "done"}}})
	if err != nil {
		t.Fatal(err)
	}
	col := &eventCollector{}
	template.setSink(col.add)
	m1, m2 := template.newMatcher(), template.newMatcher()
	m1.feed("s1", []byte("done\n"))
	m2.feed("s2", []byte("done\n"))
	evs := col.take()
	if len(evs) != 2 || evs[0].SessionID != "s1" || evs[1].SessionID != "s2" {
		t.Fatalf("events %+v", evs)
	}
	if evs[0].Seq != 1 || evs[1].Seq != 1 {
		t.Fatalf("seqs not per-session: %+v", evs)
	}
}

func TestEventCompileRejectsBadRE2(t *testing.T) {
	if _, err := compileEvents(&Events{Patterns: []PatternSpec{{Name: "bad", Expr: "("}}}); err == nil {
		t.Fatal("bad RE2 accepted")
	}
}
