package transport

import (
	"context"
	"encoding/binary"
	"errors"
	"io"
	"net"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/fsops"
	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
	"github.com/heavycaffeiner/remotly/daemon/internal/transfer"
)

var (
	// livenessPollInterval is how often the liveness watcher measures idle
	// time; the idle bound itself is readTimeout.
	livenessPollInterval = 5 * time.Second
)

const (
	// inputQueueCap bounds terminal input frames queued ahead of the PTY
	// writer. A full queue is a resource-limit close.
	inputQueueCap = 64
	// termReadChunk sizes the output pump's read buffer.
	termReadChunk = 32 << 10
)

// ctrlFail tells the reader that a control frame must close the connection
// with the given close info instead of answering an error response.
type ctrlFail struct{ ci closeError }

func (e ctrlFail) Error() string { return e.ci.Error() }

// termChannel is one attached session terminal: the mux channel that carries
// it, the session it reads from, and the output pump's attachment.
type termChannel struct {
	id       uint32
	sess     *session.Session
	at       *session.Attachment
	detached atomic.Bool
}

// inputJob is one terminal input frame queued for the PTY writer.
type inputJob struct {
	tc   *termChannel
	data []byte
}

// fileChannel is one transfer file channel: the mux channel that carries its
// chunk frames, the transfer it belongs to, and its direction. The transfer
// itself outlives the connection (for resume), so tearing down a conn closes
// the channel but never cancels the transfer.
type fileChannel struct {
	id         uint32
	transferID string
	dir        transfer.Direction
}

// conn is one accepted client connection: the encrypted pipe, the channel
// multiplexer, and the session attachments it owns. Sessions outlive
// connections; tearing down a conn detaches its attachments and nothing
// more.
type conn struct {
	srv *Server
	st  Stream
	lan bool

	mode       byte
	peerStatic [32]byte
	cipher     *protocol.ChaCha
	mux        *protocol.Mux

	deviceName string
	devicePub  [32]byte
	authed     atomic.Bool
	idTracker  *protocol.IDTracker

	mu        sync.Mutex
	attached  map[uint32]*termChannel
	fileChans map[uint32]*fileChannel

	inCh     chan inputJob
	stopped  chan struct{}
	stopOnce sync.Once

	failMu sync.Mutex
	failCI closeError

	lastOK atomic.Int64 // unix nano of the last accepted inbound frame
	pumps  sync.WaitGroup
}

// newConn assembles a connection and its multiplexer. The control handler is
// a conn method, so the mux is built after the conn.
func (s *Server) newConn(st Stream, lan bool) (*conn, error) {
	c := &conn{
		srv:       s,
		st:        st,
		lan:       lan,
		inCh:      make(chan inputJob, inputQueueCap),
		attached:  make(map[uint32]*termChannel),
		fileChans: make(map[uint32]*fileChannel),
		stopped:   make(chan struct{}),
		idTracker: protocol.NewIDTracker(),
	}
	mux, err := protocol.NewMux(protocol.MuxConfig{
		CtrlHandler: c.handleCtrlFrame,
		EncodeClose: protocol.EncodeChannelClose,
	})
	if err != nil {
		return nil, err
	}
	c.mux = mux
	return c, nil
}

// teardown releases the state of a connection that never became active,
// after a failed handshake. The caller closes the socket with the failure
// info.
func (c *conn) teardown() {
	c.mux.Close()
}

// fail records the first close info and stops the connection's loops. It does
// not close the socket: serve's failure goroutine sends the close frame with
// the recorded code, and the resulting socket teardown unblocks the reader.
func (c *conn) fail(ci closeError) {
	c.failMu.Lock()
	if c.failCI.code == 0 {
		c.failCI = ci
	}
	c.failMu.Unlock()
	c.stopOnce.Do(func() { close(c.stopped) })
}

// closeInfo returns the close information to present to the peer.
func (c *conn) closeInfo() closeError {
	c.failMu.Lock()
	defer c.failMu.Unlock()
	if c.failCI.code == 0 {
		return closeError{code: websocket.StatusNormalClosure, reason: "closed"}
	}
	return c.failCI
}

// serve runs the connection until it ends, then tears down in a bounded
// order: detach the attachments, close the multiplexer so the writer drains
// and exits, stop the input dispatcher, wait for the output pumps, and send
// the close frame. It returns once every connection-owned resource is
// released.
func (c *conn) serve(ctx context.Context) {
	// A failure starts the close handshake in a separate goroutine so the
	// close frame is written while the reader still holds the read lock. The
	// handshake tears down the socket, which releases the lock and returns
	// the reader. The context is only cancelled on server shutdown.
	go func() {
		select {
		case <-c.stopped:
			ci := c.closeInfo()
			_ = c.st.Close(uint16(ci.code), ci.reason)
		case <-ctx.Done():
		}
	}()

	c.lastOK.Store(time.Now().UnixNano())

	var wg sync.WaitGroup
	wg.Add(3)
	go func() { defer wg.Done(); c.liveness(ctx) }()
	go func() { defer wg.Done(); c.writeLoop(ctx) }()
	go func() { defer wg.Done(); c.inputLoop(ctx) }()

	c.readLoop(ctx)
	c.finishClose()
	wg.Wait()
	c.pumps.Wait()

	ci := c.closeInfo()
	// The failure goroutine usually closes the connection first; a second
	// Close is then expected to report the socket as already closed.
	if err := c.st.Close(uint16(ci.code), ci.reason); err != nil && !errors.Is(err, net.ErrClosed) {
		c.srv.log.Warn("transport close handshake failed", "code", int(ci.code), "reason", ci.reason, "err", err.Error())
	}
	c.srv.log.Info("transport connection closed", "code", int(ci.code), "reason", ci.reason)
}

