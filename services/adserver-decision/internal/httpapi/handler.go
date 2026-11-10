// Package httpapi exposes the ad decision endpoint:
//
//	GET /v1/ad?pub=<publisher>&placement=<placement>&w=300&h=250[&uid=..][&country=BD][&device=mobile]
//
// 200 with the ad JSON (contracts/ad-response.schema.json), 204 when there is no ad to serve.
package httpapi

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"github.com/mrasheed-cse/adcp-platform/pkg/adtoken"
	"github.com/mrasheed-cse/adcp-platform/services/adserver-decision/internal/decision"
)

type Decider interface {
	Decide(ctx context.Context, req decision.Request) *decision.Decision
}

type Handler struct {
	Decider          Decider
	Signer           *adtoken.Signer
	CollectorBaseURL string // e.g. https://track.example.com
	Now              func() time.Time
	Log              *slog.Logger
	ready            atomic.Bool
}

func (h *Handler) SetReady() { h.ready.Store(true) }

func (h *Handler) Routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/ad", h.ad)
	mux.HandleFunc("OPTIONS /v1/ad", h.preflight)
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, _ *http.Request) { w.WriteHeader(http.StatusOK) })
	mux.HandleFunc("GET /readyz", func(w http.ResponseWriter, _ *http.Request) {
		if h.ready.Load() {
			w.WriteHeader(http.StatusOK)
			return
		}
		w.WriteHeader(http.StatusServiceUnavailable)
	})
	return mux
}

type creativeJSON struct {
	ID       string `json:"id"`
	Type     string `json:"type"`
	Width    int    `json:"width"`
	Height   int    `json:"height"`
	ImageURL string `json:"image_url,omitempty"`
	HTML     string `json:"html,omitempty"`
}

type adResponse struct {
	RequestID     string       `json:"request_id"`
	LineItemID    string       `json:"line_item_id"`
	Creative      creativeJSON `json:"creative"`
	ImpressionURL string       `json:"impression_url"`
	ClickURL      string       `json:"click_url"`
}

func (h *Handler) preflight(w http.ResponseWriter, _ *http.Request) {
	cors(w)
	w.Header().Set("Access-Control-Allow-Methods", "GET")
	w.Header().Set("Access-Control-Max-Age", "86400")
	w.WriteHeader(http.StatusNoContent)
}

func (h *Handler) ad(w http.ResponseWriter, r *http.Request) {
	cors(w)
	w.Header().Set("Cache-Control", "no-store")
	q := r.URL.Query()

	pub, placement := q.Get("pub"), q.Get("placement")
	width, errW := strconv.Atoi(q.Get("w"))
	height, errH := strconv.Atoi(q.Get("h"))
	if pub == "" || placement == "" || errW != nil || errH != nil || width <= 0 || height <= 0 {
		http.Error(w, "pub, placement, w and h are required", http.StatusBadRequest)
		return
	}
	now := h.Now()
	req := decision.Request{
		RequestID:   newID(),
		PublisherID: pub,
		PlacementID: placement,
		Width:       width,
		Height:      height,
		Country:     country(r),
		Device:      device(r),
		UserKey:     h.Signer.UserKey(q.Get("uid")),
		Now:         now,
	}

	d := h.Decider.Decide(r.Context(), req)
	if d == nil {
		w.WriteHeader(http.StatusNoContent)
		return
	}

	claims := adtoken.Claims{
		RequestID: req.RequestID, LineItemID: d.LineItem.ID, CreativeID: d.Creative.ID,
		PlacementID: placement, PublisherID: pub, UserKey: req.UserKey, CPM: d.LineItem.CPM,
		Country: req.Country, Device: req.Device, LandingURL: d.Creative.LandingURL,
		IssuedAt: now.Unix(),
	}
	if fc := d.LineItem.FrequencyCap; fc != nil {
		claims.FreqWindow = fc.WindowSeconds
	}
	tok, err := h.Signer.Sign(claims)
	if err != nil {
		h.Log.Error("sign token", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	t := url.QueryEscape(tok)
	resp := adResponse{
		RequestID:  req.RequestID,
		LineItemID: d.LineItem.ID,
		Creative: creativeJSON{ID: d.Creative.ID, Type: d.Creative.Type, Width: d.Creative.Width,
			Height: d.Creative.Height, ImageURL: d.Creative.ImageURL, HTML: d.Creative.HTML},
		ImpressionURL: h.CollectorBaseURL + "/i?t=" + t,
		ClickURL:      h.CollectorBaseURL + "/c?t=" + t,
	}
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(resp)
}

func cors(w http.ResponseWriter) {
	// Ad tags call this from publisher pages; no cookies are sent, so a wildcard is safe.
	w.Header().Set("Access-Control-Allow-Origin", "*")
}

// country prefers an explicit parameter, then the edge/CDN geo header.
func country(r *http.Request) string {
	for _, v := range []string{r.URL.Query().Get("country"), r.Header.Get("CF-IPCountry"), r.Header.Get("X-Country")} {
		if len(v) == 2 {
			return strings.ToUpper(v)
		}
	}
	return ""
}

func device(r *http.Request) string {
	switch d := strings.ToLower(r.URL.Query().Get("device")); d {
	case "desktop", "mobile", "tablet":
		return d
	}
	ua := strings.ToLower(r.UserAgent())
	switch {
	case strings.Contains(ua, "ipad") || strings.Contains(ua, "tablet"):
		return "tablet"
	case strings.Contains(ua, "mobi") || strings.Contains(ua, "iphone") || strings.Contains(ua, "android"):
		return "mobile"
	default:
		return "desktop"
	}
}

func newID() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
