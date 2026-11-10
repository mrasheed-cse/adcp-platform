// Command seed publishes a sample line-item snapshot to Redis so the decision service has
// something to serve before the trafficking API (milestone 1, step 2) exists.
//
//	go run ./tools/seed -redis localhost:6379
package main

import (
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

func main() {
	addr := flag.String("redis", "localhost:6379", "Redis address")
	flag.Parse()

	rdb := redis.NewClient(&redis.Options{Addr: *addr})
	defer rdb.Close()
	ctx := context.Background()
	version, err := rdb.Incr(ctx, lineitem.SnapshotSequenceKey).Result()
	if err != nil {
		log.Fatal(err)
	}

	now := time.Now().UTC()
	s := lineitem.Snapshot{
		Version:     version,
		GeneratedAt: now,
		LineItems: []lineitem.LineItem{
			{
				ID: "li_cricket_sponsor", OrderID: "ord_1", AdvertiserID: "adv_telco", Name: "Cricket season sponsorship",
				Priority: lineitem.PrioritySponsorship, CPM: 4.0,
				Start: now.Add(-time.Hour), End: now.Add(14 * 24 * time.Hour),
				Placements:   []string{"sports_top"},
				Targeting:    lineitem.Targeting{Countries: []string{"BD"}},
				Goal:         lineitem.Goal{Impressions: 100000},
				FrequencyCap: &lineitem.FrequencyCap{Max: 5, WindowSeconds: 86400},
				Creatives: []lineitem.Creative{{ID: "cr_cricket_300x250", Type: "image", Width: 300, Height: 250,
					ImageURL: "https://placehold.co/300x250?text=Cricket+Sponsor", LandingURL: "https://example.com/cricket"}},
			},
			{
				ID: "li_ecom_standard", OrderID: "ord_2", AdvertiserID: "adv_shop", Name: "Winter sale",
				Priority: lineitem.PriorityStandard, CPM: 1.6,
				Start: now.Add(-time.Hour), End: now.Add(7 * 24 * time.Hour),
				Placements: []string{"sports_top", "news_sidebar"},
				Targeting:  lineitem.Targeting{Devices: []string{"mobile"}},
				Goal:       lineitem.Goal{Impressions: 500000},
				Creatives: []lineitem.Creative{
					{ID: "cr_sale_300x250", Type: "image", Width: 300, Height: 250,
						ImageURL: "https://placehold.co/300x250?text=Winter+Sale", LandingURL: "https://example.com/sale"},
					{ID: "cr_sale_320x50", Type: "image", Width: 320, Height: 50,
						ImageURL: "https://placehold.co/320x50?text=Winter+Sale", LandingURL: "https://example.com/sale"},
				},
			},
			{
				ID: "li_house", OrderID: "ord_house", AdvertiserID: "adv_publisher", Name: "House: subscribe",
				Priority: lineitem.PriorityHouse,
				Start:    now.Add(-time.Hour), End: now.Add(365 * 24 * time.Hour),
				Placements: []string{"sports_top", "news_sidebar"},
				Creatives: []lineitem.Creative{{ID: "cr_house_300x250", Type: "html", Width: 300, Height: 250,
					HTML:       "<div style=\"font:16px sans-serif;padding:24px\">Subscribe to our newsletter</div>",
					LandingURL: "https://example.com/subscribe"}},
			},
		},
	}

	raw, err := json.Marshal(s)
	if err != nil {
		log.Fatal(err)
	}
	stored, err := lineitem.PublishScript.Run(ctx, rdb,
		[]string{lineitem.SnapshotKey, lineitem.SnapshotVersionKey}, raw, s.Version).Int()
	if err != nil {
		log.Fatal(err)
	}
	if stored != 1 {
		log.Fatalf("a newer snapshot is already published; nothing written")
	}
	fmt.Printf("published snapshot version %d with %d line items\n", s.Version, len(s.LineItems))
	fmt.Println("note: a running trafficking API replaces this with its own snapshot on its next publish")
}
