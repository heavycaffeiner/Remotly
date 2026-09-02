// Package session manages persistent daemon terminal sessions. A session
// owns one PTY process for its whole lifetime; network clients attach to it
// transiently and never control whether it runs. Output is drained
// continuously into a bounded per-session scrollback and fanned out to any
// attached readers.
package session

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"os"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode/utf8"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
)

// Request limits. Every trust-boundary value is checked here, before any
// allocation or backend call.
const (
	MaxTitleLen   = 200
	MaxCommandLen = 4096
	MaxInputChunk = 1 << 20

	DefaultCols = 80
	DefaultRows = 24

	defaultMaxSessions     = 64
	defaultScrollbackLines = 65536
	defaultMaxAttachments  = 16
	attachmentQueue        = 256
	// defaultIDGenLen is the byte count of session id randomness. Hex-encoded
	// it must yield the 64 hex chars the protocol mandates for session_id.
	defaultIDGenLen = 32

	// shutdownWait bounds how long Shutdown waits for killed children.
	shutdownWait = 10 * time.Second
)

// Errors returned by the manager and sessions. Callers match them with
// errors.Is.
var (
	ErrUnknownSession     = errors.New("session: unknown session")
	ErrSessionExited      = errors.New("session: process has exited")
	ErrCapacity           = errors.New("session: session limit reached")
	ErrIDCollision        = errors.New("session: id collision")
	ErrTooManyAttachments = errors.New("session: attachment limit reached")
	ErrInvalidRequest     = errors.New("session: invalid request")
)

// Kind classifies a session.
type Kind string

const (
	KindShell Kind = "shell"
	KindAgent Kind = "agent"
)

// Metadata is an immutable snapshot of a session.
type Metadata struct {
	ID    string
	Title string
	Kind  Kind
	// Command is the display command line: the shell invocation for shell
	// sessions, the preset command for agent sessions.
	Command      string
	Cwd          string
	Cols         uint16
	Rows         uint16
	CreatedAt    time.Time
	LastActivity time.Time
	Running      bool
	// Exit is valid when Running is false.
	Exit pty.ExitStatus
	// Preview is the last retained output line as plain text (escape
	// sequences and control characters stripped, at most 120 bytes), or ""
	// when nothing is retained.
	Preview string
	// TitlePinned is set once someone renames the session by hand. The app
	// stops applying the terminal's own title after that, so a shell
	// repainting its title cannot overwrite a name the user chose.
	TitlePinned bool
}

// Request describes a new session.
type Request struct {
	Kind  Kind
	Title string
	// Command is the preset command line for agent sessions; required for
	// agent, forbidden for shell.
	Command string
	// KeepShell leaves an interactive shell behind when Command exits, so a
	// session started from a terminal returns to a prompt instead of ending.
	KeepShell bool
	Cwd       string
	Cols      uint16
	Rows      uint16
}

// Options configures a Manager.
type Options struct {
	Backend         pty.Backend
	Shell           string // configured shell path; empty resolves $SHELL/account
	Term            string
	MaxSessions     int
	ScrollbackLines int
	MaxAttachments  int
	// IDGen mints session ids. It must be non-guessable; tests may inject a
	// deterministic one.
	IDGen func() string
	// RetainedAfterExit keeps an exited session listed and attachable for
	// this long, so a client can still replay its final scrollback. Zero or
	// negative uses DefaultRetainedAfterExit.
	RetainedAfterExit time.Duration
	// Events configures terminal event detection (bell and output patterns).
	// Nil disables event detection. Pattern rules are compiled once at
	// manager construction; a rule that fails to compile is a construction
	// error.
	Events *Events
	// OnEvent, if set, receives terminal events (bell and pattern matches)
	// from the sessions' drain goroutines. It must not block for long.
	OnEvent func(Event)
	// OnExit, if set, is called once per session, from the session's wait
	// goroutine, after the final metadata is recorded. It must not call back
	// into the manager on the session that just exited in a blocking way;
	// the transport uses it to broadcast session.update notifications.
	OnExit func(m Metadata)
}

const (
	// DefaultRetainedAfterExit is the default post-exit retention window.
	DefaultRetainedAfterExit = 300 * time.Second
	// reapInterval is how often expired retired sessions are purged.
	reapInterval = 30 * time.Second
	// maxRetiredSessions bounds the number of exited-but-retained sessions,
	// each holding a bounded scrollback. The oldest is evicted first.
	maxRetiredSessions = 64
)

