//go:build !windows

package localctl

import (
	"encoding/base64"
	"encoding/json"
	"fmt"
	"net"
	"os"
	"testing"
	"time"

	"github.com/heavycaffeiner/remotly/daemon/internal/pairing"
	"github.com/heavycaffeiner/remotly/daemon/internal/pty"
	"github.com/heavycaffeiner/remotly/daemon/internal/session"
)

// daemonPub is the fixed long-term public key the test buildURI binds into
// every pairing URI.
var daemonPub = [32]byte{1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
	17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32}

type testEnv struct {
	path     string
	tokens   *pairing.TokenManager
	devices  *pairing.DeviceStore
	sessions *session.Manager
}

// startServer boots a localctl server over real pairing state and an empty
// session manager, wired exactly like the daemon.
func startServer(t *testing.T) *testEnv {
	t.Helper()
	dataDir := t.TempDir()

	tokens := pairing.NewTokenManager()
	devices, err := pairing.LoadDeviceStore(dataDir)
	if err != nil {
		t.Fatalf("LoadDeviceStore: %v", err)
	}
	sessions, err := session.New(session.Options{Backend: pty.New()})
	if err != nil {
		t.Fatalf("session.New: %v", err)
	}

	buildURI := func() (string, int64, error) {
		tok := tokens.Create()
		uri, err := pairing.EncodeURI(pairing.URIPayload{
			Version:      1,
			TokenID:      tok.ID[:],
			Secret:       tok.Secret[:],
			Expires:      tok.Expires.Unix(),
			EphemeralPub: tok.EphemPub,
			DaemonPub:    daemonPub,
			Hints:        []pairing.Hint{{Kind: pairing.HintName, Addr: "localhost", Port: 8443}},
			DaemonName:   "test-daemon",
		})
		return uri, tok.Expires.Unix(), err
	}

	env := &testEnv{
		path:     Path(dataDir),
		tokens:   tokens,
		devices:  devices,
		sessions: sessions,
	}
	srv := NewServer(env.path, nil, tokens, devices, sessions, buildURI)
	if err := srv.Start(); err != nil {
		t.Fatalf("Start: %v", err)
	}
	t.Cleanup(func() { _ = srv.Close() })
	return env
}

func TestSocketPermissions(t *testing.T) {
	env := startServer(t)
	fi, err := os.Stat(env.path)
	if err != nil {
		t.Fatalf("stat socket: %v", err)
	}
	if perm := fi.Mode().Perm(); perm != 0o600 {
		t.Fatalf("socket mode = %o, want 0600", perm)
	}
}

func TestCloseRemovesSocket(t *testing.T) {
	dataDir := t.TempDir()
	tokens := pairing.NewTokenManager()
	devices, err := pairing.LoadDeviceStore(dataDir)
	if err != nil {
		t.Fatal(err)
	}
	path := Path(dataDir)
	srv := NewServer(path, nil, tokens, devices, nil, nil)
	if err := srv.Start(); err != nil {
		t.Fatal(err)
	}
	if err := srv.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatalf("socket still present after Close: %v", err)
	}
}

func TestStatusEmpty(t *testing.T) {
	env := startServer(t)
	resp, err := Call(env.path, Request{Op: "status"})
	if err != nil {
		t.Fatalf("Call(status): %v", err)
	}
	if !resp.OK {
		t.Fatalf("status not ok: %s", resp.Err)
	}
	if resp.ActiveTokens != 0 || resp.PairedDevices != 0 || resp.LANAllowed {
		t.Fatalf("unexpected status: %+v", resp)
	}
}

