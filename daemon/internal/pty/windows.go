//go:build windows

package pty

import (
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"sort"
	"strings"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// closedErr reports a closed ConPTY output pipe.
func closedErr(err error) bool {
	return errors.Is(err, windows.ERROR_BROKEN_PIPE) || errors.Is(err, os.ErrClosed)
}

// ResolveShell picks the Windows shell: configured value first, then pwsh,
// then Windows PowerShell.
func ResolveShell(configured string) (string, string, error) {
	if configured != "" {
		if err := validateWindowsProgram(configured); err != nil {
			return "", "", err
		}
		return configured, "config", nil
	}
	if p, err := exec.LookPath("pwsh"); err == nil {
		return p, "pwsh", nil
	}
	if p, err := exec.LookPath("powershell"); err == nil {
		return p, "powershell", nil
	}
	return "", "", errors.New("pty: no PowerShell found (looked for pwsh then powershell)")
}

func validateWindowsProgram(p string) error {
	if strings.ContainsRune(p, '\x00') {
		return errors.New("pty: program path is invalid")
	}
	st, err := os.Stat(p)
	if err != nil {
		return errors.New("pty: configured shell not found")
	}
	if st.IsDir() {
		return errors.New("pty: configured shell is a directory")
	}
	return nil
}

// ShellFromConfig resolves the configured shell and its arguments for a
// session.
func ShellFromConfig(configured, command string) (program string, args []string, source string, err error) {
	program, source, err = ResolveShell(configured)
	if err != nil {
		return "", nil, "", err
	}
	args = ShellArgs(program, command)
	return program, args, source, nil
}

// ShellArgs builds the PowerShell argument vector. PowerShell is interactive
// by default on a console, so an interactive session takes no flags. A
// command is passed to the -Command form as a single structured argument,
// never concatenated into a string.
func ShellArgs(program, command string) []string {
	if command == "" {
		return []string{}
	}
	return []string{"-Command", command}
}

// New returns the Windows ConPTY backend.
func New() Backend { return conptyBackend{} }

type conptyBackend struct{}

func (conptyBackend) Start(req StartRequest) (Process, error) {
	if err := Validate(req); err != nil {
		return nil, err
	}
	args := append([]string{}, req.Args...)
	if req.Command != "" {
		args = append(args, "-Command", req.Command)
	}
	cmdline := windows.ComposeCommandLine(append([]string{req.Program}, args...))
	envPtr, err := envBlock(req.Env)
	if err != nil {
		return nil, err
	}
	cwdPtr, err := windows.UTF16PtrFromString(req.Cwd)
	if err != nil {
		return nil, fmt.Errorf("pty: cwd: %w", err)
	}

	hpc, inWrite, outRead, err := createConPTY(req.Cols, req.Rows)
	if err != nil {
		return nil, err
	}
	p := &conptyProcess{
		hpc:     hpc,
		inWrite: inWrite,
		outRead: outRead,
		dataCh:  make(chan ptyChunk, 256),
		done:    make(chan struct{}),
	}
	procInfo, err := p.launch(cmdline, envPtr, cwdPtr)
	if err != nil {
		windows.ClosePseudoConsole(hpc)
		windows.CloseHandle(inWrite)
		windows.CloseHandle(outRead)
		return nil, err
	}
	p.proc = procInfo
	go p.pumpOutput()
	return p, nil
}

type ptyChunk struct {
	data []byte
	err  error
}

type conptyProcess struct {
	hpc     windows.Handle
	inWrite windows.Handle
	outRead windows.Handle
	proc    *windows.ProcessInformation

	dataCh chan ptyChunk
	done   chan struct{}
	pend   []byte

	mu       sync.Mutex
	closed   bool
	procDone sync.Once

	waitMu sync.Mutex
	waited bool
	status ExitStatus
}

// pumpOutput reads the ConPTY output pipe until it breaks and feeds the
// chunk channel. The channel decouples the pipe reader from the session
// drain: the reader blocks only while the drain is slow, and stops when the
// process is closed.
func (p *conptyProcess) pumpOutput() {
	buf := make([]byte, 32<<10)
	for {
		var n uint32
		err := windows.ReadFile(p.outRead, buf, &n, nil)
		if n > 0 {
			chunk := make([]byte, n)
			copy(chunk, buf[:n])
			select {
			case p.dataCh <- ptyChunk{data: chunk}:
			case <-p.done:
				return
			}
		}
		if err != nil {
			select {
			case p.dataCh <- ptyChunk{err: err}:
			case <-p.done:
			}
			return
		}
	}
}

func (p *conptyProcess) Read(b []byte) (int, error) {
	for len(p.pend) == 0 {
		select {
		case ch := <-p.dataCh:
			if len(ch.data) > 0 {
				p.pend = ch.data
				continue
			}
			if ch.err == nil || closedErr(ch.err) {
				return 0, io.EOF
			}
			return 0, ch.err
		case <-p.done:
			return 0, io.EOF
		}
	}
	n := copy(b, p.pend)
	p.pend = p.pend[n:]
	return n, nil
}

func (p *conptyProcess) Write(b []byte) (int, error) {
	if p.isClosed() {
		return 0, errors.New("pty: process closed")
	}
	var written uint32
	if err := windows.WriteFile(p.inWrite, b, &written, nil); err != nil {
		return 0, err
	}
	return int(written), nil
}

func (p *conptyProcess) Resize(cols, rows uint16) error {
	if cols < MinCols || cols > MaxCols || rows < MinRows || rows > MaxRows {
		return errors.New("pty: size out of range")
	}
	return windows.ResizePseudoConsole(p.hpc, windows.Coord{X: int16(cols), Y: int16(rows)})
}

func (p *conptyProcess) Signal(sig os.Signal) error {
	if sig == os.Interrupt {
		// ConPTY translates an ETX byte on input into a console Ctrl-C
		// event for the foreground process group.
		_, err := p.Write([]byte{0x03})
		return err
	}
	return p.Kill()
}

func (p *conptyProcess) Kill() error {
	return p.close()
}

// close terminates the pseudoconsole, which by contract terminates the
// attached process tree. Safe to call concurrently and repeatedly.
func (p *conptyProcess) close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.closed {
		return nil
	}
	p.closed = true
	close(p.done)
	windows.ClosePseudoConsole(p.hpc)
	windows.CloseHandle(p.inWrite)
	windows.CloseHandle(p.outRead)
	return nil
}