// finishClose detaches every attachment and closes the multiplexer. It runs
// once in serve after the reader has returned.
func (c *conn) finishClose() {
	c.mu.Lock()
	tcs := make([]*termChannel, 0, len(c.attached))
	for _, tc := range c.attached {
		tcs = append(tcs, tc)
	}
	c.attached = make(map[uint32]*termChannel)
	// File channels close with the connection; the transfers themselves
	// persist in the manager so a reconnecting app can resume them.
	c.fileChans = make(map[uint32]*fileChannel)
	c.mu.Unlock()
	for _, tc := range tcs {
		if tc.detached.Swap(true) {
			continue
		}
		_ = tc.at.Close()
	}
	c.mux.Close()
}

// readLoop reads encrypted frames from the socket and delivers them to the
// multiplexer until the connection fails.
func (c *conn) readLoop(ctx context.Context) {
	for {
		data, err := c.st.Read(ctx)
		if err != nil {
			if errors.Is(err, errTextFrame) {
				c.fail(closeError{code: protocol.CloseProtocol, reason: "text frame"})
			} else {
				c.fail(readErrInfo(err))
			}
			return
		}
		if len(data) > maxFrameLen {
			c.fail(closeError{code: protocol.CloseLimit, reason: "frame too large"})
			return
		}
		chType, chID, payload, err := c.cipher.OpenFrame(data)
		if err != nil {
			c.fail(frameErrInfo(err))
			return
		}
		c.lastOK.Store(time.Now().UnixNano())
		if err := c.mux.Deliver(protocol.Frame{Type: chType, ID: chID, Payload: payload}); err != nil {
			var cf ctrlFail
			if errors.As(err, &cf) {
				c.fail(cf.ci)
			} else {
				c.fail(deliverErrInfo(err))
			}
			return
		}
	}
}

// writeLoop reads outbound frames from the multiplexer, seals them, and
// writes them to the socket. It exits once the multiplexer is closed and
// drained.
func (c *conn) writeLoop(ctx context.Context) {
	for {
		f, err := c.mux.Read()
		if err != nil {
			if !errors.Is(err, protocol.ErrMuxClosed) {
				c.fail(internalClose())
			}
			return
		}
		wire, err := c.cipher.SealFrame(f.Type, f.ID, f.Payload)
		if err != nil {
			c.fail(internalClose())
			return
		}
		if err := c.st.Write(ctx, wire); err != nil {
			c.fail(writeErrInfo(err))
			return
		}
	}
}

// liveness pings the peer after readTimeout of inbound silence and fails the
// connection when the pong does not arrive within pongDeadline.
func (c *conn) liveness(ctx context.Context) {
	ticker := time.NewTicker(livenessPollInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopped:
			return
		case <-ticker.C:
		}
		if time.Since(time.Unix(0, c.lastOK.Load())) < readTimeout {
			continue
		}
		pctx, cancel := context.WithTimeout(ctx, pongDeadline)
		err := c.st.Ping(pctx)
		cancel()
		if err != nil {
			var cerr *CloseError
			if errors.As(err, &cerr) && cerr.Code >= 1000 && cerr.Code < 3000 {
				c.fail(closeError{code: websocket.StatusCode(cerr.Code), reason: cerr.Reason})
			} else {
				c.fail(closeError{code: websocket.StatusGoingAway, reason: "no pong"})
			}
			return
		}
		c.lastOK.Store(time.Now().UnixNano())
	}
}

// inputLoop writes queued terminal input to the session PTYs. It runs one
// job at a time so keystrokes reach the PTY in the order the app committed
// them, regardless of how many channels they came in on.
func (c *conn) inputLoop(ctx context.Context) {
	for {
		select {
		case <-ctx.Done():
			return
		case <-c.stopped:
			return
		case job := <-c.inCh:
			if job.tc.detached.Load() {
				continue
			}
			if _, err := job.tc.sess.Write(job.data); err != nil {
				if errors.Is(err, session.ErrSessionExited) {
					continue
				}
				c.fail(internalClose())
				return
			}
		}
	}
}

