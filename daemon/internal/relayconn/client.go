package relayconn

import (
	"context"
	"fmt"
	"io"
	"log/slog"
	"math/rand"
	"net"
	"sync"
	"sync/atomic"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/transport"
	"github.com/heavycaffeiner/remotly/relay/relayproto"
)

// Tunables. They are variables, not constants, so tests can shorten them.
var (
	// keepaliveInterval is how often the daemon sends a relay keepalive. The
	// relay requires any inbound byte within 60 seconds, so 30 seconds keeps
	// a comfortable margin.
	keepaliveInterval = 30 * time.Second
	// dialTimeout bounds the TCP dial and the join exchange.
	dialTimeout = 3 * time.Second
	// inboxCap bounds one stream's inbound queue. The consumer (the
	// connection reader) is non-blocking and fast, so the cap only smooths
	// bursts; a full inbox during teardown drops in-flight frames rather than
	// stall the demultiplexer.
	inboxCap = 64

	defaultMinBackoff = 1 * time.Second
	defaultMaxBackoff = 60 * time.Second
)

// Config configures the relay connector.
type Config struct {
	// Addr is the relay's host:port.
	Addr string
	// RelayID is the daemon's 16-byte relay identity: the first 16 bytes of
	// its long-term X25519 public key. The relay stores and compares it
	// opaquely.
	RelayID [16]byte
	// OnStream is called for each new stream the relay opens. The connector
	// invokes it in its own goroutine; it should hand the stream to the
	// transport server and return promptly.
	OnStream func(st *Stream)
	// Log may be nil, in which case logging is suppressed.
	Log *slog.Logger
	// MinBackoff and MaxBackoff bound the reconnect backoff; zero values take
	// the defaults.
	MinBackoff time.Duration
	MaxBackoff time.Duration
}

// Client maintains one outbound relay registration. It dials the relay, joins
// with the daemon's relay identity, keeps the connection alive, and reconnects
// with bounded exponential backoff and jitter. A relay outage never affects
// live sessions or direct LAN service: the registration is additive.
type Client struct {
	cfg Config
	log *slog.Logger

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu     sync.Mutex
	closed bool
	reg    *registration
}

// New assembles a relay connector. It creates no connection; Start does.
func New(cfg Config) *Client {
	log := cfg.Log
	if log == nil {
		log = slog.New(slog.NewTextHandler(io.Discard, nil))
	}
	ctx, cancel := context.WithCancel(context.Background())
	return &Client{cfg: cfg, log: log, ctx: ctx, cancel: cancel}
}

func (c *Client) minBackoff() time.Duration {
	if c.cfg.MinBackoff > 0 {
		return c.cfg.MinBackoff
	}
	return defaultMinBackoff
}

func (c *Client) maxBackoff() time.Duration {
	if c.cfg.MaxBackoff > 0 {
		return c.cfg.MaxBackoff
	}
	return defaultMaxBackoff
}

// Start begins the background connect-reconnect loop. It is idempotent.
func (c *Client) Start() {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return
	}
	c.mu.Unlock()
	c.wg.Add(1)
	go c.loop()
}

// Close stops the connector: it cancels the loop, tears down the live
// registration if any, and waits for the loop to exit. It is idempotent.
func (c *Client) Close() error {
	c.mu.Lock()
	if c.closed {
		c.mu.Unlock()
		return nil
	}
	c.closed = true
	c.mu.Unlock()
	c.cancel()
	if reg := c.current(); reg != nil {
		reg.close()
	}
	c.wg.Wait()
	return nil
}

func (c *Client) current() *registration {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.reg
}

func (c *Client) setReg(r *registration) {
	c.mu.Lock()
	c.reg = r
	c.mu.Unlock()
}

func (c *Client) clearReg() {
	c.mu.Lock()
	c.reg = nil
	c.mu.Unlock()
}

