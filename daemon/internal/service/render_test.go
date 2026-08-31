package service

import (
	"strings"
	"testing"
)

func TestLinuxUnitRoundTrip(t *testing.T) {
	binary := "/opt/remotly/remotly"
	logFile := "/home/u/.local/share/remotly/daemon.log"
	unit := LinuxUnit(binary, logFile)
	gotBin, gotLog, ok := parseExecStart(unit)
	if !ok {
		t.Fatalf("parseExecStart failed on rendered unit:\n%s", unit)
	}
	if gotBin != binary || gotLog != logFile {
		t.Fatalf("round-trip: got (%q, %q) want (%q, %q)", gotBin, gotLog, binary, logFile)
	}
}

func TestLinuxUnitSpacesInPaths(t *testing.T) {
	binary := "/home/u/App Data/remotly/remotly"
	logFile := "/home/u/App Data/remotly/daemon.log"
	unit := LinuxUnit(binary, logFile)
	gotBin, gotLog, ok := parseExecStart(unit)
	if !ok {
		t.Fatalf("parseExecStart failed on spaced unit:\n%s", unit)
	}
	if gotBin != binary || gotLog != logFile {
		t.Fatalf("spaced round-trip: got (%q, %q) want (%q, %q)", gotBin, gotLog, binary, logFile)
	}
}

func TestExecArgAndShellSplitRoundTrip(t *testing.T) {
	cases := []string{
		"/opt/remotly",
		"/home/u/App Data/remotly",
		`/home/u/has "quote"/remotly`,
		`/home/u/has\back/remotly`,
		"  ",
	}
	for _, in := range cases {
		quoted := execArg(in)
		// A full ExecStart value: <arg> run --log-file <arg>. Reuse the same
		// arg in both slots to prove a single round-trip.
		line := "ExecStart=" + quoted + " run --log-file " + quoted
		fields := shellSplit(strings.TrimPrefix(line, "ExecStart="))
		if len(fields) != 4 {
			t.Fatalf("input %q: got %d fields %v", in, len(fields), fields)
		}
		if fields[0] != in || fields[3] != in {
			t.Fatalf("input %q: round-trip got (%q, %q)", in, fields[0], fields[3])
		}
	}
}

func TestShellSplitEdgeCases(t *testing.T) {
	if got := shellSplit(""); len(got) != 0 {
		t.Fatalf("empty: got %v", got)
	}
	if got := shellSplit("   "); len(got) != 0 {
		t.Fatalf("whitespace only: got %v", got)
	}
	got := shellSplit(`/a run --log-file /b`)
	if len(got) != 4 || got[0] != "/a" || got[1] != "run" || got[2] != "--log-file" || got[3] != "/b" {
		t.Fatalf("simple: got %v", got)
	}
	// Quoted empty string is a present empty argument.
	got = shellSplit(`""`)
	if len(got) != 1 || got[0] != "" {
		t.Fatalf("quoted empty: got %v", got)
	}
}

func TestParseExecStartRejectsForeign(t *testing.T) {
	// A unit not written by Remotly must not be treated as installed.
	if _, _, ok := parseExecStart("[Service]\nExecStart=/usr/bin/other --flag\n"); ok {
		t.Fatal("expected ok=false for a foreign ExecStart")
	}
	if _, _, ok := parseExecStart("[Unit]\nDescription=hello\n"); ok {
		t.Fatal("expected ok=false for a unit without ExecStart")
	}
}

func TestDarwinPlistRoundTrip(t *testing.T) {
	binary := "/opt/remotly/remotly"
	logFile := "/Users/u/Library/Remotly/daemon.log"
	home := "/Users/u"
	plist := DarwinPlistXML(binary, logFile, home)
	gotBin, gotLog, ok := plistBinary(plist)
	if !ok {
		t.Fatalf("plistBinary failed on rendered plist:\n%s", plist)
	}
	if gotBin != binary || gotLog != logFile {
		t.Fatalf("plist round-trip: got (%q, %q) want (%q, %q)", gotBin, gotLog, binary, logFile)
	}
}

func TestDarwinPlistEscapesMetacharacters(t *testing.T) {
	binary := `/opt/a&b<c>/remotly`
	plist := DarwinPlistXML(binary, "/tmp/log", "/tmp")
	if strings.Contains(plist, `<string>/opt/a&b<c>/remotly</string>`) {
		t.Fatal("metacharacters were not escaped in the plist")
	}
	gotBin, _, ok := plistBinary(plist)
	if !ok || gotBin != binary {
		t.Fatalf("escaped plist round-trip: got (%q, %v) want %q", gotBin, ok, binary)
	}
}

func TestWindowsTaskXMLRoundTrip(t *testing.T) {
	binary := `C:\Program Files\Remotly\remotly.exe`
	logFile := `C:\Users\u\AppData\Local\remotly\daemon.log`
	home := `C:\Users\u`
	xml := string(WindowsTaskXML(binary, logFile, home))
	gotBin, ok := taskBinaryXML(xml)
	if !ok {
		t.Fatal("taskBinaryXML failed on rendered task XML")
	}
	if gotBin != binary {
		t.Fatalf("task round-trip: got %q want %q", gotBin, binary)
	}
}

func TestWindowsTaskXMLHasUTF16BOM(t *testing.T) {
	b := WindowsTaskXML("/opt/remotly", "/tmp/log", "/home")
	if len(b) < 2 || b[0] != 0xFF || b[1] != 0xFE {
		t.Fatalf("expected UTF-16LE BOM, got % x", b[:2])
	}
}

func TestDecodeMaybeUTF16(t *testing.T) {
	// UTF-8 passes through unchanged.
	if got := decodeMaybeUTF16("plain ascii"); got != "plain ascii" {
		t.Fatalf("utf8 passthrough: got %q", got)
	}
	// UTF-16LE with BOM decodes.
	enc := string(utf16leBOM("<Command>C:\\bin</Command>"))
	got := decodeMaybeUTF16(enc)
	if !strings.Contains(got, "<Command>C:\\bin</Command>") {
		t.Fatalf("utf16 decode: got %q", got)
	}
}

func TestPidFrom(t *testing.T) {
	if pidFrom("pid = 12345") != 12345 {
		t.Fatal("pidFrom basic")
	}
	if pidFrom("no pid here") != 0 {
		t.Fatal("pidFrom absent should be 0")
	}
}
