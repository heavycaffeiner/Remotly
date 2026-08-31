// Package server implements the Remotly relay service: an opaque router for
// Remotly transport messages between one daemon and its apps.
//
// The relay parses only the envelope defined in docs/protocol.md section
// 10. Remotly payloads pass through untouched. All state is in memory:
// a registration map, live connections, bounded queues, rate buckets, and
// counters. Nothing is persisted; a restart is transparent because daemons
// re-register and apps rejoin.
package server

import (
	"context"
	"errors"
	"fmt"
	"io"
	"math"
	"net"
	"net/http"
	"runtime"
	"slices"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/heavycaffeiner/remotly/relay/relaycfg"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

// joinTimeout bounds the join handshake on a fresh connection.
const joinTimeout = 10 * time.Second

// ipStateCap bounds per-source join rate state. Under a flood the map is
// reset and joins are rejected until it refills slowly.
const ipStateCap = 65536

// ipStateStale drops per-source state after this long without a join.
const ipStateStale = 60 * time.Second

// daemonQueueFrames and daemonQueueBytes bound the daemon connection's
// outbound queue: the headroom between the per-pair queues and the socket.
const (
	daemonQueueFrames = 1024
	daemonQueueBytes  = 64 << 20
)

// appQueueFrames and appQueueBytes bound the app connection's outbound
// queue (ack, frames, end) ahead of the socket.
const (
	appQueueFrames = 16
	appQueueBytes  = 4 << 20
)

// Logger is the log sink a Server writes to.
type Logger interface {
	Info(msg string, kv ...any)
	Warn(msg string, kv ...any)
	Error(msg string, kv ...any)
}

// Options configures a Server.
type Options struct {
	Cfg relaycfg.Config
	// Log receives all relay log lines.
	Log Logger
	// Now overrides the clock. Tests only.
	Now func() time.Time
	// SweepInterval overrides the idle sweep period. Tests only.
	SweepInterval time.Duration
	// AppQueueFrames and AppQueueBytes override the app connection's
	// outbound queue size. Tests only.
	AppQueueFrames int
	AppQueueBytes  int
	// WriteDelay delays every writer write. Tests only.
	WriteDelay time.Duration
}

// Server is one running relay.
type Server struct {
	cfg   relaycfg.Config
	log   Logger
	now   func() time.Time
	sweep time.Duration
	// sweepStop is closed by Shutdown so the sweep loop does not hold
	// the drain open for a full ticker period.
	sweepStop chan struct{}

	appQFrames int
	appQBytes  int
	writeDelay time.Duration

	listener net.Listener
	admin    *http.Server

	mu      sync.Mutex
	closing bool
	conns   int
	regs    map[[16]byte]*daemonReg
	ipRate  map[string]*ipBucket
	tracked []*trackedConn
	counts  *counters

	wg sync.WaitGroup
}

// trackedConn is one live endpoint connection for idle sweeping.
type trackedConn struct {
	last   atomic.Int64 // unix nano of last inbound message
	expire func()
}

// ipBucket is one source address' join token bucket.
type ipBucket struct {
	tokens float64
	last   time.Time
}

// New builds a Server. The caller starts it with Listen and stops it with
// Shutdown.
func New(opts Options) (*Server, error) {
	log := opts.Log
	if log == nil {
		log = defaultLogger{}
	}
	now := opts.Now
	if now == nil {
		now = time.Now
	}
	sweep := opts.SweepInterval
	if sweep == 0 {
		sweep = time.Duration(opts.Cfg.Limits.IdleTimeoutSec) * time.Second / 10
		if sweep < 5*time.Second {
			sweep = 5 * time.Second
		}
	}
	return &Server{
		cfg:        opts.Cfg,
		log:        log,
		now:        now,
		regs:       map[[16]byte]*daemonReg{},
		ipRate:     map[string]*ipBucket{},
		counts:     &counters{},
		sweep:      sweep,
		sweepStop:  make(chan struct{}),
		appQFrames: opts.AppQueueFrames,
		appQBytes:  opts.AppQueueBytes,
		writeDelay: opts.WriteDelay,
	}, nil
}

// Listen binds the data and admin listeners and starts the service.
func (s *Server) Listen() error {
	ln, err := net.Listen("tcp", s.cfg.Listen)
	if err != nil {
		return fmt.Errorf("relay: listen %s: %w", s.cfg.Listen, err)
	}
	s.listener = ln

	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; charset=utf-8")
		_, _ = w.Write([]byte("ok\n"))
	})
	mux.HandleFunc("/metrics", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
		_, _ = io.WriteString(w, s.metricsText())
	})
	s.admin = &http.Server{
		Addr:              s.cfg.AdminListen,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}
	go func() {
		if err := s.admin.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			s.log.Error("relay: admin listener failed", "err", err.Error())
		}
	}()

	s.wg.Add(1)
	go s.acceptLoop()
	s.wg.Add(1)
	go s.sweepLoop()
	s.log.Info("relay: listening", "listen", s.cfg.Listen, "admin", s.cfg.AdminListen)
	return nil
}

