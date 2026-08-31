# Remotly protocol, version 1

**Protocol version: 1.0.0. Status: FROZEN (2026-08-20).** This is the v1
release contract. The wire surfaces it defines are stable: identifiers, field
names, bounds, error codes, and compatibility behavior are fixed for all v1
implementations. A change to any of them requires a new major version (2), not
an edit here. Additive, optional fields that an older peer ignores are the only
thing a later v1.x patch may introduce, and only with a note in this header.

This document is the normative wire specification for the daemon and app.
It is language-neutral: both the Go daemon and the app implement against it and
are tested against the same vectors (section 12). Crypto and framing decisions
are inherited from ADR 0003 and the M0-06 spike. Sections 1 through 8 describe
the M1 base; section 9 lists the M2 additions, section 10 the relay, and
section 11 the M4 additions, all of which are additive and do not change any
earlier rule.

## 1. Version and compatibility

- The protocol version is the integer `1`, carried as one byte.
- The server accepts only the version it supports. Any other version byte is
  rejected before the Noise handshake with close code `4000` and reason
  `unsupported protocol version N`.
- There is no downgrade path: a newer or older peer always fails closed with a
  clear, bounded error. Version negotiation is the single version byte; nothing
  negotiates silently.

## 2. Connection setup

Transport is a binary WebSocket. All messages on the socket are binary. A text
frame at any point is a protocol error (close `4002`).

### 2.1 Handshake

Cipher suite: `Noise_*_25519_ChaChaPoly_BLAKE2b` (X25519, ChaCha20-Poly1305,
BLAKE2b). Prologue: the ASCII string `remotly-v1`. The prologue binds the
protocol version into the transcript, so a version change breaks the handshake
before any application data.

Two handshake modes share one endpoint:

| Mode byte | Pattern | Use |
| --- | --- | --- |
| `0` | `IK` | Reconnect of an already paired device |
| `1` | `XXpsk0` | First-time pairing; the pairing secret is the PSK at placement 0 |

The client sends the first handshake message as one binary WebSocket message:

```
version(1) | mode(1) | params | noise_msg1
```

- `version` must be `1`.
- `mode` is `0` or `1`. Any other value closes with `4002` reason `bad mode`.
- `params`: empty for mode `0`. For mode `1`:
  `varint(token_id_len) | token_id`, where `token_id_len` is in `1..64`.
  The server looks up the token before starting the handshake. Unknown,
  expired, or already consumed tokens close with `4003` and a reason from
  `token_unknown`, `token_expired`, `token_used`. The token secret is never
  transmitted in the clear; it enters only as the Noise PSK.
- `noise_msg1` is at most `65535` bytes (the Noise spec maximum).

The server replies with one binary message:

```
version(1) | mode(1) | noise_msg2
```

echoing the accepted version and mode. For mode `1` there is a third client
message, `noise_msg3`, with no prefix. For mode `0` the handshake completes
after `noise_msg2`.

Handshake rules:

- A connection has a 10 second deadline to finish the handshake; expiry closes
  with `4002` reason `handshake timeout`.
- IK reconnect: the initiator (app) pre-seeds the daemon's pinned long-term
  static key. The daemon does not verify the initiator's key in Noise; it
  verifies it in the `hello` message (section 3.2).
- Pairing: the daemon completes `XXpsk0` with the token's secret. Completing
  the handshake proves knowledge of the secret, so the daemon claims the token
  atomically at that point. A second concurrent or later attempt with the same
  token is rejected with `4003` `token_used` before application data is
  accepted.
- The daemon's and app's long-term X25519 keypairs are static keys in Noise.
  After `Split()`, each side sends with `c1` (initiator direction) or `c2`
  (responder direction) and keeps a per-direction 64-bit nonce counter.

### 2.2 Post-handshake authentication

The first control message on the control channel must be a `hello` request
(section 3). The server:

- pairing mode: stores the device record (name plus the app's long-term public
  key) on success. A public key that is already paired or revoked is rejected
  with `device_duplicate` or `device_revoked`.
- IK mode: looks the app's public key up in the device store. Unknown keys are
  rejected with `device_unknown`, revoked keys with `device_revoked`.

Any other request before a successful `hello` closes the connection with `4002`
reason `hello required`. A failed or missing `hello` closes with `4001`.

## 3. Channels

One connection multiplexes logical channels. Every transport frame belongs to
exactly one channel; the channel fields are part of the frame's authenticated
data, so they cannot be tampered.

