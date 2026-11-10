package collector

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"sync"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"
	"github.com/redis/go-redis/v9"

	"github.com/mrasheed-cse/adcp-platform/pkg/adtoken"
	"github.com/mrasheed-cse/adcp-platform/pkg/events"
	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

type memSink struct {
	mu  sync.Mutex
	evs []events.Event
}

func (m *memSink) Publish(e events.Event) { m.mu.Lock(); m.evs = append(m.evs, e); m.mu.Unlock() }

var now = time.Unix(1_800_000_000, 0)

func setup(t *testing.T) (*Collector, *miniredis.Miniredis, *memSink) {
	t.Helper()
	mr := miniredis.RunT(t)
	s, _ := adtoken.NewSigner([]byte("0123456789abcdef0123456789abcdef"))
	sink := &memSink{}
	c := &Collector{Signer: s, Redis: redis.NewClient(&redis.Options{Addr: mr.Addr()}), Sink: sink,
		Now: func() time.Time { return now }, ImpressionMaxAge: time.Hour, ClickMaxAge: 24 * time.Hour,
		Log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	return c, mr, sink
}

func token(t *testing.T, c *Collector, mut func(*adtoken.Claims)) string {
	t.Helper()
	cl := adtoken.Claims{RequestID: "r1", LineItemID: "li1", CreativeID: "cr1", PlacementID: "home_top",
		PublisherID: "p1", UserKey: "uk1", CPM: 2, Country: "BD", Device: "mobile",
		LandingURL: "https://shop.test/sale", FreqWindow: 3600, IssuedAt: now.Unix()}
	if mut != nil {
		mut(&cl)
	}
	tok, err := c.Signer.Sign(cl)
	if err != nil {
		t.Fatal(err)
	}
	return url.QueryEscape(tok)
}

func hit(c *Collector, path string) *httptest.ResponseRecorder {
	w := httptest.NewRecorder()
	c.Routes().ServeHTTP(w, httptest.NewRequest(http.MethodGet, path, nil))
	return w
}

func TestImpressionCountsOnce(t *testing.T) {
	c, mr, sink := setup(t)
	tok := token(t, c, nil)
	for i := 0; i < 3; i++ {
		w := hit(c, "/i?t="+tok)
		if w.Code != http.StatusOK || w.Header().Get("Content-Type") != "image/gif" || w.Body.Len() != len(transparentGIF) {
			t.Fatalf("bad pixel response %d %v", w.Code, w.Header())
		}
	}
	if got := mr.HGet(lineitem.DeliveredKey, "li1"); got != "1" {
		t.Fatalf("delivered = %q, want 1", got)
	}
	fk := lineitem.FrequencyKey("li1", "uk1")
	if got, _ := mr.Get(fk); got != "1" {
		t.Fatalf("frequency = %q, want 1", got)
	}
	if ttl := mr.TTL(fk); ttl != time.Hour {
		t.Fatalf("frequency TTL = %v, want 1h", ttl)
	}
	if len(sink.evs) != 1 || sink.evs[0].EventType != events.TypeImpression || sink.evs[0].EventID != "impression:r1" ||
		sink.evs[0].TimestampMs != now.UnixMilli() || sink.evs[0].Country != "BD" {
		t.Fatalf("bad events %+v", sink.evs)
	}
}

func TestFrequencyWindowNotExtendedByLaterImpressions(t *testing.T) {
	c, mr, _ := setup(t)
	hit(c, "/i?t="+token(t, c, nil))
	mr.FastForward(30 * time.Minute)
	hit(c, "/i?t="+token(t, c, func(cl *adtoken.Claims) { cl.RequestID = "r2" }))
	fk := lineitem.FrequencyKey("li1", "uk1")
	if got, _ := mr.Get(fk); got != "2" {
		t.Fatalf("frequency = %q, want 2", got)
	}
	if ttl := mr.TTL(fk); ttl != 30*time.Minute {
		t.Fatalf("TTL = %v, want 30m left in the original window", ttl)
	}
}

func TestNoUserKeyNoFrequencyCounter(t *testing.T) {
	c, mr, _ := setup(t)
	hit(c, "/i?t="+token(t, c, func(cl *adtoken.Claims) { cl.UserKey = "" }))
	if len(mr.Keys()) != 2 { // dedup key + delivered hash
		t.Fatalf("unexpected keys %v", mr.Keys())
	}
}

func TestInvalidOrExpiredTokenRejected(t *testing.T) {
	c, mr, sink := setup(t)
	if w := hit(c, "/i?t=garbage"); w.Code != http.StatusBadRequest {
		t.Fatalf("got %d", w.Code)
	}
	old := token(t, c, func(cl *adtoken.Claims) { cl.IssuedAt = now.Add(-2 * time.Hour).Unix() })
	if w := hit(c, "/i?t="+old); w.Code != http.StatusBadRequest {
		t.Fatalf("expired impression accepted: %d", w.Code)
	}
	if mr.Exists(lineitem.DeliveredKey) || len(sink.evs) != 0 {
		t.Fatal("rejected token was counted")
	}
}

func TestClickRedirectsAndCountsOnce(t *testing.T) {
	c, _, sink := setup(t)
	tok := token(t, c, func(cl *adtoken.Claims) { cl.IssuedAt = now.Add(-3 * time.Hour).Unix() }) // within 24h
	for i := 0; i < 2; i++ {
		w := hit(c, "/c?t="+tok)
		if w.Code != http.StatusFound || w.Header().Get("Location") != "https://shop.test/sale" {
			t.Fatalf("bad redirect %d %v", w.Code, w.Header())
		}
	}
	if len(sink.evs) != 1 || sink.evs[0].EventType != events.TypeClick {
		t.Fatalf("bad events %+v", sink.evs)
	}
}

func TestClickRejectsNonHTTPLanding(t *testing.T) {
	c, _, sink := setup(t)
	for _, lp := range []string{"javascript:alert(1)", "ftp://x.test/", "https://", "not a url"} {
		tok := token(t, c, func(cl *adtoken.Claims) { cl.LandingURL = lp })
		if w := hit(c, "/c?t="+tok); w.Code != http.StatusBadRequest {
			t.Fatalf("%q: got %d", lp, w.Code)
		}
	}
	if len(sink.evs) != 0 {
		t.Fatal("bad click published")
	}
}

func TestRedisDownStillServesAndPublishes(t *testing.T) {
	c, mr, sink := setup(t)
	mr.Close()
	w := hit(c, "/i?t="+token(t, c, nil))
	if w.Code != http.StatusOK {
		t.Fatalf("got %d", w.Code)
	}
	if len(sink.evs) != 1 {
		t.Fatal("event not published while Redis was down")
	}
}
