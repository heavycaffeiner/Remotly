package sshcore

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/ed25519"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/rsa"
	"encoding/binary"
	"encoding/pem"
	"errors"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

// --- in-process SSH server (PTY echo) --------------------------------------

const testPassword = "testpass"

type testServer struct {
	addr    string
	hostKey ssh.Signer

	mu     sync.Mutex
	gotPty bool
	// last window change as (rows, cols)
	window [2]int
}

func startTestServer(t *testing.T) *testServer {
	t.Helper()
	_, edPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hostKey, err := ssh.NewSignerFromKey(edPriv)
	if err != nil {
		t.Fatal(err)
	}
	config := &ssh.ServerConfig{
		PasswordCallback: func(_ ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			if string(password) == testPassword {
				return nil, nil
			}
			return nil, errors.New("bad password")
		},
		PublicKeyCallback: func(_ ssh.ConnMetadata, _ ssh.PublicKey) (*ssh.Permissions, error) {
			return nil, nil
		},
	}
	config.AddHostKey(hostKey)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ts := &testServer{addr: ln.Addr().String(), hostKey: hostKey}
	t.Cleanup(func() { ln.Close() })

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go ts.handleConn(conn, config)
		}
	}()
	return ts
}

func (ts *testServer) handleConn(conn net.Conn, config *ssh.ServerConfig) {
	sconn, chans, reqs, err := ssh.NewServerConn(conn, config)
	if err != nil {
		conn.Close()
		return
	}
	defer sconn.Close()
	go ssh.DiscardRequests(reqs)
	for newChan := range chans {
		if newChan.ChannelType() != "session" {
			newChan.Reject(ssh.UnknownChannelType, "unknown channel type")
			continue
		}
		ch, requests, err := newChan.Accept()
		if err != nil {
			continue
		}
		go ts.handleSession(ch, requests)
	}
}

func (ts *testServer) handleSession(ch ssh.Channel, requests <-chan *ssh.Request) {
	defer ch.Close()
	go func() {
		buf := make([]byte, 32*1024)
		for {
			n, err := ch.Read(buf)
			if n > 0 {
				_, _ = ch.Write(buf[:n])
			}
			if err != nil {
				return
			}
		}
	}()
	for req := range requests {
		switch req.Type {
		case "pty-req":
			ts.mu.Lock()
			ts.gotPty = true
			ts.mu.Unlock()
			if req.WantReply {
				req.Reply(true, nil)
			}
		case "shell":
			_, _ = ch.Write([]byte("SHELL-READY\n"))
			if req.WantReply {
				req.Reply(true, nil)
			}
		case "window-change":
			var w struct {
				Columns uint32
				Rows    uint32
			}
			ssh.Unmarshal(req.Payload, &w)
			ts.mu.Lock()
			ts.window = [2]int{int(w.Columns), int(w.Rows)}
			ts.mu.Unlock()
			if req.WantReply {
				req.Reply(true, nil)
			}
		default:
			if req.WantReply {
				req.Reply(false, nil)
			}
		}
	}
}

func (ts *testServer) host() string { h, _, _ := net.SplitHostPort(ts.addr); return h }
func (ts *testServer) port() int {
	_, p, _ := net.SplitHostPort(ts.addr)
	var port int
	fmt.Sscanf(p, "%d", &port)
	return port
}

// --- test listener ---------------------------------------------------------

type testListener struct {
	sess *Session

	hostKey chan struct{ alg, fp string }
	ready   chan struct{}
	data    chan []byte
	closed  chan struct{ code int }
	failed  chan struct{ code, msg string }
	auto    bool
}

func newTestListener() *testListener {
	return &testListener{
		hostKey: make(chan struct{ alg, fp string }, 4),
		ready:   make(chan struct{}, 1),
		data:    make(chan []byte, 64),
		closed:  make(chan struct{ code int }, 1),
		failed:  make(chan struct{ code, msg string }, 1),
		auto:    true,
	}
}

func (l *testListener) OnHostKey(alg, fp string) {
	select {
	case l.hostKey <- struct{ alg, fp string }{alg, fp}:
	default:
	}
	if l.auto {
		l.sess.DecideHostKey(true)
	}
}
func (l *testListener) OnReady() {
	select {
	case l.ready <- struct{}{}:
	default:
	}
}
func (l *testListener) OnData(d []byte) { l.data <- d }
func (l *testListener) OnClosed(code int, _ string) {
	select {
	case l.closed <- struct{ code int }{code}:
	default:
	}
}
func (l *testListener) OnFailure(code, msg string) {
	select {
	case l.failed <- struct{ code, msg string }{code, msg}:
	default:
	}
}

