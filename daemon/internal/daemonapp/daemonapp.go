// Package daemonapp wires the daemon's long-running services together:
// session management, pairing state, device identity, and the local control
// channel, plus graceful shutdown. Each service receives only what it needs;
// the wiring here is the single place that knows the full object graph.
package daemonapp

import (
	"context"
	"log/slog"
	"net"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/config"
	"github.com/heavycaffeiner/remotly/daemon/internal/fsops"
	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
	"github.com/heavycaffeiner/remotly/daemon/internal/relayconn"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
	"github.com/heavycaffeiner/remotly/daemon/internal/transfer"
	"github.com/heavycaffeiner/remotly/daemon/internal/transport"
)

// shutdownTimeout bounds graceful cleanup (connection drain, session kill)
// after a termination signal before the process exits.
const shutdownTimeout = 5 * time.Second

// App owns the running daemon. Build assembles it from configuration; Run
// starts it and blocks until the context is cancelled or a fatal error.
type App struct {
	cfg       config.Config
	log       *slog.Logger
	dataDir   string
	identity  *pairing.Identity
	tokens    *pairing.TokenManager
	devices   *pairing.DeviceStore
	sessions  *session.Manager
	transport *transport.Server
	relay     *relayconn.Client
	local     *localctl.Server
}

// Build validates configuration and assembles the daemon's state: the data
// directory, long-term identity, device store, token manager, and session
// manager. It creates no listeners; Run starts the local control channel.
func Build(ctx context.Context, cfg config.Config, log *slog.Logger) (*App, error) {
	_ = ctx
	if err := cfg.SanityCheck(); err != nil {
		return nil, err
	}
	dataDir, err := paths.Ensure(paths.DataKind)
	if err != nil {
		return nil, err
	}
	identity, err := pairing.LoadOrCreateIdentity(dataDir)
	if err != nil {
		return nil, err
	}
	devices, err := pairing.LoadDeviceStore(dataDir)
	if err != nil {
		return nil, err
	}
	tokens := pairing.NewTokenManager()
	a := &App{
		cfg:      cfg,
		log:      log,
		dataDir:  dataDir,
		identity: identity,
		tokens:   tokens,
		devices:  devices,
	}
	a.sessions, err = session.New(session.Options{
		Backend:           pty.New(),
		Shell:             cfg.Shell,
		Term:              cfg.Term,
		MaxSessions:       cfg.Sessions.Max,
		ScrollbackLines:   cfg.Scrollback.Lines,
		RetainedAfterExit: time.Duration(cfg.Sessions.RetainedAfterExit) * time.Second,
		Events:            eventsConfig(cfg),
		OnExit:            a.sessionExited,
		OnEvent:           a.sessionEvent,
	})
	if err != nil {
		return nil, err
	}
	a.transport = transport.NewServer(transport.Options{
		LoopbackAddr:    net.JoinHostPort("127.0.0.1", strconv.Itoa(cfg.Listen.LoopbackPort)),
		LoopbackEnabled: cfg.Listen.Loopback,
		LANAddr:         net.JoinHostPort("0.0.0.0", strconv.Itoa(cfg.Listen.LANPort)),
		LANEnabled:      cfg.Listen.LAN,
		Identity:        identity,
		Tokens:          tokens,
		Devices:         devices,
		Sessions:        a.sessions,
		FS:              fsops.New(),
		Transfers:       transfer.NewManager(transfer.Options{FS: fsops.New()}),
		Presets:         presetsConfig(cfg),
		DaemonName:      daemonName(),
		Log:             log,
	})
	if cfg.Relay.Enabled {
		a.relay = relayconn.New(relayconn.Config{
			Addr:     cfg.Relay.Addr,
			RelayID:  relayID(identity),
			OnStream: func(st *relayconn.Stream) { a.transport.HandleStream(st) },
			Log:      log,
		})
	}
	return a, nil
}

// eventsConfig derives the session package's event configuration from the
// validated daemon configuration. A nil result disables event detection.
func eventsConfig(cfg config.Config) *session.Events {
	if !cfg.Notify.BellOn() && len(cfg.Notify.Patterns) == 0 {
		return nil
	}
	e := &session.Events{BellEnabled: cfg.Notify.BellOn()}
	for _, p := range cfg.Notify.Patterns {
		e.Patterns = append(e.Patterns, session.PatternSpec{Name: p.Name, Expr: p.Expr})
	}
	return e
}

