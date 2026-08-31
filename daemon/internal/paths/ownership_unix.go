//go:build !windows

package paths

import (
	"fmt"
	"os"
	"syscall"
)

// AssertOwned refuses to proceed when path exists and is not owned by the
// current effective user. Remotly state (config, data, keys) is per-user; a
// directory planted by another user must not be silently rewritten, chmod'd,
// or deleted. Absent paths are allowed.
func AssertOwned(path string) error {
	fi, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return nil
	}
	if int(st.Uid) != os.Geteuid() {
		return fmt.Errorf("refusing: %s is owned by another user; remove it manually if you did not create it", path)
	}
	return nil
}
