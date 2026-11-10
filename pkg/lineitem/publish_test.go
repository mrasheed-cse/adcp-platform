package lineitem

import (
	"context"
	"testing"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"
)

func TestPublishScriptNeverGoesBackwards(t *testing.T) {
	mr := miniredis.RunT(t)
	rdb := redis.NewClient(&redis.Options{Addr: mr.Addr()})
	ctx := context.Background()
	keys := []string{SnapshotKey, SnapshotVersionKey}

	publish := func(body string, v int64) int {
		n, err := PublishScript.Run(ctx, rdb, keys, body, v).Int()
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	if publish(`{"v":5}`, 5) != 1 {
		t.Fatal("first publish rejected")
	}
	if publish(`{"v":4}`, 4) != 0 || publish(`{"v":5b}`, 5) != 0 {
		t.Fatal("older or equal version accepted")
	}
	if got, _ := mr.Get(SnapshotKey); got != `{"v":5}` {
		t.Fatalf("snapshot overwritten: %s", got)
	}
	if publish(`{"v":6}`, 6) != 1 {
		t.Fatal("newer publish rejected")
	}
	if v, _ := mr.Get(SnapshotVersionKey); v != "6" {
		t.Fatalf("version = %s", v)
	}
}