func testConfig(h string, port int) *Config {
	return &Config{
		Host: h, User: "tester", Port: port,
		Password: testPassword,
		Cols:     80, Rows: 24, ConnectTimeout: 5000,
	}
}

func waitData(t *testing.T, l *testListener, want string, d time.Duration) string {
	t.Helper()
	deadline := time.Now().Add(d)
	var got []byte
	for time.Now().Before(deadline) {
		select {
		case b := <-l.data:
			got = append(got, b...)
			if containsBytes(got, []byte(want)) {
				return string(got)
			}
		default:
			time.Sleep(5 * time.Millisecond)
		}
	}
	return string(got)
}

func containsBytes(hay, needle []byte) bool {
	for i := 0; i+len(needle) <= len(hay); i++ {
		if string(hay[i:i+len(needle)]) == string(needle) {
			return true
		}
	}
	return false
}

func hasPrefix(s, p string) bool { return len(s) >= len(p) && s[:len(p)] == p }

func mustReady(t *testing.T, l *testListener) {
	t.Helper()
	select {
	case <-l.ready:
	case c := <-l.failed:
		t.Fatalf("session failed: %s %s", c.code, c.msg)
	case <-time.After(10 * time.Second):
		t.Fatal("timed out waiting for ready")
	}
}

// --- terminal tests --------------------------------------------------------