func (o *Options) fillDefaults() {
	if o.Term == "" {
		o.Term = "xterm-256color"
	}
	if o.MaxSessions <= 0 {
		o.MaxSessions = defaultMaxSessions
	}
	if o.ScrollbackLines <= 0 {
		o.ScrollbackLines = defaultScrollbackLines
	}
	if o.MaxAttachments <= 0 {
		o.MaxAttachments = defaultMaxAttachments
	}
	if o.IDGen == nil {
		o.IDGen = randomID
	}
	if o.RetainedAfterExit <= 0 {
		o.RetainedAfterExit = DefaultRetainedAfterExit
	}
}

func randomID() string {
	var b [defaultIDGenLen]byte
	if _, err := rand.Read(b[:]); err != nil {
		// rand.Read fails only on a broken OS; fail the operation.
		panic(fmt.Sprintf("session: %v", err))
	}
	return hex.EncodeToString(b[:])
}

// Stats are observable counters. They never record terminal content.
type Stats struct {
	Created           int
	Exited            int
	Killed            int
	ReadersOverflowed int // slow readers dropped
	ReadersCancelled  int // readers closed by their caller
	ReadersExited     int // readers ended by session exit
}

// Manager owns all sessions. It is safe for concurrent use.
type Manager struct {
	opts      Options
	shellPath string
	shellArgs []string
	shellCmd  string // display string for shell sessions

	mu       sync.Mutex
	sessions map[string]*Session
	// retired holds exited sessions still within their retention window.
	// They stay listed (Running=false) and attachable for final replay, but
	// they do not count toward MaxSessions.
	retired map[string]*retiredEntry
	// matcherTemplate holds the compiled event rules; each session derives
	// its own per-session matcher from it. Nil when events are disabled.
	matcherTemplate *eventMatcher

	stopCh   chan struct{}
	stopOnce sync.Once
	reapDone chan struct{} // closed when the reaper has stopped

	statsMu sync.Mutex
	stats   Stats
}

// retiredEntry is one exited session kept for post-exit replay.
type retiredEntry struct {
	session   *Session
	expiresAt time.Time
}

// New resolves the shell and returns a ready manager. It does not start
// sessions.
func New(opts Options) (*Manager, error) {
	opts.fillDefaults()
	program, args, _, err := pty.ShellFromConfig(opts.Shell, "")
	if err != nil {
		return nil, fmt.Errorf("session: %w", err)
	}
	m := &Manager{
		opts:      opts,
		shellPath: program,
		shellArgs: args,
		shellCmd:  program + " " + strings.Join(args, " "),
		sessions:  make(map[string]*Session),
		retired:   make(map[string]*retiredEntry),
		stopCh:    make(chan struct{}),
		reapDone:  make(chan struct{}),
	}
	if opts.Events != nil {
		template, err := compileEvents(opts.Events)
		if err != nil {
			return nil, err
		}
		template.setSink(func(e Event) {
			if opts.OnEvent != nil {
				opts.OnEvent(e)
			}
		})
		m.matcherTemplate = template
	}
	go m.reapLoop()
	return m, nil
}

// reapLoop purges expired retired sessions on a fixed interval and at
// shutdown. Purging is also done lazily in Get and List, so a quiet daemon
// still converges.
func (m *Manager) reapLoop() {
	defer close(m.reapDone)
	ticker := time.NewTicker(reapInterval)
	defer ticker.Stop()
	for {
		select {
		case <-m.stopCh:
			m.purgeRetired(time.Now())
			return
		case now := <-ticker.C:
			m.purgeRetired(now)
		}
	}
}

// Stats returns a copy of the counters.
func (m *Manager) Stats() Stats {
	m.statsMu.Lock()
	defer m.statsMu.Unlock()
	return m.stats
}

func (m *Manager) bump(field int) {
	m.statsMu.Lock()
	defer m.statsMu.Unlock()
	switch field {
	case statCreated:
		m.stats.Created++
	case statExited:
		m.stats.Exited++
	case statKilled:
		m.stats.Killed++
	case statReadersOverflowed:
		m.stats.ReadersOverflowed++
	case statReadersCancelled:
		m.stats.ReadersCancelled++
	case statReadersExited:
		m.stats.ReadersExited++
	}
}

