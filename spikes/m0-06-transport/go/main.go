package main

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"

	"github.com/flynn/noise"
)

func main() {
	if len(os.Args) < 2 {
		usage()
		os.Exit(2)
	}
	var err error
	switch os.Args[1] {
	case "frame-vectors":
		err = cmdFrameVectors()
	case "handshake-vectors":
		err = cmdHandshakeVectors()
	case "selftest":
		err = cmdSelfTest()
	case "server":
		err = cmdServer(os.Args[2:])
	default:
		usage()
		os.Exit(2)
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "error: %v\n", err)
		os.Exit(1)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, "usage: m0-06 {frame-vectors|handshake-vectors|selftest|server}")
}

// deterministicReader yields deterministic 32-byte blocks derived from a seed
// and a counter, so key generation is reproducible without holding secrets.
type deterministicReader struct {
	seed  []byte
	count uint64
}

func (d *deterministicReader) Read(p []byte) (int, error) {
	for i := range p {
		block := sha256.Sum256(append(append([]byte{}, d.seed...), byte(d.count)))
		p[i] = block[i%32]
		if i%32 == 31 {
			d.count++
		}
	}
	return len(p), nil
}

func seedReader(seed string) *deterministicReader {
	return &deterministicReader{seed: []byte(seed)}
}

// cmdFrameVectors emits deterministic frame vectors (fixed key, nonce, AAD, and
// plaintext) for byte-for-byte comparison with the app-side implementation.
func cmdFrameVectors() error {
	keyBytes := sha256.Sum256([]byte("remotly-frame-vector-key"))

	vectors := []map[string]string{}
	cases := []struct {
		chType  byte
		chID    uint32
		payload string
	}{
		{ChannelCtrl, 0, "hello"},
		{ChannelTerm, 7, ""},
		{ChannelFile, 300, "a longer payload for a file channel"},
		{ChannelCtrl, 0, "wide chars: \xed\x95\x9c\xea\xb8\x80"},
	}
	for _, c := range cases {
		ck := newCipherKey(keyBytes, 0)
		frame, err := ck.SealFrame(c.chType, c.chID, []byte(c.payload))
		if err != nil {
			return err
		}
		vectors = append(vectors, map[string]string{
			"channel_type": fmt.Sprintf("%d", c.chType),
			"channel_id":   fmt.Sprintf("%d", c.chID),
			"plaintext":    hex.EncodeToString([]byte(c.payload)),
			"frame":        hex.EncodeToString(frame),
		})
	}
	return writeJSON(vectors)
}

// cmdHandshakeVectors emits deterministic split keys for XX and IK, computed
// with fixed static and ephemeral keys. The app side verifies the same key
// schedule through a live handshake and its own Noise implementation.
func cmdHandshakeVectors() error {
	type handshakeVector struct {
		Pattern    string `json:"pattern"`
		InitStatic string `json:"init_static_pub"`
		RespStatic string `json:"resp_static_pub"`
		InitSend   string `json:"init_send_key"`
		InitRecv   string `json:"init_recv_key"`
		RespSend   string `json:"resp_send_key"`
		RespRecv   string `json:"resp_recv_key"`
	}
	var out []handshakeVector

	for _, p := range []noise.HandshakePattern{patternXX, patternIK} {
		iStat, _ := noise.DH25519.GenerateKeypair(seedReader("init-static-" + p.Name))
		rStat, _ := noise.DH25519.GenerateKeypair(seedReader("resp-static-" + p.Name))

		// IK lets the initiator know the responder's static key in advance; XX
		// transmits it in-band, so the initiator must not pre-seed it.
		var peerStatic []byte
		if p.Name == "IK" {
			peerStatic = rStat.Public
		}

		init := &role{initiator: true, hs: mustState(noise.NewHandshakeState(noise.Config{
			CipherSuite: cs, Pattern: p, Initiator: true,
			Prologue: []byte("remotly-m0-06"), StaticKeypair: iStat,
			PeerStatic: peerStatic, Random: seedReader("init-ephemeral-" + p.Name),
		}))}
		resp := &role{initiator: false, hs: mustState(noise.NewHandshakeState(noise.Config{
			CipherSuite: cs, Pattern: p, Initiator: false,
			Prologue: []byte("remotly-m0-06"), StaticKeypair: rStat,
			Random: seedReader("resp-ephemeral-" + p.Name),
		}))}

		iSend, iRecv, rSend, rRecv, err := runHandshake(init, resp)
		if err != nil {
			return err
		}
		out = append(out, handshakeVector{
			Pattern:    p.Name,
			InitStatic: hex.EncodeToString(iStat.Public),
			RespStatic: hex.EncodeToString(rStat.Public),
			InitSend:   hex.EncodeToString(iSend.key[:]),
			InitRecv:   hex.EncodeToString(iRecv.key[:]),
			RespSend:   hex.EncodeToString(rSend.key[:]),
			RespRecv:   hex.EncodeToString(rRecv.key[:]),
		})
	}
	return writeJSON(out)
}

