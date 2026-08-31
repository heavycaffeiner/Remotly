package session

import (
	"fmt"
	"regexp"
	"sync"
	"time"
	"unicode/utf8"
)

// PatternSpec is one configured output-pattern rule. Expr is a RE2 regular
// expression; Name identifies the rule in notifications.
type PatternSpec struct {
	Name string
	Expr string
}

// Events configures terminal event detection for every session: the
// terminal-bell event and the configured output patterns.
type Events struct {
	// BellEnabled enables the bell event. A bell is a 0x07 byte on the
	// session's live output stream.
	BellEnabled bool
	// Patterns are the output-pattern rules.
	Patterns []PatternSpec
}

// Event is one terminal event on a session, delivered to the manager's
// event sink. Text is bounded plain text derived from terminal content; it
// must never be logged.
type Event struct {
	SessionID string
	Seq       uint64 // per-session, starts at 1, strictly increasing
	Kind      string // "bell" or "pattern"
	Pattern   string // rule name, pattern kind only
	Text      string // bounded plain text, "" when there is none
	At        time.Time
}

// EventSink receives events. It is called from the session's drain
// goroutine and must not block for long; the transport forwards it to the
// network.
type EventSink func(e Event)

const (
	// EventWindowBytes bounds the rolling window a pattern is matched
	// against: the last 16 KiB of raw output.
	EventWindowBytes = 16 << 10
	// EventMaxText bounds the plain-text context carried in an event.
	EventMaxText = 120

	// BellCooldown suppresses repeated bell events on one session within
	// this interval.
	BellCooldown = 2 * time.Second
	// PatternCooldown suppresses repeated matches of one rule on one
	// session within this interval.
	PatternCooldown = 1 * time.Second
	// EventRate and EventBurst bound the event rate per session to a
	// token bucket: a runaway pattern (for example, one that matches every
	// line of a verbose build log) cannot flood the app.
	EventRate  = 10.0 / 6.0 // 10 events per 6 seconds
	EventBurst = 10
)

type compiledPattern struct {
	name  string
	regex *regexp.Regexp
}

type eventMatcher struct {
	bellEnabled bool
	patterns    []compiledPattern
	sink        EventSink

	mu        sync.Mutex
	window    []byte // last EventWindowBytes raw output bytes
	seq       uint64
	lastBell  time.Time
	coolUntil map[string]time.Time // per-pattern cooldown
	tokens    float64
	lastTick  time.Time
	dropped   int // events dropped by the rate limit, since start
}

// compileEvents compiles the pattern rules once, at manager construction.
// A rule that fails to compile is a configuration error surfaced before any
// session starts.
func compileEvents(cfg *Events) (*eventMatcher, error) {
	if cfg == nil {
		return nil, nil
	}
	m := &eventMatcher{
		bellEnabled: cfg.BellEnabled,
		coolUntil:   make(map[string]time.Time),
		tokens:      EventBurst,
	}
	for _, p := range cfg.Patterns {
		re, err := regexp.Compile(p.Expr)
		if err != nil {
			return nil, fmt.Errorf("session: pattern %q: %v", p.Name, err)
		}
		m.patterns = append(m.patterns, compiledPattern{name: p.Name, regex: re})
	}
	return m, nil
}

// newMatcher derives a per-session matcher from the shared compiled rules.
// The sink is shared: every session's events flow to the manager's OnEvent.
func (m *eventMatcher) newMatcher() *eventMatcher {
	m.mu.Lock()
	sink := m.sink
	m.mu.Unlock()
	return &eventMatcher{
		bellEnabled: m.bellEnabled,
		patterns:    m.patterns,
		sink:        sink,
		coolUntil:   make(map[string]time.Time),
		tokens:      EventBurst,
	}
}

// setSink installs the event sink.
func (m *eventMatcher) setSink(s EventSink) {
	m.mu.Lock()
	m.sink = s
	m.mu.Unlock()
}