const (
	statCreated = iota
	statExited
	statKilled
	statReadersOverflowed
	statReadersCancelled
	statReadersExited
)

// Create validates req, starts a session, and returns it. The session runs
// until its process exits, it is killed, or the daemon shuts down.
func (m *Manager) Create(req Request) (*Session, error) {
	if err := validateRequest(req); err != nil {
		return nil, err
	}
	m.mu.Lock()
	if len(m.sessions) >= m.opts.MaxSessions {
		m.mu.Unlock()
		return nil, ErrCapacity
	}
	m.mu.Unlock()
	normalizeSize(&req)
	id := m.opts.IDGen()
	cwd, err := pty.ValidateCwd(req.Cwd)
	if err != nil {
		return nil, fmt.Errorf("%w: %v", ErrInvalidRequest, err)
	}
	s := &Session{
		m:    m,
		id:   id,
		done: make(chan struct{}),
	}
	if m.matcherTemplate != nil {
		s.matcher = m.matcherTemplate.newMatcher()
		// A bell means "the agent wants you" only for an agent session. An
		// interactive shell rings it as ordinary feedback: zsh beeps on an
		// ambiguous completion, a failed history search, and backspace at the
		// start of the line, so notifying on it turned normal typing into a
		// steady stream of notifications. Patterns still apply to both.
		if req.Kind != KindAgent {
			s.matcher.disableBell()
		}
	}
	s.ring = newRing(m.opts.ScrollbackLines, derivedByteCap(m.opts.ScrollbackLines))
	s.meta = Metadata{
		ID:           id,
		Title:        req.Title,
		Kind:         req.Kind,
		Cwd:          cwd,
		Cols:         req.Cols,
		Rows:         req.Rows,
		CreatedAt:    time.Now(),
		LastActivity: time.Now(),
		Running:      true,
	}
	if s.meta.Title == "" {
		if req.Kind == KindAgent {
			s.meta.Title = "agent"
		} else {
			s.meta.Title = "shell"
		}
	}
	if req.Kind == KindAgent {
		s.meta.Command = req.Command
	} else {
		s.meta.Command = m.shellCmd
	}

	proc, err := m.opts.Backend.Start(pty.StartRequest{
		Program:   m.shellPath,
		Args:      m.shellArgs,
		Command:   req.Command,
		KeepShell: req.KeepShell,
		Cwd:       cwd,
		Env:       BuildEnv(m.opts.Term, id),
		Cols:      req.Cols,
		Rows:      req.Rows,
	})
	if err != nil {
		return nil, fmt.Errorf("session: start: %w", err)
	}
	s.proc = proc

	m.mu.Lock()
	if len(m.sessions) >= m.opts.MaxSessions {
		m.mu.Unlock()
		proc.Kill()
		proc.Close()
		return nil, ErrCapacity
	}
	if _, exists := m.sessions[id]; exists {
		m.mu.Unlock()
		proc.Kill()
		proc.Close()
		return nil, ErrIDCollision
	}
	m.sessions[id] = s
	m.mu.Unlock()
	m.bump(statCreated)

	go s.waitLoop()
	go s.drainLoop()
	return s, nil
}

// Get returns the session with the given id, live or within its post-exit
// retention window.
func (m *Manager) Get(id string) (*Session, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if s, ok := m.sessions[id]; ok {
		return s, nil
	}
	m.purgeRetiredLocked(time.Now())
	if e, ok := m.retired[id]; ok {
		return e.session, nil
	}
	return nil, ErrUnknownSession
}

// List returns metadata for every live session and every session within its
// post-exit retention window, oldest first. Exited sessions carry
// Running=false, which clients use to offer final replay without attaching
// to a live process.
func (m *Manager) List() []Metadata {
	m.mu.Lock()
	m.purgeRetiredLocked(time.Now())
	out := make([]Metadata, 0, len(m.sessions)+len(m.retired))
	for _, s := range m.sessions {
		out = append(out, s.Meta())
	}
	for _, e := range m.retired {
		out = append(out, e.session.Meta())
	}
	m.mu.Unlock()
	sort.Slice(out, func(i, j int) bool {
		if out[i].CreatedAt.Equal(out[j].CreatedAt) {
			return out[i].ID < out[j].ID
		}
		return out[i].CreatedAt.Before(out[j].CreatedAt)
	})
	return out
}

