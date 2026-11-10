package store

import (
	"context"
	"encoding/json"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

func setup(t *testing.T) (*miniredis.Miniredis, *Redis) {
	t.Helper()
	mr := miniredis.RunT(t)
	c := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	return mr, NewRedis(c, c)
}

func publish(t *testing.T, mr *miniredis.Miniredis, s lineitem.Snapshot) {
	t.Helper()
	raw, _ := json.Marshal(s)
	mr.Set(lineitem.SnapshotKey, string(raw))
	mr.Set(lineitem.SnapshotVersionKey, jsonInt(s.Version))
}

func jsonInt(v int64) string { b, _ := json.Marshal(v); return string(b) }

func TestSnapshotLoadsOnlyWhenVersionChanges(t *testing.T) {
	mr, r := setup(t)
	ctx := context.Background()

	if s, err := r.LoadSnapshotIfChanged(ctx, 0); err != nil || s != nil {
		t.Fatalf("empty redis: got %v, %v", s, err)
	}
	publish(t, mr, lineitem.Snapshot{Version: 7, LineItems: []lineitem.LineItem{{ID: "a"}}})
	s, err := r.LoadSnapshotIfChanged(ctx, 0)
	if err != nil || s == nil || s.Version != 7 || s.LineItems[0].ID != "a" {
		t.Fatalf("got %+v, %v", s, err)
	}
	if s, _ := r.LoadSnapshotIfChanged(ctx, 7); s != nil {
		t.Fatal("unchanged version reloaded")
	}
}

func TestDeliveredAndCounts(t *testing.T) {
	mr, r := setup(t)
	ctx := context.Background()
	mr.HSet(lineitem.DeliveredKey, "a", "12", "b", "3", "bad", "x")
	d, err := r.LoadDelivered(ctx)
	if err != nil || d["a"] != 12 || d["b"] != 3 || len(d) != 2 {
		t.Fatalf("got %v, %v", d, err)
	}
	mr.Set(lineitem.FrequencyKey("a", "u1"), "2")
	c, err := r.Counts(ctx, "u1", []string{"a", "b"})
	if err != nil || c["a"] != 2 || c["b"] != 0 {
		t.Fatalf("got %v, %v", c, err)
	}
}