func TestPasswordAuthAndPtyEcho(t *testing.T) {
	ts := startTestServer(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(ts.host(), ts.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)

	select {
	case hk := <-l.hostKey:
		if !hasPrefix(hk.fp, "SHA256:") {
			t.Errorf("fingerprint not SHA256-qualified: %s", hk.fp)
		}
	case <-time.After(2 * time.Second):
		t.Error("expected a host-key challenge")
	}

	sess.Write([]byte("echo hi\r"))
	out := waitData(t, l, "echo hi", 5*time.Second)
	if !containsBytes([]byte(out), []byte("echo hi")) {
		t.Errorf("pty echo missing; got %q", out)
	}

	ts.mu.Lock()
	gotPty := ts.gotPty
	ts.mu.Unlock()
	if !gotPty {
		t.Error("server did not receive a pty-req")
	}

	sess.Close()
	select {
	case c := <-l.closed:
		if c.code != CloseGoingAway {
			t.Errorf("close code = %d, want %d", c.code, CloseGoingAway)
		}
	case <-time.After(5 * time.Second):
		t.Error("timed out waiting for close")
	}
}

func TestWindowChange(t *testing.T) {
	ts := startTestServer(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(ts.host(), ts.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)

	sess.WindowChange(120, 40) // cols=120, rows=40
	deadline := time.Now().Add(5 * time.Second)
	for {
		ts.mu.Lock()
		w := ts.window
		ts.mu.Unlock()
		if w == [2]int{120, 40} {
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("window change not observed; last=%v want [120 40]", w)
		}
		time.Sleep(5 * time.Millisecond)
	}
	sess.Close()
}

func TestHostKeyReject(t *testing.T) {
	ts := startTestServer(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	l.auto = false
	if err := sess.Connect(testConfig(ts.host(), ts.port())); err != nil {
		t.Fatal(err)
	}
	select {
	case <-l.hostKey:
	case <-time.After(5 * time.Second):
		t.Fatal("no host-key challenge")
	}
	sess.DecideHostKey(false)
	select {
	case f := <-l.failed:
		if f.code != CodeHostKeyRejected {
			t.Errorf("failure code = %s, want %s", f.code, CodeHostKeyRejected)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("timed out waiting for host-key rejection failure")
	}
}

// TestHostKeySlowDecision proves D1 is gone: a decision made well after the
// connect timeout (1.5s) still succeeds because only the 120s prompt bound
// runs during the handshake.
func TestHostKeySlowDecision(t *testing.T) {
	if testing.Short() {
		t.Skip("slow (35s)")
	}
	ts := startTestServer(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	l.auto = false
	cfg := testConfig(ts.host(), ts.port())
	cfg.ConnectTimeout = 1500
	if err := sess.Connect(cfg); err != nil {
		t.Fatal(err)
	}
	select {
	case <-l.hostKey:
	case <-time.After(5 * time.Second):
		t.Fatal("no host-key challenge")
	}
	time.Sleep(35 * time.Second)
	sess.DecideHostKey(true)
	mustReady(t, l)
	sess.Close()
}

// --- key auth (D2: Ed25519 works without JCE) ------------------------------

func opensshKey(t *testing.T, key crypto.PrivateKey, passphrase []byte) []byte {
	t.Helper()
	var block *pem.Block
	var err error
	if len(passphrase) > 0 {
		block, err = ssh.MarshalPrivateKeyWithPassphrase(key, "", passphrase)
	} else {
		block, err = ssh.MarshalPrivateKey(key, "")
	}
	if err != nil {
		t.Fatal(err)
	}
	return pem.EncodeToMemory(block)
}

func TestKeyAuth(t *testing.T) {
	cases := map[string]func(t *testing.T) (keyBytes, passphrase []byte){
		"ed25519": func(t *testing.T) ([]byte, []byte) {
			_, priv, _ := ed25519.GenerateKey(rand.Reader)
			return opensshKey(t, priv, nil), nil
		},
		"rsa": func(t *testing.T) ([]byte, []byte) {
			k, _ := rsa.GenerateKey(rand.Reader, 2048)
			return opensshKey(t, k, nil), nil
		},
		"ecdsa": func(t *testing.T) ([]byte, []byte) {
			k, _ := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
			return opensshKey(t, k, nil), nil
		},
		"encrypted-ed25519": func(t *testing.T) ([]byte, []byte) {
			_, priv, _ := ed25519.GenerateKey(rand.Reader)
			return opensshKey(t, priv, []byte("sekret")), []byte("sekret")
		},
	}
	for name, gen := range cases {
		t.Run(name, func(t *testing.T) {
			ts := startTestServer(t)
			keyBytes, passphrase := gen(t)
			l := newTestListener()
			sess := NewSession(l)
			l.sess = sess
			cfg := testConfig(ts.host(), ts.port())
			cfg.Password = ""
			cfg.PrivateKey = keyBytes
			cfg.Passphrase = passphrase
			if err := sess.Connect(cfg); err != nil {
				t.Fatal(err)
			}
			mustReady(t, l)
			sess.Close()
		})
	}
}

func TestAuthFailure(t *testing.T) {
	ts := startTestServer(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	cfg := testConfig(ts.host(), ts.port())
	cfg.Password = "wrong"
	if err := sess.Connect(cfg); err != nil {
		t.Fatal(err)
	}
	select {
	case f := <-l.failed:
		if f.code != CodeAuthFailed {
			t.Errorf("failure code = %s, want %s", f.code, CodeAuthFailed)
		}
	case <-l.ready:
		t.Error("expected auth failure, got ready")
	case <-time.After(10 * time.Second):
		t.Fatal("timed out")
	}
}

func TestConfigValidation(t *testing.T) {
	sess := NewSession(nil)
	if err := sess.Connect(&Config{Host: "h", Port: 22, User: "u"}); err == nil {
		t.Error("expected error when no credential provided")
	}
	cfg := testConfig("h", 22)
	cfg.PrivateKey = []byte("x")
	if err := sess.Connect(cfg); err == nil {
		t.Error("expected error when both credentials provided")
	}
}

func TestCloseIdempotent(t *testing.T) {
	ts := startTestServer(t)
	l := newTestListener()
	sess := NewSession(l)
	l.sess = sess
	if err := sess.Connect(testConfig(ts.host(), ts.port())); err != nil {
		t.Fatal(err)
	}
	mustReady(t, l)
	sess.Close()
	sess.Close()
	select {
	case <-l.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("no close event")
	}
	select {
	case <-l.closed:
		t.Error("double close event emitted")
	case <-time.After(200 * time.Millisecond):
	}
}

// --- SFTP (in-process server over a temp dir) ------------------------------

// startSftpServer starts an in-process server whose SFTP subsystem is backed
// by a temp directory (via WithServerWorkingDirectory), so round-trips are
// exercised end to end, including an NFD-named entry.
func startSftpServer(t *testing.T) *testServer {
	t.Helper()
	workDir := t.TempDir()
	home := filepath.Join(workDir, "home")
	if err := os.MkdirAll(home, 0o755); err != nil {
		t.Fatal(err)
	}
	seed := map[string]string{
		"plain.txt":      "hello\n",
		"cafe\u0301.txt": "nfd\n", // "café.txt" as NFD (e + U+0301)
	}
	for name, content := range seed {
		if err := os.WriteFile(filepath.Join(home, name), []byte(content), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	_, edPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	hostKey, err := ssh.NewSignerFromKey(edPriv)
	if err != nil {
		t.Fatal(err)
	}
	config := &ssh.ServerConfig{
		PasswordCallback: func(_ ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			if string(password) == testPassword {
				return nil, nil
			}
			return nil, errors.New("bad password")
		},
	}
	config.AddHostKey(hostKey)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	ts := &testServer{addr: ln.Addr().String(), hostKey: hostKey}
	t.Cleanup(func() { ln.Close() })

	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go handleSftpConn(conn, config, workDir)
		}
	}()
	return ts
}

func handleSftpConn(conn net.Conn, config *ssh.ServerConfig, workDir string) {
	sconn, chans, reqs, err := ssh.NewServerConn(conn, config)
	if err != nil {
		conn.Close()
		return
	}
	defer sconn.Close()
	go ssh.DiscardRequests(reqs)
	for newChan := range chans {
		if newChan.ChannelType() != "session" {
			newChan.Reject(ssh.UnknownChannelType, "unknown channel type")
			continue
		}
		ch, requests, err := newChan.Accept()
		if err != nil {
			continue
		}
		go serveSftp(ch, requests, workDir)
	}
}

func serveSftp(ch ssh.Channel, requests <-chan *ssh.Request, workDir string) {
	defer ch.Close()
	for req := range requests {
		if req.Type != "subsystem" {
			if req.WantReply {
				req.Reply(false, nil)
			}
			continue
		}
		var sub struct{ Name string }
		ssh.Unmarshal(req.Payload, &sub)
		if req.WantReply {
			req.Reply(sub.Name == "sftp", nil)
		}
		if sub.Name == "sftp" {
			svr, err := sftp.NewServer(ch, sftp.WithServerWorkingDirectory(workDir))
			if err == nil {
				_ = svr.Serve()
			}
		}
		return
	}
}

type sftpListener struct{ conn *Sftp }

func (l *sftpListener) OnHostKey(_, _ string) {
	if l.conn != nil {
		l.conn.DecideHostKey(true)
	}
}

func connectSftp(t *testing.T, ts *testServer) *Sftp {
	t.Helper()
	l := &sftpListener{}
	conn := NewSftp(l)
	l.conn = conn
	if res := conn.Connect(testConfig(ts.host(), ts.port())); !res.Ready {
		t.Fatalf("sftp connect not ready: %s %s", res.Code, res.Message)
	}
	t.Cleanup(conn.Close)
	return conn
}

func TestSftpRoundTrip(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	raw, err := conn.List("home")
	if err != nil {
		t.Fatal(err)
	}
	entries, err := decodeEntries(raw)
	if err != nil {
		t.Fatal(err)
	}
	names := map[string]SftpEntry{}
	for _, e := range entries {
		names[e.Name] = e
	}
	if _, ok := names["plain.txt"]; !ok {
		t.Error("plain.txt missing from listing")
	}
	nfdName := "cafe\u0301.txt"
	if _, ok := names[nfdName]; !ok {
		t.Errorf("NFD entry missing or mangled; got %v", keys(names))
	}

	st, err := conn.Lstat("home/" + nfdName)
	if err != nil {
		t.Fatal(err)
	}
	if st.Name != nfdName {
		t.Errorf("lstat name = %q, want %q", st.Name, nfdName)
	}

	nfdDir := "dir-cafe\u0301"
	if err := conn.Mkdir("home/" + nfdDir); err != nil {
		t.Fatal(err)
	}
	if err := conn.RemoveDir("home/" + nfdDir); err != nil {
		t.Fatal(err)
	}
}

func TestSftpDownloadUpload(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	w, err := conn.OpenWrite("home/upload.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	payload := []byte("0123456789abcdef")
	if _, err := w.Write(payload[:6]); err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(payload[6:]); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/upload.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()
	size, err := r.Size()
	if err != nil {
		t.Fatal(err)
	}
	if size != int64(len(payload)) {
		t.Fatalf("size = %d, want %d", size, len(payload))
	}
	var got []byte
	for {
		chunk, err := r.ReadChunk(4)
		if err != nil {
			t.Fatal(err)
		}
		if chunk == nil {
			break
		}
		got = append(got, chunk...)
	}
	if string(got) != string(payload) {
		t.Errorf("download = %q, want %q", got, payload)
	}
}

// ReadChunk is what the Android binding calls, because a []byte argument
// crosses gomobile as a copy and Read's output never reaches the caller.
// Downloads that used Read wrote the caller's untouched buffer to disk: a file
// of the right length, full of zero bytes.
func TestSftpReadChunkReturnsItsBytes(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	payload := []byte("chunked download payload, long enough to span reads")
	w, err := conn.OpenWrite("home/chunk.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write(payload); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/chunk.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()

	first, err := r.ReadChunk(8)
	if err != nil {
		t.Fatal(err)
	}
	if len(first) != 8 {
		t.Fatalf("first chunk = %d bytes, want 8", len(first))
	}
	if string(first) != string(payload[:8]) {
		t.Errorf("first chunk = %q, want %q", first, payload[:8])
	}

	var rest []byte
	for {
		chunk, err := r.ReadChunk(8)
		if err != nil {
			t.Fatal(err)
		}
		if chunk == nil {
			break
		}
		rest = append(rest, chunk...)
	}
	if got := append(append([]byte{}, first...), rest...); string(got) != string(payload) {
		t.Errorf("round trip = %q, want %q", got, payload)
	}
}

// End of file is a nil slice and a nil error: gomobile cannot carry io.EOF as
// a sentinel, and surfacing it as an error would make a normal finish look
// like a failed transfer.
func TestSftpReadChunkReportsEofWithoutError(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	w, err := conn.OpenWrite("home/empty.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/empty.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()

	chunk, err := r.ReadChunk(16)
	if err != nil {
		t.Fatalf("eof reported as error: %v", err)
	}
	if chunk != nil {
		t.Errorf("chunk = %q, want nil at eof", chunk)
	}
}

func TestSftpReadChunkRejectsNonPositiveSize(t *testing.T) {
	ts := startSftpServer(t)
	conn := connectSftp(t, ts)

	w, err := conn.OpenWrite("home/size.bin", true, false)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := w.Write([]byte("x")); err != nil {
		t.Fatal(err)
	}
	if err := w.Close(); err != nil {
		t.Fatal(err)
	}

	r, err := conn.OpenRead("home/size.bin")
	if err != nil {
		t.Fatal(err)
	}
	defer r.Close()

	if _, err := r.ReadChunk(0); err == nil {
		t.Error("ReadChunk(0) = nil error, want a rejection")
	}
}

func keys(m map[string]SftpEntry) []string {
	out := make([]string, 0, len(m))
	for k := range m {
		out = append(out, k)
	}
	return out
}

// decodeEntries is the test-side mirror of encodeEntries.
func decodeEntries(raw []byte) ([]SftpEntry, error) {
	if len(raw) < 4 {
		return nil, errors.New("short")
	}
	n := int(binary.BigEndian.Uint32(raw[:4]))
	off := 4
	out := make([]SftpEntry, 0, n)
	for i := 0; i < n; i++ {
		if off+4 > len(raw) {
			return nil, errors.New("short name len")
		}
		ln := int(binary.BigEndian.Uint32(raw[off:]))
		off += 4
		if off+ln+1+1+8+8+4 > len(raw) {
			return nil, errors.New("short entry")
		}
		e := SftpEntry{
			Name: string(raw[off : off+ln]),
		}
		off += ln
		e.IsDirectory = raw[off] == 1
		off++
		e.IsSymlink = raw[off] == 1
		off++
		e.Size = int64(binary.BigEndian.Uint64(raw[off:]))
		off += 8
		e.ModifyTimeMillis = int64(binary.BigEndian.Uint64(raw[off:]))
		off += 8
		e.Permissions = int32(binary.BigEndian.Uint32(raw[off:]))
		off += 4
		out = append(out, e)
	}
	return out, nil
}
