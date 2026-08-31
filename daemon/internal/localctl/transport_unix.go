//go:build !windows

package localctl

import (
	"fmt"
	"net"
	"os"
	"time"
)

// listen starts a Unix domain socket listener at path, clearing a stale socket
// file left by a previous run. The socket is 0600 so only the owning user can
// connect.
func listen(path string) (net.Listener, error) {
	if fi, err := os.Lstat(path); err == nil {
		if fi.Mode()&os.ModeSocket != 0 {
			if err := os.Remove(path); err != nil {
				return nil, fmt.Errorf("remove stale socket: %w", err)
			}
		} else {
			return nil, fmt.Errorf("%s exists and is not a socket", path)
		}
	}
	ln, err := net.Listen("unix", path)
	if err != nil {
		return nil, fmt.Errorf("listen: %w", err)
	}
	if err := os.Chmod(path, 0o600); err != nil {
		ln.Close()
		return nil, fmt.Errorf("chmod socket: %w", err)
	}
	return ln, nil
}

func dial(path string, timeout time.Duration) (net.Conn, error) {
	d := net.Dialer{Timeout: timeout}
	return d.Dial("unix", path)
}

func removeSocket(path string) {
	// Only remove a socket, never something else that may have taken the path.
	if fi, err := os.Lstat(path); err == nil && fi.Mode()&os.ModeSocket != 0 {
		_ = os.Remove(path)
	}
}