// Addr returns the bound data listener address.
func (s *Server) Addr() string {
	if s.listener == nil {
		return ""
	}
	return s.listener.Addr().String()
}

// AdminAddr returns the admin listener address from config; it is bound
// before the data listener, so it is already live after Listen.
func (s *Server) AdminAddr() string { return s.cfg.AdminListen }

// Shutdown stops accepting, ends every live connection with a going-away
// close, and waits for handlers to drain. It is idempotent; the context
// bounds the drain.
func (s *Server) Shutdown(ctx context.Context) error {
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		return nil
	}
	s.closing = true
	ln := s.listener
	close(s.sweepStop)
	s.mu.Unlock()

	if ln != nil {
		_ = ln.Close()
	}
	if s.admin != nil {
		_ = s.admin.Close()
	}

	// End every registration and its apps.
	s.mu.Lock()
	type rd struct {
		reg *daemonReg
		dc  *daemonConn
	}
	rds := make([]rd, 0, len(s.regs))
	for _, reg := range s.regs {
		reg.mu.Lock()
		if reg.dc != nil {
			rds = append(rds, rd{reg, reg.dc})
		}
		reg.mu.Unlock()
	}
	s.mu.Unlock()
	for _, x := range rds {
		// The relay is going away, so the apps get the going-away code
		// rather than the peer-gone code the daemon teardown would give.
		x.reg.mu.Lock()
		pairs := make([]*pair, 0, len(x.reg.pairs))
		for _, p := range x.reg.pairs {
			pairs = append(pairs, p)
		}
		x.reg.mu.Unlock()
		for _, p := range pairs {
			s.closeApp(p, relayproto.CodeGoingAway, "relay shutting down")
		}
		s.teardownDaemon(x.dc, x.reg, relayproto.CodeGoingAway, "relay shutting down", true)
	}

	done := make(chan struct{})
	go func() {
		s.wg.Wait()
		close(done)
	}()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	}
}

// acceptLoop accepts endpoint connections until the listener closes.
func (s *Server) acceptLoop() {
	defer s.wg.Done()
	for {
		rc, err := s.listener.Accept()
		if err != nil {
			return
		}
		if !s.incConns() {
			s.counts.joinRejectedLimit.Add(1)
			go func() {
				defer rc.Close()
				_ = rc.SetDeadline(s.now().Add(joinTimeout))
				// Consume the join so the peer gets a clean answer.
				if m, err := relayproto.Read(rc); err == nil && m.Type == relayproto.TypeJoin {
					_ = writeAll(rc, endBytes(relayproto.CodeLimit, "connection limit"))
				}
			}()
			continue
		}
		s.wg.Add(1)
		go s.handleEndpoint(rc)
	}
}

func (s *Server) incConns() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closing || s.conns >= s.cfg.Limits.MaxConnections {
		return false
	}
	s.conns++
	return true
}

func (s *Server) decConns() {
	s.mu.Lock()
	s.conns--
	s.mu.Unlock()
}

// handleEndpoint reads the join, then dispatches by role.
func (s *Server) handleEndpoint(rc net.Conn) {
	defer s.wg.Done()
	defer s.decConns()
	defer rc.Close()

	_ = rc.SetReadDeadline(s.now().Add(joinTimeout))
	m, err := relayproto.Read(rc)
	_ = rc.SetReadDeadline(time.Time{})
	if err != nil || m.Type != relayproto.TypeJoin {
		var (
			code   uint16 = relayproto.CodeProtocol
			reason string = "bad join"
		)
		if err != nil && !errors.Is(err, relayproto.ErrMalformed) {
			reason = "join failed"
		}
		s.counts.countJoinReject(code)
		_ = writeAll(rc, endBytes(code, reason))
		return
	}
	if !s.allowJoin(srcKey(rc.RemoteAddr())) {
		s.counts.joinRejectedRate.Add(1)
		_ = writeAll(rc, endBytes(relayproto.CodeLimit, "join rate limit"))
		return
	}

	switch m.Role {
	case relayproto.RoleDaemon:
		s.handleDaemon(rc, m.RelayID)
	case relayproto.RoleApp:
		s.handleApp(rc, m.RelayID)
	}
}

