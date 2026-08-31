// Command remotly-relay runs one Remotly relay service.
//
// The relay is an opaque router for Remotly transport messages. It stores
// nothing and keeps no persistent state; see docs/protocol.md section 10
// for the wire contract and relay/README.md for operations.
package main

import (
	"context"
	"flag"
	"fmt"
	"log/slog"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/heavycaffeiner/remotly/relay/relaycfg"
	"github.com/heavycaffeiner/remotly/relay/server"
)

const drainTimeout = 5 * time.Second

// version is overridden at release builds with -ldflags.
var version = "0.1.0-dev"

func main() {
	// Checked before flag parsing so it works as a subcommand, matching the
	// daemon's `remotly version`.
	if len(os.Args) > 1 && os.Args[1] == "version" {
		fmt.Printf("remotly-relay %s\n", version)
		return
	}

	configPath := flag.String("config", "", "path to the relay JSON config")
	flag.Parse()
	if *configPath == "" {
		fmt.Fprintln(os.Stderr, "usage: remotly-relay -config <path.json>")
		fmt.Fprintln(os.Stderr, "       remotly-relay version")
		os.Exit(2)
	}

	cfg, err := relaycfg.Load(*configPath)
	if err != nil {
		fmt.Fprintf(os.Stderr, "remotly-relay: %v\n", err)
		os.Exit(1)
	}

	log := slog.New(slog.NewJSONHandler(os.Stderr, &slog.HandlerOptions{
		Level: slog.LevelInfo,
	})).With("service", "remotly-relay")

	srv, err := server.New(server.Options{
		Cfg: cfg,
		Log: slogLogger{log: log},
	})
	if err != nil {
		log.Error("relay: build failed", "err", err.Error())
		os.Exit(1)
	}
	if err := srv.Listen(); err != nil {
		log.Error("relay: listen failed", "err", err.Error())
		os.Exit(1)
	}
	log.Info("relay: started", "listen", srv.Addr())

	stop := make(chan os.Signal, 1)
	signal.Notify(stop, syscall.SIGINT, syscall.SIGTERM)
	sig := <-stop
	log.Info("relay: stopping", "signal", sig.String())

	ctx, cancel := context.WithTimeout(context.Background(), drainTimeout)
	defer cancel()
	if err := srv.Shutdown(ctx); err != nil {
		log.Warn("relay: shutdown did not drain in time", "err", err.Error())
	}
	log.Info("relay: stopped")
}

// slogLogger adapts *slog.Logger to the relay Logger interface.
type slogLogger struct {
	log *slog.Logger
}

func (l slogLogger) Info(msg string, kv ...any)  { l.log.Info(msg, kv...) }
func (l slogLogger) Warn(msg string, kv ...any)  { l.log.Warn(msg, kv...) }
func (l slogLogger) Error(msg string, kv ...any) { l.log.Error(msg, kv...) }