// presetsConfig converts the validated presets to their wire form.
func presetsConfig(cfg config.Config) []protocol.Preset {
	out := make([]protocol.Preset, 0, len(cfg.Presets))
	for _, p := range cfg.Presets {
		out = append(out, protocol.Preset{Name: p.Name, Command: p.Command, IconHint: p.IconHint})
	}
	return out
}

// sessionExited is the session manager's exit hook. It broadcasts the final
// metadata to every transport connection. It runs in the session's wait
// goroutine and must not block.
func (a *App) sessionExited(m session.Metadata) {
	if a.transport != nil {
		a.transport.NotifySessionExit(m)
	}
}

// sessionEvent is the session manager's event hook. It broadcasts terminal
// events (bell, pattern match) to every transport connection. It runs in a
// session's drain goroutine and must not block.
func (a *App) sessionEvent(e session.Event) {
	if a.transport != nil {
		a.transport.NotifySessionEvent(e)
	}
}

// PairedDevices reports how many devices are currently paired. A zero count
// means nobody can reach this daemon yet, which is what makes a startup
// pairing invitation worth showing.
func (a *App) PairedDevices() int { return a.devices.ActiveCount() }

// PairingInvite mints a one-time pairing token and returns its URI and expiry
// (unix seconds). Minting opens the LAN gate, and Run evaluates that gate when
// it starts the transport, so calling this before Run is what makes the
// listener come up already reachable for the invitation on screen.
func (a *App) PairingInvite() (string, int64, error) { return a.buildPairingURI() }

// Run starts the daemon and blocks until ctx is cancelled. It returns
// context.Canceled on clean shutdown.
func (a *App) Run(ctx context.Context) error {
	a.local = localctl.NewServer(
		localctl.Path(a.dataDir),
		a.log,
		a.tokens,
		a.devices,
		a.sessions,
		a.buildPairingURI,
	)
	// A revocation must drop the revoked device's live connections, not wait
	// for its next reconnect; otherwise a revoked key keeps an active session.
	// NotifyGate re-evaluates the listener rule too, so revoking the last
	// paired device closes the LAN listener now, not at the next 30s poll.
	a.local.SetOnDevicesChanged(func(pub [32]byte) {
		if a.transport != nil {
			a.transport.CloseDevice(pub)
			a.transport.NotifyGate()
		}
	})
	// A freshly minted pairing token opens the LAN gate. The app connects as
	// soon as the URI is on screen, so the listener must open now; waiting for
	// the 30s safety poll shows the user a connection failure.
	a.local.SetOnTokenIssued(func() {
		if a.transport != nil {
			a.transport.NotifyGate()
		}
	})
	if err := a.local.Start(); err != nil {
		return err
	}
	if err := a.transport.Start(); err != nil {
		_ = a.local.Close()
		return err
	}
	if a.relay != nil {
		a.relay.Start()
		a.log.Info("relay enabled", "addr", a.cfg.Relay.Addr)
	}
	a.log.Info("daemon ready", "lan_port", a.cfg.Listen.LANPort)
	<-ctx.Done()
	a.shutdown()
	return ctx.Err()
}

// shutdown performs bounded cleanup: stop the transport and the local control
// channel, then kill all sessions.
func (a *App) shutdown() {
	a.log.Info("shutting down", "timeout_ms", shutdownTimeout.Milliseconds())
	if a.relay != nil {
		_ = a.relay.Close()
	}
	if a.transport != nil {
		_ = a.transport.Close()
	}
	if a.local != nil {
		_ = a.local.Close()
	}
	if a.sessions != nil {
		_ = a.sessions.Shutdown()
	}
}

// buildPairingURI mints a pairing token and renders the canonical pairing URI
// for it. The URI carries the token id, secret, ephemeral public key, the
// daemon's long-term public key, LAN connection hints, and the daemon name.
func (a *App) buildPairingURI() (string, int64, error) {
	t := a.tokens.Create()
	pub := a.identity.PublicBytes()
	payload := pairing.URIPayload{
		Version:      1,
		TokenID:      t.ID[:],
		Secret:       t.Secret[:],
		Expires:      t.Expires.Unix(),
		EphemeralPub: t.EphemPub,
		DaemonPub:    pub,
		Hints:        a.pairingHints(),
		DaemonName:   daemonName(),
	}
	uri, err := pairing.EncodeURI(payload)
	if err != nil {
		return "", 0, err
	}
	return uri, t.Expires.Unix(), nil
}

