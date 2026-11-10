package httpapi

import (
	"context"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/mrasheed-cse/adcp-platform/pkg/adtoken"
	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
	"github.com/mrasheed-cse/adcp-platform/services/adserver-decision/internal/decision"
)

type stubDecider struct {
	d    *decision.Decision
	last decision.Request
}

func (s *stubDecider) Decide(_ context.Context, r decision.Request) *decision.Decision {
	s.last = r
	return s.d
}

var now = time.Unix(1_800_000_000, 0)

func newHandler(t *testing.T, d *decision.Decision) (*Handler, *stubDecider) {
	t.Helper()
	s, err := adtoken.NewSigner([]byte("0123456789abcdef0123456789abcdef"))
	if err != nil {
		t.Fatal(err)
	}
	sd := &stubDecider{d: d}
	return &Handler{Decider: sd, Signer: s, CollectorBaseURL: "https://track.test",
		Now: func() time.Time { return now }, Log: slog.New(slog.NewTextHandler(io.Discard, nil))}, sd
}

func get(h http.Handler, target string, hdr map[string]string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodGet, target, nil)
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func TestBadRequest(t *testing.T) {
	h, _ := newHandler(t, nil)
	for _, q := range []string{"/v1/ad", "/v1/ad?pub=p&placement=x&w=abc&h=250", "/v1/ad?pub=p&placement=x&w=0&h=250"} {
		if w := get(h.Routes(), q, nil); w.Code != http.StatusBadRequest {
			t.Fatalf("%s: got %d", q, w.Code)
		}
	}
}

func TestNoFill(t *testing.T) {
	h, _ := newHandler(t, nil)
	w := get(h.Routes(), "/v1/ad?pub=p1&placement=home_top&w=300&h=250", nil)
	if w.Code != http.StatusNoContent || w.Header().Get("Access-Control-Allow-Origin") != "*" {
		t.Fatalf("got %d %v", w.Code, w.Header())
	}
}

func TestServeSignsTrackingURLs(t *testing.T) {
	li := &lineitem.LineItem{ID: "li1", CPM: 2.5, FrequencyCap: &lineitem.FrequencyCap{Max: 3, WindowSeconds: 3600}}
	cr := lineitem.Creative{ID: "cr1", Type: "image", Width: 300, Height: 250,
		ImageURL: "https://cdn.test/a.png", LandingURL: "https://shop.test/?x=1"}
	h, sd := newHandler(t, &decision.Decision{LineItem: li, Creative: cr})

	w := get(h.Routes(), "/v1/ad?pub=p1&placement=home_top&w=300&h=250&uid=user-9",
		map[string]string{"CF-IPCountry": "bd", "User-Agent": "Mozilla/5.0 (iPhone) Mobile"})
	if w.Code != http.StatusOK {
		t.Fatalf("got %d: %s", w.Code, w.Body)
	}
	if sd.last.Country != "BD" || sd.last.Device != "mobile" || sd.last.UserKey == "" || sd.last.UserKey == "user-9" {
		t.Fatalf("request not normalized: %+v", sd.last)
	}
	var resp adResponse
	if err := json.Unmarshal(w.Body.Bytes(), &resp); err != nil {
		t.Fatal(err)
	}
	if resp.LineItemID != "li1" || resp.Creative.ImageURL != "https://cdn.test/a.png" {
		t.Fatalf("bad response %+v", resp)
	}
	if strings.Contains(w.Body.String(), "shop.test") {
		t.Fatal("landing URL leaked outside the signed token")
	}
	if !strings.HasPrefix(resp.ImpressionURL, "https://track.test/i?t=") || !strings.HasPrefix(resp.ClickURL, "https://track.test/c?t=") {
		t.Fatalf("bad tracking URLs %+v", resp)
	}
	u, _ := url.Parse(resp.ClickURL)
	claims, err := h.Signer.Verify(u.Query().Get("t"), now, time.Hour)
	if err != nil {
		t.Fatal(err)
	}
	if claims.LineItemID != "li1" || claims.LandingURL != "https://shop.test/?x=1" || claims.FreqWindow != 3600 ||
		claims.RequestID != resp.RequestID || claims.Country != "BD" {
		t.Fatalf("bad claims %+v", claims)
	}
}

func TestReadiness(t *testing.T) {
	h, _ := newHandler(t, nil)
	if w := get(h.Routes(), "/readyz", nil); w.Code != http.StatusServiceUnavailable {
		t.Fatalf("want 503 before load, got %d", w.Code)
	}
	h.SetReady()
	if w := get(h.Routes(), "/readyz", nil); w.Code != http.StatusOK {
		t.Fatalf("want 200 after load, got %d", w.Code)
	}
}
