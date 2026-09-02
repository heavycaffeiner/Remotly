package sshcore

import (
	"encoding/binary"
	"errors"
	"fmt"
	"io"
	"io/fs"
	"net"
	"os"
	"strconv"
	"sync"
	"time"

	"github.com/pkg/sftp"
	"golang.org/x/crypto/ssh"
)

// SftpListener is implemented by the Kotlin layer for an SFTP connection. Go
// calls OnHostKey when a host-key challenge occurs during Connect; the caller
// answers through Sftp.DecideHostKey.
type SftpListener interface {
	OnHostKey(algorithm, fingerprint string)
}

// SftpConnectResult is the outcome of a blocking Sftp.Connect.
type SftpConnectResult struct {
	Ready   bool
	Code    string
	Message string
}

// SftpEntry is one filesystem entry, mirroring the Kotlin SftpEntry.
// Permissions is the POSIX mode with the type bits in the high nibble;
// IsDirectory and IsSymlink are decoded from it for convenience.
type SftpEntry struct {
	Name             string
	IsDirectory      bool
	IsSymlink        bool
	Size             int64
	ModifyTimeMillis int64
	Permissions      int32
}

// Sftp is one authenticated SFTP connection: an SSH session with the SFTP
// subsystem open and no shell channel. Create with NewSftp, then call Connect
// (blocking) on a worker thread. Paths are passed through untouched: nothing
// here normalizes, trims, or case-folds a path, so NFD names round-trip
// unchanged.
type Sftp struct {
	listener SftpListener

	decideCh  chan bool
	closeCh   chan struct{}
	closeOnce sync.Once

	clientMu  sync.Mutex
	sshClient *ssh.Client
	client    *sftp.Client
}

// guard converts a panic into an error.
//
// These methods are called from Java threads through gomobile. A panic that
// unwinds out of an exported function crosses the JNI boundary and aborts the
// whole process, so the app dies with no diagnosis instead of the transfer
// failing. That is reachable in practice: closing the connection while a
// download goroutine is still inside a read leaves pkg/sftp reading from a
// closed client, and a library panic there is indistinguishable from a crash
// in the app itself.
//
// Recovering here does not make the operation succeed; it reports it as a
// failure the caller can surface. Callers use it with a named error return:
//
//	func (s *Sftp) Op() (err error) { defer guard(&err); ... }
func guard(err *error) {
	if r := recover(); r != nil {
		*err = fmt.Errorf("sftp: internal failure: %v", r)
	}
}

// NewSftp creates a handle for an SFTP connection without connecting.
func NewSftp(l SftpListener) *Sftp {
	return &Sftp{
		listener: l,
		decideCh: make(chan bool, 1),
		closeCh:  make(chan struct{}),
	}
}

// Connect establishes the SSH connection and opens the SFTP subsystem. It
// blocks until the subsystem is open (Ready) or the connect fails. A host-key
// challenge pauses the connect: listener.OnHostKey is called and the connect
// blocks until DecideHostKey is invoked (bounded by the 120s prompt).
func (s *Sftp) Connect(cfg *Config) *SftpConnectResult {
	addr := net.JoinHostPort(cfg.Host, strconv.Itoa(cfg.Port))

	timeout := time.Duration(cfg.ConnectTimeout) * time.Millisecond
	if timeout <= 0 {
		timeout = 15 * time.Second
	}
	conn, err := net.DialTimeout("tcp", addr, timeout)
	if err != nil {
		return &SftpConnectResult{Code: CodeConnectFailed, Message: err.Error()}
	}

	var signer ssh.Signer
	if len(cfg.PrivateKey) > 0 {
		signer, err = parsePrivateKey(cfg.PrivateKey, cfg.Passphrase)
		if err != nil {
			conn.Close()
			return &SftpConnectResult{Code: CodeAuthFailed, Message: err.Error()}
		}
	}

	sshConfig := &ssh.ClientConfig{
		User:            cfg.User,
		Auth:            sftpAuthMethods(cfg, signer),
		HostKeyCallback: s.hostKeyCallback,
	}
	zeroCreds(cfg)

	clientConn, chans, reqs, err := ssh.NewClientConn(conn, addr, sshConfig)
	if err != nil {
		conn.Close()
		code := CodeConnectFailed
		if isAuthError(err) {
			code = CodeAuthFailed
		}
		return &SftpConnectResult{Code: code, Message: err.Error()}
	}
	sshClient := ssh.NewClient(clientConn, chans, reqs)
	s.clientMu.Lock()
	s.sshClient = sshClient
	s.clientMu.Unlock()

	c, err := sftp.NewClient(sshClient)
	if err != nil {
		sshClient.Close()
		s.clientMu.Lock()
		s.sshClient = nil
		s.clientMu.Unlock()
		return &SftpConnectResult{Code: CodeProtocol, Message: err.Error()}
	}
	s.clientMu.Lock()
	s.client = c
	s.clientMu.Unlock()
	return &SftpConnectResult{Ready: true}
}