// handleCtrlFrame validates and dispatches one control request. It runs in
// the reader goroutine. A nil error answered the request or is non-fatal; a
// ctrlFail error closes the connection.
func (c *conn) handleCtrlFrame(f protocol.Frame) error {
	if len(f.Payload) > protocol.MaxControlLen {
		return ctrlFail{closeError{code: protocol.CloseProtocol, reason: "control frame too large"}}
	}
	req, err := protocol.Parse(f.Payload)
	if err != nil {
		var cerr *protocol.ErrRequest
		if errors.As(err, &cerr) {
			c.sendCtrl(protocol.EncodeErrorResponse(cerr.ID, cerr.Type, cerr.Code, cerr.Code))
			return nil
		}
		if errors.Is(err, protocol.ErrBadJSON) {
			return ctrlFail{closeError{code: protocol.CloseProtocol, reason: "bad json"}}
		}
		return ctrlFail{closeError{code: protocol.CloseProtocol, reason: "bad control frame"}}
	}
	if err := c.idTracker.See(req.ID); err != nil {
		if errors.Is(err, protocol.ErrTooManyIDs) {
			return ctrlFail{closeError{code: protocol.CloseLimit, reason: "request id limit"}}
		}
		return ctrlFail{closeError{code: protocol.CloseProtocol, reason: "duplicate request id"}}
	}
	if !c.authed.Load() {
		if req.Type != protocol.TypeHello {
			return ctrlFail{closeError{code: protocol.CloseProtocol, reason: "hello required"}}
		}
		return c.handleHello(req)
	}
	switch req.Type {
	case protocol.TypeSessionCreate:
		return c.sessionCreate(req)
	case protocol.TypeSessionList:
		return c.sessionList(req)
	case protocol.TypeSessionAttach:
		return c.sessionAttach(req)
	case protocol.TypePresetList:
		return c.presetList(req)
	case protocol.TypeFSList:
		return c.fsList(req)
	case protocol.TypeFSStat:
		return c.fsStat(req)
	case protocol.TypeFSMkdir:
		return c.fsMkdir(req)
	case protocol.TypeFSRemove:
		return c.fsRemove(req)
	case protocol.TypeFSRename:
		return c.fsRename(req)
	case protocol.TypeFSRoots:
		return c.fsRoots(req)
	case protocol.TypeTransferCreate:
		return c.transferCreate(req)
	case protocol.TypeTransferResume:
		return c.transferResume(req)
	case protocol.TypeTransferStatus:
		return c.transferStatus(req)
	case protocol.TypeTransferComplete:
		return c.transferComplete(req)
	case protocol.TypeTransferCancel:
		return c.transferCancel(req)
	case protocol.TypeSessionDetach:
		return c.sessionDetach(req)
	case protocol.TypeSessionResize:
		return c.sessionResize(req)
	case protocol.TypeSessionKill:
		return c.sessionKill(req)
	case protocol.TypeHello:
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeInvalidRequest, protocol.CodeInvalidRequest))
		return nil
	default:
		// Parse already rejected unknown types.
		return ctrlFail{closeError{code: protocol.CloseProtocol, reason: "bad control frame"}}
	}
}

// handleHello validates the device identity and completes pairing. The app's
// long-term key is its Noise static, so device_pub must equal the key the
// peer proved in the handshake; the daemon only trusts a key the handshake
// already authenticated.
func (c *conn) handleHello(req *protocol.Request) error {
	if req.DevicePub != c.peerStatic {
		return ctrlFail{closeError{code: protocol.CloseAuth, reason: "bad hello"}}
	}
	switch c.mode {
	case protocol.ModePair:
		if _, err := c.srv.opts.Devices.Pair(req.DevicePub, *req.DeviceName); err != nil {
			return c.authClose(err)
		}
		c.srv.NotifyGate()
	case protocol.ModeIK:
		if _, err := c.srv.opts.Devices.Verify(req.DevicePub); err != nil {
			return c.authClose(err)
		}
	}
	c.deviceName = *req.DeviceName
	c.devicePub = req.DevicePub
	c.authed.Store(true)
	c.sendCtrl(protocol.EncodeHelloResponse(req.ID, c.srv.opts.DaemonName, c.srv.opts.Identity.PublicBytes()))
	return nil
}

// authClose maps a device store error to its authentication close info.
func (c *conn) authClose(err error) ctrlFail {
	switch {
	case errors.Is(err, pairing.ErrDeviceUnknown):
		return ctrlFail{closeError{code: protocol.CloseAuth, reason: "device_unknown"}}
	case errors.Is(err, pairing.ErrDeviceRevoked):
		return ctrlFail{closeError{code: protocol.CloseAuth, reason: "device_revoked"}}
	case errors.Is(err, pairing.ErrDeviceDuplicate):
		return ctrlFail{closeError{code: protocol.CloseAuth, reason: "device_duplicate"}}
	default:
		return ctrlFail{closeError{code: protocol.CloseLimit, reason: "device limit"}}
	}
}

// sessionCreate starts a new session.
func (c *conn) sessionCreate(req *protocol.Request) error {
	sr := session.Request{Kind: session.Kind(*req.Kind)}
	if req.Title != nil {
		sr.Title = *req.Title
	}
	if req.Command != nil {
		sr.Command = *req.Command
	}
	if req.Cwd != nil {
		sr.Cwd = *req.Cwd
	}
	if req.Cols != nil {
		sr.Cols = uint16(*req.Cols)
	}
	if req.Rows != nil {
		sr.Rows = uint16(*req.Rows)
	}
	s, err := c.srv.opts.Sessions.Create(sr)
	if err != nil {
		c.respondError(req, err)
		return nil
	}
	c.sendCtrl(protocol.EncodeCreateResponse(req.ID, toMeta(s.Meta())))
	return nil
}

// sessionList reports every live session, oldest first.
func (c *conn) sessionList(req *protocol.Request) error {
	live := c.srv.opts.Sessions.List()
	out := make([]protocol.Meta, 0, len(live))
	for _, m := range live {
		out = append(out, toMeta(m))
	}
	c.sendCtrl(protocol.EncodeListResponse(req.ID, out))
	return nil
}