// Shutdown kills every live session, waits for all of them to exit, and
// discards the retired retention set. It returns an error naming any child
// that did not die within the bound, rather than abandoning it.
func (m *Manager) Shutdown() error {
	m.mu.Lock()
	ss := make([]*Session, 0, len(m.sessions)+len(m.retired))
	for _, s := range m.sessions {
		ss = append(ss, s)
	}
	for _, e := range m.retired {
		ss = append(ss, e.session)
	}
	m.mu.Unlock()

	live := ss[:0:0]
	for _, s := range ss {
		if s.running() {
			live = append(live, s)
		}
	}
	for _, s := range live {
		s.proc.Kill()
	}
	var errs []error
	deadline := time.Now().Add(shutdownWait)
	for _, s := range live {
		select {
		case <-s.done:
		case <-time.After(time.Until(deadline)):
			errs = append(errs, fmt.Errorf("session: %s still running after shutdown", s.id))
		}
	}
	m.stopOnce.Do(func() { close(m.stopCh) })
	<-m.reapDone
	for _, s := range live {
		// Retired sessions already closed their PTY in waitLoop.
		s.proc.Close()
	}
	m.mu.Lock()
	m.sessions = make(map[string]*Session)
	m.retired = make(map[string]*retiredEntry)
	m.mu.Unlock()
	return errors.Join(errs...)
}

// Session is one persistent PTY process and its scrollback. Operations are
// safe for concurrent use; Read on an attachment is single-goroutine.
type Session struct {
	m    *Manager
	id   string
	proc pty.Process

	done     chan struct{}
	doneOnce sync.Once
	// discard marks a session the user explicitly killed. Retention is for a
	// shell that ended on its own, so a killed one is dropped when it exits
	// instead of lingering in the list.
	discard atomic.Bool

	mu   sync.Mutex
	meta Metadata
	// totalBytes counts every output byte since creation, including bytes
	// already evicted from the ring. It is the coordinate system of replay
	// cursors: a cursor is a byte offset into this stream.
	totalBytes  int64
	ring        *ring
	matcher     *eventMatcher
	attachments map[int]*attachment
	nextAttach  int
	// drainDone is set under mu when the drain loop ends. After this, no live
	// output will ever reach a new attachment, so Attach must close its queue
	// itself or the reader would block forever after the replay.
	drainDone bool
}

// AttachInfo reports how an attach's replay was positioned relative to the
// session's cumulative output stream.
type AttachInfo struct {
	// Continuity is one of the protocol.Continuity* values.
	Continuity   string
	ReplayedFrom uint64
}

// ErrCursorOutOfRange reports a resume cursor beyond the session's total
// output. Callers match with errors.Is.
var ErrCursorOutOfRange = errors.New("session: resume cursor beyond end of output")

// ID returns the session id.
func (s *Session) ID() string { return s.id }

// Meta returns a snapshot of the metadata, with the preview computed from
// the current ring contents.
func (s *Session) Meta() Metadata {
	s.mu.Lock()
	defer s.mu.Unlock()
	m := s.meta
	m.Preview = previewFromTail(s.ring.tail(previewTailBytes))
	return m
}

// Done is closed when the process has exited.
func (s *Session) Done() <-chan struct{} { return s.done }

// Write sends terminal input to the PTY. It records no scrollback.
func (s *Session) Write(b []byte) (int, error) {
	if len(b) > MaxInputChunk {
		return 0, fmt.Errorf("%w: input exceeds %d bytes", ErrInvalidRequest, MaxInputChunk)
	}
	if !s.running() {
		return 0, ErrSessionExited
	}
	n, err := s.proc.Write(b)
	if err == nil {
		s.touch()
	}
	return n, err
}

// Resize changes the PTY dimensions.
func (s *Session) Resize(cols, rows uint16) error {
	if cols < pty.MinCols || cols > pty.MaxCols || rows < pty.MinRows || rows > pty.MaxRows {
		return fmt.Errorf("%w: size %dx%d out of range", ErrInvalidRequest, cols, rows)
	}
	if !s.running() {
		return ErrSessionExited
	}
	s.mu.Lock()
	s.meta.Cols = cols
	s.meta.Rows = rows
	s.mu.Unlock()
	return s.proc.Resize(cols, rows)
}