| Channel type | Name | Direction | M1 status |
| --- | --- | --- | --- |
| `0` | `ctrl` | bidirectional JSON | in use, exactly one, id `0` |
| `1` | `term` | bidirectional raw bytes | in use, one per attached session |
| `2` | `file` | reserved | rejected: close `4002` reason `channel type not enabled` |

### 3.1 Channel identity

- Channel ids are connection-local `uint32`.
- The control channel is always id `0`, type `0`. A control-type frame with any
  other id, or a non-control frame with id `0`, is a protocol error (`4002`).
- Id allocation is split so both sides can allocate without coordination: the
  daemon allocates odd ids starting at `1`; an app-initiated channel (no M1
  consumer) would allocate even ids starting at `2`. Ids are never reused
  within a connection.
- M1 creates term channels only from the daemon, as the result of a
  `session.attach` request. An app frame on a channel id the daemon never
  assigned is a protocol error (`4002` reason `unknown channel`).

### 3.2 Control channel (type 0)

The control channel carries JSON objects, UTF-8, at most `65536` bytes per
frame. The envelope is:

```json
{ "id": <number>, "type": "<string>", ... }
```

- `id` is present on requests and responses, absent on notifications. It is an
  unsigned integer below `2^53` (JavaScript-safe). Request ids are unique per
  connection per sender; a duplicate closes with `4002` reason
  `duplicate request id`.
- `type` selects the message. Unknown types get an `unknown_type` error
  response (not fatal), so a newer peer can probe safely. Unknown fields inside
  a known type are rejected with an `invalid_request` error response.
- A response repeats the request's `id` and `type`, and carries either result
  fields (success) or `"error": { "code": <string>, "message": <string> }`.
  `message` is bounded to 512 bytes and must never contain terminal content or
  secrets.

#### Requests

`hello` (must be first):

```json
{ "id": 1, "type": "hello",
  "device_name": "<1..100 chars>", "device_pub": "<base64url, 32 bytes>" }
```

Response:

```json
{ "id": 1, "type": "hello", "daemon_name": "<string>",
  "daemon_pub": "<base64url, 32 bytes>" }
```

The app must verify `daemon_pub` against its pinned key on every connection,
including pairing, and treat a mismatch as fatal.

`session.create`:

```json
{ "id": 2, "type": "session.create", "kind": "shell" | "agent",
  "title": "<optional, <=200>", "command": "<agent only, <=4096>",
  "cwd": "<optional, absolute>", "cols": <1..1000>, "rows": <1..1000> }
```

Omitted `cols`/`rows` default to `80`/`24`. Response:
`{ "id": 2, "type": "session.create", "session": <Meta> }`.

`session.list`:

```json
{ "id": 3, "type": "session.list" }
```

Response: `{ "id": 3, "type": "session.list", "sessions": [<Meta>...] }`.
Only live sessions are listed; ended sessions are reported via
`session.update`.

`session.attach`:

```json
{ "id": 4, "type": "session.attach", "session_id": "<id>" }
```

Response: `{ "id": 4, "type": "session.attach", "channel_id": <odd uint32> }`.
From then on, the daemon sends the session's retained scrollback and then live
output on that term channel, in order. The app sends terminal input on the
same channel id.

`session.detach`:

```json
{ "id": 5, "type": "session.detach", "channel_id": <id> }
```

Response: `{ "id": 5, "type": "session.detach" }`. The daemon then closes the
term channel with reason `detached`.

`session.resize`:

```json
{ "id": 6, "type": "session.resize", "session_id": "<id>",
  "cols": <1..1000>, "rows": <1..1000> }
```

Response: `{ "id": 6, "type": "session.resize" }`.

`session.kill`:

```json
{ "id": 7, "type": "session.kill", "session_id": "<id>" }
```

Response: `{ "id": 7, "type": "session.kill" }`. Idempotent: killing an
already ended session succeeds.

#### Meta object

```json
{ "id": "<64 hex chars>", "title": "<string>", "kind": "shell" | "agent",
  "command": "<string>", "cwd": "<string>", "cols": <int>, "rows": <int>,
  "created_at": "<RFC 3339>", "last_activity": "<RFC 3339>",
  "running": <bool>, "exit": null | { "code": <int>, "signal": "<string>" } }
```

`exit` is non-null only when `running` is false.

#### Notifications

Notifications have no `id` and expect no response.

`channel.close`:

```json
{ "type": "channel.close", "channel_id": <id>,
  "reason": "session_exited" | "overflow" | "detached" | "closed" }
```

