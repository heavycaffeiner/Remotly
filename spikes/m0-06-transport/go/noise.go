package main

import (
	"crypto/rand"
	"errors"

	"github.com/flynn/noise"
)

// cs is the single supported cipher suite: Noise_*_25519_ChaChaPoly_BLAKE2b,
// chosen because it is the suite the app-side native Noise libraries expose.
var cs = noise.NewCipherSuite(noise.DH25519, noise.CipherChaChaPoly, noise.HashBLAKE2b)

var (
	patternXX = noise.HandshakeXX
	patternIK = noise.HandshakeIK
)

// role drives a full interactive handshake from either side.
type role struct {
	hs        *noise.HandshakeState
	initiator bool
}

// writeMessage produces the next handshake message. When the handshake is
// complete it returns the split send and receive keys. Noise's Split returns
// c1 (initiator to responder) then c2 (responder to initiator) for both roles,
// so the responder swaps them to get its own send and receive keys.
func (r *role) writeMessage(payload []byte) (msg []byte, send, recv *CipherKey, done bool, err error) {
	out, cs1, cs2, err := r.hs.WriteMessage(nil, payload)
	if err != nil {
		return nil, nil, nil, false, err
	}
	if cs1 != nil && cs2 != nil {
		send, recv := r.split(cs1, cs2)
		return out, send, recv, true, nil
	}
	return out, nil, nil, false, nil
}

func (r *role) readMessage(msg []byte) (payload []byte, send, recv *CipherKey, done bool, err error) {
	out, cs1, cs2, err := r.hs.ReadMessage(nil, msg)
	if err != nil {
		return nil, nil, nil, false, err
	}
	if cs1 != nil && cs2 != nil {
		send, recv := r.split(cs1, cs2)
		return out, send, recv, true, nil
	}
	return out, nil, nil, false, nil
}

func (r *role) split(c1, c2 *noise.CipherState) (send, recv *CipherKey) {
	if r.initiator {
		return toCipherKey(c1), toCipherKey(c2)
	}
	return toCipherKey(c2), toCipherKey(c1)
}

func toCipherKey(s *noise.CipherState) *CipherKey {
	return newCipherKey(s.UnsafeKey(), s.Nonce())
}

// runHandshake drives two roles to completion and returns each side's view of
// the split. Both sides finish on the same (final) handshake message, one by
// writing and one by reading, so their completion flags must always agree.
func runHandshake(init, resp *role) (iSend, iRecv, rSend, rRecv *CipherKey, err error) {
	initTurn := true
	for {
		if initTurn {
			msg, iSend, iRecv, iDone, e := init.writeMessage(nil)
			if e != nil {
				return nil, nil, nil, nil, e
			}
			_, rSend, rRecv, rDone, e := resp.readMessage(msg)
			if e != nil {
				return nil, nil, nil, nil, e
			}
			if iDone != rDone {
				return nil, nil, nil, nil, errors.New("split mismatch")
			}
			if iDone {
				return iSend, iRecv, rSend, rRecv, nil
			}
		} else {
			msg, rSend, rRecv, rDone, e := resp.writeMessage(nil)
			if e != nil {
				return nil, nil, nil, nil, e
			}
			_, iSend, iRecv, iDone, e := init.readMessage(msg)
			if e != nil {
				return nil, nil, nil, nil, e
			}
			if iDone != rDone {
				return nil, nil, nil, nil, errors.New("split mismatch")
			}
			if iDone {
				return iSend, iRecv, rSend, rRecv, nil
			}
		}
		initTurn = !initTurn
	}
}

func genKey() (noise.DHKey, error) {
	return noise.DH25519.GenerateKeypair(rand.Reader)
}