// SetTitle renames the session and pins the name.
//
// The name is what the user picks a session out of a list by, so it outlives
// the process: renaming an exited session that is still retained is allowed.
// The title is untrusted input from the app, bounded and validated the same
// way a create request's title is. It returns the updated metadata so the
// caller can broadcast the change.
func (s *Session) SetTitle(title string) (Metadata, error) {
	return s.setTitle(title, true)
}

// SetTerminalTitle records the title the running program set with an escape
// sequence. It is ignored once someone has renamed the session by hand: a
// shell repaints its title on every prompt and would otherwise overwrite the
// name the user chose.
func (s *Session) SetTerminalTitle(title string) (Metadata, bool, error) {
	s.mu.Lock()
	pinned := s.meta.TitlePinned
	s.mu.Unlock()
	if pinned {
		return Metadata{}, false, nil
	}
	m, err := s.setTitle(title, false)
	if err != nil {
		return Metadata{}, false, err
	}
	return m, true, nil
}

func (s *Session) setTitle(title string, pin bool) (Metadata, error) {
	name := strings.TrimSpace(title)
	if name == "" {
		return Metadata{}, fmt.Errorf("%w: title must not be empty", ErrInvalidRequest)
	}
	if len(name) > MaxTitleLen {
		return Metadata{}, fmt.Errorf("%w: title exceeds %d bytes", ErrInvalidRequest, MaxTitleLen)
	}
	if !utf8.ValidString(name) {
		return Metadata{}, fmt.Errorf("%w: title must be valid UTF-8", ErrInvalidRequest)
	}
	s.mu.Lock()
	s.meta.Title = name
	if pin {
		s.meta.TitlePinned = true
	}
	m := s.meta
	s.mu.Unlock()
	return m, nil
}

// Signal sends a graceful stop to the foreground process.
func (s *Session) Signal(sig os.Signal) error {
	if !s.running() {
		return ErrSessionExited
	}
	return s.proc.Signal(sig)
}

// Kill terminates the process tree. It is idempotent: killing an already
// exited session is not an error.
//
// A killed session is dropped rather than retained. Retention exists so a
// shell that exited on its own can still be reattached for its final output;
// a kill is someone saying they are finished with it, and keeping those
// around left the list filling with sessions the user had already closed.
func (s *Session) Kill() error {
	s.mu.Lock()
	running := s.meta.Running
	s.mu.Unlock()
	s.discard.Store(true)
	if !running {
		// Already exited and sitting in retention: drop it now.
		s.m.forget(s.id)
		return nil
	}
	if err := s.proc.Kill(); err != nil {
		return err
	}
	s.m.bump(statKilled)
	return nil
}

// Attach opens a reader on the session's output with no resume cursor: the
// whole retained scrollback is replayed first, then live output, in order,
// each byte exactly once (continuity "full"). It is allowed after exit, in
// which case the attachment serves the retained stream and then EOF.
func (s *Session) Attach() (*Attachment, AttachInfo, error) {
	return s.attachWithCursor(0, false)
}

// AttachFrom opens a reader that resumes the replay at the given offset into
// the session's cumulative output stream. An offset beyond the total output
// fails with ErrCursorOutOfRange; an offset older than the retained window
// clamps to the window's start and reports continuity "gap".
func (s *Session) AttachFrom(resumeFrom uint64) (*Attachment, AttachInfo, error) {
	return s.attachWithCursor(resumeFrom, true)
}

