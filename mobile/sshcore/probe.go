package sshcore

import (
	"errors"
	"net"
	"strconv"
	"sync"
	"time"

	"golang.org/x/crypto/ssh"
)

// ProbeResult is the outcome of a test connection.
//
// A probe authenticates and then disconnects. It never writes to the host
// store, so nothing it learns is trusted afterwards: an accepted host key here
// does not become a pinned key, and the credential is not saved.
type ProbeResult struct {
	// Ok is true when the endpoint accepted the credential.
	Ok bool
	// Code is a stable SshCode when Ok is false.
	Code string
	// Stage names the operation that failed.
	Stage string
	// Message is short and safe to display. Never a server banner.
	Message string

	// HostKeyAlgorithm and HostKeyFingerprint describe the key the server
	// presented, reported whether or not authentication succeeded.
	HostKeyAlgorithm   string
	HostKeyFingerprint string
	// HostKeyKnown is true when the caller said this key was already accepted.
	HostKeyKnown bool
	// HostKeyChanged is true when the caller has a different key pinned. The
	// probe fails closed in that case.
	HostKeyChanged bool
}

// ProbeConfig is a one-shot test connection.
type ProbeConfig struct {
	Host string
	User string
	Port int

	Password   string
	PrivateKey []byte
	Passphrase []byte

	// ExpectedFingerprints is the caller's already-accepted set, newline
	// separated. Empty means the endpoint has no pinned key yet, and the probe
	// reports what it saw without trusting it.
	ExpectedFingerprints string

	TimeoutMillis int
}

// Probe dials, verifies the host key against what the caller already trusts,
// authenticates, and closes. Bounded by its own timeout; it never blocks on a
// user decision, because a test connection has no prompt.
func Probe(cfg *ProbeConfig) *ProbeResult {
	if cfg == nil {
		return &ProbeResult{Code: CodeConnectFailed, Stage: StageDial, Message: "no configuration"}
	}
	hasPassword := cfg.Password != ""
	hasKey := len(cfg.PrivateKey) > 0
	if hasPassword == hasKey {
		return &ProbeResult{
			Code:    CodeAuthFailed,
			Stage:   StageAuth,
			Message: "exactly one of password or private key is required",
		}
	}

	timeout := time.Duration(cfg.TimeoutMillis) * time.Millisecond
	if timeout <= 0 {
		timeout = 15 * time.Second
	}

	result := &ProbeResult{}
	expected := splitFingerprints(cfg.ExpectedFingerprints)

	// The caller's key and passphrase buffers are cleared on every exit path,
	// including the early ones, so a test connection does not leave the secret
	// in memory.
	defer zeroProbeCreds(cfg)

	addr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		result.Code, result.Stage = CodeConnectFailed, StageDial
		result.Message = err.Error()
		return result
	}
	defer conn.Close()

	var signer ssh.Signer
	if hasKey {
		signer, err = parsePrivateKey(cfg.PrivateKey, cfg.Passphrase)
		if err != nil {
			result.Code, result.Stage = CodeAuthFailed, StageAuth
			result.Message = err.Error()
			return result
		}
	}

	var mu sync.Mutex
	hostKeyRejected := false

	clientCfg := &ssh.ClientConfig{
		User:    cfg.User,
		Timeout: timeout,
		HostKeyCallback: func(_ string, _ net.Addr, key ssh.PublicKey) error {
			alg, fp := hostKeyFingerprint(key)
			mu.Lock()
			result.HostKeyAlgorithm = alg
			result.HostKeyFingerprint = fp
			mu.Unlock()

			if len(expected) == 0 {
				// Nothing pinned yet. The probe continues so the user learns
				// whether the credential works, but the key is only reported,
				// never trusted here.
				return nil
			}
			for _, want := range expected {
				if want == fp {
					mu.Lock()
					result.HostKeyKnown = true
					mu.Unlock()
					return nil
				}
			}
			// A pinned key that no longer matches fails closed, exactly as the
			// real connection does.
			mu.Lock()
			result.HostKeyChanged = true
			hostKeyRejected = true
			mu.Unlock()
			return errors.New("host key changed")
		},
	}

	if signer != nil {
		clientCfg.Auth = append(clientCfg.Auth, ssh.PublicKeys(signer))
	}
	if cfg.Password != "" {
		password := cfg.Password
		clientCfg.Auth = append(clientCfg.Auth, ssh.Password(password))
		clientCfg.Auth = append(clientCfg.Auth, ssh.KeyboardInteractive(passwordChallenge(password)))
	}

	clientConn, chans, reqs, err := ssh.NewClientConn(conn, addr, clientCfg)
	if err != nil {
		mu.Lock()
		changed := hostKeyRejected
		mu.Unlock()
		switch {
		case changed:
			result.Code, result.Stage = CodeHostKeyChanged, StageHostKey
			result.Message = "the host key does not match the one this device accepted"
		case isAuthError(err):
			result.Code, result.Stage = CodeAuthFailed, StageAuth
			result.Message = "the server rejected the credential"
		default:
			result.Code, result.Stage = CodeConnectFailed, StageHandshake
			result.Message = err.Error()
		}
		return result
	}

	// Authentication succeeded. Close immediately: a probe has no session.
	client := ssh.NewClient(clientConn, chans, reqs)
	_ = client.Close()

	result.Ok = true
	return result
}

func splitFingerprints(s string) []string {
	if s == "" {
		return nil
	}
	var out []string
	start := 0
	for i := 0; i <= len(s); i++ {
		if i == len(s) || s[i] == '\n' {
			if i > start {
				out = append(out, s[start:i])
			}
			start = i + 1
		}
	}
	return out
}

func zeroProbeCreds(cfg *ProbeConfig) {
	zeroByteSlice(cfg.PrivateKey)
	zeroByteSlice(cfg.Passphrase)
}
