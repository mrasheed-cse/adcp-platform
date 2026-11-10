// Command event-collector records impressions and clicks.
//
// Environment:
//
//	COLLECTOR_ADDR      listen address (default :8082)
//	REDIS_ADDR          Redis address (default localhost:6379)
//	TOKEN_SECRET        shared with the decision service, at least 32 bytes (required)
//	KAFKA_BROKERS       comma-separated brokers (default localhost:9094)
//	KAFKA_TOPIC         topic (default ad-events)
//	IMPRESSION_MAX_AGE  how long after serving an impression is accepted (default 1h)
//	CLICK_MAX_AGE       how long after serving a click is accepted (default 24h)
package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/adtoken"
	"github.com/mrasheed-cse/adcp-platform/pkg/events"
	"github.com/mrasheed-cse/adcp-platform/services/event-collector/internal/collector"
	"github.com/mrasheed-cse/adcp-platform/services/event-collector/internal/sink"
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
	impAge, err := time.ParseDuration(env("IMPRESSION_MAX_AGE", "1h"))
	if err != nil {
		return err
	}
	clickAge, err := time.ParseDuration(env("CLICK_MAX_AGE", "24h"))
	if err != nil {
		return err
	}

	rdb := redis.NewClient(&redis.Options{
		Addr:         env("REDIS_ADDR", "localhost:6379"),
		ReadTimeout:  50 * time.Millisecond,
		WriteTimeout: 50 * time.Millisecond,
		PoolSize:     64,
	})
	defer rdb.Close()

	k := sink.NewKafka(strings.Split(env("KAFKA_BROKERS", "localhost:9094"), ","), env("KAFKA_TOPIC", events.Topic), log)
	defer func() {
		if err := k.Close(); err != nil {
			log.Error("kafka close", "err", err)
		}
	}()

	c := &collector.Collector{Signer: signer, Redis: rdb, Sink: k, Now: time.Now,
		ImpressionMaxAge: impAge, ClickMaxAge: clickAge, Log: log}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	srv := &http.Server{
		Addr:              env("COLLECTOR_ADDR", ":8082"),
		Handler:           c.Routes(),
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