// allowJoin applies the per-source join rate limit.
func (s *Server) allowJoin(src string) bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.closing {
		return false
	}
	if len(s.ipRate) > ipStateCap {
		// Under attack: reset the map and reject until it refills.
		s.ipRate = map[string]*ipBucket{}
		return false
	}
	if len(s.ipRate) > 8000 {
		// Opportunistic prune of stale state.
		deadline := s.now().Add(-ipStateStale)
		for k, b := range s.ipRate {
			if b.last.Before(deadline) {
				delete(s.ipRate, k)
			}
		}
	}
	now := s.now()
	b, ok := s.ipRate[src]
	if !ok {
		s.ipRate[src] = &ipBucket{tokens: float64(s.cfg.Limits.JoinBurst) - 1, last: now}
		return true
	}
	elapsed := now.Sub(b.last).Seconds()
	b.tokens = math.Min(float64(s.cfg.Limits.JoinBurst), b.tokens+elapsed*float64(s.cfg.Limits.JoinRatePerSec))
	b.last = now
	if b.tokens < 1 {
		return false
	}
	b.tokens--
	return true
}

func srcKey(addr net.Addr) string {
	host, _, err := net.SplitHostPort(addr.String())
	if err != nil {
		return addr.String()
	}
	return host
}

// sweepLoop closes connections that exceed the idle timeout.
func (s *Server) sweepLoop() {
	defer s.wg.Done()
	t := time.NewTicker(s.sweep)
	defer t.Stop()
	for {
		select {
		case <-t.C:
		case <-s.sweepStop:
			return
		}
		s.mu.Lock()
		if s.closing {
			s.mu.Unlock()
			return
		}
		snap := slices.Clone(s.tracked)
		s.mu.Unlock()
		deadline := s.now().Add(-time.Duration(s.cfg.Limits.IdleTimeoutSec) * time.Second)
		for _, tc := range snap {
			if time.Unix(0, tc.last.Load()).Before(deadline) {
				tc.expire()
			}
		}
	}
}

func (s *Server) trackConn(tc *trackedConn) {
	s.mu.Lock()
	tc.last.Store(s.now().UnixNano())
	s.tracked = append(s.tracked, tc)
	s.mu.Unlock()
}

func (s *Server) untrackConn(tc *trackedConn) {
	s.mu.Lock()
	for i, c := range s.tracked {
		if c == tc {
			s.tracked = append(s.tracked[:i], s.tracked[i+1:]...)
			break
		}
	}
	s.mu.Unlock()
}

// ---- daemon side ----

// daemonReg is the live registration for one relay id.
type daemonReg struct {
	id [16]byte
	mu sync.Mutex
	// dc is the current daemon connection; nil once torn down.
	dc *daemonConn
	// pairs maps stream id to the attached app pair.
	pairs map[uint32]*pair
	// nextID is the next stream id to hand out on the current daemon
	// connection.
	nextID uint32
}

// daemonConn is one daemon's persistent connection.
type daemonConn struct {
	s     *Server
	rc    net.Conn
	id    [16]byte
	reg   atomic.Pointer[daemonReg]
	out   *outQueue
	done  chan struct{}
	wDone chan struct{}
	alive atomic.Bool

	tc *trackedConn

	finishOnce   sync.Once
	teardownOnce sync.Once
}

