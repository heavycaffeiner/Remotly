package transport

import (
	"context"
	"errors"
	"fmt"

	"github.com/flynn/noise"
	"nhooyr.io/websocket"

	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
)

// suite is the single supported Noise cipher suite:
// Noise_*_25519_ChaChaPoly_BLAKE2b, the suite the app-side native Noise
// libraries expose.
var suite = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2b)

// closeError is the WebSocket close information recorded for one connection
// failure. The first failure wins; the writer closes the socket with it.
type closeError struct {
	code   websocket.StatusCode
	reason string
}

func (e closeError) Error() string {
	return fmt.Sprintf("transport: close %d %s", int(e.code), e.reason)
}

func internalClose() closeError {
	return closeError{code: websocket.StatusCode(1011), reason: "internal error"}
}

// tokenCloseInfo maps a token manager error to its close code and reason.
func tokenCloseInfo(err error) closeError {
	switch {
	case errors.Is(err, pairing.ErrTokenUnknown):
		return closeError{code: protocol.CloseToken, reason: "token_unknown"}
	case errors.Is(err, pairing.ErrTokenExpired):
		return closeError{code: protocol.CloseToken, reason: "token_expired"}
	case errors.Is(err, pairing.ErrTokenUsed):
		return closeError{code: protocol.CloseToken, reason: "token_used"}
	default:
		return closeError{code: protocol.CloseToken, reason: "token_unknown"}
	}
}

// runHandshake performs the versioned Noise handshake on a freshly accepted
// WebSocket. On success it installs the cipher, peer identity, and mode on
// the connection. On failure it returns the close information; the caller
// closes the socket with it.
func (s *Server) runHandshake(ctx context.Context, c *conn) (closeError, error) {
	c.st.SetReadLimit(protocol.MaxHandshake)
	rctx, cancel := context.WithTimeout(ctx, handshakeTimeout)
	defer cancel()

	data, err := s.readHandshakeMsg(rctx, c)
	if err != nil {
		return s.handshakeReadErr(err), err
	}
	if len(data) < 2 {
		return closeError{code: protocol.CloseProtocol, reason: "bad handshake"}, errors.New("transport: short handshake message")
	}
	version := data[0]
	if version != protocol.Version {
		return closeError{
			code:   protocol.CloseVersion,
			reason: fmt.Sprintf("unsupported protocol version %d", version),
		}, nil
	}
	mode := data[1]
	switch mode {
	case protocol.ModeIK, protocol.ModePair:
	default:
		return closeError{code: protocol.CloseProtocol, reason: "bad mode"}, nil
	}
	body := data[2:]

	var (
		hs      *noise.HandshakeState
		tokenID []byte
	)
	if mode == protocol.ModePair {
		lv, n, err := protocol.ReadVarint(body)
		if err != nil || lv < 1 || lv > protocol.MaxTokenIDLen {
			return closeError{code: protocol.CloseProtocol, reason: "bad token id"}, nil
		}
		body = body[n:]
		if uint64(len(body)) < lv {
			return closeError{code: protocol.CloseProtocol, reason: "bad token id"}, nil
		}
		tokenID = make([]byte, lv)
		copy(tokenID, body[:lv])
		body = body[lv:]

		// The token must be live before the handshake starts; its secret is
		// the PSK and its ephemeral keypair the responder static.
		snap, err := s.opts.Tokens.Lookup(tokenID)
		if err != nil {
			return tokenCloseInfo(err), nil
		}
		var priv, pub [32]byte
		copy(priv[:], snap.EphemeralPriv[:])
		copy(pub[:], snap.EphemeralPub[:])
		hs, err = noise.NewHandshakeState(noise.Config{
			CipherSuite:           suite,
			Pattern:               noise.HandshakeXX,
			Initiator:             false,
			Prologue:              []byte(protocol.Prologue),
			StaticKeypair:         noise.DHKey{Private: priv[:], Public: pub[:]},
			PresharedKey:          snap.Secret[:],
			PresharedKeyPlacement: 0,
		})
		if err != nil {
			return internalClose(), nil
		}
	} else {
		// IK: the responder pre-seeds its long-term static. The initiator's
		// key is not verified here; hello verifies it against the device
		// store.
		priv, pub := s.opts.Identity.KeyPair()
		hs, err = noise.NewHandshakeState(noise.Config{
			CipherSuite:   suite,
			Pattern:       noise.HandshakeIK,
			Initiator:     false,
			Prologue:      []byte(protocol.Prologue),
			StaticKeypair: noise.DHKey{Private: priv[:], Public: pub[:]},
		})
		if err != nil {
			return internalClose(), nil
		}
	}

	peer, sendKey, recvKey, ci, err := s.noiseExchange(rctx, c, hs, mode, body)
	if err != nil {
		return ci, err
	}
	if mode == protocol.ModePair {
		// Completing XXpsk0 proves knowledge of the secret; claim the token
		// atomically before any application data is accepted.
		if err := s.opts.Tokens.Claim(tokenID); err != nil {
			return tokenCloseInfo(err), nil
		}
		s.NotifyGate()
	}

	c.peerStatic = peer
	c.mode = mode
	c.cipher = protocol.NewChaCha(sendKey, recvKey)
	return closeError{}, nil
}

