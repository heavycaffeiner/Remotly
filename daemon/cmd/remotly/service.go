package main

import (
	"fmt"
	"os"
	"path/filepath"

	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
	"github.com/heavycaffeiner/remotly/daemon/internal/service"
)

// logFile returns the daemon log path under the 0700 data directory.
func daemonLogFile() (string, error) {
	dataDir, err := paths.Dir(paths.DataKind)
	if err != nil {
		return "", err
	}
	return filepath.Join(dataDir, "daemon.log"), nil
}

// cmdStart installs the per-user service definition on first use (pointing at
// the running binary) and ensures the daemon is running. It is idempotent. If
// the installed definition points at a different binary, it stops the old
// instance, rewrites the definition, and starts the new one (the upgrade path).
func cmdStart(args []string) int {
	bin, err := service.SelfPath()
	if err != nil {
		fmtErr("cannot install: %v\n", err)
		return 1
	}
	if _, err := paths.Ensure(paths.ConfigKind); err != nil {
		fmtErr("ensure config dir: %v\n", err)
		return 1
	}
	if _, err := paths.Ensure(paths.DataKind); err != nil {
		fmtErr("ensure data dir: %v\n", err)
		return 1
	}
	logFile, err := daemonLogFile()
	if err != nil {
		fmtErr("log path: %v\n", err)
		return 1
	}

	mgr := service.New()
	st, _ := mgr.State()
	needInstall := !st.Installed || st.Binary != bin

	if needInstall && st.Running {
		// Stop the old instance so a locked binary (Windows) or a stale unit
		// (Linux/macOS) can be replaced, then install the new definition.
		if err := mgr.Stop(); err != nil {
			fmtErr("stop existing service: %v\n", err)
			return 1
		}
	}
	if needInstall {
		if err := mgr.Install(bin, logFile); err != nil {
			fmtErr("install service: %v\n", err)
			return 1
		}
	}
	if !st.Running || needInstall {
		if err := mgr.Start(); err != nil {
			fmtErr("start service: %v\n", err)
			return 1
		}
	}

	st2, _ := mgr.State()
	printServiceState(st2)
	return 0
}

func cmdStop() int {
	mgr := service.New()
	st, _ := mgr.State()
	if !st.Installed {
		fmt.Println("daemon service: not installed")
		return 0
	}
	if err := mgr.Stop(); err != nil {
		fmtErr("stop service: %v\n", err)
		return 1
	}
	st2, _ := mgr.State()
	printServiceState(st2)
	return 0
}

// cmdUninstall stops the service and removes its definition. It preserves
// config and data (identity keys, paired devices) so a reinstall is seamless.
// Data removal is a separate, explicit `remotly wipe`.
func cmdUninstall() int {
	mgr := service.New()
	if err := mgr.Uninstall(); err != nil {
		fmtErr("uninstall service: %v\n", err)
		return 1
	}
	fmt.Println("daemon service: uninstalled")
	if cfg, err := paths.Dir(paths.ConfigKind); err == nil {
		fmt.Printf("  config kept: %s\n", cfg)
	}
	if data, err := paths.Dir(paths.DataKind); err == nil {
		fmt.Printf("  data kept:   %s\n", data)
	}
	return 0
}

// cmdWipe is the explicit data-removal action. It names the exact directories
// it will delete, requires --yes, refuses to remove a directory owned by
// another user, and stops the service first so it cannot recreate state
// mid-delete.
func cmdWipe(args []string) int {
	yes := false
	for _, a := range args {
		if a == "--yes" || a == "-y" {
			yes = true
		}
	}
	cfgDir, err := paths.Dir(paths.ConfigKind)
	if err != nil {
		fmtErr("config dir: %v\n", err)
		return 1
	}
	dataDir, err := paths.Dir(paths.DataKind)
	if err != nil {
		fmtErr("data dir: %v\n", err)
		return 1
	}
	fmt.Println("remotly wipe will permanently delete:")
	fmt.Printf("  config: %s\n", cfgDir)
	fmt.Printf("  data:   %s\n", dataDir)
	fmt.Println("This removes identity keys and all paired-device records.")
	if !yes {
		fmt.Println("Nothing removed. Re-run with --yes to confirm.")
		return 1
	}
	if err := paths.AssertOwned(cfgDir); err != nil {
		fmtErr("refuse: %v\n", err)
		return 1
	}
	if err := paths.AssertOwned(dataDir); err != nil {
		fmtErr("refuse: %v\n", err)
		return 1
	}
	// Stop the service first (best effort) so it does not hold the control
	// socket or rewrite keys while we delete.
	mgr := service.New()
	if err := mgr.Stop(); err != nil {
		fmt.Fprintf(os.Stderr, "remotly: warning: could not stop service before wipe: %v\n", err)
	}
	if err := os.RemoveAll(cfgDir); err != nil {
		fmtErr("remove config: %v\n", err)
		return 1
	}
	if err := os.RemoveAll(dataDir); err != nil {
		fmtErr("remove data: %v\n", err)
		return 1
	}
	fmt.Println("removed.")
	return 0
}

func printServiceState(st service.State) {
	running := "stopped"
	if st.Running {
		running = "running"
	}
	installed := "not installed"
	if st.Installed {
		installed = "installed"
	}
	fmt.Printf("daemon service: %s, %s\n", installed, running)
	if st.PID != 0 {
		fmt.Printf("  pid:    %d\n", st.PID)
	}
	if st.Binary != "" {
		fmt.Printf("  binary: %s\n", st.Binary)
	}
	if st.Detail != "" {
		fmt.Printf("  detail: %s\n", st.Detail)
	}
}