// handleDaemon runs one daemon connection: registration, then the read loop
// until the connection ends.
func (s *Server) handleDaemon(rc net.Conn, id [16]byte) {
	dc := &daemonConn{
		s:     s,
		rc:    rc,
		id:    id,
		out:   newOutQueue(daemonQueueFrames, daemonQueueBytes),
		done:  make(chan struct{}),
		wDone: make(chan struct{}),
		tc:    &trackedConn{},
	}
	dc.alive.Store(true)
	dc.tc.expire = func() { s.teardownDaemon(dc, dc.reg.Load(), relayproto.CodeIdle, "idle timeout", true) }
	s.trackConn(dc.tc)
	defer s.untrackConn(dc.tc)
	defer close(dc.done)
	defer s.waitWriter(dc.wDone)

	go s.runWriter(rc, dc.out, dc.wDone)

	reg, code, reason := s.registerDaemon(dc)
	if reg == nil {
		s.counts.countJoinReject(code)
		dc.finish(code, reason)
		return
	}
	dc.reg.Store(reg)
	reg.mu.Lock()
	reg.dc = dc
	reg.mu.Unlock()
	s.setRegCount()
	s.counts.joinsDaemon.Add(1)
	s.log.Info("relay: daemon registered")
	dc.push(relayproto.NewJoinAck())

	for {
		m, err := relayproto.Read(rc)
		if err != nil {
			if errors.Is(err, relayproto.ErrMalformed) {
				s.counts.countClose(relayproto.CodeProtocol)
				s.log.Warn("relay: daemon protocol error", "err", err.Error())
			}
			break
		}
		dc.tc.last.Store(s.now().UnixNano())
		switch m.Type {
		case relayproto.TypeKeepalive:
			dc.push(relayproto.NewKeepalive())
		case relayproto.TypeStreamFrame:
			p := reg.pair(m.StreamID)
			if p == nil {
				// The stream was likely just closed; in-flight frames for
				// it are dropped, not fatal.
				s.counts.dropsUnknownStream.Add(1)
				continue
			}
			if !p.bwDA.take(len(m.Data), s.now()) {
				s.closeApp(p, relayproto.CodeLimit, "bandwidth limit")
				continue
			}
			if !p.qDA.push(m.Data) {
				s.closeApp(p, relayproto.CodeLimit, "queue full")
				continue
			}
			s.counts.framesD2A.Add(1)
			s.counts.bytesD2A.Add(int64(len(m.Data)))
		case relayproto.TypeStreamClose:
			p := reg.pair(m.StreamID)
			if p != nil {
				s.closeApp(p, m.Code, m.Reason)
			}
		case relayproto.TypeStreamPing:
			p := reg.pair(m.StreamID)
			if p == nil {
				s.counts.dropsUnknownStream.Add(1)
				continue
			}
			p.awaitPong.Store(true)
			p.ac.push(relayproto.NewKeepalive())
		case relayproto.TypeEnd:
			// The daemon is closing; the loop exits and the teardown
			// orphans its apps.
			break
		default:
			s.counts.countClose(relayproto.CodeProtocol)
			s.teardownDaemon(dc, dc.reg.Load(), relayproto.CodeProtocol, "bad message", true)
			return
		}
	}
	s.teardownDaemon(dc, dc.reg.Load(), relayproto.CodePeerGone, "daemon connection lost", false)
}

// registerDaemon installs the registration for dc's relay id, superseding
// any older registration for that id.
func (s *Server) registerDaemon(dc *daemonConn) (*daemonReg, uint16, string) {
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		return nil, relayproto.CodeGoingAway, "shutting down"
	}
	if len(s.regs) >= s.cfg.Limits.MaxRegistrations && s.regs[dc.id] == nil {
		s.mu.Unlock()
		return nil, relayproto.CodeLimit, "registration limit"
	}
	old := s.regs[dc.id]
	reg := &daemonReg{id: dc.id, pairs: map[uint32]*pair{}}
	s.regs[dc.id] = reg
	s.mu.Unlock()

	if old != nil {
		// The old registration is superseded: its connection and every
		// attached app end.
		s.teardownDaemon(old.dc, old, relayproto.CodeReplaced, "replaced", true)
	}
	return reg, 0, ""
}

// removeReg drops the registration if it still belongs to dc.
func (s *Server) removeReg(dc *daemonConn) {
	s.mu.Lock()
	if reg := s.regs[dc.id]; reg != nil && reg.dc == dc {
		delete(s.regs, dc.id)
	}
	s.counts.regs.Store(int64(len(s.regs)))
	s.mu.Unlock()
}

// setRegCount refreshes the registrations metric.
func (s *Server) setRegCount() {
	s.mu.Lock()
	s.counts.regs.Store(int64(len(s.regs)))
	s.mu.Unlock()
}

// teardownDaemon ends a daemon connection and orphans its apps. reg is the
// registration owned by dc (nil when dc never registered). sendEnd controls
// whether the daemon gets an explicit end message.
func (s *Server) teardownDaemon(dc *daemonConn, reg *daemonReg, code uint16, reason string, sendEnd bool) {
	dc.teardownOnce.Do(func() {
		dc.alive.Store(false)
		s.removeReg(dc)
		if reg != nil {
			reg.mu.Lock()
			reg.dc = nil
			pairs := make([]*pair, 0, len(reg.pairs))
			for _, p := range reg.pairs {
				pairs = append(pairs, p)
			}
			reg.pairs = map[uint32]*pair{}
			reg.mu.Unlock()
			for _, p := range pairs {
				s.closeApp(p, relayproto.CodePeerGone, "daemon connection lost")
			}
		}
		if sendEnd {
			dc.finish(code, reason)
		} else {
			dc.out.close()
		}
		// Let the final message flush, then drop the socket so the daemon
		// handler's read loop unblocks even if the peer stays open.
		s.waitWriter(dc.wDone)
		_ = dc.rc.Close()
		s.counts.countClose(code)
	})
}

