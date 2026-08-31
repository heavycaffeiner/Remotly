package main

import (
	"crypto/cipher"
	"encoding/binary"
	"errors"
	"fmt"

	"golang.org/x/crypto/chacha20poly1305"
)

// Wire protocol constants shared with the app-side implementation.
const (
	Version = 1

	ChannelCtrl  = 0
	ChannelTerm  = 1
	ChannelFile  = 2
	channelCount = 3 // types >= channelCount are reserved and rejected

	MaxPayloadLen = 1 << 20 // 1 MiB
	MaxFrameLen   = MaxPayloadLen + chacha20poly1305.Overhead + 11
	MaxHandshake  = 65535 // Noise spec maximum message size
)

var (
	errVarintTooLong   = errors.New("varint too long")
	errVarintTruncated = errors.New("varint truncated")
	errBadChannel      = errors.New("unsupported channel type")
	errFrameTooLarge   = errors.New("frame exceeds size limit")
	errFrameTooSmall   = errors.New("frame smaller than tag")
	errDecrypt         = errors.New("authentication failed")
	errNonceExhausted  = errors.New("nonce exhausted")
)

// appendVarint encodes v as unsigned LEB128. Callers must keep v < 2^32.
func appendVarint(dst []byte, v uint64) []byte {
	for {
		b := byte(v & 0x7f)
		v >>= 7
		if v != 0 {
			b |= 0x80
		}
		dst = append(dst, b)
		if v == 0 {
			return dst
		}
	}
}

// consumeVarint decodes an unsigned LEB128 from the front of b, bounded to 5
// bytes so a crafted prefix cannot allocate an unbounded integer.
func consumeVarint(b []byte) (uint64, int, error) {
	var v uint64
	for i := 0; i < len(b); i++ {
		if i == 5 {
			return 0, 0, errVarintTooLong
		}
		c := b[i]
		v |= uint64(c&0x7f) << (7 * i)
		if c&0x80 == 0 {
			return v, i + 1, nil
		}
	}
	return 0, 0, errVarintTruncated
}

// CipherKey is one direction of a split Noise transport state: a 32-byte key
// and a 64-bit nonce counter. The AEAD nonce is four zero bytes followed by the
// counter in little-endian order, matching the Noise ChaChaPoly convention.
type CipherKey struct {
	key   [32]byte
	nonce uint64
}

func newCipherKey(key [32]byte, nonce uint64) *CipherKey {
	return &CipherKey{key: key, nonce: nonce}
}

func (c *CipherKey) aead() (cipher.AEAD, error) {
	return chacha20poly1305.New(c.key[:])
}

func nonce12(counter uint64) []byte {
	var n [12]byte
	binary.LittleEndian.PutUint64(n[4:], counter)
	return n[:]
}

// SealFrame encrypts payload and returns the wire frame. The header is the
// authenticated data, so a tampered channel type, id, or length fails decryption.
func (c *CipherKey) SealFrame(chType byte, chID uint32, payload []byte) ([]byte, error) {
	if int(chType) >= channelCount {
		return nil, errBadChannel
	}
	if len(payload) > MaxPayloadLen {
		return nil, errFrameTooLarge
	}
	aead, err := c.aead()
	if err != nil {
		return nil, err
	}
	if c.nonce == ^uint64(0) {
		return nil, errNonceExhausted
	}
	header := append([]byte{chType}, appendVarint(nil, uint64(chID))...)
	header = appendVarint(header, uint64(len(payload)+chacha20poly1305.Overhead))
	ct := aead.Seal(nil, nonce12(c.nonce), payload, header)
	c.nonce++
	return append(header, ct...), nil
}

// OpenFrame authenticates and decrypts a wire frame. It rejects unsupported
// channel types, malformed varints, and over-large lengths before dispatching.
func (c *CipherKey) OpenFrame(data []byte) (chType byte, chID uint32, payload []byte, err error) {
	if len(data) < 1 {
		return 0, 0, nil, errFrameTruncated
	}
	chType = data[0]
	if int(chType) >= channelCount {
		return 0, 0, nil, errBadChannel
	}
	chIDv, n, err := consumeVarint(data[1:])
	if err != nil {
		return 0, 0, nil, err
	}
	ctLen, m, err := consumeVarint(data[1+n:])
	if err != nil {
		return 0, 0, nil, err
	}
	headerEnd := 1 + n + m
	if ctLen < chacha20poly1305.Overhead {
		return 0, 0, nil, errFrameTooSmall
	}
	if ctLen > MaxPayloadLen+chacha20poly1305.Overhead {
		return 0, 0, nil, errFrameTooLarge
	}
	if uint64(len(data)-headerEnd) != ctLen {
		return 0, 0, nil, errFrameTruncated
	}
	aead, err := c.aead()
	if err != nil {
		return 0, 0, nil, err
	}
	if c.nonce == ^uint64(0) {
		return 0, 0, nil, errNonceExhausted
	}
	plain, err := aead.Open(nil, nonce12(c.nonce), data[headerEnd:], data[:headerEnd])
	if err != nil {
		return 0, 0, nil, errDecrypt
	}
	c.nonce++
	return chType, uint32(chIDv), plain, nil
}

var errFrameTruncated = errors.New("frame truncated")

func (c *CipherKey) String() string {
	return fmt.Sprintf("key=%x nonce=%d", c.key, c.nonce)
}
