# Operating the Remotly relay

The relay is an opaque TCP router that carries Remotly transport bytes between
one daemon and the apps paired with it. It stores nothing, parses only its own
envelope, and never initiates contact with either side. The wire contract is
`protocol.md` section 10. This document covers running it on Linux.

There is no default public relay. Each deployment is self-hosted by a user who
runs the relay on a host the daemon and the app can both reach, and the relay
is only ever used when a pairing URI or stored host carries a relay hint.

## Build

The module has no third-party dependencies.

```sh
cd relay
go build -o remotly-relay ./cmd/remotly-relay
go test -race ./...
```

The binary is a single static Go executable. Ship it to the relay host, for
example to `/usr/local/bin/remotly-relay`.

## Configuration

One JSON file. Every field except the two listeners has a default. Unknown
fields and out-of-range values are rejected at startup, so a bad config fails
the process rather than running with a silent fallback.

```json
{
  "listen": "0.0.0.0:8443",
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

- `listen` (required): the data listener. Daemons and apps connect here. Bind
  it to the interface the reverse proxy or clients use.
- `admin_listen` (required): a loopback-only listener for `GET /healthz` and
  `GET /metrics`. Keep it on `127.0.0.1`; do not expose it.
- `limits`: the operational bounds, all optional. `queue_bytes` is in bytes
  (8388608 = 8 MiB); `bandwidth_bps` is the per-pair, per-direction sustained
  rate in bytes per second (52428800 = 50 MiB/s). The per-direction burst
  allowance equals one second of `bandwidth_bps`.

Frame size is fixed at 1048704 bytes (1 MiB + 128) on the wire and is not a
setting.

## Run as a non-root service

Create a dedicated system user and install the unit. The unit in
`relay/systemd/remotly-relay.service` runs as the unprivileged `remotly-relay`
user with a hardened profile: no new privileges, read-only system and home,
private tmp and devices, no kernel-tunable or module access, no SUID/SGID,
memory write-execute denied, and address families restricted to IPv4, IPv6, and
Unix sockets.

```sh
sudo useradd --system --home-dir /etc/remotly-relay \
    --shell /usr/sbin/nologin remotly-relay