// sessionAttach opens a term channel on a session: retained scrollback
// first, then live output, on a fresh mux channel. With resume_from the
// replay starts at that output-stream offset and the response reports the
// resulting continuity (full, gapless, or gap).
func (c *conn) sessionAttach(req *protocol.Request) error {
	s, err := c.srv.opts.Sessions.Get(*req.SessionID)
	if err != nil {
		c.respondError(req, err)
		return nil
	}
	var at *session.Attachment
	var info session.AttachInfo
	if req.ResumeFrom != nil {
		at, info, err = s.AttachFrom(*req.ResumeFrom)
	} else {
		at, info, err = s.Attach()
	}
	if err != nil {
		c.respondError(req, err)
		return nil
	}
	id, err := c.mux.OpenTerm(c.termInputHandler)
	if err != nil {
		_ = at.Close()
		if errors.Is(err, protocol.ErrTooManyChannels) {
			return ctrlFail{closeError{code: protocol.CloseLimit, reason: "channel limit"}}
		}
		return ctrlFail{internalClose()}
	}
	tc := &termChannel{id: id, sess: s, at: at}
	c.mu.Lock()
	c.attached[id] = tc
	c.mu.Unlock()
	c.pumps.Add(1)
	// The response is enqueued before the pump starts so the client sees it
	// before any channel output, including replay_complete.
	c.sendCtrl(protocol.EncodeAttachResponse(req.ID, id, info.Continuity, info.ReplayedFrom))
	go c.pumpTerm(tc)
	return nil
}

// presetList reports the daemon's configured session presets.
func (c *conn) presetList(req *protocol.Request) error {
	c.sendCtrl(protocol.EncodePresetListResponse(req.ID, c.srv.opts.Presets))
	return nil
}

// sessionDetach ends one attached channel; the channel.close notification
// follows the channel's last output frame.
func (c *conn) sessionDetach(req *protocol.Request) error {
	c.mu.Lock()
	tc := c.attached[*req.ChannelID]
	c.mu.Unlock()
	if tc == nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeUnknownChannel, protocol.CodeUnknownChannel))
		return nil
	}
	if tc.detached.Swap(true) {
		c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
		return nil
	}
	_ = tc.at.Close()
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

// sessionResize changes a session's PTY dimensions.
func (c *conn) sessionResize(req *protocol.Request) error {
	s, err := c.srv.opts.Sessions.Get(*req.SessionID)
	if err != nil {
		c.respondError(req, err)
		return nil
	}
	if err := s.Resize(uint16(*req.Cols), uint16(*req.Rows)); err != nil {
		c.respondError(req, err)
		return nil
	}
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

// sessionKill terminates a session's process tree.
func (c *conn) sessionKill(req *protocol.Request) error {
	s, err := c.srv.opts.Sessions.Get(*req.SessionID)
	if err != nil {
		c.respondError(req, err)
		return nil
	}
	if err := s.Kill(); err != nil {
		c.respondError(req, err)
		return nil
	}
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

// termInputHandler queues one terminal input frame for the PTY writer. It
// runs in the reader goroutine and must not block.
func (c *conn) termInputHandler(f protocol.Frame) error {
	c.mu.Lock()
	tc := c.attached[f.ID]
	c.mu.Unlock()
	if tc == nil || tc.detached.Load() {
		return deliverErrInfo(protocol.ErrUnknownChannel)
	}
	if len(f.Payload) > session.MaxInputChunk {
		return ctrlFail{closeError{code: protocol.CloseLimit, reason: "input too large"}}
	}
	select {
	case c.inCh <- inputJob{tc: tc, data: f.Payload}:
		return nil
	case <-c.stopped:
		return nil
	default:
		return ctrlFail{closeError{code: protocol.CloseLimit, reason: "input queue full"}}
	}
}

// pumpTerm forwards one attachment's output to its term channel until the
// stream ends, then emits the channel.close notification with the end
// reason. The first ReplayBytes bytes are retained replay; the
// channel.replay_complete notification marks the replay/live boundary with
// the exact resume cursor at that point (replayed_from plus replayed
// bytes).
//
// Ordering note: the mux serves control frames with priority, so the
// notification may reach the client before the final replay bytes. Clients
// must not depend on wire order for cursor tracking; they compute the
// resume cursor as replayed_from plus the term bytes received on the
// channel, which is exact and order-independent. The notification is the
// boundary marker: once it has been sent, every byte the channel delivers
// afterwards is live output. A term frame straddling the boundary is split
// so no frame mixes replay and live bytes.
func (c *conn) pumpTerm(tc *termChannel) {
	defer c.pumps.Done()
	defer c.unregisterTerm(tc)
	buf := make([]byte, termReadChunk)
	replayTotal := tc.at.ReplayBytes()
	replayedFrom := tc.at.ReplayedFrom()
	replayDone := replayTotal == 0
	if replayDone {
		// An empty replay completes before the first output byte.
		c.sendCtrl(protocol.EncodeReplayComplete(tc.id, uint64(replayedFrom)))
	}
	sent := int64(0) // output bytes sent on this channel
	for {
		n, err := tc.at.Read(buf)
		if n > 0 {
			parts := [][]byte{buf[:n]}
			if !replayDone && sent < replayTotal && sent+int64(n) > replayTotal {
				cut := int(replayTotal - sent)
				parts = [][]byte{buf[:cut], buf[cut:n]}
			}
			for _, part := range parts {
				payload := make([]byte, len(part))
				copy(payload, part)
				if serr := c.mux.Send(protocol.Frame{Type: protocol.ChannelTerm, ID: tc.id, Payload: payload}); serr != nil {
					// A full queue means this reader is too slow, per the
					// backpressure rule; anything else is connection teardown.
					reason := protocol.ReasonClosed
					if errors.Is(serr, protocol.ErrChannelFull) {
						reason = protocol.ReasonOverflow
					}
					c.dropTerm(tc, reason)
					return
				}
				sent += int64(len(part))
				if !replayDone && sent == replayTotal {
					// The last replay byte was just delivered; announce the
					// boundary before live output follows.
					c.sendCtrl(protocol.EncodeReplayComplete(tc.id, uint64(replayedFrom+replayTotal)))
					replayDone = true
				}
			}
		}
		if err != nil {
			break
		}
	}
	reason := streamEndReason(tc.at.Reason())
	select {
	case <-c.stopped:
		reason = protocol.ReasonClosed
	default:
	}
	_ = c.mux.CloseTerm(tc.id, reason)
}

// dropTerm ends a channel because its data is no longer deliverable: the
// queue overflowed or the connection is tearing down. The queue is dropped.
func (c *conn) dropTerm(tc *termChannel, reason string) {
	if tc.detached.Swap(true) {
		return
	}
	_ = tc.at.Close()
	_ = c.mux.DropTerm(tc.id, reason)
}

func (c *conn) unregisterTerm(tc *termChannel) {
	c.mu.Lock()
	if c.attached[tc.id] == tc {
		delete(c.attached, tc.id)
	}
	c.mu.Unlock()
}

// sendCtrl enqueues a control response. A full control queue fails the
// connection: the app cannot progress without its answers.
func (c *conn) sendCtrl(payload []byte) {
	if err := c.mux.Send(protocol.Frame{Type: protocol.ChannelCtrl, Payload: payload}); err != nil {
		c.fail(closeError{code: protocol.CloseLimit, reason: "control queue full"})
	}
}

// respondError answers a request with a protocol error response.
func (c *conn) respondError(req *protocol.Request, err error) {
	code := sessionErrorCode(err)
	msg := code
	if code != protocol.CodeSpawnFailed {
		msg = err.Error()
	}
	c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, code, msg))
}

