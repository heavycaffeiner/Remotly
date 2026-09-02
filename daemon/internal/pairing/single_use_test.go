package pairing

import (
	"errors"
	"sync"
	"testing"
)

// A pairing token admits exactly one handshake.
//
// This is what makes concurrent dialing incorrect rather than merely wasteful:
// a client that races several addresses against one token has the first
// handshake claim it and every other refused with ErrTokenUsed, so the attempt
// fails even though an address answered. The app dials its targets in turn for
// this reason, and this test is the contract that says why.
func TestTokenAdmitsOneHandshake(t *testing.T) {
	m := NewTokenManager()
	tok := m.Create()

	const dials = 4
	var wg sync.WaitGroup
	errs := make([]error, dials)
	start := make(chan struct{})
	for i := range dials {
		wg.Add(1)
		go func(i int) {
			defer wg.Done()
			<-start
			errs[i] = m.Claim(tok.ID[:])
		}(i)
	}
	close(start)
	wg.Wait()

	claimed := 0
	for _, err := range errs {
		switch {
		case err == nil:
			claimed++
		case errors.Is(err, ErrTokenUsed):
		default:
			t.Fatalf("unexpected claim error: %v", err)
		}
	}
	if claimed != 1 {
		t.Fatalf("%d concurrent claims succeeded, want exactly 1", claimed)
	}

	// A refused claim does not release the token either: it stays used.
	if err := m.Claim(tok.ID[:]); !errors.Is(err, ErrTokenUsed) {
		t.Fatalf("claim after the race = %v, want ErrTokenUsed", err)
	}
}
