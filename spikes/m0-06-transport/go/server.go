package main

import (
	"encoding/hex"
	"flag"
	"fmt"
	"net/http"

	"github.com/flynn/noise"
	"github.com/gorilla/websocket"
)

const prologue = "remotly-m0-06"

// cmdServer runs a WebSocket responder (the daemon side) for the live
// cross-runtime round trip. It performs one handshake per connection and then
// echoes sealed frames.
func cmdServer(args []string) error {
	fs := flag.NewFlagSet("server", flag.ContinueOnError)
	addr := fs.String("addr", "127.0.0.1:8777", "listen address")
	pattern := fs.String("pattern", "XX", "handshake pattern: XX or IK")
	pskHex := fs.String("psk", "", "optional pairing PSK (hex) for XXpsk0")
	printKeys := fs.Bool("print-keys", false, "log derived session keys (off by default: no secrets in logs)")
	if err := fs.Parse(args); err != nil {
		return err
	}

	var p = patternXX
	switch *pattern {
	case "XX":
		p = patternXX
	case "IK":
		p = patternIK
	default:
		return fmt.Errorf("unsupported pattern %q", *pattern)
	}

	var psk []byte
	if *pskHex != "" {
		var err error
		psk, err = hex.DecodeString(*pskHex)
		if err != nil {
			return err
		}
	}

	static, err := genKey()
	if err != nil {
		return err
	}
	fmt.Printf("server: pattern=%s addr=%s static_pub=%x\n", p.Name, *addr, static.Public)

	upgrader := websocket.Upgrader{CheckOrigin: func(*http.Request) bool { return true }}
	http.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
		conn, err := upgrader.Upgrade(w, r, nil)
		if err != nil {
			return
		}
		serveConn(conn, p, static, psk, *printKeys)
	})
	return http.ListenAndServe(*addr, nil)
}

func serveConn(conn *websocket.Conn, p noise.HandshakePattern, static noise.DHKey, psk []byte, printKeys bool) {
	defer conn.Close()

	_, data, err := conn.ReadMessage()
	if err != nil {
		return
	}
	if len(data) < 1 {
		writeError(conn, "empty handshake message")
		return
	}
	if data[0] != Version {
		writeError(conn, fmt.Sprintf("unsupported protocol version %d", data[0]))
		return
	}
	if len(data)-1 > MaxHandshake {
		writeError(conn, "handshake message too large")
		return
	}

	hs, err := noise.NewHandshakeState(noise.Config{
		CipherSuite: cs, Pattern: p, Initiator: false,
		Prologue: []byte(prologue), StaticKeypair: static,
		PresharedKey: psk, PresharedKeyPlacement: 0,
	})
	if err != nil {
		writeError(conn, err.Error())
		return
	}
	resp := &role{initiator: false, hs: hs}

	var send, recv *CipherKey
	_, send, recv, done, err := resp.readMessage(data[1:])
	if err != nil {
		writeError(conn, "handshake failed: "+err.Error())
		return
	}
	if done {
		// One-way patterns never occur here; treat as protocol error.
		writeError(conn, "unexpected one-way handshake")
		return
	}

	msg2, send, recv, done, err := resp.writeMessage(nil)
	if err != nil {
		writeError(conn, "handshake failed: "+err.Error())
		return
	}
	if err := conn.WriteMessage(websocket.BinaryMessage, append([]byte{Version}, msg2...)); err != nil {
		return
	}
	if !done {
		_, data3, err := conn.ReadMessage()
		if err != nil {
			return
		}
		_, send, recv, done, err = resp.readMessage(data3)
		if err != nil || !done {
			writeError(conn, "handshake failed: "+err.Error())
			return
		}
	}

	// Echo loop: decrypt a frame, re-seal it, send it back.
	if printKeys {
		fmt.Printf("server: handshake done send=%x recv=%x\n", send.key, recv.key)
	}
	for {
		_, data, err := conn.ReadMessage()
		if err != nil {
			return
		}
		ch, id, payload, err := recv.OpenFrame(data)
		if err != nil {
			writeError(conn, "frame failed: "+err.Error())
			return
		}
		echo, err := send.SealFrame(ch, id, payload)
		if err != nil {
			writeError(conn, "echo failed: "+err.Error())
			return
		}
		if err := conn.WriteMessage(websocket.BinaryMessage, echo); err != nil {
			return
		}
	}
}

func writeError(conn *websocket.Conn, reason string) {
	_ = conn.WriteMessage(websocket.CloseMessage, websocket.FormatCloseMessage(4000, reason))
}