func mustState(hs *noise.HandshakeState, err error) *noise.HandshakeState {
	if err != nil {
		panic(err)
	}
	return hs
}

// cmdSelfTest runs Go-side negative and pairing checks: invalid frames, version
// rejection, and the XXpsk0 pairing handshake with a shared secret.
func cmdSelfTest() error {
	keyBytes := sha256.Sum256([]byte("selftest-key"))
	ck := newCipherKey(keyBytes, 0)

	// A valid frame round-trips.
	frame, err := ck.SealFrame(ChannelTerm, 1, []byte("ping"))
	if err != nil {
		return err
	}
	ck2 := newCipherKey(keyBytes, 0)
	ch, id, payload, err := ck2.OpenFrame(frame)
	if err != nil || ch != ChannelTerm || id != 1 || !bytes.Equal(payload, []byte("ping")) {
		return fmt.Errorf("round trip failed: %v %d %d %q", err, ch, id, payload)
	}

	// Invalid frames must be rejected without panic.
	badCases := map[string][]byte{
		"bad-channel":      {0xFF, 0x00, 0x10, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0},
		"truncated-varint": {ChannelCtrl, 0x80, 0x80, 0x80, 0x80, 0x80, 0x80},
		"oversized":        append([]byte{ChannelCtrl, 0x00}, appendVarint(nil, MaxPayloadLen+16+1)...),
		"short-tag":        {ChannelCtrl, 0x00, 0x10},
		"empty":            {},
	}
	for name, data := range badCases {
		probe := newCipherKey(keyBytes, 0)
		if _, _, _, err := probe.OpenFrame(data); err == nil {
			return fmt.Errorf("case %s: expected error, got none", name)
		}
		fmt.Printf("selftest: %s rejected\n", name)
	}

	// Tampered ciphertext must fail authentication.
	tampered := append([]byte{}, frame...)
	tampered[len(tampered)-1] ^= 0x01
	probe := newCipherKey(keyBytes, 0)
	if _, _, _, err := probe.OpenFrame(tampered); err == nil {
		return fmt.Errorf("tampered frame: expected authentication failure")
	}
	fmt.Println("selftest: tampered tag rejected")

	// Version rejection is asserted at the wire layer; verify the constant.
	if Version != 1 {
		return fmt.Errorf("unexpected version %d", Version)
	}

	// Pairing handshake with a shared secret (XXpsk0), Go-to-Go.
	return pairingSelfTest()
}

func pairingSelfTest() error {
	secret := sha256.Sum256([]byte("pairing-secret"))

	iStat, _ := genKey()
	rStat, _ := genKey()

	mk := func(initiator bool, static noise.DHKey, psk []byte) *noise.HandshakeState {
		cfg := noise.Config{
			CipherSuite: cs, Pattern: patternXX, Initiator: initiator,
			Prologue: []byte("remotly-pair"), PresharedKey: psk,
			PresharedKeyPlacement: 0, StaticKeypair: static,
		}
		hs, err := noise.NewHandshakeState(cfg)
		if err != nil {
			panic(err)
		}
		return hs
	}

	init := &role{initiator: true, hs: mk(true, iStat, secret[:])}
	resp := &role{initiator: false, hs: mk(false, rStat, secret[:])}
	if _, _, _, _, err := runHandshake(init, resp); err != nil {
		return fmt.Errorf("psk pairing failed: %w", err)
	}
	fmt.Println("selftest: XXpsk0 pairing handshake succeeded with matching secret")

	// A different secret must fail.
	other := sha256.Sum256([]byte("wrong-secret"))
	init2 := &role{initiator: true, hs: mk(true, iStat, other[:])}
	resp2 := &role{initiator: false, hs: mk(false, rStat, secret[:])}
	if _, _, _, _, err := runHandshake(init2, resp2); err == nil {
		return fmt.Errorf("mismatched secret: expected failure")
	}
	fmt.Println("selftest: mismatched pairing secret rejected")
	return nil
}

func writeJSON(v interface{}) error {
	enc := json.NewEncoder(os.Stdout)
	enc.SetIndent("", "  ")
	return enc.Encode(v)
}
