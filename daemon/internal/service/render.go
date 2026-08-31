package service

import (
	"encoding/binary"
	"regexp"
	"strconv"
	"strings"
)

// Service identifiers, shared by the CLI display and the platform managers.
const (
	LinuxUnitName   = "remotly.service"
	DarwinLabel     = "com.remotly.daemon"
	DarwinPlistName = "com.remotly.daemon.plist"
	WindowsTask     = "RemotlyDaemon"
	description     = "Remotly daemon (per-user PTY session host)"
)

// LinuxUnit renders the systemd user unit. systemd parses ExecStart with
// shell-like word splitting, so each path argument is quoted when it contains
// whitespace (via execArg). The binary and log file are absolute paths the
// caller has already validated.
func LinuxUnit(binary, logFile string) string {
	var b strings.Builder
	b.WriteString("[Unit]\n")
	b.WriteString("Description=" + description + "\n")
	b.WriteString("After=network.target\n\n")
	b.WriteString("[Service]\n")
	b.WriteString("Type=simple\n")
	b.WriteString("ExecStart=" + execArg(binary) + " run --log-file " + execArg(logFile) + "\n")
	b.WriteString("Restart=on-failure\n")
	b.WriteString("RestartSec=2\n\n")
	b.WriteString("[Install]\n")
	b.WriteString("WantedBy=default.target\n")
	return b.String()
}

// execArg quotes a single systemd ExecStart argument when it contains
// whitespace, a backslash, or a double quote (systemd treats backslash as an
// escape even outside quotes), escaping embedded backslashes and quotes.
// Bare, safe arguments are returned unchanged. This is the inverse of
// shellSplit for the paths we embed (no single quotes, no variables).
func execArg(s string) string {
	if s == "" || strings.ContainsAny(s, " \t\n\\\"") {
		q := strings.NewReplacer(`\`, `\\`, `"`, `\"`)
		return `"` + q.Replace(s) + `"`
	}
	return s
}

// shellSplit splits a systemd ExecStart value into arguments, honoring double
// quotes and backslash escapes so quoted paths with spaces round-trip.
func shellSplit(s string) []string {
	var args []string
	var cur strings.Builder
	inQuote := false
	has := false // whether the current argument has any content
	for i := 0; i < len(s); i++ {
		c := s[i]
		switch {
		case c == '\\' && i+1 < len(s):
			next := s[i+1]
			if inQuote && (next == '"' || next == '\\') {
				cur.WriteByte(next)
			} else if !inQuote {
				cur.WriteByte(next)
				if next == ' ' || next == '\t' || next == '\n' {
					has = true
				}
			}
			i++
		case c == '"':
			inQuote = !inQuote
			has = true
		case (c == ' ' || c == '\t' || c == '\n') && !inQuote:
			if has || cur.Len() > 0 {
				args = append(args, cur.String())
				cur.Reset()
				has = false
			}
		default:
			cur.WriteByte(c)
			has = true
		}
	}
	if has || cur.Len() > 0 {
		args = append(args, cur.String())
	}
	return args
}

// parseExecStart extracts the binary (first token) and log file (last token)
// from a rendered or on-disk unit's ExecStart= line. It returns ok=false when
// the line is absent or not in the expected "run --log-file" shape, which the
// caller treats as "not installed by Remotly" and overwrites.
func parseExecStart(unit string) (binary, logFile string, ok bool) {
	for _, line := range strings.Split(unit, "\n") {
		line = strings.TrimSpace(line)
		if !strings.HasPrefix(line, "ExecStart=") {
			continue
		}
		fields := shellSplit(strings.TrimPrefix(line, "ExecStart="))
		// Expected: <binary> run --log-file <logFile>
		if len(fields) != 4 || fields[1] != "run" || fields[2] != "--log-file" {
			return "", "", false
		}
		return fields[0], fields[3], true
	}
	return "", "", false
}

// DarwinPlist renders the launchd LaunchAgent property list. Values are XML
// escaped so a path containing & or < cannot break the document.
func DarwinPlistXML(binary, logFile, home string) string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-8"?>` + "\n")
	b.WriteString(`<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">` + "\n")
	b.WriteString("<plist version=\"1.0\">\n<dict>\n")
	b.WriteString("  <key>Label</key>\n  <string>" + DarwinLabel + "</string>\n")
	b.WriteString("  <key>ProgramArguments</key>\n  <array>\n")
	b.WriteString("    <string>" + xmlEscape(binary) + "</string>\n")
	b.WriteString("    <string>run</string>\n")
	b.WriteString("    <string>--log-file</string>\n")
	b.WriteString("    <string>" + xmlEscape(logFile) + "</string>\n")
	b.WriteString("  </array>\n")
	b.WriteString("  <key>RunAtLoad</key>\n  <true/>\n")
	b.WriteString("  <key>KeepAlive</key>\n  <true/>\n")
	b.WriteString("  <key>WorkingDirectory</key>\n  <string>" + xmlEscape(home) + "</string>\n")
	b.WriteString("</dict>\n</plist>\n")
	return b.String()
}