// sessionErrorCode maps a session manager error to its protocol error code.
func sessionErrorCode(err error) string {
	switch {
	case errors.Is(err, session.ErrUnknownSession):
		return protocol.CodeUnknownSession
	case errors.Is(err, session.ErrSessionExited):
		return protocol.CodeSessionExited
	case errors.Is(err, session.ErrCapacity):
		return protocol.CodeCapacity
	case errors.Is(err, session.ErrTooManyAttachments):
		return protocol.CodeAttachmentLimit
	case errors.Is(err, session.ErrCursorOutOfRange):
		return protocol.CodeCursorOutOfRange
	case errors.Is(err, session.ErrInvalidRequest):
		return protocol.CodeInvalidRequest
	default:
		return protocol.CodeSpawnFailed
	}
}

// fsErrorCode maps an fsops error to its protocol error code. The app matches
// on the code, never the message.
func fsErrorCode(err error) string {
	switch {
	case errors.Is(err, fsops.ErrNotFound):
		return protocol.CodeFSNotFound
	case errors.Is(err, fsops.ErrNotDir):
		return protocol.CodeFSNotDir
	case errors.Is(err, fsops.ErrIsDir):
		return protocol.CodeFSIsDir
	case errors.Is(err, fsops.ErrNotEmpty):
		return protocol.CodeFSNotEmpty
	case errors.Is(err, fsops.ErrPermission):
		return protocol.CodeFSPermission
	case errors.Is(err, fsops.ErrExist):
		return protocol.CodeFSExist
	case errors.Is(err, fsops.ErrInvalidPath):
		return protocol.CodeFSInvalidPath
	default:
		return protocol.CodeInvalidRequest
	}
}

func toProtocolEntry(e fsops.Entry) protocol.Entry {
	return protocol.Entry{
		Name:       e.Name,
		IsDir:      e.IsDir,
		IsSymlink:  e.IsSymlink,
		Size:       e.Size,
		ModTime:    e.ModTime,
		Perm:       e.Perm,
		LinkTarget: e.LinkTarget,
	}
}

func (c *conn) fsList(req *protocol.Request) error {
	offset, limit := 0, protocol.MaxFSPage
	if req.Offset != nil {
		offset = *req.Offset
	}
	if req.Limit != nil {
		limit = *req.Limit
	}
	entries, more, total, err := c.srv.opts.FS.List(*req.Path, offset, limit)
	if err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, fsErrorCode(err), err.Error()))
		return nil
	}
	out := make([]protocol.Entry, 0, len(entries))
	for _, e := range entries {
		out = append(out, toProtocolEntry(e))
	}
	c.sendCtrl(protocol.EncodeFSListResponse(req.ID, out, more, total))
	return nil
}

func (c *conn) fsStat(req *protocol.Request) error {
	e, err := c.srv.opts.FS.Stat(*req.Path)
	if err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, fsErrorCode(err), err.Error()))
		return nil
	}
	c.sendCtrl(protocol.EncodeFSStatResponse(req.ID, toProtocolEntry(e)))
	return nil
}