// loop dials and joins the relay, serves the registration until it ends, and
// retries with bounded backoff until the context is cancelled.
func (c *Client) loop() {
	defer c.wg.Done()
	backoff := c.minBackoff()
	for {
		if c.ctx.Err() != nil {
			return
		}
		reg, err := c.dialAndJoin()
		if err != nil {
			if c.ctx.Err() != nil {
				return
			}
			c.log.Warn("relayconn: connect failed", "addr", c.cfg.Addr, "err", err.Error())
			if c.sleepBackoff(&backoff) {
				return
			}
			continue
		}
		// A live registration resets the backoff for the next outage.
		backoff = c.minBackoff()
		c.setReg(reg)
		reg.run()
		<-reg.closed
		c.clearReg()
		c.log.Info("relayconn: registration ended, reconnecting", "addr", c.cfg.Addr)
		if c.ctx.Err() != nil {
			return
		}
		if c.sleepBackoff(&backoff) {
			return
		}
	}
}

// sleepBackoff sleeps for a jittered delay and advances the backoff. It
// reports true when the context was cancelled during the sleep.
func (c *Client) sleepBackoff(b *time.Duration) bool {
	delay := *b
	*b = min(*b*2, c.maxBackoff())
	delay = time.Duration(rand.Int63n(int64(delay) + 1))
	timer := time.NewTimer(delay)
	defer timer.Stop()
	select {
	case <-c.ctx.Done():
		return true
	case <-timer.C:
		return false
	}
}

// dialAndJoin opens the TCP connection and completes the relay join. On
// success it returns a ready registration; on any failure it closes the
// socket and returns an error.
func (c *Client) dialAndJoin() (*registration, error) {
	d := net.Dialer{Timeout: dialTimeout}
	conn, err := d.DialContext(c.ctx, "tcp", c.cfg.Addr)
	if err != nil {
		return nil, err
	}
	join, err := relayproto.Encode(relayproto.NewJoin(relayproto.RoleDaemon, c.cfg.RelayID))
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	if err := conn.SetWriteDeadline(time.Now().Add(dialTimeout)); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if _, err := conn.Write(join); err != nil {
		_ = conn.Close()
		return nil, err
	}
	if err := conn.SetReadDeadline(time.Now().Add(dialTimeout)); err != nil {
		_ = conn.Close()
		return nil, err
	}
	msg, err := relayproto.Read(conn)
	_ = conn.SetReadDeadline(time.Time{})
	_ = conn.SetWriteDeadline(time.Time{})
	if err != nil {
		_ = conn.Close()
		return nil, err
	}
	if msg.Type != relayproto.TypeJoinAck {
		code, reason := uint16(0), "unexpected join response"
		if msg.Type == relayproto.TypeEnd {
			code, reason = msg.Code, msg.Reason
		}
		_ = conn.Close()
		return nil, fmt.Errorf("relay join rejected: %d %s", code, reason)
	}
	return newRegistration(conn, c), nil
}

// registration is one live relay connection: the socket, the demultiplexer,
// and the keepalive writer. It ends when the socket closes, the relay sends an
// end, or the connector shuts down.
type registration struct {
	conn net.Conn
	cli  *Client

	wmu sync.Mutex

	mu      sync.Mutex
	streams map[uint32]*Stream
	pings   map[uint32]chan struct{}

	drops atomic.Int64

	closed    chan struct{}
	closeOnce sync.Once
}

func newRegistration(conn net.Conn, cli *Client) *registration {
	return &registration{
		conn:    conn,
		cli:     cli,
		streams: make(map[uint32]*Stream),
		pings:   make(map[uint32]chan struct{}),
		closed:  make(chan struct{}),
	}
}

// run starts the read and keepalive loops. It returns immediately; the
// registration ends when closed is signalled.
func (r *registration) run() {
	go r.keepaliveLoop()
	r.readLoop()
}

// close ends the registration: it signals closed and drops the socket, which
// unblocks the read loop. It is idempotent.
func (r *registration) close() {
	r.closeOnce.Do(func() {
		close(r.closed)
		_ = r.conn.Close()
	})
}

func (r *registration) isClosed() bool {
	select {
	case <-r.closed:
		return true
	default:
		return false
	}
}

