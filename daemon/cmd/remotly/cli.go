package main

import (
	"fmt"
	"os"

	"github.com/heavycaffeiner/remotly/daemon/internal/localctl"
	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
)

// flagValue returns the value following name in args, or "" when absent.
// Only two-token "--flag value" form is supported, which is all the service
// definitions emit.
func flagValue(args []string, name string) string {
	for i := 0; i+1 < len(args); i++ {
		if args[i] == name {
			return args[i+1]
		}
	}
	return ""
}

// fmtErr writes a "remotly: ..." line to stderr for early failures before a
// logger exists.
func fmtErr(format string, a ...interface{}) {
	fmt.Fprintf(os.Stderr, "remotly: "+format, a...)
}

// mustLocalPath returns the local control endpoint path for the current user,
// exiting on failure. It does not create the directory; the daemon owns that.
func mustLocalPath() string {
	dir, err := paths.Dir(paths.DataKind)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		os.Exit(1)
	}
	return localctl.Path(dir)
}

// callDaemon sends one localctl request and prints a friendly error on
// failure. It returns the response and whether the call succeeded.
func callDaemon(req localctl.Request) (localctl.Response, bool) {
	resp, err := localctl.Call(mustLocalPath(), req)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly: %v\n", err)
		fmt.Fprintln(os.Stderr, "remotly: the daemon may not be running; start it with `remotly run`")
		return localctl.Response{}, false
	}
	if !resp.OK {
		fmt.Fprintf(os.Stderr, "remotly: %s\n", resp.Err)
		return localctl.Response{}, false
	}
	return resp, true
}
