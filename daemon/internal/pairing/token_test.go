package pairing

import (
	"errors"
	"testing"
	"time"
)

var testClockBase = time.Date(2026, 8, 16, 12, 0, 0, 0, time.UTC)

// newTestTokenManager returns a manager driven by a fixed clock the test can
// move by reassigning m.now.
func newTestTokenManager(at time.Time) *TokenManager {
	m := NewTokenManager()
	m.now = func() time.Time { return at }
	return m
}

func TestTokenCreateAndLookup(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	tok := m.Create()

	if tok.ID == [tokenIDLen]byte{} {
		t.Fatal("token id is all zero")
	}
	if tok.Secret == [secretLen]byte{} {
		t.Fatal("token secret is all zero")
	}
	if got, want := tok.Expires, testClockBase.Add(DefaultTokenTTL); !got.Equal(want) {
		t.Fatalf("expires = %v, want %v", got, want)
	}

	snap, err := m.Lookup(tok.ID[:])
	if err != nil {
		t.Fatalf("Lookup: %v", err)
	}
	if snap.Secret != tok.Secret {
		t.Fatal("snapshot secret mismatch")
	}
	if snap.EphemeralPub != tok.EphemPub {
		t.Fatal("snapshot ephemeral pub mismatch")
	}
	if !snap.Expires.Equal(tok.Expires) {
		t.Fatal("snapshot expiry mismatch")
	}
	// The snapshot's ephemeral keypair must be a real X25519 pair.
	key, err := x25519.NewPrivateKey(snap.EphemeralPriv[:])
	if err != nil {
		t.Fatalf("NewPrivateKey(ephemeral): %v", err)
	}
	if want := snap.EphemeralPub; [32]byte(key.PublicKey().Bytes()) != want {
		t.Fatal("ephemeral private does not derive the snapshot public key")
	}
}

func TestTokenLookupUnknown(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	var unknown [tokenIDLen]byte
	unknown[0] = 0xff
	if _, err := m.Lookup(unknown[:]); !errors.Is(err, ErrTokenUnknown) {
		t.Fatalf("Lookup(unknown) = %v, want ErrTokenUnknown", err)
	}
	// Out-of-range id lengths are unknown, not a panic or a different error.
	if _, err := m.Lookup(nil); !errors.Is(err, ErrTokenUnknown) {
		t.Fatalf("Lookup(empty) = %v, want ErrTokenUnknown", err)
	}
	if _, err := m.Lookup(make([]byte, 65)); !errors.Is(err, ErrTokenUnknown) {
		t.Fatalf("Lookup(65 bytes) = %v, want ErrTokenUnknown", err)
	}
}

func TestTokenExpiryBoundary(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	tok := m.Create()
	expires := tok.Expires

	// Exactly at the expiry instant the token is still valid: the check is
	// now.After(expires).
	m.now = func() time.Time { return expires }
	if _, err := m.Lookup(tok.ID[:]); err != nil {
		t.Fatalf("Lookup at expiry instant: %v", err)
	}

	m.now = func() time.Time { return expires.Add(time.Nanosecond) }
	if _, err := m.Lookup(tok.ID[:]); !errors.Is(err, ErrTokenExpired) {
		t.Fatalf("Lookup after expiry = %v, want ErrTokenExpired", err)
	}
	if err := m.Claim(tok.ID[:]); !errors.Is(err, ErrTokenExpired) {
		t.Fatalf("Claim after expiry = %v, want ErrTokenExpired", err)
	}
}

func TestTokenClaimIsSingleUse(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	tok := m.Create()

	if err := m.Claim(tok.ID[:]); err != nil {
		t.Fatalf("first Claim: %v", err)
	}
	if err := m.Claim(tok.ID[:]); !errors.Is(err, ErrTokenUsed) {
		t.Fatalf("second Claim = %v, want ErrTokenUsed", err)
	}
	// A claimed token still fails Lookup with the used error.
	m.now = func() time.Time { return testClockBase.Add(time.Minute) }
	if _, err := m.Lookup(tok.ID[:]); !errors.Is(err, ErrTokenUsed) {
		t.Fatalf("Lookup(claimed) = %v, want ErrTokenUsed", err)
	}
}