Sent after the last frame of the channel, in order. `session_exited`: the
session process ended. `overflow`: the app read too slowly and was dropped.
`detached`: the app requested detach. `closed`: connection teardown (sent only
if the connection is still usable to say so; a hard drop implies it).

`session.update`:

```json
{ "type": "session.update", "session": <Meta> }
```

Sent when a listed session changes state in M1, which is the transition to
exited.

`session.update` and `channel.close` are independent. When a session exits the
daemon sends both a `channel.close` (`session_exited`) for each attached
channel and one `session.update`; which of the two a connection receives first
is unspecified. A channel's `channel.close` stays ordered after that channel's
last output frame.

### 3.3 Term channels (type 1)

- Payloads are raw bytes, at most `1048576` (1 MiB) per frame.
- Daemon to app: output. A freshly attached channel first delivers the
  retained scrollback, then live output, each byte exactly once, in order.
- App to daemon: committed terminal input, at most 1 MiB per frame.
- Closing a term channel: the daemon delivers all queued output in order, then
  sends the `channel.close` notification. The notification is held back until
  the channel's own queue is empty, so no output frame can arrive after its
  close notice.
- Backpressure: each channel has a bounded send queue (256 frames or 8 MiB,
  whichever fills first). When the queue of a term channel is full, the daemon
  drops the attachment for that session on that connection (reason `overflow`),
  discards the queue, and sends `channel.close` with reason `overflow`. One
  slow channel never blocks the control channel or other term channels.
- The session itself is unaffected by channel or connection loss: the PTY keeps
  running, and a new attachment replays the scrollback.

## 4. Frame format

Inherited from ADR 0003 and verified by the M0-06 vectors:

```
channel_type(1) | varint(channel_id) | varint(ciphertext_len) | ciphertext
```

- `channel_type` is `0`, `1`, or `2`; other values are rejected.
- `varint` is unsigned LEB128, at most 5 bytes (uint32). A longer varint is a
  protocol error; it must never be used to allocate memory.
- The three header fields are the AEAD associated data.
- `ciphertext_len = plaintext_len + 16`. Payloads are capped at 1 MiB.
- The AEAD nonce is four zero bytes followed by the 64-bit counter in
  little-endian order. Counters are per direction, start at zero after the
  handshake split, and increment per frame. The maximum counter value is
  reserved and must be rejected.
- A frame whose remaining bytes do not match `ciphertext_len`, whose
  `ciphertext_len` is below the 16-byte tag, or that fails authentication is a
  protocol error (`4002`).

## 5. Limits

| Quantity | Limit |
| --- | --- |
| Protocol version | `1` only |
| Handshake message | 65535 bytes |
| Handshake deadline | 10 seconds |
| Control frame (JSON) | 65536 bytes |
| Frame payload | 1 MiB |
| Varint | 5 bytes (uint32) |
| Concurrent connections | 16 |
| Channels per connection | 64 |
| Channel send queue | 256 frames or 8 MiB |
| `device_name` | 1..100 bytes, UTF-8, no control characters |
| `session_id` | 64 hex characters |
| Request id | below 2^53, unique per connection per sender |
| WebSocket ping interval | 30 seconds, 10 second pong deadline |
| Pairing token lifetime | 5 minutes, single use |

## 6. WebSocket close codes

| Code | Meaning |
| --- | --- |
| `4000` | Unsupported protocol version |
| `4001` | Authentication failure: unknown, revoked, duplicate device, or bad hello |
| `4002` | Protocol error: framing, JSON, channel, ordering, or timeout violation |
| `4003` | Pairing token error: unknown, expired, or already used |
| `4004` | Resource limit: too many connections or channels |

Close reasons are short deterministic strings. No close reason carries
terminal content, keys, or secrets.

## 7. Connection state machine

```
accept -> handshaking -> authenticating -> active -> closed
              |              |               |
              +--------------+---------------+-> closed (error, per table above)
```

- `handshaking`: version, mode, token lookup, Noise messages.
- `authenticating`: waiting for `hello` and its validation.
- `active`: control dispatch and channel traffic.
- Every close releases all connection-owned state: channels, queues,
  attachments. Sessions are never closed by a connection close.

## 8. Security invariants

- All decoded bytes are hostile until the AEAD tag accepts them; parsing is
  bounded and must not panic, allocate from attacker-declared sizes, or open
  unbounded channels.
- No key material, pairing secret, or terminal content appears in logs or in
  error strings on either side.
- Replay is impossible: handshake messages bind fresh ephemerals, and transport
  frames bind per-direction advancing counters.