// DecideHostKey answers a pending host-key challenge. Safe from any thread.
func (s *Sftp) DecideHostKey(accept bool) {
	select {
	case s.decideCh <- accept:
	default:
	}
}

// List returns the entries in a directory, encoded as a []byte in the
// directory-encoding documented in this package (see encodeEntries). Names are
// passed through untouched as UTF-8 bytes, so NFD names round-trip unchanged.
// The caller (Kotlin) decodes the bytes into SftpEntry values.
func (s *Sftp) List(path string) (out []byte, err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return nil, errors.New("sftp not connected")
	}
	infos, err := c.ReadDir(path)
	if err != nil {
		return nil, err
	}
	entries := make([]SftpEntry, 0, len(infos))
	for _, fi := range infos {
		entries = append(entries, toEntry(fi.Name(), fi))
	}
	return encodeEntries(entries), nil
}

// Lstat stats a path without following a final symlink.
func (s *Sftp) Lstat(path string) (out *SftpEntry, err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return nil, errors.New("sftp not connected")
	}
	fi, err := c.Lstat(path)
	if err != nil {
		return nil, err
	}
	e := toEntry(fi.Name(), fi)
	return &e, nil
}

// Mkdir creates a directory.
func (s *Sftp) Mkdir(path string) (err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return errors.New("sftp not connected")
	}
	return c.Mkdir(path)
}

// Rename moves or renames a path.
func (s *Sftp) Rename(oldPath, newPath string) (err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return errors.New("sftp not connected")
	}
	return c.Rename(oldPath, newPath)
}

// RemoveFile removes a file. Directories use RemoveDir.
func (s *Sftp) RemoveFile(path string) (err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return errors.New("sftp not connected")
	}
	return c.Remove(path)
}

// RemoveDir removes an empty directory. Non-recursive.
func (s *Sftp) RemoveDir(path string) (err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return errors.New("sftp not connected")
	}
	return c.RemoveDirectory(path)
}

// OpenRead opens a file for chunked reading.
func (s *Sftp) OpenRead(path string) (out *SftpFile, err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return nil, errors.New("sftp not connected")
	}
	f, err := c.Open(path)
	if err != nil {
		return nil, err
	}
	return &SftpFile{f: f}, nil
}

// OpenAppend opens a file for writing and seeks to its current end.
//
// This is what makes an interrupted upload resumable: the bytes already on the
// server are kept and writing continues after them. Returns the offset to
// resume from through the file's Offset method.
func (s *Sftp) OpenAppend(path string) (out *SftpFile, err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return nil, errors.New("sftp not connected")
	}
	f, err := c.OpenFile(path, os.O_WRONLY|os.O_CREATE)
	if err != nil {
		return nil, err
	}
	off, err := f.Seek(0, io.SeekEnd)
	if err != nil {
		_ = f.Close()
		return nil, err
	}
	return &SftpFile{f: f, offset: off}, nil
}