// finish sends a final end message to the daemon and stops its writer.
func (dc *daemonConn) finish(code uint16, reason string) {
	dc.finishOnce.Do(func() {
		b, err := relayproto.Encode(relayproto.NewEnd(code, reason))
		if err != nil {
			dc.out.close()
			return
		}
		dc.out.pushFinish(b)
	})
}

// push encodes and enqueues one outbound message to the daemon. It returns
// false when the queue is full or closed; the caller treats that as a
// failure of the daemon connection.
func (dc *daemonConn) push(m relayproto.Message) bool {
	if !dc.alive.Load() {
		return false
	}
	b, err := relayproto.Encode(m)
	if err != nil {
		return false
	}
	if !dc.out.push(b) {
		// A full outbound queue means the daemon socket is clogged: the
		// daemon is the slow consumer.
		return false
	}
	return true
}

// ---- app side ----

// appConn is one app connection.
type appConn struct {
	s     *Server
	rc    net.Conn
	out   *outQueue
	done  chan struct{}
	wDone chan struct{}
	// pair is set once the join is attached.
	pair *pair

	tc *trackedConn

	finishOnce sync.Once
}

// handleApp runs one app connection: attach to a registration, then forward
// frames until the connection ends.
func (s *Server) handleApp(rc net.Conn, id [16]byte) {
	qn, qb := appQueueFrames, appQueueBytes
	if s.appQFrames > 0 && s.appQBytes > 0 {
		qn, qb = s.appQFrames, s.appQBytes
	}
	ac := &appConn{
		s:     s,
		rc:    rc,
		out:   newOutQueue(qn, qb),
		done:  make(chan struct{}),
		wDone: make(chan struct{}),
		tc:    &trackedConn{},
	}
	ac.tc.expire = func() {
		if p := ac.pair; p != nil {
			s.closeApp(p, relayproto.CodeIdle, "idle timeout")
		}
	}
	s.trackConn(ac.tc)
	defer s.untrackConn(ac.tc)
	defer close(ac.done)
	defer s.waitWriter(ac.wDone)

	go s.runWriter(rc, ac.out, ac.wDone)

	p, code, reason := s.attachApp(ac, id)
	if p == nil {
		s.counts.countJoinReject(code)
		ac.finish(code, reason)
		return
	}
	ac.pair = p
	s.counts.joinsApp.Add(1)
	s.log.Info("relay: app attached", "stream", p.id)

	// The stream open must reach the daemon before the app is admitted, so
	// the daemon is ready to accept the stream's first frame.
	if b, err := relayproto.Encode(relayproto.NewStreamOpen(p.id)); err == nil {
		if !p.dc.out.push(b) {
			s.teardownDaemon(p.dc, p.dc.reg.Load(), relayproto.CodeLimit, "daemon queue full", true)
			s.closeApp(p, relayproto.CodePeerGone, "daemon connection lost")
			return
		}
	}
	ac.push(relayproto.NewJoinAck())

	s.wg.Add(1)
	go s.pumpAppToDaemon(p)
	s.wg.Add(1)
	go s.pumpDaemonToApp(p)

	for {
		m, err := relayproto.Read(rc)
		if err != nil {
			var (
				code   uint16 = 1006
				reason string = "app closed"
			)
			if errors.Is(err, relayproto.ErrMalformed) {
				code, reason = relayproto.CodeProtocol, "malformed message"
			}
			s.closeApp(p, code, reason)
			return
		}
		ac.tc.last.Store(s.now().UnixNano())
		switch m.Type {
		case relayproto.TypeFrame:
			if !p.bwAD.take(len(m.Data), s.now()) {
				s.closeApp(p, relayproto.CodeLimit, "bandwidth limit")
				return
			}
			s.counts.framesA2D.Add(1)
			s.counts.bytesA2D.Add(int64(len(m.Data)))
			b, err := relayproto.Encode(relayproto.NewStreamFrame(p.id, m.Data))
			if err != nil || !p.qAD.push(b) {
				s.closeApp(p, relayproto.CodeLimit, "queue full")
				return
			}
		case relayproto.TypeKeepalive:
			// The app's keepalive is consumed for liveness. It is not echoed
			// back: the relay sends a keepalive to the app only when the daemon
			// pings the stream, and the app answers every keepalive it receives.
			// Echoing would turn that answer into a second keepalive and loop.
			if p.awaitPong.Swap(false) {
				p.dc.push(relayproto.NewStreamPong(p.id))
			}
		case relayproto.TypeEnd:
			s.closeApp(p, m.Code, m.Reason)
			return
		default:
			s.closeApp(p, relayproto.CodeProtocol, "bad message")
			return
		}
	}
}