- The daemon listens on the LAN only while a pairing token is active or at
  least one device is paired; otherwise it is loopback-only.

## 9. M2 additions

M2 adds replay cursors, post-exit session retention, presets, and terminal
events. Every addition is optional at the wire level: an M1 client that omits
the new request fields and ignores the new response fields and notification
types keeps working, and an M2 client talking to an M1 daemon degrades to M1
behavior (a `resume_from` sent to an M1 daemon is rejected with
`invalid_request`, which the client treats as "no cursor support" and retries
without a cursor).

### 9.1 Replay cursor

Each session's output is a cumulative byte stream; the retained scrollback is
a sliding window of it. A replay cursor is a byte offset in that stream, an
unsigned integer below `2^53` (JavaScript-safe). Offsets at or above `2^53`
are rejected with `cursor_out_of_range`.

`session.attach` accepts one new optional field:

```json
{ "id": 4, "type": "session.attach", "session_id": "<id>",
  "resume_from": <0..2^53-1> }
```

`resume_from` is the first byte offset the app still needs. The response gains
two required fields:

```json
{ "id": 4, "type": "session.attach", "channel_id": <odd uint32>,
  "continuity": "full" | "gapless" | "gap",
  "replayed_from": <0..2^53-1> }
```

- `replayed_from` is the offset of the first replayed byte. For `full` it is
  the window start (or zero); for `gapless` it equals `resume_from`; for
  `gap` it is the window start.
- `full`: no cursor was sent; the whole retained scrollback is replayed.
- `gapless`: the cursor was inside the retained window; the app receives every
  byte from the cursor forward exactly once.
- `gap`: the cursor is older than the retained window; replay starts at the
  window's oldest byte and the bytes in between are lost. The app must show a
  continuity-loss state and must never render the stream as gap-free.

The replay/live boundary is exact per channel: the daemon splits any frame
that would mix replayed and live bytes so no term frame contains both, and it
sends one `channel.replay_complete` notification per channel after the last
replayed byte (immediately if the replay is empty).

`channel.replay_complete`:

```json
{ "type": "channel.replay_complete", "channel_id": <id>, "offset": <0..2^53-1> }
```

`offset` is the resume cursor at the boundary: `replayed_from` plus the
number of replayed bytes. It marks the boundary; it is not an ordering
guarantee. The control channel is prioritized over term channels by the
multiplexer, so the notification can arrive before the final replayed term
bytes. A client that tracks its own cursor as `replayed_from` plus the term
bytes received on the channel gets the exact cursor regardless of ordering;
`offset` is a cross-check.

Malformed values (missing `offset`, `offset` at or above `2^53`, missing
`channel_id`) are protocol errors on both sides: the connection closes with
`4002`.

### 9.2 Post-exit retention

`session.list` also lists sessions that exited within the retention window
(default 300 seconds, capped at 64 sessions; the oldest is evicted first). A
retained session has `running: false` and a non-null `exit`; it stays
attachable, and attaching replays its final scrollback. `session.kill` on a
retained session is a no-op success. `session.update` fires on the exit
transition as in M1; the session leaves the list when the retention window
expires.

`Meta` gains one optional field:

```json
"preview": "<plain text, <=120 bytes, absent when empty>"
```

`preview` is the last retained output line as plain text: escape sequences
and C0/C1 control characters stripped, whitespace collapsed. It is terminal
content and must never reach logs or analytics.

### 9.3 Presets

`preset.list`:

```json
{ "id": 8, "type": "preset.list" }
```

Response: `{ "id": 8, "type": "preset.list", "presets": [<Preset>...] }` with
an empty array when none are configured.

```json
{ "name": "<1..50 chars>", "command": "<1..4096 chars>",
  "icon_hint": "<0..32 chars>" }
```

Presets are daemon configuration (at most 16). A preset is not a session type:
the app resolves it into a `session.create` with `kind: "agent"`, the preset
`command`, and the preset `name` as title.

### 9.4 Terminal events

`session.event` is a daemon-to-app notification broadcast to every connection
on the connection's device, deduplicated by the app on `seq`:

```json
{ "type": "session.event", "session_id": "<id>", "seq": <1..2^53-1>,
  "kind": "bell" | "pattern", "pattern": "<name, pattern kind only>",
  "text": "<plain text, <=120 bytes, may be absent>", "ts": <unix seconds> }
```

- `seq` is a per-session counter starting at 1. The app accepts an event only
  when its `seq` is greater than the last seen `seq` for that session; events
  arrive at most once per connected app under this rule.