func TestTokenClaimUnknown(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	var unknown [tokenIDLen]byte
	if err := m.Claim(unknown[:]); !errors.Is(err, ErrTokenUnknown) {
		t.Fatalf("Claim(unknown) = %v, want ErrTokenUnknown", err)
	}
	if err := m.Claim(nil); !errors.Is(err, ErrTokenUnknown) {
		t.Fatalf("Claim(empty) = %v, want ErrTokenUnknown", err)
	}
}

func TestTokenConcurrentClaim(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	tok := m.Create()

	const n = 16
	results := make(chan error, n)
	for i := 0; i < n; i++ {
		go func() {
			results <- m.Claim(tok.ID[:])
		}()
	}
	var ok, used int
	for i := 0; i < n; i++ {
		switch err := <-results; {
		case err == nil:
			ok++
		case errors.Is(err, ErrTokenUsed):
			used++
		default:
			t.Fatalf("unexpected Claim error: %v", err)
		}
	}
	if ok != 1 || used != n-1 {
		t.Fatalf("claims: ok=%d used=%d, want ok=1 used=%d", ok, used, n-1)
	}
}

func TestTokenActiveCount(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	if got := m.ActiveCount(); got != 0 {
		t.Fatalf("ActiveCount(empty) = %d, want 0", got)
	}
	if m.Active() {
		t.Fatal("Active with no tokens")
	}

	a := m.Create()
	m.Create()
	if got := m.ActiveCount(); got != 2 {
		t.Fatalf("ActiveCount = %d, want 2", got)
	}

	if err := m.Claim(a.ID[:]); err != nil {
		t.Fatal(err)
	}
	if got := m.ActiveCount(); got != 1 {
		t.Fatalf("ActiveCount after claim = %d, want 1", got)
	}

	// Past the TTL both tokens are dead; the sweep inside ActiveCount drops
	// the expired one and the claimed one is not counted.
	m.now = func() time.Time { return testClockBase.Add(DefaultTokenTTL + time.Second) }
	if got := m.ActiveCount(); got != 0 {
		t.Fatalf("ActiveCount after expiry = %d, want 0", got)
	}
	if m.Active() {
		t.Fatal("Active after expiry")
	}
}

func TestTokenExpiredSurvivesSweepAsTombstone(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	tok := m.Create()

	m.now = func() time.Time { return testClockBase.Add(DefaultTokenTTL + time.Second) }
	m.ActiveCount() // triggers the sweep

	// The record is kept so a late handshake gets the honest reason. The LAN
	// gate wakes exactly at expiry and sweeps, so dropping it here told a user
	// who scanned seconds too late that the token never existed.
	if _, err := m.Lookup(tok.ID[:]); !errors.Is(err, ErrTokenExpired) {
		t.Fatalf("Lookup(expired) = %v, want ErrTokenExpired", err)
	}

	// Past the grace window the record finally goes.
	m.now = func() time.Time {
		return testClockBase.Add(DefaultTokenTTL + expiredGrace + time.Second)
	}
	m.ActiveCount()
	if _, err := m.Lookup(tok.ID[:]); !errors.Is(err, ErrTokenUnknown) {
		t.Fatalf("Lookup(swept) = %v, want ErrTokenUnknown", err)
	}
}

func TestTokenClaimedSurvivesSweepUntilExpiry(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	tok := m.Create()
	if err := m.Claim(tok.ID[:]); err != nil {
		t.Fatal(err)
	}

	// Still valid: a replayed handshake must see token_used, not
	// token_unknown.
	m.now = func() time.Time { return testClockBase.Add(4 * time.Minute) }
	if _, err := m.Lookup(tok.ID[:]); !errors.Is(err, ErrTokenUsed) {
		t.Fatalf("Lookup(claimed, valid) = %v, want ErrTokenUsed", err)
	}

	// After expiry the tombstone is swept and the error becomes expired.
	m.now = func() time.Time { return testClockBase.Add(DefaultTokenTTL + time.Second) }
	if _, err := m.Lookup(tok.ID[:]); !errors.Is(err, ErrTokenExpired) {
		t.Fatalf("Lookup(claimed, expired) = %v, want ErrTokenExpired", err)
	}
}

func TestTokenUniqueIDs(t *testing.T) {
	m := newTestTokenManager(testClockBase)
	seen := make(map[[tokenIDLen]byte]bool)
	for i := 0; i < 1000; i++ {
		tok := m.Create()
		if seen[tok.ID] {
			t.Fatalf("duplicate token id at i=%d", i)
		}
		seen[tok.ID] = true
	}
}