// attachApp links a fresh app connection to a live registration, assigning
// its stream id.
// attachApp attaches an app connection to the registration for id.
func (s *Server) attachApp(ac *appConn, id [16]byte) (*pair, uint16, string) {
	s.mu.Lock()
	if s.closing {
		s.mu.Unlock()
		return nil, relayproto.CodeGoingAway, "shutting down"
	}
	reg := s.regs[id]
	if reg == nil {
		s.mu.Unlock()
		return nil, relayproto.CodeNoDaemon, "no daemon registered"
	}
	s.mu.Unlock()

	reg.mu.Lock()
	dc := reg.dc
	if dc == nil || !dc.alive.Load() {
		reg.mu.Unlock()
		return nil, relayproto.CodeNoDaemon, "no daemon registered"
	}
	if len(reg.pairs) >= s.cfg.Limits.MaxAppsPerRelay {
		reg.mu.Unlock()
		return nil, relayproto.CodeLimit, "app limit reached"
	}
	reg.nextID++
	if reg.nextID == 0 {
		reg.nextID = 1 // never hand out stream id 0
	}
	p := &pair{
		reg:  reg,
		dc:   dc,
		ac:   ac,
		id:   reg.nextID,
		qAD:  newOutQueue(s.cfg.Limits.QueueFrames, s.cfg.Limits.QueueBytes),
		qDA:  newOutQueue(s.cfg.Limits.QueueFrames, s.cfg.Limits.QueueBytes),
		bwAD: newBucket(s.cfg.Limits.BandwidthBPS),
		bwDA: newBucket(s.cfg.Limits.BandwidthBPS),
	}
	reg.pairs[reg.nextID] = p
	reg.mu.Unlock()
	return p, 0, ""
}

// closeApp ends one app pair: it tells the daemon the stream is closed,
// detaches the pair from the registration, stops the pumps, and ends the
// app connection. It is idempotent.
func (s *Server) closeApp(p *pair, code uint16, reason string) {
	p.mu.Lock()
	if p.closed {
		p.mu.Unlock()
		return
	}
	p.closed = true
	p.mu.Unlock()
	if p.dc.alive.Load() {
		p.dc.push(relayproto.NewStreamClose(p.id, code, reason))
	}
	p.reg.removePair(p)
	p.qAD.close()
	p.qDA.close()
	p.ac.finish(code, reason)
	// Let the final end flush, then drop the socket so the app handler's
	// read loop unblocks even if the peer stays open.
	s.waitWriter(p.ac.wDone)
	_ = p.ac.rc.Close()
	s.counts.apps.Add(-1)
	s.counts.countClose(code)
}

// finish sends a final end message to the app and stops its writer.
func (ac *appConn) finish(code uint16, reason string) {
	ac.finishOnce.Do(func() {
		b, err := relayproto.Encode(relayproto.NewEnd(code, reason))
		if err != nil {
			ac.out.close()
			return
		}
		ac.out.pushFinish(b)
	})
}

func (ac *appConn) push(m relayproto.Message) {
	b, err := relayproto.Encode(m)
	if err != nil {
		return
	}
	ac.out.push(b)
}

// ---- pumps and writer ----

// pumpAppToDaemon moves encoded stream frames from the pair's app-to-daemon
// queue onto the daemon connection's outbound queue.
func (s *Server) pumpAppToDaemon(p *pair) {
	defer s.wg.Done()
	for {
		b, _ := p.qAD.pop()
		if b == nil {
			return
		}
		if !p.dc.alive.Load() || !p.dc.out.push(b) {
			s.closeApp(p, relayproto.CodeLimit, "daemon queue full")
			return
		}
	}
}

// pumpDaemonToApp wraps raw daemon messages in app frames and moves them
// onto the app connection's outbound queue.
func (s *Server) pumpDaemonToApp(p *pair) {
	defer s.wg.Done()
	for {
		b, _ := p.qDA.pop()
		if b == nil {
			return
		}
		eb, err := relayproto.Encode(relayproto.NewFrame(b))
		if err != nil {
			s.closeApp(p, relayproto.CodeProtocol, "bad frame")
			return
		}
		if !p.ac.out.push(eb) {
			s.closeApp(p, relayproto.CodeLimit, "app queue full")
			return
		}
	}
}

// runWriter drains one outbound queue onto a socket. It exits after the
// finish item (an end message) or when the queue closes and drains, then the
// handler closes the socket.
func (s *Server) runWriter(rc net.Conn, q *outQueue, wDone chan struct{}) {
	defer close(wDone)
	for {
		b, finish := q.pop()
		if b == nil {
			return
		}
		if s.writeDelay > 0 {
			time.Sleep(s.writeDelay)
		}
		if err := writeAll(rc, b); err != nil {
			return
		}
		if finish {
			return
		}
	}
}