- `bell`: a live BEL (0x07) byte, suppressed per session for 2 seconds.
- `pattern`: a configured RE2 output-pattern match, suppressed per rule per
  session for 1 second. `pattern` is the rule's name, `text` is the matched
  line as plain text.
- `ts` is Unix time in seconds.
- Events are generated from live output only: replayed scrollback never emits
  events, and a reconnected client sees no events for bytes it already has.
- A full notification queue drops the event, not the connection.
- `text` is terminal content; it must never reach logs or analytics. An event
  with a `text` over 120 bytes, a missing or out-of-range `seq` or `ts`, an
  unknown `kind`, or a `bell` carrying a `pattern` is a protocol error
  (`4002`) on the receiving side.
- Pattern configuration is bounded (at most 32 rules, name at most 50 bytes,
  pattern at most 256 bytes) and compiled with RE2 semantics: matching runs
  in linear time over a 16 KiB window of the output stream, and invalid UTF-8
  is replaced with U+FFFD before matching.

### 9.5 M2 limits

| Quantity | Limit |
| --- | --- |
| Replay cursor (`resume_from`, `replayed_from`, `offset`) | below `2^53` |
| `Meta.preview`, event `text` | 120 bytes, plain text |
| Presets | 16, name 1..50, command 1..4096, icon hint 0..32 |
| Output-pattern rules | 32, name 1..50, pattern 1..256 (RE2) |
| Event match window | 16 KiB |
| Event `seq` | 1..2^53-1, per session |
| Retained exited sessions | 300 seconds default, 64 max |

New error code: `cursor_out_of_range` (a `resume_from` at or above `2^53`).

## 10. Relay protocol (M3, v1)

The relay is an opaque TCP service. It routes Remotly transport messages
between a daemon and one or more apps. It never parses Remotly messages,
never stores them, and keeps no persistent state: an in-memory registry,
live connections, and counters are its entire state.

Transport: plain TCP. TLS termination, if any, is an operator concern
(reverse proxy in front of the relay). The wire below is the contract.

### 10.1 Messages

Every message starts with a one-byte type.

```
0x01 join          version(1) | role(1) | relay_id(16)
0x02 join_ack      status(1)
0x03 frame         varint(len) | bytes(len)
0x04 keepalive     (no payload)
0x05 end           code(2, BE) | reason_len(1) | reason
0x06 stream_open   stream_id(4, BE)
0x07 stream_frame  stream_id(4, BE) | varint(len) | bytes(len)
0x08 stream_close  stream_id(4, BE) | code(2, BE) | reason_len(1) | reason
0x09 stream_ping   stream_id(4, BE)
0x0a stream_pong   stream_id(4, BE)
```

- `varint` is unsigned LEB128, at most 5 bytes.
- `join` is the first message on every connection, exactly 18 payload bytes.
  `version` must be 1. `role` is 0 (daemon) or 1 (app).
- `relay_id` is 16 bytes, opaque to the relay. It is the first 16 bytes of
  the daemon's long-term X25519 public key. A daemon computes it from its own
  identity; an app derives it from the daemon public key it pinned at
  pairing. The relay never sees the full key and cannot link a relay id to
  anything else.
- `join_ack.status` is 0 on success. On any failure the relay sends `end`
  instead of an ack and closes the connection.
- `frame` carries one opaque Remotly transport message (a handshake message
  or one sealed `frame`). `len` must be in [19, 1048704] (1 MiB + 128).
- `keepalive` is used for liveness. Any inbound message, including
  keepalive, resets idle timers. The relay echoes a daemon keepalive back to
  the daemon (which ignores it). An app keepalive is consumed for liveness and
  is not echoed: the relay sends a keepalive to an app only when the daemon
  pings that stream, and the app answers every keepalive it receives, so that
  answer becomes the stream's pong.
- `end` terminates the connection. The sender writes `end`, then closes the
  TCP connection.
- `stream_open`, `stream_frame`, `stream_close`, `stream_ping`, and
  `stream_pong` occur only on a daemon connection and refer to a stream
  assigned by the relay for one attached app.
- Unknown type bytes, malformed lengths, and out-of-range values are
  protocol errors: the relay sends `end` with code 3006 and closes.
- Stream messages that reference an unknown stream id are dropped and
  counted. A stream may have been closed moments earlier, so in-flight
  messages for it are expected and never tear down the daemon
  connection.

### 10.2 Roles and registration