func (p *conptyProcess) Close() error {
	return p.close()
}

func (p *conptyProcess) isClosed() bool {
	p.mu.Lock()
	defer p.mu.Unlock()
	return p.closed
}

// Wait blocks until the shell process exits and reports its status. It owns
// closing the process and thread handles. Concurrent calls observe the same
// status.
func (p *conptyProcess) Wait() ExitStatus {
	p.waitMu.Lock()
	defer p.waitMu.Unlock()
	if p.waited {
		return p.status
	}
	var st ExitStatus
	if p.proc == nil {
		st = ExitStatus{Exited: true, Code: -1}
	} else {
		windows.WaitForSingleObject(p.proc.Process, windows.INFINITE)
		var ec uint32
		windows.GetExitCodeProcess(p.proc.Process, &ec)
		st = ExitStatus{Exited: true, Code: int(ec)}
		p.procDone.Do(func() {
			windows.CloseHandle(p.proc.Process)
			windows.CloseHandle(p.proc.Thread)
		})
	}
	p.waited = true
	p.status = st
	p.close()
	return st
}

func (p *conptyProcess) launch(cmdline string, envPtr, cwdPtr *uint16) (*windows.ProcessInformation, error) {
	attrList, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return nil, err
	}
	defer attrList.Delete()
	if err := attrList.Update(windows.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, unsafe.Pointer(&p.hpc), unsafe.Sizeof(p.hpc)); err != nil {
		return nil, err
	}
	si := windows.StartupInfoEx{StartupInfo: windows.StartupInfo{Flags: windows.STARTF_USESTDHANDLES}}
	si.Cb = uint32(unsafe.Sizeof(si))
	si.ProcThreadAttributeList = attrList.List()
	procInfo := new(windows.ProcessInformation)
	cmdlinePtr, err := windows.UTF16PtrFromString(cmdline)
	if err != nil {
		return nil, err
	}
	err = windows.CreateProcess(nil, cmdlinePtr, nil, nil, false,
		windows.EXTENDED_STARTUPINFO_PRESENT, envPtr, cwdPtr, &si.StartupInfo, procInfo)
	if err != nil {
		return nil, err
	}
	return procInfo, nil
}

// createConPTY creates the pseudoconsole and its pipes. The returned
// inWrite and outRead are the daemon's ends; the pseudoconsole owns the
// others.
func createConPTY(cols, rows uint16) (hpc, inWrite, outRead windows.Handle, err error) {
	var inRead, outWrite windows.Handle
	if err = windows.CreatePipe(&inRead, &inWrite, nil, 0); err != nil {
		return
	}
	if err = windows.CreatePipe(&outRead, &outWrite, nil, 0); err != nil {
		windows.CloseHandle(inRead)
		windows.CloseHandle(inWrite)
		return
	}
	err = windows.CreatePseudoConsole(windows.Coord{X: int16(cols), Y: int16(rows)}, inRead, outWrite, 0, &hpc)
	if err != nil {
		windows.CloseHandle(inRead)
		windows.CloseHandle(inWrite)
		windows.CloseHandle(outRead)
		windows.CloseHandle(outWrite)
		return
	}
	windows.CloseHandle(inRead)
	windows.CloseHandle(outWrite)
	return
}

// envBlock encodes a Windows environment block: sorted KEY=VALUE pairs,
// UTF-16LE, NUL-separated, double-NUL-terminated.
func envBlock(env []string) (*uint16, error) {
	m := make(map[string]string, len(env))
	for _, kv := range env {
		i := strings.IndexByte(kv, '=')
		m[kv[:i]] = kv[i+1:]
	}
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(m[k])
		b.WriteByte(0)
	}
	b.WriteByte(0)
	utf16, err := syscall.UTF16FromString(b.String())
	if err != nil {
		return nil, err
	}
	return &utf16[0], nil
}