func (s *Session) attachWithCursor(resumeFrom uint64, hasCursor bool) (*Attachment, AttachInfo, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if len(s.attachments) >= s.m.opts.MaxAttachments {
		return nil, AttachInfo{}, ErrTooManyAttachments
	}
	total := uint64(s.totalBytes)
	ringStart := total - uint64(s.ring.len())
	info := AttachInfo{Continuity: protocol.ContinuityFull, ReplayedFrom: ringStart}
	var replay []byte
	switch {
	case !hasCursor:
		replay = s.ring.snapshot()
	case resumeFrom > total:
		return nil, AttachInfo{}, ErrCursorOutOfRange
	case resumeFrom < ringStart:
		info.Continuity = protocol.ContinuityGap
		replay = s.ring.snapshot()
	default:
		info.Continuity = protocol.ContinuityGapless
		info.ReplayedFrom = resumeFrom
		replay = s.ring.snapshotFrom(int(resumeFrom - ringStart))
	}
	a := &attachment{
		s:            s,
		id:           s.nextAttach,
		queue:        make(chan []byte, attachmentQueue),
		replay:       replay,
		replayedFrom: int64(info.ReplayedFrom),
	}
	s.nextAttach++
	if s.drainDone {
		// The drain loop has ended, so nothing will ever close this queue.
		// Close it now: Read serves the retained stream and then returns EOF.
		// It is not registered, because it will receive no live output.
		a.reason.Store(int32(ReasonExited))
		close(a.queue)
		return &Attachment{a: a}, info, nil
	}
	if s.attachments == nil {
		s.attachments = make(map[int]*attachment)
	}
	s.attachments[a.id] = a
	return &Attachment{a: a}, info, nil
}

// running reports whether the process is still alive.
func (s *Session) running() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.meta.Running
}

func (s *Session) touch() {
	s.mu.Lock()
	s.meta.LastActivity = time.Now()
	s.mu.Unlock()
}

// detachLocked removes a and wakes its Read with the given reason. Callers
// hold s.mu.
func (s *Session) detachLocked(a *attachment, reason DetachReason) {
	a.reason.Store(int32(reason))
	if _, ok := s.attachments[a.id]; ok {
		delete(s.attachments, a.id)
		close(a.queue)
		s.m.bump(reasonStat(reason))
	}
}

// detach removes a and wakes its Read with the given reason.
func (s *Session) detach(a *attachment, reason DetachReason) {
	s.mu.Lock()
	s.detachLocked(a, reason)
	s.mu.Unlock()
}

func reasonStat(r DetachReason) int {
	switch r {
	case ReasonOverflow:
		return statReadersOverflowed
	case ReasonCancelled:
		return statReadersCancelled
	default:
		return statReadersExited
	}
}

// waitLoop reaps the process, releases the PTY, and records the final
// metadata. The session then enters its post-exit retention window instead
// of being forgotten: it stays listed and attachable for final replay until
// the window elapses or the retired set is full.
func (s *Session) waitLoop() {
	st := s.proc.Wait()
	s.proc.Close()
	s.mu.Lock()
	s.meta.Running = false
	s.meta.Exit = st
	s.mu.Unlock()
	s.m.retire(s)
	s.m.bump(statExited)
	s.doneOnce.Do(func() { close(s.done) })
	if s.m.opts.OnExit != nil {
		s.mu.Lock()
		m := s.meta
		s.mu.Unlock()
		s.m.opts.OnExit(m)
	}
}

// retire moves an exited session from the live set into the retention set.
// The session stops counting toward MaxSessions here.
//
// A session the user killed is dropped instead of retained: retention is for
// reattaching to a shell that ended on its own.
func (m *Manager) retire(s *Session) {
	m.mu.Lock()
	if cur, ok := m.sessions[s.id]; ok && cur == s {
		delete(m.sessions, s.id)
	}
	if s.discard.Load() {
		delete(m.retired, s.id)
		m.mu.Unlock()
		return
	}
	if len(m.retired) >= maxRetiredSessions {
		// Evict the earliest-expiring entry to make room.
		var oldestID string
		var oldestAt time.Time
		for id, e := range m.retired {
			if oldestID == "" || e.expiresAt.Before(oldestAt) {
				oldestID, oldestAt = id, e.expiresAt
			}
		}
		if oldestID != "" {
			delete(m.retired, oldestID)
		}
	}
	if _, exists := m.retired[s.id]; !exists {
		m.retired[s.id] = &retiredEntry{session: s, expiresAt: time.Now().Add(m.opts.RetainedAfterExit)}
	}
	m.mu.Unlock()
}

// forget drops a session from both sets without waiting for its retention
// window. Killing an already-exited session takes this path.
func (m *Manager) forget(id string) {
	m.mu.Lock()
	delete(m.sessions, id)
	delete(m.retired, id)
	m.mu.Unlock()
}