func (c *conn) fsMkdir(req *protocol.Request) error {
	if err := c.srv.opts.FS.Mkdir(*req.Path); err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, fsErrorCode(err), err.Error()))
		return nil
	}
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

func (c *conn) fsRemove(req *protocol.Request) error {
	var err error
	if *req.RemoveKind == "dir" {
		err = c.srv.opts.FS.RemoveDir(*req.Path)
	} else {
		err = c.srv.opts.FS.RemoveFile(*req.Path)
	}
	if err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, fsErrorCode(err), err.Error()))
		return nil
	}
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

func (c *conn) fsRename(req *protocol.Request) error {
	if err := c.srv.opts.FS.Rename(*req.From, *req.To); err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, fsErrorCode(err), err.Error()))
		return nil
	}
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

func (c *conn) fsRoots(req *protocol.Request) error {
	c.sendCtrl(protocol.EncodeFSRootsResponse(req.ID, c.srv.opts.FS.Roots()))
	return nil
}

// --- transfer (M4-05) ------------------------------------------------------

// chunk frame on the file channel: [8-byte big-endian offset][payload]. The
// offset lets the receiver place re-sent or out-of-order chunks correctly and
// resume after a reconnect.
func encodeChunkFrame(offset int64, data []byte) []byte {
	out := make([]byte, 8+len(data))
	binary.BigEndian.PutUint64(out[:8], uint64(offset))
	copy(out[8:], data)
	return out
}

func decodeChunkFrame(b []byte) (offset int64, data []byte, ok bool) {
	if len(b) < 8 {
		return 0, nil, false
	}
	return int64(binary.BigEndian.Uint64(b[:8])), b[8:], true
}

func transferErrorCode(err error) string {
	switch {
	case errors.Is(err, transfer.ErrNotFound):
		return protocol.CodeTransferNotFound
	case errors.Is(err, transfer.ErrNotAuthorized):
		return protocol.CodeTransferNotAuthorized
	case errors.Is(err, transfer.ErrCapacity):
		return protocol.CodeTransferCapacity
	case errors.Is(err, transfer.ErrTooLarge):
		return protocol.CodeTransferTooLarge
	case errors.Is(err, transfer.ErrBadOffset):
		return protocol.CodeTransferBadOffset
	case errors.Is(err, transfer.ErrOverLength):
		return protocol.CodeTransferOverLength
	case errors.Is(err, transfer.ErrHashMismatch):
		return protocol.CodeTransferHashMismatch
	case errors.Is(err, transfer.ErrSourceChanged):
		return protocol.CodeTransferSourceChanged
	case errors.Is(err, transfer.ErrIncomplete):
		return protocol.CodeTransferIncomplete
	case errors.Is(err, transfer.ErrConflict):
		return protocol.CodeTransferConflict
	default:
		return protocol.CodeTransferInvalidArg
	}
}

// transferCreate starts a transfer and opens its file channel. Uploads await
// chunk frames on the channel; downloads are pumped by the daemon.
func (c *conn) transferCreate(req *protocol.Request) error {
	m := c.srv.opts.Transfers
	if m == nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferInvalidArg, "transfers disabled"))
		return nil
	}
	dir := transfer.Up
	if *req.Direction == "down" {
		dir = transfer.Down
	}
	conflict := transfer.ConflictFail
	if req.Conflict != nil && *req.Conflict == "replace" {
		conflict = transfer.ConflictReplace
	}
	var expSize int64
	if req.ExpectedSize != nil {
		expSize = *req.ExpectedSize
	}
	hash := ""
	if req.Hash != nil {
		hash = *req.Hash
	}
	tr, err := m.Create(transfer.CreateParams{
		Device: c.peerStatic, Direction: dir, Path: *req.Path,
		ExpectedSize: expSize, Hash: hash, Conflict: conflict,
	})
	if err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, transferErrorCode(err), transferErrorCode(err)))
		return nil
	}
	chID, ctrlFail := c.openFileChannel(tr.ID, dir)
	if ctrlFail != nil {
		_ = m.Cancel(c.peerStatic, tr.ID)
		return *ctrlFail
	}
	off, size, _, h, _ := m.Get(c.peerStatic, tr.ID)
	c.sendCtrl(protocol.EncodeTransferCreateResponse(req.ID, tr.ID, chID, *req.Direction, size, h, off))
	return nil
}

// transferResume re-attaches a file channel to a transfer that already exists
// in the daemon, for a reconnecting app. It reports the current offset so the
// app can continue from there. The transfer must be bound to this device.
func (c *conn) transferResume(req *protocol.Request) error {
	m := c.srv.opts.Transfers
	if m == nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferInvalidArg, "transfers disabled"))
		return nil
	}
	off, size, dir, h, ok := m.Get(c.peerStatic, *req.TransferID)
	if !ok {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferNotFound, protocol.CodeTransferNotFound))
		return nil
	}
	chID, ctrlFail := c.openFileChannel(*req.TransferID, dir)
	if ctrlFail != nil {
		return *ctrlFail
	}
	dirName := "up"
	if dir == transfer.Down {
		dirName = "down"
	}
	c.sendCtrl(protocol.EncodeTransferCreateResponse(req.ID, *req.TransferID, chID, dirName, size, h, off))
	return nil
}

