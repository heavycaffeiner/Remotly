package sshcore

import (
	"crypto/sha256"
	"encoding/base64"
	"strings"

	"golang.org/x/crypto/ssh"
)

// parsePrivateKey parses a PEM or OpenSSH private key, using the passphrase
// when the key is encrypted. It accepts Ed25519, ECDSA, and RSA; the Go
// stdlib crypto is independent of Android's JCE provider table, so Ed25519
// works on every API level (D2 is structurally absent).
func parsePrivateKey(pem, passphrase []byte) (ssh.Signer, error) {
	if len(passphrase) > 0 {
		return ssh.ParsePrivateKeyWithPassphrase(pem, passphrase)
	}
	return ssh.ParsePrivateKey(pem)
}

// isAuthError reports whether err is an authentication failure from the SSH
// handshake. x/crypto/ssh surfaces a bad credential as a plain error with a
// stable message; a missing key passphrase is a typed error.
func isAuthError(err error) bool {
	if err == nil {
		return false
	}
	if _, ok := err.(*ssh.PassphraseMissingError); ok {
		return true
	}
	return strings.Contains(err.Error(), "unable to authenticate")
}

// hostKeyFingerprint returns the algorithm and the "SHA256:<base64>"
// fingerprint of a presented host key, matching the MINA bridge's
// KeyUtils.getFingerPrint output so the stored-key comparison is unchanged.
func hostKeyFingerprint(key ssh.PublicKey) (algorithm, fingerprint string) {
	sum := sha256.Sum256(key.Marshal())
	return key.Type(), "SHA256:" + base64.StdEncoding.EncodeToString(sum[:])
}

// zeroCreds clears the per-connect credential buffers after the auth
// material is built. The app passes credentials per connect and expects them
// not to be retained; this is defense in depth on the Go copy.
func zeroCreds(cfg *Config) {
	zeroByteSlice(cfg.PrivateKey)
	zeroByteSlice(cfg.Passphrase)
}

// zeroByteSlice clears a byte buffer.
func zeroByteSlice(b []byte) {
	for i := range b {
		b[i] = 0
	}
}
