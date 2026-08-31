package transport

import (
	"os"
	"path/filepath"
	"testing"

	"github.com/heavycaffeiner/remotly/daemon/internal/protocol"
)

// TestFSControlRoundTrip drives the fs.* operations over the control channel
// end to end: roots, mkdir, list, stat, rename, and remove, plus the error
// model for a missing path. The daemon FS runs as the test process user, so
// a t.TempDir() is reachable by both the test and the daemon.
func TestFSControlRoundTrip(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	base := t.TempDir()
	seed := filepath.Join(base, "seed.txt")
	if err := os.WriteFile(seed, []byte("0123456789"), 0o644); err != nil {
		t.Fatal(err)
	}

	// fs.roots reports at least one navigable root.
	roots := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSRoots))
	if roots.Error != nil || len(roots.Roots) == 0 {
		t.Fatalf("fs.roots: %v roots=%v", roots.Error, roots.Roots)
	}

	// fs.mkdir creates a subdirectory that does not yet exist.
	sub := filepath.Join(base, "subdir")
	mk := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSMkdir, "path", sub))
	if mk.Error != nil {
		t.Fatalf("fs.mkdir: %v", mk.Error)
	}
	mkAgain := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSMkdir, "path", sub))
	if mkAgain.Error == nil || mkAgain.Error.Code != protocol.CodeFSExist {
		t.Fatalf("fs.mkdir existing: got %v, want fs_exist", mkAgain.Error)
	}
	// The new subdir is now visible in the listing.
	lstDirs := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSList, "path", base))
	if lstDirs.Error != nil || lstDirs.Total != 2 {
		t.Fatalf("fs.list after mkdir: %v total=%d, want 2", lstDirs.Error, lstDirs.Total)
	}
	dirEntry := false
	for _, en := range lstDirs.Entries {
		if en.Name == "subdir" && en.IsDir {
			dirEntry = true
		}
	}
	if !dirEntry {
		t.Errorf("subdir not listed as a directory: %+v", lstDirs.Entries)
	}

	// fs.list returns the seeded file with its size (and the new subdir).
	lst := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSList, "path", base))
	if lst.Error != nil {
		t.Fatalf("fs.list: %v", lst.Error)
	}
	if lst.Total != 2 || lst.More {
		t.Fatalf("fs.list total=%d more=%v, want 2/false", lst.Total, lst.More)
	}
	foundSeed := false
	for _, en := range lst.Entries {
		if en.Name == "seed.txt" && en.Size == 10 && !en.IsDir {
			foundSeed = true
		}
	}
	if !foundSeed {
		t.Fatalf("fs.list missing seed.txt size 10: %+v", lst.Entries)
	}

	// fs.stat reports a single entry.
	st := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSStat, "path", seed))
	if st.Error != nil || st.Entry == nil || st.Entry.Size != 10 || st.Entry.IsDir {
		t.Fatalf("fs.stat: %v entry=%+v", st.Error, st.Entry)
	}

	// fs.rename moves the file.
	src := seed
	dst := filepath.Join(base, "renamed.txt")
	rn := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSRename, "from", src, "to", dst))
	if rn.Error != nil {
		t.Fatalf("fs.rename: %v", rn.Error)
	}
	if _, err := os.Stat(src); !os.IsNotExist(err) {
		t.Errorf("source should be gone after rename, got %v", err)
	}

	// fs.remove removes the renamed file.
	rm := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSRemove, "path", dst, "remove_kind", "file"))
	if rm.Error != nil {
		t.Fatalf("fs.remove: %v", rm.Error)
	}

	// A stat on a now-missing path returns the typed not-found code.
	miss := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSStat, "path", dst))
	if miss.Error == nil || miss.Error.Code != protocol.CodeFSNotFound {
		t.Fatalf("fs.stat missing: got %v, want fs_not_found", miss.Error)
	}

	// Removing a nonempty directory is refused with fs_not_empty: base still
	// holds the empty subdir.
	rmBase := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSRemove, "path", base, "remove_kind", "dir"))
	if rmBase.Error == nil || rmBase.Error.Code != protocol.CodeFSNotEmpty {
		t.Fatalf("fs.remove nonempty dir: got %v, want fs_not_empty", rmBase.Error)
	}
	// Remove the empty subdir, then base becomes removable.
	rmSub := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSRemove, "path", sub, "remove_kind", "dir"))
	if rmSub.Error != nil {
		t.Fatalf("fs.remove subdir: %v", rmSub.Error)
	}
	rmBase2 := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSRemove, "path", base, "remove_kind", "dir"))
	if rmBase2.Error != nil {
		t.Fatalf("fs.remove empty dir: %v", rmBase2.Error)
	}
}

// TestFSListPagination pages a directory larger than the request limit over
// the control channel and checks the more flag and disjoint pages.
func TestFSListPagination(t *testing.T) {
	e := newEnv(t, envCfg{})
	app := newAppKey(t)
	c := e.newClientPair(t, app, e.tokens.Create())
	c.hello(t, e, "phone")

	dir := t.TempDir()
	for i := 0; i < 10; i++ {
		name := filepath.Join(dir, string(rune('a'+i))+".txt")
		if err := os.WriteFile(name, []byte("x"), 0o644); err != nil {
			t.Fatal(err)
		}
	}
	p1 := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSList, "path", dir, "offset", 0, "limit", 4))
	if p1.Error != nil || p1.Total != 10 || !p1.More || len(p1.Entries) != 4 {
		t.Fatalf("page1: %v total=%d more=%v n=%d", p1.Error, p1.Total, p1.More, len(p1.Entries))
	}
	p2 := c.request(t, ctrlJSON(c.newID(), protocol.TypeFSList, "path", dir, "offset", 4, "limit", 4))
	if p2.Error != nil || !p2.More || len(p2.Entries) != 4 {
		t.Fatalf("page2: %v more=%v n=%d", p2.Error, p2.More, len(p2.Entries))
	}
	seen := map[string]bool{}
	for _, en := range p1.Entries {
		seen[en.Name] = true
	}
	for _, en := range p2.Entries {
		if seen[en.Name] {
			t.Fatalf("overlap between pages: %s", en.Name)
		}
	}
}
