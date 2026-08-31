package sshcore

import (
	"errors"
	"testing"

	"golang.org/x/crypto/ssh"
)

// The test connection. It has to authenticate exactly like the real client and
// then leave nothing behind: no saved host, no pinned key, no stored
// credential.

func probeConfig(host string, port int) *ProbeConfig {
	return &ProbeConfig{
		Host: host, User: "tester", Port: port,
		Password:      testPassword,
		TimeoutMillis: 5000,
	}
}

func TestProbeSucceedsWithAValidPassword(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	res := Probe(probeConfig(s.host(), s.port()))
	if !res.Ok {
		t.Fatalf("probe failed: %s %s %s", res.Code, res.Stage, res.Message)
	}
	if res.HostKeyFingerprint == "" {
		t.Fatal("the probe did not report the host key")
	}
}


func TestProbeReportsHostKeyForAnUnpinnedEndpoint(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	res := Probe(probeConfig(s.host(), s.port()))
	if res.HostKeyAlgorithm == "" || res.HostKeyFingerprint == "" {
		t.Fatal("the fingerprint must be reported so the user can compare it")
	}
	// Nothing was pinned, so it is neither known nor changed. The probe
	// reports it; trusting it is a separate, explicit act.
	if res.HostKeyKnown || res.HostKeyChanged {
		t.Fatalf("unexpected trust state: known=%v changed=%v", res.HostKeyKnown, res.HostKeyChanged)
	}
}

func TestProbeAcceptsAPinnedKeyThatMatches(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	first := Probe(probeConfig(s.host(), s.port()))
	if !first.Ok {
		t.Fatalf("probe failed: %s", first.Message)
	}

	cfg := probeConfig(s.host(), s.port())
	cfg.ExpectedFingerprints = first.HostKeyFingerprint
	res := Probe(cfg)
	if !res.Ok {
		t.Fatalf("probe with a matching pin failed: %s", res.Message)
	}
	if !res.HostKeyKnown {
		t.Fatal("a matching pinned key should be reported as known")
	}
}

func TestProbeFailsClosedOnAChangedHostKey(t *testing.T) {
	// The security property: a mismatch is refused, never silently accepted.
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	cfg := probeConfig(s.host(), s.port())
	cfg.ExpectedFingerprints = "SHA256:definitely-not-this-server"
	res := Probe(cfg)

	if res.Ok {
		t.Fatal("a changed host key must not pass the probe")
	}
	if res.Code != CodeHostKeyChanged {
		t.Fatalf("code = %s, want %s", res.Code, CodeHostKeyChanged)
	}
	if !res.HostKeyChanged {
		t.Fatal("the changed flag should be set")
	}
}

func TestProbeReportsAuthFailure(t *testing.T) {
	s := startInteropServer(t, passwordServerConfig(t), &interopServer{})
	cfg := probeConfig(s.host(), s.port())
	cfg.Password = "wrong-password"
	res := Probe(cfg)

	if res.Ok {
		t.Fatal("a wrong password must not pass")
	}
	if res.Code != CodeAuthFailed || res.Stage != StageAuth {
		t.Fatalf("code=%s stage=%s, want auth failure", res.Code, res.Stage)
	}
}

func TestProbeReportsADialFailure(t *testing.T) {
	cfg := probeConfig("127.0.0.1", 1)
	cfg.TimeoutMillis = 2000
	res := Probe(cfg)

	if res.Ok {
		t.Fatal("a refused port must not pass")
	}
	if res.Stage != StageDial {
		t.Fatalf("stage = %s, want %s", res.Stage, StageDial)
	}
}

func TestProbeRejectsAmbiguousCredentials(t *testing.T) {
	cfg := probeConfig("127.0.0.1", 22)
	cfg.PrivateKey = []byte("-----BEGIN OPENSSH PRIVATE KEY-----")
	// Both a password and a key: the caller has to pick one.
	res := Probe(cfg)
	if res.Ok || res.Code != CodeAuthFailed {
		t.Fatalf("expected an auth configuration error, got %+v", res)
	}
}

func TestProbeRejectsAnUnreadableKey(t *testing.T) {
	cfg := probeConfig("127.0.0.1", 22)
	cfg.Password = ""
	cfg.PrivateKey = []byte("not a private key")
	res := Probe(cfg)

	if res.Ok {
		t.Fatal("an unparseable key must not pass")
	}
	if res.Stage != StageAuth {
		t.Fatalf("stage = %s, want %s", res.Stage, StageAuth)
	}
}

func TestProbeZeroesTheKeyBuffer(t *testing.T) {
	// The caller's buffer is cleared once the auth material is built, so the
	// key does not linger in memory after a test connection.
	key := []byte("-----BEGIN OPENSSH PRIVATE KEY-----\nnot real\n")
	cfg := probeConfig("127.0.0.1", 1)
	cfg.Password = ""
	cfg.PrivateKey = key
	cfg.TimeoutMillis = 1000
	_ = Probe(cfg)

	for i, b := range key {
		if b != 0 {
			t.Fatalf("key byte %d was not cleared", i)
		}
	}
}

func TestProbeUsesKeyboardInteractiveWhenPasswordAuthIsOff(t *testing.T) {
	// The Windows default policy. The probe must reach the same verdict the
	// real connection would.
	cfg := &ssh.ServerConfig{
		KeyboardInteractiveCallback: func(
			_ ssh.ConnMetadata,
			challenge ssh.KeyboardInteractiveChallenge,
		) (*ssh.Permissions, error) {
			answers, err := challenge("", "", []string{"Password: "}, []bool{false})
			if err != nil {
				return nil, err
			}
			if len(answers) != 1 || answers[0] != testPassword {
				return nil, errors.New("bad password")
			}
			return nil, nil
		},
	}
	cfg.AddHostKey(testHostKey(t))

	s := startInteropServer(t, cfg, &interopServer{})
	res := Probe(probeConfig(s.host(), s.port()))
	if !res.Ok {
		t.Fatalf("keyboard-interactive probe failed: %s %s", res.Code, res.Message)
	}
}

func TestSplitFingerprints(t *testing.T) {
	got := splitFingerprints("a\nb\n\nc")
	want := []string{"a", "b", "c"}
	if len(got) != len(want) {
		t.Fatalf("got %v, want %v", got, want)
	}
	for i := range want {
		if got[i] != want[i] {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
	if splitFingerprints("") != nil {
		t.Fatal("an empty string should yield no fingerprints")
	}
}
