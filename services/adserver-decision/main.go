// Command adserver-decision serves ad decisions over HTTP.
//
// Environment:
//
//	ADSERVER_ADDR        listen address (default :8081)
//	REDIS_ADDR           Redis address (default localhost:6379)
//	TOKEN_SECRET         shared with the event collector, at least 32 bytes (required)
//	COLLECTOR_BASE_URL   public base URL of the event collector (default http://localhost:8082)
//	REFRESH_INTERVAL     snapshot and delivered-count refresh interval (default 2s)
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/adtoken"
	"github.com/mrasheed-cse/adcp-platform/services/adserver-decision/internal/decision"
	"github.com/mrasheed-cse/adcp-platform/services/adserver-decision/internal/httpapi"
	"github.com/mrasheed-cse/adcp-platform/services/adserver-decision/internal/store"
)

func main() {
	log := slog.New(slog.NewJSONHandler(os.Stdout, nil))
	if err := run(log); err != nil {
		log.Error("exit", "err", err)
		os.Exit(1)
	}
}

func run(log *slog.Logger) error {
	signer, err := adtoken.NewSigner([]byte(os.Getenv("TOKEN_SECRET")))
	if err != nil {
		return err
	}
	refresh, err := time.ParseDuration(env("REFRESH_INTERVAL", "2s"))
	if err != nil {
		return err
	}

	addr := env("REDIS_ADDR", "localhost:6379")
	hot := redis.NewClient(&redis.Options{
		Addr:         addr,
		ReadTimeout:  20 * time.Millisecond, // stay inside the ad request's latency budget
		WriteTimeout: 20 * time.Millisecond,
		PoolSize:     64,
	})
	defer hot.Close()
	bulk := redis.NewClient(&redis.Options{Addr: addr, ReadTimeout: 2 * time.Second, PoolSize: 2})
	defer bulk.Close()
	st := store.NewRedis(bulk, hot)

	engine := decision.New(st, func(err error) { log.Warn("frequency cap lookup failed", "err", err) })
	h := &httpapi.Handler{Decider: engine, Signer: signer, CollectorBaseURL: env("COLLECTOR_BASE_URL", "http://localhost:8082"),
		Now: time.Now, Log: log}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	go st.Refresh(ctx, engine, refresh, h.SetReady, log)

	srv := &http.Server{
		Addr:              env("ADSERVER_ADDR", ":8081"),
		Handler:           h.Routes(),
		ReadHeaderTimeout: 2 * time.Second,
		ReadTimeout:       5 * time.Second,
		WriteTimeout:      5 * time.Second,
		IdleTimeout:       60 * time.Second,
	}
	errc := make(chan error, 1)
	go func() {
		log.Info("listening", "addr", srv.Addr)
		errc <- srv.ListenAndServe()
	}()
	select {
	case err := <-errc:
		if !errors.Is(err, http.ErrServerClosed) {
			return err
		}
	case <-ctx.Done():
	}
	shutdown, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	return srv.Shutdown(shutdown)
}

func env(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}