// noiseExchange drives the responder side of the pattern to completion and
// returns the peer's static key and the split direction keys. The responder
// sends with c2 and receives with c1. Neither pattern encrypts handshake
// payloads, so the transport frame nonce counters start at zero.
func (s *Server) noiseExchange(ctx context.Context, c *conn, hs *noise.HandshakeState, mode byte, noiseMsg1 []byte) (peer, send, recv [32]byte, ci closeError, err error) {
	if _, _, _, err := hs.ReadMessage(nil, noiseMsg1); err != nil {
		return zeroKey(), zeroKey(), zeroKey(), closeError{code: protocol.CloseProtocol, reason: "handshake failed"}, err
	}
	var cs1, cs2 *noise.CipherState
	if mode == protocol.ModeIK {
		// IK completes for the responder when it writes the second message.
		msg2, a, b, err := hs.WriteMessage(nil, nil)
		if err != nil {
			return zeroKey(), zeroKey(), zeroKey(), closeError{code: protocol.CloseProtocol, reason: "handshake failed"}, err
		}
		cs1, cs2 = a, b
		if err := s.sendHandshakeMsg(ctx, c, mode, msg2); err != nil {
			return zeroKey(), zeroKey(), zeroKey(), s.handshakeWriteErr(err), err
		}
	} else {
		// XXpsk0 completes for the responder when it reads the third message.
		msg2, _, _, err := hs.WriteMessage(nil, nil)
		if err != nil {
			return zeroKey(), zeroKey(), zeroKey(), closeError{code: protocol.CloseProtocol, reason: "handshake failed"}, err
		}
		if err := s.sendHandshakeMsg(ctx, c, mode, msg2); err != nil {
			return zeroKey(), zeroKey(), zeroKey(), s.handshakeWriteErr(err), err
		}
		msg3, err := s.readHandshakeMsg(ctx, c)
		if err != nil {
			return zeroKey(), zeroKey(), zeroKey(), s.handshakeReadErr(err), err
		}
		_, a, b, err := hs.ReadMessage(nil, msg3)
		if err != nil {
			return zeroKey(), zeroKey(), zeroKey(), closeError{code: protocol.CloseProtocol, reason: "handshake failed"}, err
		}
		cs1, cs2 = a, b
	}
	peerBytes := hs.PeerStatic()
	if len(peerBytes) != 32 {
		return zeroKey(), zeroKey(), zeroKey(), internalClose(), nil
	}
	copy(peer[:], peerBytes)
	if k := cs1.UnsafeKey(); len(k) == 32 {
		copy(recv[:], k[:])
	} else {
		return zeroKey(), zeroKey(), zeroKey(), internalClose(), nil
	}
	if k := cs2.UnsafeKey(); len(k) == 32 {
		copy(send[:], k[:])
	} else {
		return zeroKey(), zeroKey(), zeroKey(), internalClose(), nil
	}
	return peer, send, recv, closeError{}, nil
}

func zeroKey() [32]byte { return [32]byte{} }

// readHandshakeMsg reads one binary WebSocket message within the handshake
// deadline.
func (s *Server) readHandshakeMsg(ctx context.Context, c *conn) ([]byte, error) {
	data, err := c.st.Read(ctx)
	if err != nil {
		return nil, err
	}
	if len(data) > protocol.MaxHandshake {
		return nil, errors.New("transport: handshake message too large")
	}
	return data, nil
}

// sendHandshakeMsg writes one server handshake message: the version and mode
// echo followed by the Noise message.
func (s *Server) sendHandshakeMsg(ctx context.Context, c *conn, mode byte, msg []byte) error {
	buf := make([]byte, 0, 2+len(msg))
	buf = append(buf, protocol.Version, mode)
	buf = append(buf, msg...)
	return c.st.Write(ctx, buf)
}

// handshakeReadErr maps a read failure inside the handshake to close
// information.
func (s *Server) handshakeReadErr(err error) closeError {
	var cerr *CloseError
	if errors.As(err, &cerr) {
		return closeError{code: websocket.StatusCode(cerr.Code), reason: cerr.Reason}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return closeError{code: protocol.CloseProtocol, reason: "handshake timeout"}
	}
	if errors.Is(err, context.Canceled) {
		return closeError{code: websocket.StatusCode(1001), reason: "going away"}
	}
	return closeError{code: protocol.CloseProtocol, reason: "bad handshake"}
}

// handshakeWriteErr maps a write failure inside the handshake to close
// information.
func (s *Server) handshakeWriteErr(err error) closeError {
	var cerr *CloseError
	if errors.As(err, &cerr) {
		return closeError{code: websocket.StatusCode(cerr.Code), reason: cerr.Reason}
	}
	if errors.Is(err, context.DeadlineExceeded) {
		return closeError{code: protocol.CloseProtocol, reason: "handshake timeout"}
	}
	return closeError{code: websocket.StatusCode(1011), reason: "write error"}
}