func TestPairMintsTokenAndURI(t *testing.T) {
	env := startServer(t)
	before := time.Now().Unix()

	resp, err := Call(env.path, Request{Op: "pair"})
	if err != nil {
		t.Fatalf("Call(pair): %v", err)
	}
	if !resp.OK {
		t.Fatalf("pair not ok: %s", resp.Err)
	}
	if resp.URI == "" {
		t.Fatal("pair returned no URI")
	}
	if resp.Expires <= before || resp.Expires > before+int64(pairing.DefaultTokenTTL) {
		t.Fatalf("expires %d outside (%d, %d]", resp.Expires, before, before+int64(pairing.DefaultTokenTTL))
	}

	payload, err := pairing.DecodeURI(resp.URI)
	if err != nil {
		t.Fatalf("DecodeURI: %v", err)
	}
	if len(payload.TokenID) != 16 || len(payload.Secret) != 32 {
		t.Fatalf("token material sizes: id=%d secret=%d", len(payload.TokenID), len(payload.Secret))
	}
	if payload.DaemonPub != daemonPub {
		t.Fatal("daemon pub mismatch in URI")
	}
	if payload.EphemeralPub == [32]byte{} {
		t.Fatal("ephemeral pub is all zero")
	}
	if payload.DaemonName != "test-daemon" {
		t.Fatalf("daemon name = %q", payload.DaemonName)
	}
	if len(payload.Hints) != 1 || payload.Hints[0].Addr != "localhost" || payload.Hints[0].Port != 8443 {
		t.Fatalf("hints = %+v", payload.Hints)
	}
	if payload.Expires != resp.Expires {
		t.Fatalf("URI expires %d != response expires %d", payload.Expires, resp.Expires)
	}

	// The URI must carry the live token's handshake material.
	snap, err := env.tokens.Lookup(payload.TokenID)
	if err != nil {
		t.Fatalf("Lookup(token from URI): %v", err)
	}
	if snap.EphemeralPub != payload.EphemeralPub || snap.Secret != [32]byte(payload.Secret) {
		t.Fatal("URI does not match the live token")
	}

	// The token is now visible to the LAN gate.
	resp, err = Call(env.path, Request{Op: "status"})
	if err != nil {
		t.Fatal(err)
	}
	if resp.ActiveTokens != 1 || !resp.LANAllowed {
		t.Fatalf("status after pair = %+v, want ActiveTokens=1 LANAllowed=true", resp)
	}
}

func TestDevicesAndRevoke(t *testing.T) {
	env := startServer(t)
	var pub [32]byte
	for i := range pub {
		pub[i] = byte(i + 1)
	}
	if _, err := env.devices.Pair(pub, "phone"); err != nil {
		t.Fatalf("Pair: %v", err)
	}
	wantPub := base64.RawURLEncoding.EncodeToString(pub[:])

	resp, err := Call(env.path, Request{Op: "devices"})
	if err != nil {
		t.Fatal(err)
	}
	if !resp.OK || len(resp.Devices) != 1 {
		t.Fatalf("devices = %+v", resp)
	}
	if resp.Devices[0].Public != wantPub || resp.Devices[0].Name != "phone" {
		t.Fatalf("device = %+v", resp.Devices[0])
	}

	// Revoke it.
	resp, err = Call(env.path, Request{Op: "revoke", Public: wantPub})
	if err != nil || !resp.OK {
		t.Fatalf("revoke: %v %s", err, resp.Err)
	}
	resp, err = Call(env.path, Request{Op: "devices"})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Devices) != 0 {
		t.Fatalf("devices after revoke = %+v, want empty", resp.Devices)
	}

	// Idempotent.
	resp, err = Call(env.path, Request{Op: "revoke", Public: wantPub})
	if err != nil || !resp.OK {
		t.Fatalf("second revoke: %v %s", err, resp.Err)
	}

	// Unknown key fails.
	var unknown [32]byte
	resp, err = Call(env.path, Request{Op: "revoke", Public: base64.RawURLEncoding.EncodeToString(unknown[:])})
	if err != nil {
		t.Fatal(err)
	}
	if resp.OK {
		t.Fatal("revoke of unknown key reported ok")
	}

	// The LAN gate follows paired devices even without live tokens.
	resp, err = Call(env.path, Request{Op: "status"})
	if err != nil {
		t.Fatal(err)
	}
	if resp.PairedDevices != 0 || resp.LANAllowed {
		t.Fatalf("status = %+v, want no devices and LAN off", resp)
	}
}