// DarwinLabelOf returns the launchd service target ("gui/<uid>/<label>") used
// by the modern bootstrap/bootout/kickstart API. The caller supplies the uid.
func DarwinTarget(uid int) string {
	return "gui/" + itoa(uid) + "/" + DarwinLabel
}

// WindowsTaskXML renders the per-user scheduled task definition as UTF-16LE
// bytes with a BOM, which is the encoding schtasks /Create /XML expects. The
// task runs in the user's interactive session at logon (InteractiveToken), so
// it inherits the login-shell environment, and RestartOnFailure approximates
// a keep-alive. MultipleInstancesPolicy=IgnoreNew prevents a duplicate daemon.
func WindowsTaskXML(binary, logFile, home string) []byte {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-16"?>` + "\r\n")
	b.WriteString(`<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">` + "\r\n")
	b.WriteString("  <RegistrationInfo>\r\n")
	b.WriteString("    <Description>" + description + "</Description>\r\n")
	b.WriteString("  </RegistrationInfo>\r\n")
	b.WriteString("  <Triggers>\r\n")
	b.WriteString("    <LogonTrigger>\r\n")
	b.WriteString("      <Enabled>true</Enabled>\r\n")
	b.WriteString("    </LogonTrigger>\r\n")
	b.WriteString("  </Triggers>\r\n")
	b.WriteString("  <Principals>\r\n")
	b.WriteString("    <Principal id=\"Author\">\r\n")
	b.WriteString("      <LogonType>InteractiveToken</LogonType>\r\n")
	b.WriteString("      <RunLevel>LeastPrivilege</RunLevel>\r\n")
	b.WriteString("    </Principal>\r\n")
	b.WriteString("  </Principals>\r\n")
	b.WriteString("  <Settings>\r\n")
	b.WriteString("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n")
	b.WriteString("    <RestartOnFailure>\r\n")
	b.WriteString("      <Interval>PT2S</Interval>\r\n")
	b.WriteString("      <Count>999</Count>\r\n")
	b.WriteString("    </RestartOnFailure>\r\n")
	b.WriteString("    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\r\n")
	b.WriteString("    <StartWhenAvailable>true</StartWhenAvailable>\r\n")
	b.WriteString("    <AllowHardTerminate>true</AllowHardTerminate>\r\n")
	b.WriteString("  </Settings>\r\n")
	b.WriteString("  <Actions>\r\n")
	b.WriteString("    <Exec>\r\n")
	b.WriteString("      <Command>" + xmlEscape(binary) + "</Command>\r\n")
	b.WriteString("      <Arguments>run --log-file " + xmlEscape(logFile) + "</Arguments>\r\n")
	b.WriteString("      <WorkingDirectory>" + xmlEscape(home) + "</WorkingDirectory>\r\n")
	b.WriteString("    </Exec>\r\n")
	b.WriteString("  </Actions>\r\n")
	b.WriteString("</Task>" + "\r\n")
	return utf16leBOM(b.String())
}

// xmlEscape escapes the five XML metacharacters. Paths are the only values
// embedded, so no CDATA or attribute context is needed.
func xmlEscape(s string) string {
	r := strings.NewReplacer(
		"&", "&amp;",
		"<", "&lt;",
		">", "&gt;",
		"\"", "&quot;",
		"'", "&apos;",
	)
	return r.Replace(s)
}

// utf16leBOM encodes s as UTF-16LE with a leading BOM (FF FE).
func utf16leBOM(s string) []byte {
	out := make([]byte, 0, 2+len(s)*2)
	out = append(out, 0xFF, 0xFE)
	var unit [2]byte
	for _, r := range s {
		u := uint16(r)
		binary.LittleEndian.PutUint16(unit[:], u)
		out = append(out, unit[:]...)
	}
	return out
}