- A daemon connection is a registration for its `relay_id`. One
  registration per relay id. If a daemon joins while a registration exists,
  the new registration wins: the old daemon connection receives `end` 3002
  (`replaced`), and every app attached to the old registration receives
  `end` 3007 (`peer_gone`).
- An app connection joins with the `relay_id` of the daemon it wants to
  reach. If no registration exists, the relay sends `end` 3001 (`no_daemon`)
  and holds nothing; the app retries.
- When a daemon connection ends for any reason, its registration is removed
  and all attached apps receive `end` 3007.
- When an app connection ends, the relay sends `stream_close` with code 1006
  to the daemon and frees the pair.

### 10.3 Streams

- On an app join, the relay allocates a `stream_id` on the daemon
  connection (32-bit big-endian, starting at 1, never reused within the
  daemon connection's lifetime) and sends `stream_open`.
- App `frame`s are forwarded to the daemon as `stream_frame(stream_id, ...)`
  and daemon `stream_frame`s are forwarded to the app as `frame`. Ordering is
  preserved per pair.
- Remotly close codes travel between endpoints unchanged: an app `end`
  becomes `stream_close` to the daemon, and a daemon `stream_close` becomes
  `end` to the app.
- `stream_ping` asks the peer for a liveness answer on that stream. The
  peer answers with `stream_pong` for the same `stream_id`; any `frame` or
  `stream_frame` on that stream also counts as an answer. The relay
  translates a daemon `stream_ping` into a keepalive on the app connection and
  marks the stream as awaiting an answer; the app's next keepalive (its answer
  to that keepalive) is converted into `stream_pong` for the daemon.
- When a stream's Remotly session ends at the daemon (the daemon closes the
  transport connection for that stream), the daemon sends `stream_close`
  with the Remotly close code and reason.

### 10.4 Close codes

Remotly codes (section 6) pass through the relay untouched:

| Code | Meaning (unchanged from section 6) |
| ---- | ---------------------------------- |
| 1000 | normal closure |
| 1001 | going away |
| 1006 | abnormal closure (TCP end with no close message) |
| 4000 | protocol error |
| 4001 | authentication failure |
| 4002 | protocol error, handshake or crypto |
| 4003 | pairing token already used |
| 4004 | resource limit |

Relay-generated codes, used only by the relay in `end` and `stream_close`:

| Code | Name | Meaning |
| ---- | ---- | ------- |
| 3001 | no_daemon | app joined with no registered daemon |
| 3002 | replaced | daemon registration superseded |
| 3003 | limit | a resource limit was hit (queue, bandwidth, connections, rate) |
| 3004 | idle | idle timeout expired |
| 3005 | going_away | relay shutting down |
| 3006 | protocol | malformed relay message |
| 3007 | peer_gone | the daemon connection for this pair is gone |

Endpoints treat 3001 and 3007 as network-style failures (retry with
backoff). 3002 on a daemon connection means a newer registration for the
same identity is live. 3003 and 3004 are retryable with backoff. 3005 means
the relay is restarting; retry with backoff.

### 10.5 Limits and resource control

Defaults (operator-configurable where noted):

| Limit | Default | On breach |
| ----- | ------- | --------- |
| Frame size | 1048704 bytes | `end` 3006, close |
| Per-pair queue | 256 frames and 8 MiB, per direction | close the app side, `end` 3003 |
| Per-pair bandwidth | 50 MiB/s per direction, 1 s burst bucket | close the sender (app), `end` 3003 |
| Total connections | 1024 | `end` 3003, close |
| Registrations | 4096 | `end` 3003, close |
| Apps per relay id | 32 | `end` 3003, close |
| Idle timeout | 300 s | `end` 3004, close |
| Join rate per source IP | 10/s, burst 20 | `end` 3003, close |
| Per-IP join state entries | 65536 | reset the map; joins are rejected with 3003 while full |

- The relay enforces limits before allocating meaningful resources: an
  unknown app join allocates only the connection record, never a pair.
- Queues are per pair and per direction. Overflow closes the app side: the
  app is either the fast writer or the slow reader, and closing it bounds
  the blast radius to one session. The daemon connection is never closed by
  queue pressure.
- Bandwidth buckets are per pair and per direction, computed on send with no
  timers. The bucket capacity equals the rate (a one-second burst), refilled
  from elapsed time.
- The relay is stateless across restarts: a daemon re-registers and apps
  rejoin; Remotly sessions resume over the relay exactly as over a direct
  connection.

### 10.6 Endpoints

