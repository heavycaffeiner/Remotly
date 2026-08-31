// Command remotly-sshd is a tiny SSH + SFTP server used to verify the Go
// sshcore engine on a device (RN-05). It accepts a fixed user/password and
// serves a small temp directory over SFTP (including an NFD-named file) so the
// app's SSH terminal and SFTP browser can be exercised end to end. It is a
// test aid, not shipped.
//
// A shell session either echoes input back (the default, enough to prove the
// channel carries bytes) or runs a real shell on a pty when -shell is passed.
// Only the second reproduces what a terminal actually has to render: a shell
// echoing, redrawing its line, and emitting escape sequences that can be split
// across reads.
package main

import (
	"crypto/ed25519"
	"crypto/rand"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"sync"

	"github.com/creack/pty"
	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

// realShell runs a shell on a pty instead of echoing. Set by -shell.
var realShell bool

func main() {
	port := "2222"
	user := "probe"
	pass := "probe"
	if len(os.Args) > 1 {
		port = os.Args[1]
	}
	if len(os.Args) > 2 {
		user = os.Args[2]
	}
	if len(os.Args) > 3 {
		pass = os.Args[3]
	}
	for _, a := range os.Args[1:] {
		if a == "-shell" {
			realShell = true
		}
	}

	// A fresh host key per run; the app approves it on first use.
	_, edPriv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		fatal(err)
	}
	hostKey, err := ssh.NewSignerFromKey(edPriv)
	if err != nil {
		fatal(err)
	}

	workDir := mustWorkDir()

	config := &ssh.ServerConfig{
		PasswordCallback: func(_ ssh.ConnMetadata, password []byte) (*ssh.Permissions, error) {
			if string(password) == pass {
				return nil, nil
			}
			return nil, fmt.Errorf("password rejected")
		},
	}
	config.AddHostKey(hostKey)

	ln, err := net.Listen("tcp", "0.0.0.0:"+port)
	if err != nil {
		fatal(err)
	}
	fmt.Printf("remotly-sshd listening on :%s user=%s workdir=%s\n", port, user, workDir)
	for {
		conn, err := ln.Accept()
		if err != nil {
			continue
		}
		go handleConn(conn, config, workDir)
	}
}

func handleConn(conn net.Conn, config *ssh.ServerConfig, workDir string) {
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
		go handleSession(ch, requests, workDir)
	}
}

func handleSession(ch ssh.Channel, requests <-chan *ssh.Request, workDir string) {
	defer ch.Close()
	var winCh chan winSize
	if realShell {
		winCh = make(chan winSize, 4)
	}
	for req := range requests {
		switch req.Type {
		case "pty-req":
			if realShell {
				if w, h, ok := parsePtyReq(req.Payload); ok {
					select {
					case winCh <- winSize{w, h}:
					default:
					}
				}
			}
			if req.WantReply {
				req.Reply(true, nil)
			}
		case "shell":
			if req.WantReply {
				req.Reply(true, nil)
			}
			if realShell {
				runShell(ch, workDir, winCh)
				return
			}
			_, _ = ch.Write([]byte("SHELL-READY\n"))
			shellLoop(ch)
			return
		case "subsystem":
			var sub struct{ Name string }
			ssh.Unmarshal(req.Payload, &sub)
			if req.WantReply {
				if sub.Name == "sftp" {
					req.Reply(true, nil)
					serveSftp(ch, workDir)
					return
				}
				req.Reply(false, nil)
			}
		case "window-change":
			if realShell {
				if w, h, ok := parseWindowChange(req.Payload); ok {
					select {
					case winCh <- winSize{w, h}:
					default:
					}
				}
			}
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

// shellLoop echoes everything the client writes straight back until the channel
// closes. It is only started for shell sessions, never for SFTP subsystem
// sessions (whose channel carries the SFTP protocol, not shell bytes).
func shellLoop(ch ssh.Channel) {
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
}

func serveSftp(ch ssh.Channel, workDir string) {
	svr, err := sftp.NewServer(ch, sftp.WithServerWorkingDirectory(workDir))
	if err != nil {
		return
	}
	_ = svr.Serve()
}

// Builds a small temp tree with a known file and an NFD-named file.
func mustWorkDir() string {
	dir, err := os.MkdirTemp("", "remotly-sshd-")
	if err != nil {
		fatal(err)
	}
	if err := os.WriteFile(filepath.Join(dir, "probe.txt"), []byte("hello from remotly sshd\n"), 0o644); err != nil {
		fatal(err)
	}
	// NFD: "cafe" + combining acute (U+0301) + ".txt".
	nfd := "cafe\u0301.txt"
	if err := os.WriteFile(filepath.Join(dir, nfd), []byte("nfd\n"), 0o644); err != nil {
		fatal(err)
	}
	if err := os.Mkdir(filepath.Join(dir, "subdir"), 0o755); err != nil {
		fatal(err)
	}
	return dir
}

func fatal(err error) {
	fmt.Fprintln(os.Stderr, "remotly-sshd:", err)
	os.Exit(1)
}

var _ = io.Discard

type winSize struct{ cols, rows uint32 }

// parsePtyReq reads the terminal size out of a pty-req payload. The layout is
// a term name string followed by four uint32s, of which the first two are the
// character dimensions.
func parsePtyReq(payload []byte) (uint32, uint32, bool) {
	if len(payload) < 4 {
		return 0, 0, false
	}
	nameLen := binary.BigEndian.Uint32(payload)
	rest := payload[4:]
	if uint32(len(rest)) < nameLen+8 {
		return 0, 0, false
	}
	rest = rest[nameLen:]
	return binary.BigEndian.Uint32(rest), binary.BigEndian.Uint32(rest[4:]), true
}

// parseWindowChange reads the new size from a window-change payload, which is
// four uint32s starting with the character dimensions.
func parseWindowChange(payload []byte) (uint32, uint32, bool) {
	if len(payload) < 8 {
		return 0, 0, false
	}
	return binary.BigEndian.Uint32(payload), binary.BigEndian.Uint32(payload[4:]), true
}

// runShell runs an interactive shell on a pty and bridges it to the channel.
//
// This is what makes the server useful for rendering work: a real shell echoes
// what is typed, repaints its line on an edit, and emits escape sequences that
// a read can split in half. An echo loop reproduces none of that.
func runShell(ch ssh.Channel, workDir string, winCh <-chan winSize) {
	shell := os.Getenv("SHELL")
	if shell == "" {
		shell = "/bin/sh"
	}
	cmd := exec.Command(shell, "-i")
	cmd.Dir = workDir
	// A fixed, minimal environment so the prompt does not depend on whatever
	// started the server.
	cmd.Env = append(os.Environ(),
		"TERM=xterm-256color",
		"PS1=$ ",
	)

	f, err := pty.Start(cmd)
	if err != nil {
		fmt.Fprintf(ch, "shell failed: %v\r\n", err)
		return
	}
	defer func() {
		_ = f.Close()
		_ = cmd.Process.Kill()
		_, _ = cmd.Process.Wait()
	}()

	go func() {
		for w := range winCh {
			_ = pty.Setsize(f, &pty.Winsize{Cols: uint16(w.cols), Rows: uint16(w.rows)})
		}
	}()

	var once sync.Once
	done := make(chan struct{})
	finish := func() { once.Do(func() { close(done) }) }

	go func() {
		defer finish()
		_, _ = io.Copy(f, ch)
	}()
	go func() {
		defer finish()
		_, _ = io.Copy(ch, f)
	}()
	<-done
}
