package pairing

import (
	"crypto/rand"
	"encoding/hex"
	"errors"
	"fmt"
	"sync"
	"time"
)

// Token lifetime and material sizes. The five-minute TTL and single-use rule
// are normative (protocol.md, section 5).
const (
	DefaultTokenTTL = 5 * time.Minute
	tokenIDLen      = 16
	secretLen       = 32

	// How long an expired token's record is kept after it lapses.
	//
	// The protocol distinguishes token_expired from token_unknown, and the
	// difference is only representable while the record still exists. The LAN
	// gate wakes exactly at each token's expiry and sweeps, so dropping the
	// record at expiry meant a client that scanned a code seconds too late was
	// told the token never existed. The tombstone keeps the honest answer for
	// long enough that a real handshake sees it, then the record goes.
	expiredGrace = 10 * time.Minute
)

// Errors returned by the token manager. Callers match them with errors.Is and
// map them to the protocol close reasons: ErrTokenUnknown -> token_unknown,
// ErrTokenExpired -> token_expired, ErrTokenUsed -> token_used.
var (
	ErrTokenUnknown = errors.New("pairing: token unknown")
	ErrTokenExpired = errors.New("pairing: token expired")
	ErrTokenUsed    = errors.New("pairing: token already used")
)

// Token is one one-time pairing credential. The exported fields are the
// material the daemon needs to build the pairing URI; the rest is internal.
// A token is consumed at most once, by a successful handshake.
type Token struct {
	ID        [tokenIDLen]byte
	Secret    [secretLen]byte
	EphemPub  [32]byte // X25519 public key used as the Noise static in XXpsk0
	Expires   time.Time
	ephemPriv [32]byte // X25519 private half; never exported
	claimed   bool
}

// TokenSnapshot is an immutable copy of a token's handshake material. It is
// what the transport layer (M1-07) uses to run the XXpsk0 responder, and it is
// safe to pass across goroutines and packages.
type TokenSnapshot struct {
	Secret        [secretLen]byte
	EphemeralPub  [32]byte
	EphemeralPriv [32]byte
	Expires       time.Time
}

// TokenManager owns the in-memory set of live pairing tokens. It is safe for
// concurrent use. Tokens are ephemeral: they live only for the daemon process
// and vanish on restart, which is intended (a reboot invalidates unscanned
// codes).
type TokenManager struct {
	mu     sync.Mutex
	now    func() time.Time
	ttl    time.Duration
	tokens map[string]*Token // keyed by hex(token ID)
}

// NewTokenManager returns a manager with the default five-minute TTL.
func NewTokenManager() *TokenManager {
	return NewTokenManagerTTL(DefaultTokenTTL)
}

// NewTokenManagerTTL returns a manager with the given TTL. The transport
// layer's gate tests use it to exercise token expiry without waiting.
func NewTokenManagerTTL(ttl time.Duration) *TokenManager {
	if ttl <= 0 {
		ttl = DefaultTokenTTL
	}
	return &TokenManager{
		now:    time.Now,
		ttl:    ttl,
		tokens: make(map[string]*Token),
	}
}

// Create mints a fresh token: a random id, a random secret, and a fresh
// ephemeral X25519 keypair. It returns the token so the caller can build the
// pairing URI.
func (m *TokenManager) Create() *Token {
	var id [tokenIDLen]byte
	var secret [secretLen]byte
	if _, err := rand.Read(id[:]); err != nil {
		panic(fmt.Sprintf("pairing: read id: %v", err))
	}
	if _, err := rand.Read(secret[:]); err != nil {
		panic(fmt.Sprintf("pairing: read secret: %v", err))
	}
	epKey, err := x25519.GenerateKey(rand.Reader)
	if err != nil {
		panic(fmt.Sprintf("pairing: generate ephemeral: %v", err))
	}
	epPub := epKey.PublicKey().Bytes()
	epPriv := epKey.Bytes()
	if len(epPub) != 32 || len(epPriv) != 32 {
		panic("pairing: bad ephemeral key length")
	}
	now := m.now()
	t := &Token{
		ID:      id,
		Secret:  secret,
		Expires: now.Add(m.ttl),
	}
	copy(t.EphemPub[:], epPub)
	copy(t.ephemPriv[:], epPriv)
	m.mu.Lock()
	m.sweepLocked(now)
	m.tokens[tokenKey(id[:])] = t
	m.mu.Unlock()
	return t
}