// itoa is a small non-negative int to string for the launchd target.
func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var buf [20]byte
	i := len(buf)
	for n > 0 {
		i--
		buf[i] = byte('0' + n%10)
		n /= 10
	}
	return string(buf[i:])
}

// strconvAtoi is a thin alias so callers read intent without the import.
func strconvAtoi(s string) (int, error) { return strconv.Atoi(s) }

var (
	plistString = regexp.MustCompile(`<string>(.*?)</string>`)
	pidLine     = regexp.MustCompile(`pid = (\d+)`)
)

// xmlUnescape reverses xmlEscape for the five metacharacters.
func xmlUnescape(s string) string {
	r := strings.NewReplacer(
		"&quot;", "\"",
		"&apos;", "'",
		"&lt;", "<",
		"&gt;", ">",
		"&amp;", "&",
	)
	return r.Replace(s)
}

// plistBinary extracts the binary (first argument) and log file (last
// argument) from the ProgramArguments array of a rendered plist. It validates
// the argument shape matches what DarwinPlistXML emits; a mismatch returns
// ok=false so the caller treats the file as not written by Remotly.
func plistBinary(content string) (binary, logFile string, ok bool) {
	i := strings.Index(content, "<key>ProgramArguments</key>")
	if i < 0 {
		return "", "", false
	}
	rest := content[i:]
	arrStart := strings.Index(rest, "<array>")
	arrEnd := strings.Index(rest, "</array>")
	if arrStart < 0 || arrEnd < 0 || arrEnd < arrStart {
		return "", "", false
	}
	block := rest[arrStart:arrEnd]
	matches := plistString.FindAllStringSubmatch(block, -1)
	if len(matches) != 4 {
		return "", "", false
	}
	args := make([]string, 4)
	for i, m := range matches {
		args[i] = xmlUnescape(m[1])
	}
	if args[1] != "run" || args[2] != "--log-file" {
		return "", "", false
	}
	return args[0], args[3], true
}

// pidFrom pulls the numeric pid out of `launchctl print` output.
func pidFrom(out string) int {
	m := pidLine.FindStringSubmatch(out)
	if m == nil {
		return 0
	}
	n, err := strconv.Atoi(m[1])
	if err != nil {
		return 0
	}
	return n
}

// taskBinaryXML extracts the <Command> (binary path) from the task XML that
// `schtasks /Query /XML` returns. schtasks emits UTF-16LE with a BOM, so the
// input is decoded first; without that the ASCII tags would not match because
// each byte is interleaved with NULs.
func taskBinaryXML(s string) (string, bool) {
	s = decodeMaybeUTF16(s)
	start := strings.Index(s, "<Command>")
	if start < 0 {
		return "", false
	}
	start += len("<Command>")
	end := strings.Index(s[start:], "</Command>")
	if end < 0 {
		return "", false
	}
	return xmlUnescape(s[start : start+end]), true
}

// decodeMaybeUTF16 converts a UTF-16LE string (with or without BOM) to UTF-8.
// If the input is not UTF-16LE it is returned unchanged, so a source that
// emits UTF-8 still parses.
func decodeMaybeUTF16(s string) string {
	b := []byte(s)
	off := 0
	if len(b) >= 2 && b[0] == 0xFF && b[1] == 0xFE {
		off = 2
	}
	body := b[off:]
	if len(body) == 0 || len(body)%2 != 0 {
		return s
	}
	if !(looksUTF16LE(body) || (body[1] == 0 && body[0] < 0x80)) {
		return s
	}
	out := make([]byte, 0, len(body)/2)
	for i := 0; i+1 < len(body); i += 2 {
		u := binary.LittleEndian.Uint16(body[i:])
		out = utf8Append(out, rune(u))
	}
	return string(out)
}

func looksUTF16LE(b []byte) bool {
	n := 0
	for i := 1; i+1 < len(b); i += 2 {
		if b[i] == 0 {
			n++
		}
		if n > 3 {
			return true
		}
	}
	return false
}

func utf8Append(b []byte, r rune) []byte {
	switch {
	case r < 0x80:
		return append(b, byte(r))
	case r < 0x800:
		return append(b, byte(0xC0|r>>6), byte(0x80|r&0x3F))
	default:
		return append(b, byte(0xE0|r>>12), byte(0x80|r>>6&0x3F), byte(0x80|r&0x3F))
	}
}