- Daemon: keeps one persistent outbound connection per configured relay.
  It sends keepalives every 30 s, requires any inbound byte within 60 s, and
  re-registers with exponential backoff (1 s base, 60 s cap, full jitter) on
  any failure.
- App: one connection per relay transport session, same keepalive cadence.
  The app never sees stream ids; the relay strips them on the app side.
- Neither endpoint trusts relay-originated Remotly state: the Noise
  handshake, device identity, and pairing-token authentication all run
  inside the opaque frames, exactly as on a direct connection. A relay can
  drop, delay, or truncate frames, but it cannot read or forge them.

## 11. M4 additions

M4 adds authenticated filesystem metadata operations and resumable file
transfers. Both are optional at the wire level: an M3 client that never sends
an `fs.*` or `transfer.*` request, and never opens a file channel, keeps
working against an M4 daemon unchanged. An M4 client talking to an M3 daemon
is rejected cleanly (the M3 daemon does not know those types and closes with
`invalid_request`), which the client treats as "no M4 support".

### 11.1 File channel

The file channel type (`ChannelFile`, type byte `2`), reserved in M1, is
enabled in M4. It carries transfer chunk content as raw binary frames, so a
chunk is not constrained by the JSON control-payload limit. A file channel is
opened by the daemon (odd id, like a term channel) in response to
`transfer.create` or `transfer.resume`, and is named `file:<transfer-id>`.

Every file frame is one chunk: an 8-byte big-endian offset followed by the
chunk bytes.

```
file frame payload = [offset:8 bytes big-endian][chunk bytes]
```

The offset lets the receiver place re-sent or out-of-order chunks correctly
and resume after a reconnect. For an upload the app sends chunks on the
channel and the daemon acknowledges each with a `transfer.ack` control
notification. For a download the daemon pushes chunks and the app reassembles
by offset; `transfer.done` marks the last byte.

### 11.2 Filesystem metadata

`fs.roots`, `fs.list`, `fs.stat`, `fs.mkdir`, `fs.remove`, and `fs.rename`
run with the daemon operating-system user's privileges. There is no sandbox
root, but every path is validated and normalized, handled with direct syscall
APIs (never a shell), and results are bounded. `fs.list` returns a name-sorted
page of at most 500 entries plus the total count and a `more` flag; the app
pages with `offset` and `limit`.

Paths are absolute in the host's native form and are kept byte-faithful: NFC
and NFD spellings of the same name are distinct entries, with no
normalization. Entries report `name`, `is_dir`, `is_symlink`, `size`,
`mod_time` (unix seconds, 0 when not reported), `perm` (full POSIX mode
bits), and, for symlinks, a best-effort `link_target` for display.

```json
{ "id": 7, "type": "fs.list", "path": "/home/dev", "offset": 0, "limit": 100 }
```
```json
{ "id": 7, "type": "fs.list",
  "entries": [ { "name": "src", "is_dir": true, "is_symlink": false,
                 "size": 0, "mod_time": 1712345678, "perm": 16877 } ],
  "more": false, "total": 1 }
```

`fs.remove` takes `remove_kind` of `file` or `dir`. `dir` is non-recursive:
a nonempty directory fails with `fs_not_empty` rather than being discarded.
`fs.rename` is a plain atomic rename on the same filesystem.

Filesystem error codes: `fs_not_found`, `fs_not_dir`, `fs_is_dir`,
`fs_not_empty`, `fs_permission`, `fs_exist`, `fs_invalid_path`. The app
matches on the code, never the message.

### 11.3 Transfers

Transfers are chunked upload and download with offset resume and a whole-file
SHA-256 integrity check. All transfer identifiers, paths, offsets, sizes,
hashes, and bytes are untrusted and validated. A transfer is bound to the
authenticated device that created it; every operation re-checks that binding.
State is in-memory for the running daemon's lifetime; restart resume is not
persisted.

**Create.** `transfer.create` opens the transfer and its file channel.
For an upload (`direction: "up"`) the app supplies `expected_size` and the
`hash` (64 lowercase hex chars) of the finished file, plus `conflict` of
`fail` (default) or `replace` for the case where the destination already
exists. For a download (`direction: "down"`) the daemon derives the size and
hash from the source.

```json
{ "id": 9, "type": "transfer.create", "direction": "up",
  "path": "/home/dev/out.bin", "expected_size": 12345,
  "hash": "<64 hex>", "conflict": "fail" }
```
```json
{ "id": 9, "type": "transfer.create", "transfer_id": "<32 hex>",
  "channel_id": <odd uint32>, "direction": "up", "expected_size": 12345,
  "hash": "<64 hex>", "resume_offset": 0 }
```

