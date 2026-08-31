package pairing

import (
	"bytes"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"errors"
	"fmt"
	"os"
	"path/filepath"
)

// x25519 is the curve used for all Remotly key material, accessed through the
// standard library's ecdh package.
var x25519 = ecdh.X25519()

// identityFile is the on-disk schema of identity.json.
type identityFile struct {
	Version int    `json:"version"`
	Public  string `json:"public"`
	Private string `json:"private"`
}

// Identity is the daemon's long-term X25519 keypair. It is generated once and
// persisted; it is the key the app pins and pre-seeds on IK reconnects. The
// private half never leaves the process except into the 0600 identity file.
type Identity struct {
	private [32]byte
	public  [32]byte
}

// PublicBytes returns the 32-byte public key for the URI, the hello response,
// and Noise configuration.
func (id *Identity) PublicBytes() [32]byte { return id.public }

// KeyPair returns both halves of the identity as raw 32-byte values, for
// building Noise keypairs. The private half never leaves the process except
// into the 0600 identity file; this accessor exists because the Noise
// library takes byte slices.
func (id *Identity) KeyPair() (private, public [32]byte) {
	return id.private, id.public
}

// NewIdentity generates a fresh keypair in memory. It does not touch disk.
func NewIdentity() (*Identity, error) {
	key, err := x25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, fmt.Errorf("pairing: generate identity: %w", err)
	}
	id := &Identity{}
	if err := id.setFromKey(key); err != nil {
		return nil, err
	}
	return id, nil
}

// setFromKey copies the ecdh key's private and public halves into the
// identity.
func (id *Identity) setFromKey(key *ecdh.PrivateKey) error {
	priv := key.Bytes()
	if len(priv) != 32 {
		return errors.New("pairing: bad private key length")
	}
	pub := key.PublicKey().Bytes()
	if len(pub) != 32 {
		return errors.New("pairing: bad public key length")
	}
	copy(id.private[:], priv)
	copy(id.public[:], pub)
	return nil
}

func identityPath(dir string) string { return filepath.Join(dir, "identity.json") }

// LoadOrCreateIdentity returns the daemon's long-term identity, generating and
// persisting it if the file is absent. A present but corrupt file is an error:
// regenerating would change the daemon's identity and silently invalidate every
// paired device, so the daemon fails to start instead.
func LoadOrCreateIdentity(dir string) (*Identity, error) {
	path := identityPath(dir)
	id, err := loadIdentity(path)
	if err == nil {
		return id, nil
	}
	if !errors.Is(err, os.ErrNotExist) {
		return nil, err
	}
	id, err = NewIdentity()
	if err != nil {
		return nil, err
	}
	if err := id.save(path); err != nil {
		return nil, err
	}
	return id, nil
}

func loadIdentity(path string) (*Identity, error) {
	var f identityFile
	if err := readJSONFile(path, maxStateBytes, &f); err != nil {
		return nil, err
	}
	if f.Version != 1 {
		return nil, fmt.Errorf("pairing: unsupported identity version %d (want 1)", f.Version)
	}
	pub, err := base64.RawURLEncoding.DecodeString(f.Public)
	if err != nil || len(pub) != 32 {
		return nil, errors.New("pairing: corrupt identity public key")
	}
	priv, err := base64.RawURLEncoding.DecodeString(f.Private)
	if err != nil || len(priv) != 32 {
		return nil, errors.New("pairing: corrupt identity private key")
	}
	// The private key must derive the stored public key; a mismatch means the
	// file was hand-edited or mixed from two identities.
	key, err := x25519.NewPrivateKey(priv)
	if err != nil {
		return nil, errors.New("pairing: corrupt identity private key")
	}
	if !bytes.Equal(key.PublicKey().Bytes(), pub) {
		return nil, errors.New("pairing: identity private/public key mismatch")
	}
	id := &Identity{}
	copy(id.private[:], priv)
	copy(id.public[:], pub)
	return id, nil
}

func (id *Identity) save(path string) error {
	f := identityFile{
		Version: 1,
		Public:  base64.RawURLEncoding.EncodeToString(id.public[:]),
		Private: base64.RawURLEncoding.EncodeToString(id.private[:]),
	}
	return writeJSONAtomic(path, f)
}