// pairingHints returns the pairing URI hints: the relay hint first (when the
// relay is enabled) so remote access is always advertised, then the LAN
// hints, truncated to the URI bound. The relay hint carries the relay host
// and port; the app derives the daemon's relay identity from the daemon
// public key already in the URI.
func (a *App) pairingHints() []pairing.Hint {
	var hints []pairing.Hint
	if a.cfg.Relay.Enabled {
		if host, portStr, err := net.SplitHostPort(a.cfg.Relay.Addr); err == nil {
			if port, err := strconv.Atoi(portStr); err == nil {
				hints = append(hints, pairing.Hint{Kind: pairing.HintRelay, Addr: host, Port: port})
			}
		}
	}
	hints = append(hints, a.lanHints()...)
	if len(hints) > pairing.MaxURIHints {
		hints = hints[:pairing.MaxURIHints]
	}
	return hints
}

// relayID derives the daemon's 16-byte relay identity from its long-term
// public key: the first 16 bytes, which the relay stores and compares
// opaquely.
func relayID(id *pairing.Identity) [16]byte {
	pub := id.PublicBytes()
	var out [16]byte
	copy(out[:], pub[:16])
	return out
}

// lanHints returns the daemon's reachable LAN addresses plus the LAN port. It
// returns no hints when LAN exposure is disabled.
func (a *App) lanHints() []pairing.Hint {
	if !a.cfg.Listen.LAN {
		return nil
	}
	port := a.cfg.Listen.LANPort
	var hints []pairing.Hint
	if hn := daemonName(); hn != "" {
		hints = append(hints, pairing.Hint{Kind: pairing.HintName, Addr: hn, Port: port})
	}
	addrs, err := net.InterfaceAddrs()
	if err == nil {
		for _, addr := range addrs {
			ipn, ok := addr.(*net.IPNet)
			if !ok {
				continue
			}
			ip := ipn.IP
			if !dialableHint(ip) {
				continue
			}
			if ip4 := ip.To4(); ip4 != nil {
				hints = append(hints, pairing.Hint{Kind: pairing.HintIPv4, Addr: ip4.String(), Port: port})
			} else {
				hints = append(hints, pairing.Hint{Kind: pairing.HintIPv6, Addr: ip.String(), Port: port})
			}
		}
	}
	if len(hints) > pairing.MaxURIHints {
		hints = hints[:pairing.MaxURIHints]
	}
	return hints
}

// dialableHint reports whether an interface address is worth advertising to a
// phone on the same network.
//
// A pairing URI carries every hint the daemon offers and the app dials them
// until one answers, so an address the phone cannot route to costs a full
// connect timeout. A developer machine typically has several: container and
// VM bridges, a VPN's carrier-grade NAT range, unique-local IPv6 from a
// container manager. None of those reach a phone on the LAN, and advertising
// them made pairing look like it had hung.
//
// Only ordinary private LAN addresses are kept. A public address is kept too:
// a daemon on a routable address is reachable, and refusing it would break
// that setup to tidy up a developer laptop.
func dialableHint(ip net.IP) bool {
	if ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() ||
		ip.IsUnspecified() || ip.IsMulticast() {
		return false
	}
	if ip4 := ip.To4(); ip4 != nil {
		// 100.64.0.0/10, the carrier-grade NAT range Tailscale and similar
		// overlays use. Reachable only through that overlay, never over the
		// LAN the app is pairing on.
		if ip4[0] == 100 && ip4[1] >= 64 && ip4[1] <= 127 {
			return false
		}
		return true
	}
	// Unique-local IPv6 (fc00::/7). Container and VM managers hand these out
	// per host, and they do not route to anything else on the network.
	if len(ip) == net.IPv6len && ip[0]&0xfe == 0xfc {
		return false
	}
	return true
}

// daemonName returns a sanitized host name for display in pairing URIs and
// hello responses: valid UTF-8, no control characters, at most 100 bytes.
func daemonName() string {
	hn, err := os.Hostname()
	if err != nil || hn == "" {
		return "remotly"
	}
	var b strings.Builder
	for _, r := range hn {
		if r >= 0x20 && r != 0x7f {
			b.WriteRune(r)
		}
	}
	name := b.String()
	if name == "" {
		return "remotly"
	}
	if len(name) > 100 {
		name = name[:100]
	}
	return name
}
