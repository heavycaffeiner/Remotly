# Remotly relay

The relay is an opaque router for Remotly transport messages. It carries
bytes between one daemon and the apps that are paired with it, and nothing
else.

- Wire contract: `docs/protocol.md` section 10.
- The relay parses only its own envelope. Remotly handshakes, control
  frames, and terminal data pass through untouched.
- No persistent state. Registrations, pairs, and queues live in memory.
  A restart is transparent: daemons re-register on reconnect, apps rejoin.
- No push. The relay never initiates contact with either side.

## Trust model

The relay connection is not authenticated at the relay layer. Authentication
happens inside each Remotly stream: every app sub-stream runs its own full
Noise handshake against the daemon's identity, so a relay operator cannot
read sessions even though it routes them. Run the relay on a network you
trust, or in front of a reverse proxy that terminates TLS and restricts
source addresses.

Because the relay is stateless, the blast radius of a compromised relay is
limited to seeing connection metadata (source addresses, byte counts) while
it is compromised. Payloads are Noise-encrypted end to end.

## Build

```sh
cd relay
go build -o remotly-relay ./cmd/remotly-relay
go test -race ./...
```

The module has no third-party dependencies.

## Configure

One JSON file. Every field except the two listeners has a default.

```json
{
  "listen": "127.0.0.1:8443",
  "admin_listen": "127.0.0.1:8444",
  "limits": {
    "max_connections": 1024,
    "max_registrations": 4096,
    "max_apps_per_relay": 32,
    "queue_frames": 256,
    "queue_bytes": 8388608,
    "bandwidth_bps": 52428800,
    "idle_timeout_sec": 300,
    "join_rate_per_sec": 10,
    "join_burst": 20
  }
}
```

- `listen`: the data listener. Daemons and apps connect here.
- `admin_listen`: loopback listener for `GET /healthz` and
  `GET /metrics` (Prometheus text format).
- Unknown fields and out-of-range values are rejected at startup.

## Run

```sh
remotly-relay -config /etc/remotly-relay/relay.json
```

On SIGINT/SIGTERM the relay stops accepting, sends a going-away close to
every live connection, drains for up to 5 seconds, and exits.

### systemd

Install `systemd/remotly-relay.service`, create the `remotly-relay` user,
and place the config at `/etc/remotly-relay/relay.json`. The unit is
hardened: no new privileges, read-only system, no device or kernel
access, restricted address families and namespaces.

### TLS and reachability

v1 relays speak plain TCP. Terminate TLS at a reverse proxy (Caddy, nginx,
HAProxy) and point the proxy at `listen`. Restrict the proxy to the source
addresses that are allowed to relay, and keep `admin_listen` on loopback
only. There is no public relay; each deployment is self-hosted.

## Limits and behavior

- Frame size is fixed at 1048704 bytes (1 MiB + 128) on the wire.
- Per-pair queues bound each direction by frame count and byte count.
  Overflow closes the app side with close code 3003; the daemon
  connection is never closed by queue pressure.
- Bandwidth is a per-pair, per-direction token bucket; the burst equals
  one second of the sustained rate.
- Idle connections are closed after `idle_timeout_sec`. Both sides send
  keepalives every 30 seconds and expect any byte within 60 seconds.
- Joins are rate limited per source address. Relay ids are opaque 16-byte
  values; unknown ids are rejected with close code 3001 and allocate no
  state beyond the connection record.

## Observability

- `GET /healthz` returns 200 while the data listener is up.
- `GET /metrics` exposes low-cardinality counters and gauges: live
  connections, registrations, attached apps, forwarded frames and bytes
  per direction, close counts by relay close code, join rejections by
  reason, and queue depth in bytes.
- Logs are JSON on stderr. Log lines and metrics never contain relay ids,
  payload bytes, or client addresses.

## Privacy

The relay does not log relay ids, IP addresses, or payload content.
Metrics are intentionally low cardinality. If you need longer-lived
observability, aggregate externally; the relay itself keeps nothing.
