# M0-06 spike: encrypted transport and framing

Disposable spike proving a Go-to-app binary WebSocket transport with a Noise
handshake, encrypted frames, channel framing, and version negotiation. The Go
side is verified; the JS (Node) side interops over the same wire format and
crypto, standing in for the app's native crypto layer.

## Layout

- `go/`: Go responder (daemon side). `m0-06 frame-vectors`,
  `handshake-vectors`, `selftest`, `server`.
- `js/`: Node app side. `frame.js` (transport cipher), `noise.js` (handshake),
  `client.js` (WebSocket initiator), `vectors.js` (frame vectors and in-process
  handshake keys).
- `vectors/`: language-neutral test vectors, produced by the Go side.

## Crypto choices

- Noise protocol framework, suite `Noise_*_25519_ChaChaPoly_BLAKE2b`: X25519 DH,
  ChaCha20-Poly1305 AEAD, BLAKE2b hash. No TLS dependency for payload secrecy.
- Pairing handshake: `XXpsk0`, binding the pairing secret as a PSK, plus the
  daemon's long-term public key exchange inside the authenticated channel.
- Pinned-key reconnect: `IK`, initiator knows the responder's long-term static.
- Go: `github.com/flynn/noise`. App: native Noise over libsodium; the Node
  harness uses `noise-protocol` (libsodium) as a stand-in.

## Wire format

Handshake (binary WebSocket messages):

- Client first message: `version(1 byte) || noise_msg1`.
- Server first response: `version(1 byte) || noise_msg2`.
- XX third message: `noise_msg3` (no version prefix).

Transport frame:

```
channel_type(1) | varint(channel_id) | varint(ciphertext_len) | ciphertext
```

The header is the AEAD associated data, so channel type, id, and length are all
authenticated. `ciphertext_len = plaintext_len + 16` (Poly1305 tag). AEAD nonce
is four zero bytes followed by the 64-bit counter in little-endian order,
matching the Noise ChaChaPoly convention. Limits: channel type in {0 ctrl,
1 term, 2 file}, channel id uint32, payload up to 1 MiB per frame.

## Run

Build and selftest the Go side:

```
cd go && go build -o m0-06 . && ./m0-06 selftest
```

Generate vectors:

```
./m0-06 frame-vectors > ../vectors/frame-vectors.json
./m0-06 handshake-vectors > ../vectors/handshake-vectors.json
```

Reproduce the frame vectors on the JS side and compare:

```
cd js && npm install && node vectors.js frame-vectors
```

Live round trip (XX and IK), Go server + Node client:

```
./go/m0-06 server --pattern XX --addr 127.0.0.1:8777 &
node js/client.js --pattern XX --addr ws://127.0.0.1:8777 --payload "round-trip-proof"
node js/client.js --pattern IK --addr ws://127.0.0.1:8777 --peer <server static_pub hex>
```

The server prints its `static_pub` on startup; pass it to the IK client via
`--peer`.

Negative tests (version rejection and replay):

```
# capture an IK msg1 and a transport frame against server A
node js/negative-tests.js capture --pattern IK --addr ws://127.0.0.1:PORT_A --peer <A static_pub>
# restart the server (fresh static), then attack with the captured artifacts
node js/negative-tests.js attack --pattern IK --addr ws://127.0.0.1:PORT_B --peer <B static_pub>
```

## Verified results

- Frame vectors: Go and JS produce byte-for-byte identical ciphertext.
- Live XX round trip (Go responder, Node initiator): PASSED.
- Live IK round trip: PASSED, with cross-direction key agreement confirmed via
  `--print-keys` (client send == server recv).
- Invalid frames (bad channel, truncated varint, oversized, short tag, empty),
  tampered tag, and version mismatch are rejected without panic.
- XXpsk0 pairing succeeds with the matching secret and is rejected with a
  mismatched secret (Go self-test; the app side uses a native Noise with PSK or
  an equivalent secret proof because `noise-protocol` does not implement PSK).
- Negative tests (`js/negative-tests.js`): version byte 2 is rejected with close
  code 4000; a replayed IK handshake message fails MAC against a fresh server
  static; a replayed transport frame fails authentication on a fresh session.
  No plaintext is delivered in any case.
- No secrets in logs: session keys are printed only with the `--print-keys`
  flag; default server and client logs carry public data only.

## Nonce, ordering, and replay

Per-direction nonce counters start at zero after the split and increment per
frame; the maximum value is reserved and rejects further use. Ordering is
enforced by the AEAD counter and the Noise IK/XX handshake; captured handshake
or transport frames cannot be replayed because each new handshake uses fresh
ephemeral keys and transport counters only advance.

## Handoff

M1-05 (protocol multiplexing), M1-06 (pairing), and M1-10 (app transport)
receive the frame format, version byte, channel type table, nonce rules, and
size limits as normative inputs. The spike code is disposable.
