package protocol

import (
	"encoding/binary"
	"fmt"

	"golang.org/x/crypto/chacha20poly1305"
)

// Cipher seals and opens transport frames. The frame header is the AEAD
// associated data, so channel type, id, and length are authenticated. The
// interface keeps multiplexing tests independent of any specific crypto.
type Cipher interface {
	// SealFrame encrypts payload and returns the complete wire frame.
	SealFrame(chType byte, chID uint32, payload []byte) ([]byte, error)
	// OpenFrame authenticates and decrypts a wire frame.
	OpenFrame(data []byte) (chType byte, chID uint32, payload []byte, err error)
}

var _ Cipher = (*ChaCha)(nil)

// ChaCha is the production cipher: ChaCha20-Poly1305 with per-direction
// 64-bit nonce counters, the Noise split-key convention. The AEAD nonce is
// four zero bytes followed by the counter in little-endian order.
type ChaCha struct {
	sendKey [32]byte
	recvKey [32]byte
	sendNc  uint64
	recvNc  uint64
}

// NewChaCha builds a cipher from the two direction keys returned by a Noise
// Split. send is the key for outgoing frames, recv for incoming.
func NewChaCha(send, recv [32]byte) *ChaCha {
	return &ChaCha{sendKey: send, recvKey: recv}
}

func nonce12(counter uint64) []byte {
	var n [12]byte
	binary.LittleEndian.PutUint64(n[4:], counter)
	return n[:]
}

// SendKeys exposes the raw direction keys. It exists for vector tests and
// must never be called on a live connection's keys for logging.
func (c *ChaCha) SendKeys() [32]byte { return c.sendKey }

func (c *ChaCha) SealFrame(chType byte, chID uint32, payload []byte) ([]byte, error) {
	if int(chType) >= channelCount {
		return nil, fmt.Errorf("%w: %d", ErrBadChannel, chType)
	}
	if len(payload) > MaxPayloadLen {
		return nil, ErrFrameTooLarge
	}
	if c.sendNc == ^uint64(0) {
		return nil, ErrNonceExhausted
	}
	aead, err := chacha20poly1305.New(c.sendKey[:])
	if err != nil {
		return nil, err
	}
	header := []byte{chType}
	header = AppendVarint(header, uint64(chID))
	header = AppendVarint(header, uint64(len(payload)+chacha20poly1305.Overhead))
	ct := aead.Seal(nil, nonce12(c.sendNc), payload, header)
	c.sendNc++
	return append(header, ct...), nil
}

func (c *ChaCha) OpenFrame(data []byte) (chType byte, chID uint32, payload []byte, err error) {
	if len(data) < 1 {
		return 0, 0, nil, ErrFrameTruncated
	}
	chType = data[0]
	if int(chType) >= channelCount {
		return 0, 0, nil, fmt.Errorf("%w: %d", ErrBadChannel, chType)
	}
	idv, n, err := ReadVarint(data[1:])
	if err != nil {
		return 0, 0, nil, err
	}
	// A 5-byte varint can hold 35 bits; only the low 32 are a channel id.
	if idv > uint64(^uint32(0)) {
		return 0, 0, nil, ErrVarintTooLong
	}
	ctLen, m, err := ReadVarint(data[1+n:])
	if err != nil {
		return 0, 0, nil, err
	}
	headerEnd := 1 + n + m
	if ctLen < chacha20poly1305.Overhead {
		return 0, 0, nil, ErrFrameTooSmall
	}
	if ctLen > MaxPayloadLen+chacha20poly1305.Overhead {
		return 0, 0, nil, ErrFrameTooLarge
	}
	if uint64(len(data)-headerEnd) != ctLen {
		return 0, 0, nil, ErrFrameTruncated
	}
	if c.recvNc == ^uint64(0) {
		return 0, 0, nil, ErrNonceExhausted
	}
	aead, err := chacha20poly1305.New(c.recvKey[:])
	if err != nil {
		return 0, 0, nil, err
	}
	plain, err := aead.Open(nil, nonce12(c.recvNc), data[headerEnd:], data[:headerEnd])
	if err != nil {
		return 0, 0, nil, ErrDecrypt
	}
	c.recvNc++
	return chType, uint32(idv), plain, nil
}