// purgeRetiredLocked drops expired retired entries. Callers hold m.mu.
func (m *Manager) purgeRetiredLocked(now time.Time) {
	for id, e := range m.retired {
		if now.After(e.expiresAt) {
			delete(m.retired, id)
		}
	}
}

func (m *Manager) purgeRetired(now time.Time) {
	m.mu.Lock()
	m.purgeRetiredLocked(now)
	m.mu.Unlock()
}

// drainLoop is the sole PTY reader. It feeds the ring and fans out to
// attachments until the PTY closes. A slow attachment is dropped, never
// allowed to block the drain.
func (s *Session) drainLoop() {
	buf := make([]byte, 32<<10)
	for {
		n, err := s.proc.Read(buf)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			s.mu.Lock()
			s.totalBytes += int64(n)
			s.ring.append(chunk)
			s.meta.LastActivity = time.Now()
			for _, a := range s.attachments {
				select {
				case a.queue <- chunk:
				default:
					s.detachLocked(a, ReasonOverflow)
				}
			}
			matcher := s.matcher
			s.mu.Unlock()
			// The matcher is fed after the lock: matching must never hold the
			// session lock, and replayed output (served from the ring by
			// Attach) never reaches it, so notifications fire on live output
			// only.
			if matcher != nil {
				matcher.feed(s.id, chunk)
			}
		}
		if err != nil {
			s.mu.Lock()
			s.drainDone = true
			for _, a := range s.attachments {
				s.detachLocked(a, ReasonExited)
			}
			s.mu.Unlock()
			return
		}
	}
}

// BuildEnv derives the child environment: the daemon environment with only
// TERM, COLORTERM, and REMOTLY_* removed and re-added as the session
// overrides. It is the single source of the environment-override rule, shared
// by session creation and `remotly doctor`.
func BuildEnv(term, sessionID string) []string {
	out := make([]string, 0, len(os.Environ())+3)
	for _, kv := range os.Environ() {
		i := strings.IndexByte(kv, '=')
		if i <= 0 {
			continue
		}
		k := kv[:i]
		if k == "TERM" || k == "COLORTERM" || strings.HasPrefix(k, "REMOTLY_") {
			continue
		}
		out = append(out, kv)
	}
	out = append(out, "TERM="+term, "COLORTERM=truecolor", "REMOTLY_SESSION="+sessionID)
	return out
}

func validateRequest(req Request) error {
	switch req.Kind {
	case KindShell:
		if req.Command != "" {
			return fmt.Errorf("%w: command is only valid for agent sessions", ErrInvalidRequest)
		}
	case KindAgent:
		if req.Command == "" {
			return fmt.Errorf("%w: agent requires a command", ErrInvalidRequest)
		}
		if len(req.Command) > MaxCommandLen {
			return fmt.Errorf("%w: command exceeds %d bytes", ErrInvalidRequest, MaxCommandLen)
		}
		if !utf8.ValidString(req.Command) || strings.ContainsRune(req.Command, '\x00') {
			return fmt.Errorf("%w: command must be valid UTF-8", ErrInvalidRequest)
		}
	default:
		return fmt.Errorf("%w: kind %q", ErrInvalidRequest, req.Kind)
	}
	if len(req.Title) > MaxTitleLen {
		return fmt.Errorf("%w: title exceeds %d bytes", ErrInvalidRequest, MaxTitleLen)
	}
	if !utf8.ValidString(req.Title) {
		return fmt.Errorf("%w: title must be valid UTF-8", ErrInvalidRequest)
	}
	cols, rows := req.Cols, req.Rows
	if cols == 0 {
		cols = DefaultCols
	}
	if rows == 0 {
		rows = DefaultRows
	}
	if cols < pty.MinCols || cols > pty.MaxCols || rows < pty.MinRows || rows > pty.MaxRows {
		return fmt.Errorf("%w: size %dx%d out of range", ErrInvalidRequest, cols, rows)
	}
	return nil
}

// normalizeSize fills zero dimensions with defaults. Callers apply it after
// validation.
func normalizeSize(req *Request) {
	if req.Cols == 0 {
		req.Cols = DefaultCols
	}
	if req.Rows == 0 {
		req.Rows = DefaultRows
	}
}