// transferStatus is a pure resume query: it reports the current offset, size,
// and hash without opening a channel.
func (c *conn) transferStatus(req *protocol.Request) error {
	m := c.srv.opts.Transfers
	if m == nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferInvalidArg, "transfers disabled"))
		return nil
	}
	off, size, dir, h, ok := m.Get(c.peerStatic, *req.TransferID)
	if !ok {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferNotFound, protocol.CodeTransferNotFound))
		return nil
	}
	dirName := "up"
	if dir == transfer.Down {
		dirName = "down"
	}
	c.sendCtrl(protocol.EncodeTransferStatusResponse(req.ID, *req.TransferID, off, size, dirName, h))
	return nil
}

// transferComplete finalizes an upload (verify size and hash, atomically
// replace the destination) or marks a download done. It closes the file
// channel.
func (c *conn) transferComplete(req *protocol.Request) error {
	m := c.srv.opts.Transfers
	if m == nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferInvalidArg, "transfers disabled"))
		return nil
	}
	hash, err := m.Complete(c.peerStatic, *req.TransferID)
	if err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, transferErrorCode(err), transferErrorCode(err)))
		return nil
	}
	c.closeFileChannelForTransfer(*req.TransferID)
	c.sendCtrl(protocol.EncodeTransferCompleteResponse(req.ID, *req.TransferID, hash))
	return nil
}

// transferCancel tears down a transfer and closes its file channel. The
// destination is never touched on cancel.
func (c *conn) transferCancel(req *protocol.Request) error {
	m := c.srv.opts.Transfers
	if m == nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, protocol.CodeTransferInvalidArg, "transfers disabled"))
		return nil
	}
	if err := m.Cancel(c.peerStatic, *req.TransferID); err != nil {
		c.sendCtrl(protocol.EncodeErrorResponse(req.ID, req.Type, transferErrorCode(err), transferErrorCode(err)))
		return nil
	}
	c.closeFileChannelForTransfer(*req.TransferID)
	c.sendCtrl(protocol.EncodePlainResponse(req.ID, req.Type))
	return nil
}

// openFileChannel opens a file channel for the given transfer and direction,
// registering it and starting the download pump when the direction is down.
// It returns the channel id and a non-nil ctrlFail on a resource-limit or
// internal open error (the caller tears the transfer down in that case).
func (c *conn) openFileChannel(transferID string, dir transfer.Direction) (uint32, *ctrlFail) {
	id, err := c.mux.OpenFile(c.fileInputHandler)
	if err != nil {
		if errors.Is(err, protocol.ErrTooManyChannels) {
			return 0, &ctrlFail{closeError{code: protocol.CloseLimit, reason: "channel limit"}}
		}
		return 0, &ctrlFail{internalClose()}
	}
	c.mu.Lock()
	c.fileChans[id] = &fileChannel{id: id, transferID: transferID, dir: dir}
	c.mu.Unlock()
	if dir == transfer.Down {
		c.pumps.Add(1)
		go c.pumpFileDownload(id, transferID)
	}
	return id, nil
}

// fileInputHandler applies one upload chunk frame to its transfer and sends a
// progress ack, or a failure notification and channel close on error.
func (c *conn) fileInputHandler(f protocol.Frame) error {
	c.mu.Lock()
	fc := c.fileChans[f.ID]
	c.mu.Unlock()
	if fc == nil || fc.dir != transfer.Up {
		return deliverErrInfo(protocol.ErrUnknownChannel)
	}
	m := c.srv.opts.Transfers
	if m == nil {
		return deliverErrInfo(protocol.ErrUnknownChannel)
	}
	offset, data, ok := decodeChunkFrame(f.Payload)
	if !ok || len(data) > protocol.MaxTransferChunk {
		c.failFileChannel(fc, protocol.CodeTransferInvalidArg)
		return deliverErrInfo(protocol.ErrCtrlFrame)
	}
	newOff, err := m.WriteChunk(c.peerStatic, fc.transferID, offset, data)
	if err != nil {
		c.failFileChannel(fc, transferErrorCode(err))
		return nil
	}
	c.sendCtrl(protocol.EncodeTransferAck(fc.transferID, newOff))
	return nil
}

// failFileChannel emits a transfer.failed notification and closes the channel.
func (c *conn) failFileChannel(fc *fileChannel, code string) {
	c.sendCtrl(protocol.EncodeTransferFailed(fc.transferID, code))
	c.closeFileChannel(fc.id)
}

// closeFileChannel closes a file channel and unregisters it.
func (c *conn) closeFileChannel(id uint32) {
	_ = c.mux.CloseTerm(id, protocol.ReasonClosed)
	c.mu.Lock()
	delete(c.fileChans, id)
	c.mu.Unlock()
}

// closeFileChannelForTransfer closes the file channel bound to a transfer, if
// any. Used by complete and cancel.
func (c *conn) closeFileChannelForTransfer(transferID string) {
	c.mu.Lock()
	var id uint32
	found := false
	for cid, fc := range c.fileChans {
		if fc.transferID == transferID {
			id, found = cid, true
			break
		}
	}
	c.mu.Unlock()
	if found {
		c.closeFileChannel(id)
	}
}

