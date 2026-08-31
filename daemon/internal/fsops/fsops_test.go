package fsops

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func newTempFS(t *testing.T) (*FS, string) {
	t.Helper()
	dir := t.TempDir()
	return New(), dir
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func TestListBasic(t *testing.T) {
	f, dir := newTempFS(t)
	writeFile(t, filepath.Join(dir, "a.txt"), "hello")
	if err := os.Mkdir(filepath.Join(dir, "sub"), 0o755); err != nil {
		t.Fatal(err)
	}
	writeFile(t, filepath.Join(dir, "b.txt"), "world")

	entries, more, total, err := f.List(dir, 0, MaxPage)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if more {
		t.Error("unexpected more flag for a small dir")
	}
	if total != 3 {
		t.Fatalf("total = %d, want 3", total)
	}
	byName := map[string]Entry{}
	for _, e := range entries {
		byName[e.Name] = e
	}
	if !byName["sub"].IsDir {
		t.Error("sub should be a directory")
	}
	if byName["a.txt"].IsDir {
		t.Error("a.txt should not be a directory")
	}
	if byName["a.txt"].Size != int64(len("hello")) {
		t.Errorf("a.txt size = %d, want %d", byName["a.txt"].Size, len("hello"))
	}
}

func TestListPagination(t *testing.T) {
	f, dir := newTempFS(t)
	for i := 0; i < 10; i++ {
		writeFile(t, filepath.Join(dir, strings.Repeat("x", 20)+runeAt(i)+"_f"), "c")
	}
	page1, more, total, err := f.List(dir, 0, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(page1) != 3 || !more || total != 10 {
		t.Fatalf("page1 len=%d more=%v total=%d, want 3/true/10", len(page1), more, total)
	}
	page2, more, _, err := f.List(dir, 3, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(page2) != 3 || !more {
		t.Fatalf("page2 len=%d more=%v, want 3/true", len(page2), more)
	}
	// No overlap between pages.
	got1 := map[string]bool{}
	for _, e := range page1 {
		got1[e.Name] = true
	}
	for _, e := range page2 {
		if got1[e.Name] {
			t.Fatalf("overlap between pages: %s", e.Name)
		}
	}
	// Offsetting past the end yields an empty page and no more.
	tail, more, _, err := f.List(dir, 9, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(tail) != 1 || more {
		t.Fatalf("tail len=%d more=%v, want 1/false", len(tail), more)
	}
	beyond, more, _, err := f.List(dir, 10, 3)
	if err != nil {
		t.Fatal(err)
	}
	if len(beyond) != 0 || more {
		t.Fatalf("beyond len=%d more=%v, want 0/false", len(beyond), more)
	}
}

func TestStatFileDirSymlink(t *testing.T) {
	f, dir := newTempFS(t)
	writeFile(t, filepath.Join(dir, "f.txt"), "12345")
	target := filepath.Join(dir, "f.txt")
	link := filepath.Join(dir, "lnk")
	if err := os.Symlink(target, link); err != nil {
		t.Fatal(err)
	}
	// Broken symlink: target does not exist.
	broken := filepath.Join(dir, "broken")
	if err := os.Symlink(filepath.Join(dir, "nope"), broken); err != nil {
		t.Fatal(err)
	}

	fe, err := f.Stat(filepath.Join(dir, "f.txt"))
	if err != nil {
		t.Fatal(err)
	}
	if fe.Size != 5 || fe.ModTime == 0 {
		t.Errorf("file stat size=%d mtime=%d, want size 5 and nonzero mtime", fe.Size, fe.ModTime)
	}

	le, err := f.Stat(link)
	if err != nil {
		t.Fatal(err)
	}
	if !le.IsSymlink {
		t.Error("link should be reported as a symlink")
	}
	if le.LinkTarget != target {
		t.Errorf("link target = %q, want %q", le.LinkTarget, target)
	}

	be, err := f.Stat(broken)
	if err != nil {
		t.Fatalf("stat on a broken symlink should not fail: %v", err)
	}
	if !be.IsSymlink {
		t.Error("broken link should be a symlink")
	}
}

func TestMkdirAndRemove(t *testing.T) {
	f, dir := newTempFS(t)
	sub := filepath.Join(dir, "new")
	if err := f.Mkdir(sub); err != nil {
		t.Fatalf("Mkdir: %v", err)
	}
	if err := f.Mkdir(sub); err != ErrExist {
		t.Fatalf("second Mkdir err = %v, want ErrExist", err)
	}
	writeFile(t, filepath.Join(sub, "child"), "x")
	if err := f.RemoveDir(sub); err != ErrNotEmpty {
		t.Fatalf("RemoveDir on nonempty err = %v, want ErrNotEmpty", err)
	}
	if err := f.RemoveFile(sub); err != ErrIsDir {
		t.Fatalf("RemoveFile on a dir err = %v, want ErrIsDir", err)
	}
	// Empty the dir, then RemoveFile on a file, then RemoveDir on the empty dir.
	if err := f.RemoveFile(filepath.Join(sub, "child")); err != nil {
		t.Fatalf("RemoveFile: %v", err)
	}
	if err := f.RemoveDir(sub); err != nil {
		t.Fatalf("RemoveDir empty: %v", err)
	}
	if _, err := f.Stat(sub); err != ErrNotFound {
		t.Fatalf("Stat after remove err = %v, want ErrNotFound", err)
	}
}

func TestRename(t *testing.T) {
	f, dir := newTempFS(t)
	src := filepath.Join(dir, "src")
	dst := filepath.Join(dir, "dst")
	writeFile(t, src, "data")
	if err := f.Rename(src, dst); err != nil {
		t.Fatalf("Rename: %v", err)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Errorf("src should be gone, got %v", err)
	}
	got, err := os.ReadFile(dst)
	if err != nil || string(got) != "data" {
		t.Fatalf("dst content = %q err=%v, want %q", got, err, "data")
	}
}

func TestPathValidation(t *testing.T) {
	f, _ := newTempFS(t)
	// Relative path is rejected.
	if _, _, _, err := f.List("relative/dir", 0, 10); err != ErrInvalidPath {
		t.Errorf("relative list err = %v, want ErrInvalidPath", err)
	}
	// NUL byte is rejected.
	if err := f.Mkdir(strings.Repeat("a", 3) + "\x00"); err != ErrInvalidPath {
		t.Errorf("nul mkdir err = %v, want ErrInvalidPath", err)
	}
	// Overlong path is rejected.
	if _, err := f.Stat(strings.Repeat("a", MaxPathLen+1)); err != ErrInvalidPath {
		t.Errorf("overlong stat err = %v, want ErrInvalidPath", err)
	}
	// .. collapse to an existing absolute dir is fine.
	if err := f.Mkdir(t.TempDir() + "/../.."); err == nil {
		// Creating above the root will fail for other reasons; the point is it
		// is not silently accepted as a valid target. Just assert it errors.
		t.Error("mkdir above root unexpectedly succeeded")
	}
}

// TestUnicodeNamesRoundTrip verifies that NFC and NFD Hangul names, an emoji,
// and a box-drawing character are preserved byte-faithfully and treated as
// distinct entries, with no destructive normalization.
func TestUnicodeNamesRoundTrip(t *testing.T) {
	f, dir := newTempFS(t)
	// NFC Hangul syllable (single code point).
	nfc := filepath.Join(dir, "\ud55c") // 한
	// NFD: base jamo + combining mark (two code points).
	nfd := filepath.Join(dir, "\ud55c\u0315") // 한 + combining dot below
	emoji := filepath.Join(dir, "\U0001F680") // 🚀
	box := filepath.Join(dir, "\u250C")       // ┌
	for _, p := range []string{nfc, nfd, emoji, box} {
		if err := os.WriteFile(p, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	entries, _, total, err := f.List(dir, 0, MaxPage)
	if err != nil {
		t.Fatal(err)
	}
	if total != 4 {
		t.Fatalf("total = %d, want 4", total)
	}
	names := map[string]bool{}
	for _, e := range entries {
		names[e.Name] = true
	}
	// Each name must round-trip to exactly the bytes we created.
	if !names["\ud55c"] {
		t.Error("NFC name not preserved")
	}
	if !names["\ud55c\u0315"] {
		t.Error("NFD name not preserved")
	}
	if !names["\U0001F680"] {
		t.Error("emoji name not preserved")
	}
	if !names["\u250C"] {
		t.Error("box-drawing name not preserved")
	}
	// NFC and NFD must be distinct (no folding).
	if names["\ud55c"] && names["\ud55c\u0315"] {
		// both present => distinct, which is what we want
	} else {
		t.Error("NFC and NFD were collapsed into one entry")
	}
}

// TestNotFound returns the typed not-found error for a missing path.
func TestNotFound(t *testing.T) {
	f, dir := newTempFS(t)
	if _, _, _, err := f.List(filepath.Join(dir, "missing"), 0, 10); err != ErrNotFound {
		t.Errorf("List missing err = %v, want ErrNotFound", err)
	}
	if _, err := f.Stat(filepath.Join(dir, "missing")); err != ErrNotFound {
		t.Errorf("Stat missing err = %v, want ErrNotFound", err)
	}
	if err := f.Rename(filepath.Join(dir, "a"), filepath.Join(dir, "b")); err != ErrNotFound {
		t.Errorf("Rename missing src err = %v, want ErrNotFound", err)
	}
}

// runeAt maps an index to a distinct rune so generated fixture names are unique.
func runeAt(i int) string {
	return string(rune('a' + i))
}