sudo install -d -o remotly-relay -g remotly-relay /etc/remotly-relay
sudo install -m 0640 relay.json /etc/remotly-relay/relay.json
sudo chown remotly-relay:remotly-relay /etc/remotly-relay/relay.json
sudo install -m 0755 remotly-relay /usr/local/bin/remotly-relay
sudo cp relay/systemd/remotly-relay.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now remotly-relay
```

The relay opens its listeners as the `remotly-relay` user. If `listen` is a
port below 1024, either bind a high port (recommended, since a reverse proxy
typically fronts it) or add a `AmbientCapabilities=CAP_NET_BIND_SERVICE` plus
`CapabilityBoundingSet=CAP_NET_BIND_SERVICE` to the unit.

The unit has no write access to the config directory beyond what the config
file needs; it holds no state, so there is no volume to back up.

## TLS and reachability

The relay speaks plain TCP and performs no TLS of its own. Terminate TLS at a
reverse proxy in front of `listen`, and let the proxy restrict which source
addresses may relay. The `admin_listen` stays on loopback and is never proxied.

Example Caddyfile:

```
relay.example.com {
    tls you@example.com
    @allowed remote_ip 203.0.113.0/24 198.51.100.7
    @rest not @allowed
    respond @rest 403
    reverse_proxy 127.0.0.1:8443
}
```

The proxy must pass the connection through as a TCP stream (not HTTP) if you
use a proxy that supports raw TCP; Caddy's `reverse_proxy` does this when the
listener is not HTTP. nginx and HAProxy both support L4 TCP termination. Point
the daemon's `relay.addr` and the pairing URI's relay hint at the proxy's
public host:port.

Because authentication happens inside each Remotly stream (a full Noise
handshake against the daemon's identity), the relay operator cannot read
sessions even though it routes them. The reverse proxy and source allowlist are
the network trust boundary.

## Health and monitoring

- `GET /healthz` returns `200` while the data listener is up. Use it as the
  systemd and reverse-proxy health check.
- `GET /metrics` (loopback only) exposes Prometheus text-format metrics with
  deliberately low cardinality:

  | Metric | Type | Meaning |
  | --- | --- | --- |
  | `remotly_relay_registrations` | gauge | live daemon registrations |
  | `remotly_relay_apps` | gauge | attached app streams |
  | `remotly_relay_connections` | gauge | live endpoint connections |
  | `remotly_relay_joins_total{role}` | counter | accepted joins by role |
  | `remotly_relay_join_rejections_total{reason}` | counter | rejected joins (`limit`, `rate`, `no_daemon`) |
  | `remotly_relay_frames_total{dir}` | counter | forwarded frames by direction |
  | `remotly_relay_bytes_total{dir}` | counter | forwarded bytes by direction |
  | `remotly_relay_queue_bytes` | gauge | buffered bytes across all queues |
  | `remotly_relay_drops_total{reason}` | counter | frames dropped for an unknown stream |
  | `remotly_relay_closes_total{reason}` | counter | closes by relay close code |

  There are no per-connection, per-relay-id, or per-user labels, so a busy relay
  does not grow the metric set. Scrape `admin_listen` from a local
  Prometheus/Node exporter.

Logs are JSON on stderr (and thus in the journald). They record operational
events only: listener startup, daemon registration, app attach, and limit or
protocol errors. They never contain relay ids, client addresses, or payload
bytes. See "Log privacy" below.

## Limits, abuse, and failure behavior

The relay enforces its bounds by closing the offending endpoint, never by
crashing or by closing a healthy one:

- **Frame over 1 MiB + 128** or a malformed envelope: the endpoint is closed
  with 3006 (`protocol`). A frame naming a stream the relay does not know is
  dropped and counted (`remotly_relay_drops_total{reason="unknown_stream"}`);
  it does not tear down the daemon connection.
- **Per-pair queue full** (frame count or bytes): the app side of that pair is
  closed with 3003 (`limit`). The daemon connection and sibling streams are
  unaffected, so one slow or abusive app cannot starve the others.
- **Bandwidth exceeded** on a pair direction: that app is closed with 3003.
- **Total connections, registrations, or apps-per-relay cap reached**: the new
  join is refused (3003 for caps, 3001 for no daemon) and existing connections
  continue.
- **Join rate per source address exceeded** (`join_rate_per_sec` / `join_burst`):
  the join is refused with 3003 and counted as a `rate` rejection.
- **Idle** beyond `idle_timeout_sec`: the connection is closed with 3004
  (`idle`). Both ends send keepalives every 30 seconds; a dead peer is detected
  within about 60 seconds of silence.

High-cardinality identifier attacks (many distinct unknown relay ids) allocate
only a transient connection record each; they are rejected with 3001 and leave
no registration, and the goroutine count returns to its steady state.

### Relay unavailability

The relay is stateless, so its failure is not a data-loss event. The daemon
retries its relay connection on a backoff and re-registers; the app falls back
to direct hints first and only tries the relay after every direct hint fails
(M3-03). While the relay is down, an app on the daemon's LAN keeps working over
a direct connection, and an app off-LAN cannot connect until the relay is
back. There is nothing to drain on the relay: a restart is transparent because
daemons re-register and apps rejoin.

## Graceful stop and upgrade

On SIGINT/SIGTERM the relay stops accepting, sends a going-away close (3005)
to every live connection, drains for up to 5 seconds, and exits. systemd's
`TimeoutStopSec=10` bounds the stop. A rolling restart or binary upgrade is:

```sh
sudo systemctl restart remotly-relay
```

Live sessions are not stored, so a restart does not lose scrollback or session
state (that lives in the daemon). Connected apps see a 3005 close, back off,
and reconnect to the new process; the daemon re-registers. There is no shared
socket or state to migrate.

## Log privacy

The relay is opaque, and its logs honor that. Every log line and every metric
is checked to contain none of:

- relay ids (the 16-byte routing key),
- client source or destination addresses,
- payload bytes or Remotly frame content,
- pairing data, keys, or stable user identifiers.

The unit writes logs to journald as JSON. If you ship journald to a central
collector, the relay's lines carry no per-client identifying data. Memory
diagnostics (heap profiles, goroutine dumps) are safe to take: the in-memory
state is routing tables and byte queues, and the queues hold Noise-encrypted
Remotly frames, not plaintext. The relay keeps no persistent volume, so there
is nothing on disk to inspect for traffic history.

The automated checks for these guarantees are in `relay/server`
(`TestPrivacyLogsAndMetrics` asserts no payload, relay id, or peer address in
logs or metrics; `TestHighCardinalityUnknownIDs` asserts unknown ids leave no
registrations and no goroutine growth).