// pumpFileDownload streams the source of a download transfer onto its file
// channel in chunk frames, then sends transfer.done, closes the channel, and
// finalizes the transfer on the daemon side.
func (c *conn) pumpFileDownload(chID uint32, transferID string) {
	defer c.pumps.Done()
	m := c.srv.opts.Transfers
	chunkSize := int64(1 << 20)
	// Re-read the transfer to learn its size; a cancel between open and here
	// means the transfer is gone and the pump exits quietly.
	_, size, _, _, ok := m.Get(c.peerStatic, transferID)
	if !ok {
		c.closeFileChannel(chID)
		return
	}
	for off := int64(0); off < size; {
		want := chunkSize
		if size-off < want {
			want = size - off
		}
		data, err := m.ReadChunk(c.peerStatic, transferID, off, want)
		if err != nil {
			c.sendCtrl(protocol.EncodeTransferFailed(transferID, transferErrorCode(err)))
			c.closeFileChannel(chID)
			return
		}
		if len(data) == 0 {
			break
		}
		if serr := c.mux.Send(protocol.Frame{Type: protocol.ChannelFile, ID: chID, Payload: encodeChunkFrame(off, data)}); serr != nil {
			c.closeFileChannel(chID)
			return
		}
		off += int64(len(data))
	}
	c.sendCtrl(protocol.EncodeTransferDone(transferID))
	c.closeFileChannel(chID)
	// Mark the download done so its source handle is released.
	_, _ = m.Complete(c.peerStatic, transferID)
}

// streamEndReason maps an attachment end reason to the channel close reason.
func streamEndReason(r session.DetachReason) string {
	switch r {
	case session.ReasonOverflow:
		return protocol.ReasonOverflow
	case session.ReasonExited:
		return protocol.ReasonSessionExited
	default:
		return protocol.ReasonDetached
	}
}

// toMeta converts session metadata to its wire form.
func toMeta(m session.Metadata) protocol.Meta {
	p := protocol.Meta{
		ID:           m.ID,
		Title:        m.Title,
		Kind:         string(m.Kind),
		Command:      m.Command,
		Cwd:          m.Cwd,
		Cols:         int(m.Cols),
		Rows:         int(m.Rows),
		CreatedAt:    m.CreatedAt.UTC().Format(time.RFC3339),
		LastActivity: m.LastActivity.UTC().Format(time.RFC3339),
		Running:      m.Running,
	}
	if !m.Running {
		p.Exit = &protocol.Exit{Code: m.Exit.Code, Signal: m.Exit.Signal}
	}
	p.Preview = m.Preview
	return p
}

// readErrInfo maps a socket read failure to close information.
func readErrInfo(err error) closeError {
	var cerr *CloseError
	if errors.As(err, &cerr) {
		if cerr.Code >= 1000 && cerr.Code < 3000 {
			return closeError{code: websocket.StatusCode(cerr.Code), reason: cerr.Reason}
		}
		return closeError{code: websocket.StatusGoingAway, reason: "peer closed"}
	}
	if errors.Is(err, context.Canceled) {
		return closeError{code: websocket.StatusGoingAway, reason: "going away"}
	}
	// The library has no sentinel for a read-limit violation; it writes a
	// 1009 close itself and returns a plain error.
	if strings.Contains(err.Error(), "read limited") {
		return closeError{code: protocol.CloseLimit, reason: "frame too large"}
	}
	if errors.Is(err, io.EOF) || errors.Is(err, net.ErrClosed) {
		return closeError{code: websocket.StatusGoingAway, reason: "connection lost"}
	}
	return closeError{code: protocol.CloseProtocol, reason: "read error"}
}

// frameErrInfo maps a frame authentication or decoding failure to close
// information.
func frameErrInfo(err error) closeError {
	switch {
	case errors.Is(err, protocol.ErrDecrypt):
		return closeError{code: protocol.CloseProtocol, reason: "frame auth failed"}
	case errors.Is(err, protocol.ErrFrameTooLarge):
		return closeError{code: protocol.CloseLimit, reason: "frame too large"}
	default:
		return closeError{code: protocol.CloseProtocol, reason: "bad frame"}
	}
}

// deliverErrInfo maps a multiplexer routing failure to close information.
func deliverErrInfo(err error) closeError {
	switch {
	case errors.Is(err, protocol.ErrBadChannel):
		return closeError{code: protocol.CloseProtocol, reason: "channel type not enabled"}
	case errors.Is(err, protocol.ErrUnknownChannel):
		return closeError{code: protocol.CloseProtocol, reason: "unknown channel"}
	case errors.Is(err, protocol.ErrCtrlFrame):
		return closeError{code: protocol.CloseProtocol, reason: "bad control frame"}
	default:
		return internalClose()
	}
}

// writeErrInfo maps a socket write failure to close information.
func writeErrInfo(err error) closeError {
	var cerr *CloseError
	if errors.As(err, &cerr) {
		if cerr.Code >= 1000 && cerr.Code < 3000 {
			return closeError{code: websocket.StatusCode(cerr.Code), reason: cerr.Reason}
		}
		return closeError{code: websocket.StatusGoingAway, reason: "peer closed"}
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, net.ErrClosed) {
		return closeError{code: websocket.StatusGoingAway, reason: "going away"}
	}
	return closeError{code: websocket.StatusInternalError, reason: "write error"}
}