// feed consumes one live output chunk. At most one event per rule and one
// bell event per chunk are reported, subject to cooldowns and the
// per-session rate limit.
func (m *eventMatcher) feed(sessionID string, chunk []byte) {
	if len(chunk) == 0 {
		return
	}
	now := time.Now()

	m.mu.Lock()
	defer m.mu.Unlock()

	// Update the rolling window first: both the bell text and the pattern
	// newness check must see the freshly appended bytes. A match whose end
	// falls in the new bytes is new, even when it started in the old window.
	oldLen := len(m.window)
	m.window = append(m.window, chunk...)
	if len(m.window) > EventWindowBytes {
		m.window = m.window[len(m.window)-EventWindowBytes:]
	}

	if m.bellEnabled {
		belled := false
		for _, b := range chunk {
			if b == 0x07 {
				belled = true
				break
			}
		}
		if belled && now.Sub(m.lastBell) >= BellCooldown {
			m.lastBell = now
			m.emit(sessionID, now, Event{
				SessionID: sessionID,
				Kind:      "bell",
				Text:      previewFromTail(m.windowTail()),
				At:        now,
			})
		}
	}

	if len(m.patterns) == 0 {
		return
	}

	text, rawAt := decodeWindow(m.window)
	// rawAt maps a text byte offset at a rune start to the raw-byte offset
	// of that start, so a match ending at text offset idx[1] ends at raw
	// offset rawAt[idx[1]] and is new when that exceeds oldLen.
	for _, p := range m.patterns {
		for _, idx := range p.regex.FindAllStringIndex(text, -1) {
			end, ok := rawAt[idx[1]]
			if !ok || end <= oldLen {
				continue
			}
			if until, ok := m.coolUntil[p.name]; ok && now.Before(until) {
				break // this rule is cooling down; skip its remaining matches
			}
			m.coolUntil[p.name] = now.Add(PatternCooldown)
			matched := text[idx[0]:idx[1]]
			m.emit(sessionID, now, Event{
				SessionID: sessionID,
				Kind:      "pattern",
				Pattern:   p.name,
				Text:      truncateRunes(sanitizeLine([]byte(matched)), EventMaxText),
				At:        now,
			})
			break // one event per rule per chunk
		}
	}
}

// windowTail returns the last previewTailBytes of the window for bell text.
func (m *eventMatcher) windowTail() []byte {
	if len(m.window) <= previewTailBytes {
		return m.window
	}
	return m.window[len(m.window)-previewTailBytes:]
}

// emit applies the per-session rate limit and bumps the sequence counter.
// Callers hold m.mu; the sink must not call back into the matcher.
func (m *eventMatcher) emit(sessionID string, now time.Time, ev Event) {
	if !m.allow(now) {
		m.dropped++
		return
	}
	m.seq++
	ev.Seq = m.seq
	sink := m.sink
	if sink == nil {
		return
	}
	sink(ev)
}

func (m *eventMatcher) allow(now time.Time) bool {
	if m.lastTick.IsZero() {
		m.lastTick = now
		return true
	}
	m.tokens += now.Sub(m.lastTick).Seconds() * EventRate
	if m.tokens > EventBurst {
		m.tokens = EventBurst
	}
	m.lastTick = now
	if m.tokens < 1 {
		return false
	}
	m.tokens--
	return true
}

// Dropped returns the number of events dropped by the rate limit since the
// matcher was created.
func (m *eventMatcher) Dropped() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.dropped
}

// decodeWindow decodes raw bytes as UTF-8 (invalid byte -> U+FFFD) and
// returns the text plus rawAt, which maps the text byte offset where a
// rune starts to the raw byte offset of that same rune start. A sentinel
// entry at the end of the text maps to the end of the raw window, so a
// match that ends exactly at the window end resolves.
func decodeWindow(raw []byte) (string, map[int]int) {
	rawAt := make(map[int]int, len(raw)/2+2)
	var b []byte
	for i := 0; i < len(raw); {
		c := raw[i]
		if c < 0x80 {
			rawAt[len(b)] = i
			b = append(b, c)
			i++
			continue
		}
		r, size := utf8.DecodeRune(raw[i:])
		if r == utf8.RuneError && size == 1 {
			rawAt[len(b)] = i
			b = append(b, []byte("\uFFFD")...)
			i++
			continue
		}
		rawAt[len(b)] = i
		b = append(b, raw[i:i+size]...)
		i += size
	}
	rawAt[len(b)] = len(raw)
	return string(b), rawAt
}

// truncateRunes shortens s to at most max bytes without splitting a rune.
func truncateRunes(s string, max int) string {
	if len(s) <= max {
		return s
	}
	for max > 0 && !utf8.RuneStart(s[max]) {
		max--
	}
	return s[:max]
}
