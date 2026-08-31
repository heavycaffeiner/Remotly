package main

import (
	"context"
	"errors"
	"io"
	"log/slog"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"

	"github.com/heavycaffeiner/remotly/daemon/internal/applog"
	"github.com/heavycaffeiner/remotly/daemon/internal/config"
	"github.com/heavycaffeiner/remotly/daemon/internal/daemonapp"
	"github.com/heavycaffeiner/remotly/daemon/internal/paths"
)

// cmdRun runs the daemon in the foreground. The service definitions launched by
// `remotly start` call this as `run --log-file <data>/daemon.log` so the daemon
// writes its own log under the 0700 data directory on every platform. Without
// the flag, logs go to stderr (the interactive `remotly run` case).
func cmdRun(args []string) int {
	logFile := flagValue(args, "--log-file")
	cfg, exists, err := config.Load(mustConfigPath())
	if err != nil {
		fmtErr("config: %v\n", err)
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

	var w io.Writer = os.Stderr
	if logFile != "" {
		if dir := filepath.Dir(logFile); dir != "" {
			if err := os.MkdirAll(dir, 0o700); err != nil {
				fmtErr("ensure log dir: %v\n", err)
				return 1
			}
		}
		f, err := os.OpenFile(logFile, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
		if err != nil {
			fmtErr("open log file: %v\n", err)
			return 1
		}
		defer f.Close()
		w = f
	}
	log := applog.New(w, slog.LevelInfo)

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	if exists {
		log.Info("daemon starting", "version", version, "config", "file",
			"loopback_port", cfg.Listen.LoopbackPort, "lan_port", cfg.Listen.LANPort)
	} else {
		log.Info("daemon starting", "version", version, "config", "default",
			"loopback_port", cfg.Listen.LoopbackPort, "lan_port", cfg.Listen.LANPort)
	}
	if err := daemonapp.Run(ctx, cfg, log); err != nil && !errors.Is(err, context.Canceled) {
		log.Error("daemon stopped with error", "error", err)
		return 1
	}
	log.Info("daemon stopped")
	return 0
}