// waitWriter blocks until the connection's writer has drained its last
// message (typically a final end), so the handler does not close the socket
// under a pending write. Bounded so a stuck writer cannot hold the handler.
func (s *Server) waitWriter(wDone <-chan struct{}) {
	select {
	case <-wDone:
	case <-time.After(2 * time.Second):
	}
}

// ---- shared helpers ----

// pair is one attached app: the stream on the daemon connection, the queues
// in each direction, and the bandwidth buckets.
type pair struct {
	reg *daemonReg
	dc  *daemonConn
	ac  *appConn
	id  uint32

	qAD *outQueue // app to daemon, encoded stream frames
	qDA *outQueue // daemon to app, raw Remotly messages

	bwAD, bwDA *bucket

	awaitPong atomic.Bool

	mu     sync.Mutex
	closed bool
}

func (reg *daemonReg) pair(id uint32) *pair {
	reg.mu.Lock()
	defer reg.mu.Unlock()
	return reg.pairs[id]
}

func (reg *daemonReg) removePair(p *pair) {
	reg.mu.Lock()
	delete(reg.pairs, p.id)
	reg.mu.Unlock()
}

func writeAll(w net.Conn, b []byte) error {
	for len(b) > 0 {
		n, err := w.Write(b)
		b = b[n:]
		if err != nil {
			return err
		}
	}
	return nil
}

// endBytes encodes an end message for a rejected or expired connection.
func endBytes(code uint16, reason string) []byte {
	b, err := relayproto.Encode(relayproto.NewEnd(code, reason))
	if err != nil {
		return nil
	}
	return b
}

// outQueue is a bounded FIFO of encoded messages with a byte budget.
// push is non-blocking; pop blocks until an item or the queue closes.
type outQueue struct {
	mu    sync.Mutex
	cond  *sync.Cond
	items [][]byte
	bytes int
	capN  int
	capB  int
	done  bool
	// finish marks that the queue holds its final item: pop returns it
	// with finish true, then the queue is empty and done.
	finish bool
}

func newOutQueue(capN, capB int) *outQueue {
	q := &outQueue{capN: capN, capB: capB}
	q.cond = sync.NewCond(&q.mu)
	return q
}

func (q *outQueue) push(b []byte) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.done || len(q.items) >= q.capN || q.bytes+len(b) > q.capB {
		return false
	}
	q.items = append(q.items, b)
	q.bytes += len(b)
	q.cond.Signal()
	return true
}

func (q *outQueue) pushFinish(b []byte) bool {
	q.mu.Lock()
	defer q.mu.Unlock()
	if q.done {
		return false
	}
	// A finish item always goes in, even over budget: it is the last
	// word the endpoint gets.
	q.items = append(q.items, b)
	q.bytes += len(b)
	q.finish = true
	q.cond.Signal()
	return true
}

// bytes reports the buffered byte count.
func (q *outQueue) buffered() int {
	q.mu.Lock()
	defer q.mu.Unlock()
	return q.bytes
}

// pop returns the next item. When the queue is closed and drained, or the
// finish item was consumed, it returns (nil, true).
func (q *outQueue) pop() ([]byte, bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for len(q.items) == 0 && !q.done {
		q.cond.Wait()
	}
	if len(q.items) == 0 {
		return nil, true
	}
	b := q.items[0]
	q.items = q.items[1:]
	q.bytes -= len(b)
	// The finish flag applies to the final item only. Every item popped
	// before it is plain data, even though the finish is already queued.
	if q.finish && len(q.items) == 0 {
		q.finish = false
		q.done = true
		q.cond.Broadcast()
		return b, true
	}
	return b, false
}

func (q *outQueue) close() {
	q.mu.Lock()
	q.done = true
	q.cond.Broadcast()
	q.mu.Unlock()
}

// bucket is a token bucket computed on take; no timers.
type bucket struct {
	mu     sync.Mutex
	rate   float64 // bytes per second
	tokens float64
	last   time.Time
}

func newBucket(rate int64) *bucket {
	return &bucket{rate: float64(rate), tokens: float64(rate)}
}

func (b *bucket) take(n int, now time.Time) bool {
	b.mu.Lock()
	defer b.mu.Unlock()
	elapsed := now.Sub(b.last).Seconds()
	if elapsed < 0 {
		elapsed = 0
	}
	b.tokens = math.Min(b.rate, b.tokens+elapsed*b.rate)
	b.last = now
	if b.tokens < float64(n) {
		return false
	}
	b.tokens -= float64(n)
	return true
}

// ---- metrics ----

