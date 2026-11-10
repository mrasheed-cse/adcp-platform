// Package collector records impressions and clicks from signed tracking URLs.
//
//	GET /i?t=<token>  impression pixel: 1x1 transparent GIF
//	GET /c?t=<token>  click: 302 redirect to the landing page carried inside the signed token
//
// Each new event updates real-time counters in Redis (delivered impressions, frequency caps)
// and is published to Kafka for reporting. Duplicates (same request id) are counted once.
package collector

import (
	"context"
	"log/slog"
	"net/http"
	"net/url"
	"time"

	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/adtoken"
	"github.com/mrasheed-cse/adcp-platform/pkg/events"
	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

// dedupTTL bounds how long a request id is remembered; it must cover the click max age.
const dedupTTL = 25 * time.Hour

// Sink publishes events asynchronously. It must not block the request.
type Sink interface {
	Publish(events.Event)
}

type Collector struct {
	Signer           *adtoken.Signer
	Redis            redis.UniversalClient
	Sink             Sink
	Now              func() time.Time
	ImpressionMaxAge time.Duration
	ClickMaxAge      time.Duration
	Log              *slog.Logger
}

func (c *Collector) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /i", c.impression)
	mux.HandleFunc("GET /c", c.click)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	return mux
}

// transparentGIF is the smallest valid 1x1 transparent GIF.
var transparentGIF = []byte{
	0x47, 0x49, 0x46, 0x38, 0x39, 0x61, 0x01, 0x00, 0x01, 0x00, 0x80, 0x00, 0x00, 0x00, 0x00, 0x00,
	0xff, 0xff, 0xff, 0x21, 0xf9, 0x04, 0x01, 0x00, 0x00, 0x00, 0x00, 0x2c, 0x00, 0x00, 0x00, 0x00,
	0x01, 0x00, 0x01, 0x00, 0x00, 0x02, 0x02, 0x44, 0x01, 0x00, 0x3b,
}

func (c *Collector) impression(w http.ResponseWriter, r *http.Request) {
	claims, err := c.Signer.Verify(r.URL.Query().Get("t"), c.Now(), c.ImpressionMaxAge)
	if err != nil {
		http.Error(w, "invalid token", http.StatusBadRequest)
		return
	}
	ctx := r.Context()
	isNew, redisOK := c.firstSeen(ctx, "adserver:dedup:imp:"+claims.RequestID)
	if isNew && redisOK {
		c.countImpression(ctx, claims)
	}
	if isNew {
		c.Sink.Publish(c.event(events.TypeImpression, claims))
	}
	w.Header().Set("Content-Type", "image/gif")
	w.Header().Set("Cache-Control", "no-store, no-cache, must-revalidate")
	_, _ = w.Write(transparentGIF)
}

func (c *Collector) click(w http.ResponseWriter, r *http.Request) {
	claims, err := c.Signer.Verify(r.URL.Query().Get("t"), c.Now(), c.ClickMaxAge)
	if err != nil {
		http.Error(w, "invalid token", http.StatusBadRequest)
		return
	}
	landing, err := url.Parse(claims.LandingURL)
	if err != nil || (landing.Scheme != "https" && landing.Scheme != "http") || landing.Host == "" {
		http.Error(w, "invalid landing page", http.StatusBadRequest)
		return
	}
	if isNew, _ := c.firstSeen(r.Context(), "adserver:dedup:clk:"+claims.RequestID); isNew {
		c.Sink.Publish(c.event(events.TypeClick, claims))
	}
	w.Header().Set("Cache-Control", "no-store")
	http.Redirect(w, r, landing.String(), http.StatusFound)
}

// firstSeen reports whether this key is new. When Redis is unavailable it returns (true, false):
// the event is still published (ClickHouse de-duplicates by event_id) but counters are skipped.
func (c *Collector) firstSeen(ctx context.Context, key string) (isNew, redisOK bool) {
	ok, err := c.Redis.SetNX(ctx, key, 1, dedupTTL).Result()
	if err != nil {
		c.Log.Warn("dedup check failed", "err", err)
		return true, false
	}
	return ok, true
}

func (c *Collector) countImpression(ctx context.Context, cl adtoken.Claims) {
	_, err := c.Redis.TxPipelined(ctx, func(p redis.Pipeliner) error {
		p.HIncrBy(ctx, lineitem.DeliveredKey, cl.LineItemID, 1)
		if cl.UserKey != "" && cl.FreqWindow > 0 {
			k := lineitem.FrequencyKey(cl.LineItemID, cl.UserKey)
			p.Incr(ctx, k)
			p.ExpireNX(ctx, k, time.Duration(cl.FreqWindow)*time.Second) // window starts at first impression
		}
		return nil
	})
	if err != nil {
		c.Log.Warn("counter update failed", "err", err, "line_item", cl.LineItemID)
	}
}

func (c *Collector) event(kind string, cl adtoken.Claims) events.Event {
	return events.Event{
		EventID:     kind + ":" + cl.RequestID, // deterministic, so retries de-duplicate downstream
		EventType:   kind,
		TimestampMs: c.Now().UnixMilli(),
		RequestID:   cl.RequestID,
		LineItemID:  cl.LineItemID,
		CreativeID:  cl.CreativeID,
		PlacementID: cl.PlacementID,
		PublisherID: cl.PublisherID,
		UserKey:     cl.UserKey,
		Country:     cl.Country,
		Device:      cl.Device,
		CPM:         cl.CPM,
	}
}