// write sends one encoded relay message on the registration socket.
func (r *registration) write(buf []byte) error {
	r.wmu.Lock()
	defer r.wmu.Unlock()
	if r.isClosed() {
		return net.ErrClosed
	}
	return writeAll(r.conn, buf)
}

// readLoop demultiplexes relay messages until the registration ends.
func (r *registration) readLoop() {
	defer r.close()
	for {
		msg, err := relayproto.Read(r.conn)
		if err != nil {
			return
		}
		switch msg.Type {
		case relayproto.TypeStreamOpen:
			st := r.addStream(msg.StreamID)
			go r.cli.cfg.OnStream(st)
		case relayproto.TypeStreamFrame:
			st := r.stream(msg.StreamID)
			if st == nil {
				// The stream was just closed; in-flight frames are dropped.
				continue
			}
			r.deliver(st, inboxMsg{frame: msg.Data})
		case relayproto.TypeStreamClose:
			st := r.dropStream(msg.StreamID)
			if st != nil {
				ce := &transport.CloseError{Code: msg.Code, Reason: msg.Reason}
				r.deliver(st, inboxMsg{ce: ce})
			}
		case relayproto.TypeStreamPong:
			r.signalPong(msg.StreamID)
		case relayproto.TypeKeepalive:
			// The relay echoes keepalives; the daemon's own keepalive loop
			// carries liveness, so there is nothing to do.
		case relayproto.TypeEnd:
			// The relay is ending the registration (replaced, going away,
			// idle). Every stream ends with it.
			return
		default:
			// An unknown message type is a protocol violation; end the
			// registration and reconnect.
			return
		}
	}
}

// deliver hands one item to a stream's inbox. It blocks only while the inbox
// is full and the stream's consumer is still live; once the consumer tears the
// stream down (closing st.closing) it drops the item instead of stalling the
// demultiplexer.
func (r *registration) deliver(st *Stream, m inboxMsg) {
	select {
	case st.in <- m:
	case <-st.closing:
		if r.drops.Add(1) == 1 {
			r.cli.log.Warn("relayconn: dropping frames for a closing stream")
		}
	}
}

func (r *registration) keepaliveLoop() {
	ticker := time.NewTicker(keepaliveInterval)
	defer ticker.Stop()
	buf, _ := relayproto.Encode(relayproto.NewKeepalive())
	for {
		select {
		case <-r.closed:
			return
		case <-ticker.C:
			if err := r.write(buf); err != nil {
				return
			}
		}
	}
}

func (r *registration) addStream(id uint32) *Stream {
	st := &Stream{
		id:      id,
		reg:     r,
		in:      make(chan inboxMsg, inboxCap),
		closing: make(chan struct{}),
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	// A duplicate stream id from the relay is a protocol violation; keep the
	// first and drop the later open.
	if _, ok := r.streams[id]; !ok {
		r.streams[id] = st
	}
	return st
}

func (r *registration) stream(id uint32) *Stream {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.streams[id]
}

func (r *registration) dropStream(id uint32) *Stream {
	r.mu.Lock()
	defer r.mu.Unlock()
	st := r.streams[id]
	delete(r.streams, id)
	return st
}

func (r *registration) addPing(id uint32) (chan struct{}, bool) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.isClosed() {
		return nil, false
	}
	done := make(chan struct{})
	r.pings[id] = done
	return done, true
}

func (r *registration) delPing(id uint32, done chan struct{}) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.pings[id] == done {
		delete(r.pings, id)
	}
}

func (r *registration) signalPong(id uint32) {
	r.mu.Lock()
	done := r.pings[id]
	if done != nil {
		delete(r.pings, id)
	}
	r.mu.Unlock()
	if done != nil {
		close(done)
	}
}

// writeAll writes every byte of buf to the connection.
func writeAll(conn net.Conn, buf []byte) error {
	for len(buf) > 0 {
		n, err := conn.Write(buf)
		buf = buf[n:]
		if err != nil {
			return err
		}
	}
	return nil
}