**Chunks.** Upload chunks are file frames carrying an offset and bytes.
The app sends chunks in order; a re-send at an already-acknowledged offset is
acknowledged idempotently without rewriting, and an offset past the current
one is refused with `transfer_bad_offset`. After each applied chunk the
daemon sends:

```json
{ "type": "transfer.ack", "transfer_id": "<32 hex>", "offset": <bytes so far> }
```

**Complete.** `transfer.complete` finalizes. For an upload the daemon verifies
the received size equals `expected_size`, computes the whole-file SHA-256 of
the temporary file, compares it in constant time to the agreed hash, syncs,
and atomically renames the temp file into the destination per the conflict
policy. A hash mismatch, size mismatch, or `fail`-policy collision fails the
transfer and leaves the existing destination untouched. The response carries
the verified hash.

**Resume.** After a reconnect, the app queries and re-attaches to a transfer
that still lives in the daemon. `transfer.status` is a pure query (no channel);
`transfer.resume` re-opens a file channel and reports the current offset so
the app continues from there.

```json
{ "id": 11, "type": "transfer.resume", "transfer_id": "<32 hex>" }
```
```json
{ "id": 11, "type": "transfer.resume", "transfer_id": "<32 hex>",
  "channel_id": <odd uint32>, "direction": "up", "expected_size": 12345,
  "hash": "<64 hex>", "resume_offset": 4096 }
```

**Download.** The daemon reads from a source snapshot (size and mtime taken at
create). It re-checks the source on every chunk read and fails the transfer
with `transfer_source_changed` if the source is mutated mid-transfer. The
app verifies the hash it accumulates against the hash from `transfer.create`.

**Cancel.** `transfer.cancel` tears the transfer down and removes its temp
file. The destination is never touched on cancel.

**Failure.** When a transfer fails, the daemon sends `transfer.failed` with a
code and closes the file channel:

```json
{ "type": "transfer.failed", "transfer_id": "<32 hex>", "code": "transfer_hash_mismatch" }
```

Transfer error codes: `transfer_not_found`, `transfer_not_authorized`,
`transfer_capacity`, `transfer_too_large`, `transfer_bad_offset`,
`transfer_over_length`, `transfer_hash_mismatch`, `transfer_source_changed`,
`transfer_incomplete`, `transfer_conflict`, `transfer_invalid_arg`.

### 11.4 Transfer resource bounds

Chunk payloads are bounded (default 1 MiB, `transfer_too_large` beyond).
The number of concurrent transfers is bounded (default 8,
`transfer_capacity` beyond), as is total temporary storage. Transfers idle
past a lifetime (default 10 minutes) are reaped, and their temp files
removed. Stale temp files follow a distinct name pattern and are swept without
touching unrelated files. The whole-file hash is SHA-256; content and secrets
are never logged.

## 12. Canonical test vectors (v1)

The v1 freeze is pinned to canonical byte vectors stored under
`daemon/internal/protocol/testdata/`. Both implementations read the same files
and must reproduce the same bytes, so a regression on either side is caught
without a live peer. All vectors use synthetic keys and secrets; none is real
credential material.

| File | Surface | Exercised by |
| --- | --- | --- |
| `frame-vectors.json` | AEAD transport frame (header + ChaCha20-Poly1305 ciphertext) | Go (`frame_test.go`) |
| `base64url.json` | base64url (RFC 4648 §5, unpadded) encode/decode | Go (`vectors_test.go`) and JS (`vectors.test.ts`) |
| `varint.json` | unsigned LEB128 encode/decode, 5-byte bound | Go (`vectors_test.go`) |
| `version.json` | protocol version gate (accept `1`, reject others with `4000`) | Go (`version_vector_test.go`) and Kotlin (app handshake) |
| `relay-frame.json` | relay message framing (`join`, `end`, `stream_open`) | Go (`vectors_test.go`) |
| `pairing-payload.json` | pairing URI payload encode/decode (token, secret, keys, hints, name) | Go (`vectors_test.go`) and JS (`vectors.test.ts`) |
| `transfer-chunk.json` | file-channel chunk framing (`offset:8 BE` + chunk bytes) | Go (`vectors_test.go`) and JS (`vectors.test.ts`) |

The JS fixture test reads the files from `../../daemon/internal/protocol/testdata`
relative to `app-rn`, so the two trees cannot drift: a change to a vector breaks
both suites at once.
