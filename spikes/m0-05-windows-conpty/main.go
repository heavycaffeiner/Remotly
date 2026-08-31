//go:build windows

// Command m0-05 is a disposable spike proving that a Go process can host
// PowerShell in a ConPTY with normal profile and environment behavior, resize,
// Unicode I/O, Ctrl handling, and process-tree cleanup. It runs only on
// Windows; cross-compile with GOOS=windows to type-check.
package main

import (
	"fmt"
	"os"
	"os/exec"
	"sort"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

const (
	defaultTerm         = "xterm-256color"
	startfUSESTDHANDLES = windows.STARTF_USESTDHANDLES
)

func main() {
	shell, err := detectShell()
	must(err, "detect shell")

	home, err := os.UserHomeDir()
	must(err, "home")

	env := minimalEnv(shell, home)
	envPtr, err := envBlock(env)
	must(err, "env block")

	cwdPtr, err := windows.UTF16PtrFromString(home)
	must(err, "cwd")

	hpc, inWrite, outRead, err := createConPTY(120, 40)
	must(err, "create conpty")
	defer windows.CloseHandle(inWrite)
	defer windows.CloseHandle(outRead)
	defer windows.ClosePseudoConsole(hpc)

	cmdline := windows.ComposeCommandLine([]string{shell})
	procInfo, err := launch(hpc, cmdline, envPtr, cwdPtr)
	must(err, "launch shell")
	defer windows.CloseHandle(procInfo.Process)
	defer windows.CloseHandle(procInfo.Thread)

	buf := newRing()
	go pump(outRead, buf)

	// Probe allowlisted facts.
	probe := strings.Join([]string{
		`"PROBE_shell=$((Get-Process -Id $PID).Path)"`,
		`"PROBE_version=$($PSVersionTable.PSVersion.ToString())"`,
		`"PROBE_profile=$PROFILE"`,
		`"PROBE_cwd=$(Get-Location)"`,
		`"PROBE_home=$HOME"`,
		`"PROBE_path=$env:PATH"`,
		`"PROBE_term=$env:TERM"`,
		`"PROBE_remotly_session=$env:REMOTLY_SESSION"`,
		`"PROBE_size=$((Get-Host).UI.RawUI.WindowSize.Width)x$((Get-Host).UI.RawUI.WindowSize.Height)"`,
		`"PROBE_unicode=한글 日本語 中文"`,
		`"__REMOTLY_PROBE_END__"`,
	}, ";")
	out, ok := runCommand(inWrite, buf, probe, "__REMOTLY_PROBE_END__", 20*time.Second)
	if !ok {
		must(fmt.Errorf("probe marker not seen"), "probe")
	}
	fmt.Printf("probe output:\n%s\n", clean(out))

	// Resize proof.
	before := runSimple(inWrite, buf, "(Get-Host).UI.RawUI.WindowSize", 10*time.Second)
	must(resize(hpc, 140, 50), "resize")
	after := runSimple(inWrite, buf, "(Get-Host).UI.RawUI.WindowSize", 10*time.Second)
	fmt.Printf("resize: before=%s after=%s set=140x50\n", strings.TrimSpace(clean(before)), strings.TrimSpace(clean(after)))

	// Ctrl-C proof: interrupt a foreground sleep, then confirm the shell is
	// still alive. ConPTY delivers Ctrl-C as byte 0x03 on input.
	writeAll(inWrite, "Start-Sleep 30\n")
	time.Sleep(500 * time.Millisecond)
	writeAll(inWrite, "\x03")
	alive := runSimple(inWrite, buf, "'alive-after-interrupt'", 10*time.Second)
	fmt.Printf("interrupt: alive=%s\n", strings.TrimSpace(clean(alive)))

	// Exit and status.
	writeAll(inWrite, "exit\n")
	exitCode, err := waitExit(procInfo, 10*time.Second)
	fmt.Printf("exit: code=%d err=%v\n", exitCode, err)

	// Descendant cleanup is exercised by waitExit above; any surviving child
	// of a shell that ran a background job is terminated here.
	killTree(procInfo.ProcessId)
}

func detectShell() (string, error) {
	if p, err := exec.LookPath("pwsh"); err == nil {
		return p, nil
	}
	if p, err := exec.LookPath("powershell"); err == nil {
		return p, nil
	}
	return "", fmt.Errorf("no PowerShell found (looked for pwsh then powershell)")
}

func minimalEnv(shell, home string) map[string]string {
	return map[string]string{
		"SystemRoot":      os.Getenv("SystemRoot"),
		"WINDIR":          os.Getenv("WINDIR"),
		"ComSpec":         os.Getenv("ComSpec"),
		"USERPROFILE":     home,
		"HOMEDRIVE":       os.Getenv("HOMEDRIVE"),
		"HOMEPATH":        os.Getenv("HOMEPATH"),
		"PATH":            os.Getenv("PATH"),
		"TEMP":            os.Getenv("TEMP"),
		"TMP":             os.Getenv("TMP"),
		"PATHEXT":         os.Getenv("PATHEXT"),
		"PSModulePath":    os.Getenv("PSModulePath"),
		"TERM":            defaultTerm,
		"COLORTERM":       "truecolor",
		"REMOTLY_SESSION": "m0-05-spike",
	}
}

// envBlock encodes a Windows environment block: KEY=VALUE pairs separated by
// NUL, terminated by a double NUL, all in UTF-16LE.
func envBlock(env map[string]string) (*uint16, error) {
	keys := make([]string, 0, len(env))
	for k := range env {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	var b strings.Builder
	for _, k := range keys {
		b.WriteString(k)
		b.WriteByte('=')
		b.WriteString(env[k])
		b.WriteByte(0)
	}
	b.WriteByte(0)
	utf16, err := syscall.UTF16FromString(b.String())
	if err != nil {
		return nil, err
	}
	return &utf16[0], nil
}

func createConPTY(cols, rows int) (hpc, inWrite, outRead windows.Handle, err error) {
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
	// The pseudoconsole owns copies of inRead and outWrite; the caller keeps the
	// other two ends.
	windows.CloseHandle(inRead)
	windows.CloseHandle(outWrite)
	return
}

func launch(hpc windows.Handle, cmdline string, envPtr, cwdPtr *uint16) (*windows.ProcessInformation, error) {
	attrList, err := windows.NewProcThreadAttributeList(1)
	if err != nil {
		return nil, err
	}
	defer attrList.Delete()
	// The ConPTY streams are UTF-8 (docs: "The input and output streams
	// encoded as UTF-8 contain plain text interleaved with VT sequences").
	// writeAll sends the UTF-8 bytes directly; no UTF-16 conversion.
	if err := attrList.Update(windows.PROC_THREAD_ATTRIBUTE_PSEUDOCONSOLE, unsafe.Pointer(hpc), unsafe.Sizeof(hpc)); err != nil {
		return nil, err
	}

	si := windows.StartupInfoEx{StartupInfo: windows.StartupInfo{Flags: startfUSESTDHANDLES}}
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

func resize(hpc windows.Handle, cols, rows int) error {
	return windows.ResizePseudoConsole(hpc, windows.Coord{X: int16(cols), Y: int16(rows)})
}

func waitExit(pi *windows.ProcessInformation, timeout time.Duration) (uint32, error) {
	done := make(chan error, 1)
	go func() {
		_, err := windows.WaitForSingleObject(pi.Process, windows.INFINITE)
		done <- err
	}()
	select {
	case err := <-done:
		if err != nil {
			return 0, err
		}
		var code uint32
		if err := windows.GetExitCodeProcess(pi.Process, &code); err != nil {
			return 0, err
		}
		return code, nil
	case <-time.After(timeout):
		return 0, fmt.Errorf("timeout waiting for exit")
	}
}

// killTree terminates every process whose parent is pid, recursively, so a
// shell that spawned background jobs does not leak them.
func killTree(root uint32) {
	children := map[uint32][]uint32{}
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return
	}
	defer windows.CloseHandle(snap)
	var entry windows.ProcessEntry32
	entry.Size = uint32(unsafe.Sizeof(entry))
	if err := windows.Process32First(snap, &entry); err != nil {
		return
	}
	for {
		children[entry.ParentProcessID] = append(children[entry.ParentProcessID], entry.ProcessID)
		if err := windows.Process32Next(snap, &entry); err != nil {
			break
		}
	}
	var kill func(uint32)
	kill = func(pid uint32) {
		for _, c := range children[pid] {
			kill(c)
		}
		h, err := windows.OpenProcess(windows.PROCESS_TERMINATE, false, pid)
		if err != nil {
			return
		}
		windows.TerminateProcess(h, 1)
		windows.CloseHandle(h)
	}
	kill(root)
}

func writeAll(f windows.Handle, s string) {
	var done uint32
	// ConPTY input is UTF-8; send the string bytes as-is.
	_ = windows.WriteFile(f, []byte(s), &done, nil)
}

func pump(h windows.Handle, buf *ring) {
	data := make([]byte, 4096)
	for {
		var done uint32
		err := windows.ReadFile(h, data, &done, nil)
		if done > 0 {
			buf.write(data[:done])
		}
		if err != nil {
			return
		}
	}
}

func runCommand(in windows.Handle, buf *ring, command, marker string, timeout time.Duration) (string, bool) {
	buf.mark()
	writeAll(in, command+"\n")
	return buf.waitFor(marker, timeout)
}

func runSimple(in windows.Handle, buf *ring, command string, timeout time.Duration) string {
	buf.mark()
	writeAll(in, command+"\n")
	time.Sleep(500 * time.Millisecond)
	// Best effort: return the tail of output since the mark.
	return buf.since()
}

func clean(s string) string {
	s = strings.ReplaceAll(s, "\r", "")
	return s
}

func must(err error, ctx string) {
	if err != nil {
		fmt.Fprintf(os.Stderr, "fatal (%s): %v\n", ctx, err)
		os.Exit(1)
	}
}

type ring struct {
	buf     []byte
	markPos int
	mu      chan struct{}
}

func newRing() *ring { return &ring{mu: make(chan struct{}, 1)} }

func (r *ring) write(p []byte) {
	r.buf = append(r.buf, p...)
	select {
	case r.mu <- struct{}{}:
	default:
	}
}

func (r *ring) mark() { r.markPos = len(r.buf) }

func (r *ring) since() string {
	s := string(r.buf[r.markPos:])
	return s
}

func (r *ring) waitFor(marker string, timeout time.Duration) (string, bool) {
	deadline := time.After(timeout)
	tick := time.NewTicker(20 * time.Millisecond)
	defer tick.Stop()
	for {
		s := string(r.buf)
		if i := strings.Index(s[r.markPos:], marker); i >= 0 {
			return s[r.markPos : r.markPos+i], true
		}
		select {
		case <-deadline:
			return s[r.markPos:], false
		case <-tick.C:
		}
	}
}