// OpenWrite opens a file for chunked writing. truncate empties an existing
// file; exclusive fails if the file already exists.
func (s *Sftp) OpenWrite(path string, truncate, exclusive bool) (out *SftpFile, err error) {
	defer guard(&err)
	c := s.sftpClient()
	if c == nil {
		return nil, errors.New("sftp not connected")
	}
	flags := os.O_WRONLY | os.O_CREATE
	if truncate {
		flags |= os.O_TRUNC
	}
	if exclusive {
		flags |= os.O_EXCL
	}
	f, err := c.OpenFile(path, flags)
	if err != nil {
		return nil, err
	}
	return &SftpFile{f: f}, nil
}

// Close tears down the connection. Idempotent.
func (s *Sftp) Close() {
	s.closeOnce.Do(func() {
		close(s.closeCh)
		s.clientMu.Lock()
		// Deferred, not unlocked at the end of the block: closeSafely can
		// return after a recovered panic, and an unlock that is skipped on
		// that path would leave every later sftpClient call blocked forever.
		// A hang is worse than the crash the recover is there to prevent.
		defer s.clientMu.Unlock()
		if s.client != nil {
			closeSafely(s.client)
			s.client = nil
		}
		if s.sshClient != nil {
			closeSafely(s.sshClient)
			s.sshClient = nil
		}
	})
}

// closeSafely closes c, absorbing a panic.
//
// Close races the transfer goroutines by design: the caller cancels them
// first, but one can still be inside a read, and a library panic on that path
// would otherwise cross the JNI boundary and abort the process. Scoped to the
// single call so a recover cannot skip the caller's remaining cleanup.
func closeSafely(c io.Closer) {
	defer func() { _ = recover() }()
	_ = c.Close()
}

func (s *Sftp) sftpClient() *sftp.Client {
	s.clientMu.Lock()
	defer s.clientMu.Unlock()
	return s.client
}

// hostKeyCallback blocks the handshake until the app decides, mirroring the
// terminal session's flow.
func (s *Sftp) hostKeyCallback(hostname string, _ net.Addr, key ssh.PublicKey) error {
	alg, fp := hostKeyFingerprint(key)
	s.listener.OnHostKey(alg, fp)
	select {
	case accept := <-s.decideCh:
		if accept {
			return nil
		}
		return errors.New("host key rejected")
	case <-s.closeCh:
		return errors.New("session closed")
	case <-time.After(120 * time.Second):
		return errors.New("host key prompt timed out")
	}
}

// SftpFile is an open SFTP file handle for chunked transfer.
type SftpFile struct {
	f      *sftp.File
	offset int64
}

// Offset is where the handle starts, which is non-zero only for a resumed
// transfer. gomobile cannot return a struct field, so this is a method.
func (f *SftpFile) Offset() int64 { return f.offset }

// SeekTo moves the read position, so a download can resume from what is
// already on disk rather than starting over.
func (f *SftpFile) SeekTo(offset int64) (err error) {
	defer guard(&err)
	if offset < 0 {
		return errors.New("offset must not be negative")
	}
	_, err = f.f.Seek(offset, io.SeekStart)
	if err != nil {
		return err
	}
	f.offset = offset
	return nil
}

// Size returns the file size in bytes.
func (f *SftpFile) Size() (size int64, err error) {
	defer guard(&err)
	fi, err := f.f.Stat()
	if err != nil {
		return 0, err
	}
	return fi.Size(), nil
}

// Read reads up to len(p) bytes.
//
// Not usable across the gomobile boundary: a []byte argument is marshalled by
// value, so bytes written into p never reach the caller. Bound callers use
// ReadChunk. Kept for Go callers and the tests in this package.
func (f *SftpFile) Read(p []byte) (int, error) { return f.f.Read(p) }

