// Package store reads the line-item snapshot, delivered counts and frequency counters from Redis.
package store

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"strconv"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

// Redis uses two clients: bulk (snapshot and delivered refresh, generous timeouts) and hot
// (per-request frequency lookups, tight timeouts inside the ad request's latency budget).
type Redis struct {
	c   redis.UniversalClient
	hot redis.UniversalClient
}

func NewRedis(bulk, hot redis.UniversalClient) *Redis { return &Redis{c: bulk, hot: hot} }

// LoadSnapshotIfChanged returns nil (and no error) when the stored version equals current.
func (r *Redis) LoadSnapshotIfChanged(ctx context.Context, current int64) (*lineitem.Snapshot, error) {
	v, err := r.c.Get(ctx, lineitem.SnapshotVersionKey).Int64()
	if errors.Is(err, redis.Nil) {
		return nil, nil // nothing published yet
	}
	if err != nil {
		return nil, fmt.Errorf("read snapshot version: %w", err)
	}
	if v == current {
		return nil, nil
	}
	raw, err := r.c.Get(ctx, lineitem.SnapshotKey).Bytes()
	if err != nil {
		return nil, fmt.Errorf("read snapshot: %w", err)
	}
	var s lineitem.Snapshot
	if err := json.Unmarshal(raw, &s); err != nil {
		return nil, fmt.Errorf("decode snapshot: %w", err)
	}
	return &s, nil
}

func (r *Redis) LoadDelivered(ctx context.Context) (map[string]int64, error) {
	raw, err := r.c.HGetAll(ctx, lineitem.DeliveredKey).Result()
	if err != nil {
		return nil, fmt.Errorf("read delivered: %w", err)
	}
	out := make(map[string]int64, len(raw))
	for id, s := range raw {
		n, err := strconv.ParseInt(s, 10, 64)
		if err != nil {
			continue
		}
		out[id] = n
	}
	return out, nil
}

// Counts reads frequency counters for one user in a single round trip.
func (r *Redis) Counts(ctx context.Context, userKey string, lineItemIDs []string) (map[string]int64, error) {
	keys := make([]string, len(lineItemIDs))
	for i, id := range lineItemIDs {
		keys[i] = lineitem.FrequencyKey(id, userKey)
	}
	vals, err := r.hot.MGet(ctx, keys...).Result()
	if err != nil {
		return nil, err
	}
	out := make(map[string]int64, len(vals))
	for i, v := range vals {
		s, ok := v.(string)
		if !ok {
			continue // missing key = 0
		}
		n, err := strconv.ParseInt(s, 10, 64)
		if err == nil {
			out[lineItemIDs[i]] = n
		}
	}
	return out, nil
}

// Target receives refreshed data; the decision engine implements it.
type Target interface {
	SnapshotVersion() int64
	SetSnapshot(lineitem.Snapshot)
	SetDelivered(map[string]int64)
}

// Refresh loads once, then keeps the target up to date until ctx ends.
// ready is set after the first successful snapshot load.
func (r *Redis) Refresh(ctx context.Context, t Target, every time.Duration, ready func(), log *slog.Logger) {
	tick := time.NewTicker(every)
	defer tick.Stop()
	loaded := false
	for {
		if s, err := r.LoadSnapshotIfChanged(ctx, t.SnapshotVersion()); err != nil {
			log.Warn("snapshot refresh failed", "err", err)
		} else if s != nil {
			t.SetSnapshot(*s)
			log.Info("snapshot loaded", "version", s.Version, "line_items", len(s.LineItems))
			if !loaded {
				loaded = true
				ready()
			}
		}
		if d, err := r.LoadDelivered(ctx); err != nil {
			log.Warn("delivered refresh failed", "err", err)
		} else {
			t.SetDelivered(d)
		}
		select {
		case <-ctx.Done():
			return
		case <-tick.C:
		}
	}
}