// counters is the low-cardinality metric set. It records no relay ids,
// addresses, or payloads.
type counters struct {
	joinsDaemon          atomic.Int64
	joinsApp             atomic.Int64
	apps                 atomic.Int64
	appsRejectedNoDaemon atomic.Int64
	joinRejectedLimit    atomic.Int64
	joinRejectedRate     atomic.Int64
	framesD2A            atomic.Int64
	framesA2D            atomic.Int64
	bytesD2A             atomic.Int64
	bytesA2D             atomic.Int64
	regs                 atomic.Int64
	dropsUnknownStream   atomic.Int64
	// closes by relay close code, index code-3001.
	closes [7]atomic.Int64
}

func (c *counters) countJoinReject(code uint16) {
	switch code {
	case relayproto.CodeNoDaemon:
		c.appsRejectedNoDaemon.Add(1)
	default:
		c.joinRejectedLimit.Add(1)
	}
}

func (c *counters) countClose(code uint16) {
	if code >= relayproto.CodeNoDaemon && code <= relayproto.CodePeerGone {
		c.closes[int(code-3001)].Add(1)
	}
}

// metricsText renders the metric set.
func (s *Server) metricsText() string {
	var sb strings.Builder
	line := func(name string, v int64) {
		sb.WriteString(name)
		sb.WriteString(" ")
		sb.WriteString(strconv.FormatInt(v, 10))
		sb.WriteString("\n")
	}
	line("remotly_relay_registrations", s.counts.regs.Load())
	line("remotly_relay_apps", s.counts.apps.Load())
	line("remotly_relay_connections", s.liveConns())
	line("remotly_relay_joins_total{role=\"daemon\"}", s.counts.joinsDaemon.Load())
	line("remotly_relay_joins_total{role=\"app\"}", s.counts.joinsApp.Load())
	line("remotly_relay_join_rejections_total{reason=\"limit\"}", s.counts.joinRejectedLimit.Load())
	line("remotly_relay_join_rejections_total{reason=\"rate\"}", s.counts.joinRejectedRate.Load())
	line("remotly_relay_join_rejections_total{reason=\"no_daemon\"}", s.counts.appsRejectedNoDaemon.Load())
	line("remotly_relay_frames_total{dir=\"daemon_to_app\"}", s.counts.framesD2A.Load())
	line("remotly_relay_frames_total{dir=\"app_to_daemon\"}", s.counts.framesA2D.Load())
	line("remotly_relay_bytes_total{dir=\"daemon_to_app\"}", s.counts.bytesD2A.Load())
	line("remotly_relay_bytes_total{dir=\"app_to_daemon\"}", s.counts.bytesA2D.Load())
	line("remotly_relay_queue_bytes", s.queueBytesTotal())
	line("remotly_relay_drops_total{reason=\"unknown_stream\"}", s.counts.dropsUnknownStream.Load())
	line("remotly_relay_goroutines", int64(runtime.NumGoroutine()))
	names := []string{"no_daemon", "replaced", "limit", "idle", "going_away", "protocol", "peer_gone"}
	for i, n := range names {
		line(fmt.Sprintf("remotly_relay_closes_total{reason=%q}", n), s.counts.closes[i].Load())
	}
	return sb.String()
}

// liveConns reports the current live endpoint connection count.
func (s *Server) liveConns() int64 {
	s.mu.Lock()
	defer s.mu.Unlock()
	return int64(s.conns)
}

// queueBytesTotal sums the buffered bytes across all live queues.
func (s *Server) queueBytesTotal() int64 {
	s.mu.Lock()
	regSnap := make([]*daemonReg, 0, len(s.regs))
	for _, r := range s.regs {
		regSnap = append(regSnap, r)
	}
	s.mu.Unlock()
	var total int64
	for _, reg := range regSnap {
		reg.mu.Lock()
		if reg.dc != nil {
			total += int64(reg.dc.out.buffered())
		}
		pairs := make([]*pair, 0, len(reg.pairs))
		for _, p := range reg.pairs {
			pairs = append(pairs, p)
		}
		reg.mu.Unlock()
		for _, p := range pairs {
			total += int64(p.qAD.buffered())
			total += int64(p.qDA.buffered())
			total += int64(p.ac.out.buffered())
		}
	}
	return total
}

// defaultLogger writes to stderr so the relay package stays dependency-free.
type defaultLogger struct{}

func (defaultLogger) Info(msg string, kv ...any)  { fmt.Printf("INFO %s %v\n", msg, kv) }
func (defaultLogger) Warn(msg string, kv ...any)  { fmt.Printf("WARN %s %v\n", msg, kv) }
func (defaultLogger) Error(msg string, kv ...any) { fmt.Printf("ERROR %s %v\n", msg, kv) }