// ReadChunk reads up to max bytes and returns them.
//
// Returns a nil slice with a nil error at end of file, which is how a bound
// caller sees completion: gomobile cannot express io.EOF as a sentinel, and
// returning it as an error would make an ordinary finish look like a failure.
//
// This exists because a []byte parameter crosses the binding as a copy. The
// download path used Read and wrote the caller's untouched buffer to disk,
// producing a file of the right length full of zero bytes.
// A download spends nearly all of its time here, so this is also where a
// connection torn down underneath the transfer surfaces.
func (f *SftpFile) ReadChunk(max int) (out []byte, err error) {
	defer guard(&err)
	if max <= 0 {
		return nil, errors.New("chunk size must be positive")
	}
	buf := make([]byte, max)
	// ReadFull, so a short read mid-file does not end the transfer early. Only
	// a genuine EOF stops it.
	n, err := io.ReadFull(f.f, buf)
	if n > 0 {
		return buf[:n], nil
	}
	if err == io.EOF || err == io.ErrUnexpectedEOF {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	return nil, nil
}

// Write writes len(p) bytes.
//
// Safe across the binding: the bytes travel into Go and nothing is expected to
// come back in the caller's slice.
func (f *SftpFile) Write(p []byte) (n int, err error) {
	defer guard(&err)
	return f.f.Write(p)
}

// Close closes the handle.
func (f *SftpFile) Close() (err error) {
	defer guard(&err)
	return f.f.Close()
}

// sftpAuthMethods builds the auth methods for an SFTP connect.
func sftpAuthMethods(cfg *Config, signer ssh.Signer) []ssh.AuthMethod {
	var methods []ssh.AuthMethod
	if len(cfg.PrivateKey) > 0 && signer != nil {
		methods = append(methods, ssh.PublicKeys(signer))
	}
	if cfg.Password != "" {
		methods = append(methods, ssh.Password(cfg.Password))
	}
	return methods
}

// toEntry converts an os.FileInfo to an SftpEntry with a POSIX mode.
func toEntry(name string, fi fs.FileInfo) SftpEntry {
	m := fi.Mode()
	return SftpEntry{
		Name:             name,
		IsDirectory:      m.IsDir(),
		IsSymlink:        m&fs.ModeSymlink != 0,
		Size:             fi.Size(),
		ModifyTimeMillis: fi.ModTime().UnixMilli(),
		Permissions:      toPosixMode(m),
	}
}

// toPosixMode maps an fs.FileMode to a POSIX mode with the type bits in the
// high nibble, matching the MINA bridge's SftpClient.Attributes.permissions.
func toPosixMode(m fs.FileMode) int32 {
	perm := int32(m.Perm())
	var typ int32
	switch {
	case m.IsDir():
		typ = 0x4000 // S_IFDIR
	case m&fs.ModeSymlink != 0:
		typ = 0xA000 // S_IFLNK
	case m&fs.ModeDevice != 0:
		typ = 0x6000 // S_IFBLK
	case m&fs.ModeCharDevice != 0:
		typ = 0x2000 // S_IFCHR
	case m&fs.ModeNamedPipe != 0:
		typ = 0x1000 // S_IFIFO
	case m&fs.ModeSocket != 0:
		typ = 0xC000 // S_IFSOCK
	default:
		typ = 0x8000 // S_IFREG
	}
	return typ | perm
}

// encodeEntries serializes directory entries for transfer to Kotlin. Layout
// (all integers big-endian):
//
//	uint32 entryCount
//	repeated entryCount times:
//	  uint32 nameLen
//	  <nameLen bytes of name, UTF-8, unmodified>
//	  uint8  isDirectory (0/1)
//	  uint8  isSymlink   (0/1)
//	  uint64 size
//	  int64  modifyTimeMillis
//	  uint32 permissions
//
// The name bytes are copied verbatim, so NFD and NFC names stay distinct.
func encodeEntries(entries []SftpEntry) []byte {
	buf := make([]byte, 0, 128)
	buf = binary.BigEndian.AppendUint32(buf, uint32(len(entries)))
	for _, e := range entries {
		name := []byte(e.Name)
		buf = binary.BigEndian.AppendUint32(buf, uint32(len(name)))
		buf = append(buf, name...)
		d := byte(0)
		if e.IsDirectory {
			d = 1
		}
		l := byte(0)
		if e.IsSymlink {
			l = 1
		}
		buf = append(buf, d, l)
		buf = binary.BigEndian.AppendUint64(buf, uint64(e.Size))
		buf = binary.BigEndian.AppendUint64(buf, uint64(e.ModifyTimeMillis))
		buf = binary.BigEndian.AppendUint32(buf, uint32(e.Permissions))
	}
	return buf
}