// Lookup returns an immutable snapshot of the token if it is present,
// unexpired, and unclaimed. It is the gate the transport layer runs before
// starting a pairing handshake; it returns the protocol-appropriate error
// otherwise.
func (m *TokenManager) Lookup(id []byte) (*TokenSnapshot, error) {
	if len(id) < 1 || len(id) > 64 {
		return nil, ErrTokenUnknown
	}
	key := tokenKey(id)
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tokens[key]
	if !ok {
		return nil, ErrTokenUnknown
	}
	now := m.now()
	if now.After(t.Expires) {
		return nil, ErrTokenExpired
	}
	if t.claimed {
		return nil, ErrTokenUsed
	}
	return &TokenSnapshot{
		Secret:        t.Secret,
		EphemeralPub:  t.EphemPub,
		EphemeralPriv: t.ephemPriv,
		Expires:       t.Expires,
	}, nil
}

// Claim atomically consumes the token. It re-checks presence, expiry, and the
// claimed flag under the lock, so exactly one concurrent claimant succeeds and
// every other caller gets ErrTokenUsed. A failed Claim (unknown or expired)
// does not consume the token.
func (m *TokenManager) Claim(id []byte) error {
	if len(id) < 1 || len(id) > 64 {
		return ErrTokenUnknown
	}
	key := tokenKey(id)
	m.mu.Lock()
	defer m.mu.Unlock()
	t, ok := m.tokens[key]
	if !ok {
		return ErrTokenUnknown
	}
	if m.now().After(t.Expires) {
		return ErrTokenExpired
	}
	if t.claimed {
		return ErrTokenUsed
	}
	t.claimed = true
	return nil
}

// ActiveCount reports how many tokens are live (unexpired and unclaimed). The
// LAN listener gate uses Active() to decide whether to expose the daemon.
func (m *TokenManager) ActiveCount() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	m.sweepLocked(now)
	n := 0
	for _, t := range m.tokens {
		if !t.claimed && now.Before(t.Expires) {
			n++
		}
	}
	return n
}

// Active reports whether any pairing token is currently live.
func (m *TokenManager) Active() bool { return m.ActiveCount() > 0 }

// NextExpiry returns the expiry of the soonest token that is still unexpired
// and unclaimed, or the zero time if no such token exists. The LAN gate uses
// it to close the listener exactly when the last token lapses, without
// relying on the polling interval alone.
func (m *TokenManager) NextExpiry() time.Time {
	m.mu.Lock()
	defer m.mu.Unlock()
	now := m.now()
	m.sweepLocked(now)
	var earliest time.Time
	for _, t := range m.tokens {
		// Expired records linger as tombstones so a late handshake can be
		// told token_expired. They are not something to wake up for: their
		// deadline has already passed, and returning one would have the gate
		// loop spin on a timer that fires immediately.
		if t.claimed || !now.Before(t.Expires) {
			continue
		}
		if earliest.IsZero() || t.Expires.Before(earliest) {
			earliest = t.Expires
		}
	}
	return earliest
}

// sweepLocked drops records that are past their usefulness.
//
// An expired token is kept as a tombstone for expiredGrace so a late
// handshake is told token_expired rather than token_unknown; the two are
// distinct protocol reasons and only the record can tell them apart. Claimed
// tokens are kept the same way, so a replay reports token_used. Callers hold
// m.mu.
func (m *TokenManager) sweepLocked(now time.Time) {
	for k, t := range m.tokens {
		if now.After(t.Expires.Add(expiredGrace)) {
			delete(m.tokens, k)
		}
	}
}

func tokenKey(id []byte) string { return hex.EncodeToString(id) }
