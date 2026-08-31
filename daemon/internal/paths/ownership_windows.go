//go:build windows

package paths

// AssertOwned is a no-op on Windows: ownership is expressed by ACLs, which
// os.Lstat does not expose, and Remotly state lives under the current user's
// own profile (AppData / LocalAppData), which other users cannot write.
func AssertOwned(path string) error { return nil }