func TestRevokeBadKey(t *testing.T) {
	env := startServer(t)
	for _, bad := range []string{"", "abc", base64.RawURLEncoding.EncodeToString(make([]byte, 16))} {
		resp, err := Call(env.path, Request{Op: "revoke", Public: bad})
		if err != nil {
			t.Fatalf("Call(revoke %q): %v", bad, err)
		}
		if resp.OK {
			t.Fatalf("revoke %q reported ok", bad)
		}
	}
}

func TestSessionsAndKill(t *testing.T) {
	env := startServer(t)

	resp, err := Call(env.path, Request{Op: "sessions"})
	if err != nil {
		t.Fatal(err)
	}
	if !resp.OK || len(resp.Sessions) != 0 {
		t.Fatalf("sessions = %+v, want empty", resp)
	}

	// Unknown kill targets.
	if resp, _ = Call(env.path, Request{Op: "session_kill"}); resp.OK {
		t.Fatal("kill with missing id reported ok")
	}
	if resp, _ = Call(env.path, Request{Op: "session_kill", SessionID: "nope"}); resp.OK {
		t.Fatal("kill of unknown session reported ok")
	}

	// A real shell session: create, see it listed, kill it, see it go.
	sess, err := env.sessions.Create(session.Request{
		Kind: session.KindShell, Title: "ctl", Cols: 80, Rows: 24,
	})
	if err != nil {
		t.Fatalf("session create: %v", err)
	}
	id := sess.ID()

	resp, err = Call(env.path, Request{Op: "sessions"})
	if err != nil {
		t.Fatal(err)
	}
	if len(resp.Sessions) != 1 || resp.Sessions[0].ID != id || !resp.Sessions[0].Running {
		t.Fatalf("sessions = %+v, want one running %s", resp.Sessions, id)
	}
	if resp.Sessions[0].Kind != string(session.KindShell) || resp.Sessions[0].Title != "ctl" {
		t.Fatalf("session meta = %+v", resp.Sessions[0])
	}

	resp, err = Call(env.path, Request{Op: "session_kill", SessionID: id})
	if err != nil || !resp.OK {
		t.Fatalf("session_kill: %v %s", err, resp.Err)
	}

	// The exit is async: poll until the manager has reaped the process. The
	// killed session stays listed with running=false for its post-exit
	// retention window, where it remains attachable for final replay.
	deadline := time.Now().Add(5 * time.Second)
	for {
		resp, err = Call(env.path, Request{Op: "sessions"})
		if err != nil {
			t.Fatal(err)
		}
		if len(resp.Sessions) == 1 && !resp.Sessions[0].Running {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("session %s not exited after kill: %+v", id, resp.Sessions)
		}
		time.Sleep(50 * time.Millisecond)
	}
}

func TestUnknownOp(t *testing.T) {
	env := startServer(t)
	resp, err := Call(env.path, Request{Op: "warp"})
	if err != nil {
		t.Fatal(err)
	}
	if resp.OK {
		t.Fatal("unknown op reported ok")
	}
}

func TestBadRequest(t *testing.T) {
	env := startServer(t)

	conn, err := net.Dial("unix", env.path)
	if err != nil {
		t.Fatalf("dial: %v", err)
	}
	defer conn.Close()
	_ = conn.SetDeadline(time.Now().Add(2 * time.Second))
	if _, err := fmt.Fprint(conn, "this is not json"); err != nil {
		t.Fatal(err)
	}
	var resp Response
	if err := json.NewDecoder(conn).Decode(&resp); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if resp.OK || resp.Err == "" {
		t.Fatalf("response = %+v, want not ok with error", resp)
	}
}
