package decision

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

var t0 = time.Date(2026, 11, 1, 0, 0, 0, 0, time.UTC)

type fakeFreq struct {
	counts map[string]int64
	err    error
	calls  int
}

func (f *fakeFreq) Counts(_ context.Context, _ string, ids []string) (map[string]int64, error) {
	f.calls++
	if f.err != nil {
		return nil, f.err
	}
	out := map[string]int64{}
	for _, id := range ids {
		out[id] = f.counts[id]
	}
	return out, nil
}

func li(id string, prio int, mut ...func(*lineitem.LineItem)) lineitem.LineItem {
	l := lineitem.LineItem{
		ID: id, Priority: prio, CPM: 1,
		Start: t0, End: t0.Add(10 * 24 * time.Hour),
		Placements: []string{"home_top"},
		Creatives:  []lineitem.Creative{{ID: "cr_" + id, Type: "image", Width: 300, Height: 250, LandingURL: "https://example.com"}},
	}
	for _, m := range mut {
		m(&l)
	}
	return l
}

func req(mut ...func(*Request)) Request {
	r := Request{RequestID: "r1", PlacementID: "home_top", Width: 300, Height: 250,
		Country: "BD", Device: "mobile", Now: t0.Add(5 * 24 * time.Hour)}
	for _, m := range mut {
		m(&r)
	}
	return r
}

func engine(f *fakeFreq, items ...lineitem.LineItem) *Engine {
	if f == nil {
		f = &fakeFreq{}
	}
	e := New(f, nil)
	e.SetSnapshot(lineitem.Snapshot{Version: 1, LineItems: items})
	return e
}

func pick(t *testing.T, e *Engine, r Request) string {
	t.Helper()
	d := e.Decide(context.Background(), r)
	if d == nil {
		return ""
	}
	return d.LineItem.ID
}

func TestNoFillWhenNothingMatches(t *testing.T) {
	e := engine(nil, li("a", 8))
	if got := pick(t, e, req(func(r *Request) { r.PlacementID = "other" })); got != "" {
		t.Fatalf("want no fill, got %s", got)
	}
	if got := pick(t, e, req(func(r *Request) { r.Width = 728; r.Height = 90 })); got != "" {
		t.Fatalf("size mismatch should not fill, got %s", got)
	}
}

func TestPriorityWins(t *testing.T) {
	e := engine(nil,
		li("house", lineitem.PriorityHouse, func(l *lineitem.LineItem) { l.CPM = 50 }),
		li("sponsor", lineitem.PrioritySponsorship),
		li("standard", lineitem.PriorityStandard, func(l *lineitem.LineItem) { l.CPM = 20 }))
	if got := pick(t, e, req()); got != "sponsor" {
		t.Fatalf("want sponsor, got %s", got)
	}
}

func TestFlightWindow(t *testing.T) {
	e := engine(nil,
		li("ended", 4, func(l *lineitem.LineItem) { l.End = t0.Add(24 * time.Hour) }),
		li("future", 4, func(l *lineitem.LineItem) { l.Start = t0.Add(20 * 24 * time.Hour); l.End = t0.Add(30 * 24 * time.Hour) }),
		li("live", 8))
	if got := pick(t, e, req()); got != "live" {
		t.Fatalf("want live, got %s", got)
	}
}

func TestTargeting(t *testing.T) {
	e := engine(nil,
		li("us_only", 4, func(l *lineitem.LineItem) { l.Targeting.Countries = []string{"US"} }),
		li("desktop_only", 4, func(l *lineitem.LineItem) { l.Targeting.Devices = []string{"desktop"} }),
		li("bd_mobile", 8, func(l *lineitem.LineItem) {
			l.Targeting = lineitem.Targeting{Countries: []string{"bd"}, Devices: []string{"mobile"}}
		}))
	if got := pick(t, e, req()); got != "bd_mobile" {
		t.Fatalf("want bd_mobile, got %s", got)
	}
}

func TestGoalReachedIsSkipped(t *testing.T) {
	e := engine(nil,
		li("done", 4, func(l *lineitem.LineItem) { l.Goal.Impressions = 1000 }),
		li("other", 8))
	e.SetDelivered(map[string]int64{"done": 1000})
	if got := pick(t, e, req()); got != "other" {
		t.Fatalf("want other, got %s", got)
	}
}

func TestPacingPrefersBehindSchedule(t *testing.T) {
	// Halfway through the flight: expected 500 of 1000 delivered.
	e := engine(nil,
		li("ahead", 8, func(l *lineitem.LineItem) { l.Goal.Impressions = 1000; l.CPM = 10 }),
		li("behind", 8, func(l *lineitem.LineItem) { l.Goal.Impressions = 1000 }))
	e.SetDelivered(map[string]int64{"ahead": 800, "behind": 100})
	if got := pick(t, e, req()); got != "behind" {
		t.Fatalf("want behind, got %s", got)
	}
	// An ahead-of-schedule item still serves when it is the only one.
	e2 := engine(nil, li("ahead", 8, func(l *lineitem.LineItem) { l.Goal.Impressions = 1000 }))
	e2.SetDelivered(map[string]int64{"ahead": 800})
	if got := pick(t, e2, req()); got != "ahead" {
		t.Fatalf("want ahead as only option, got %s", got)
	}
}

func TestPricePriorityHighestCPM(t *testing.T) {
	e := engine(nil,
		li("cheap", lineitem.PriorityPricePriority, func(l *lineitem.LineItem) { l.CPM = 1.2 }),
		li("rich", lineitem.PriorityPricePriority, func(l *lineitem.LineItem) { l.CPM = 3.4 }))
	if got := pick(t, e, req()); got != "rich" {
		t.Fatalf("want rich, got %s", got)
	}
}

func TestFrequencyCap(t *testing.T) {
	capped := func(l *lineitem.LineItem) { l.FrequencyCap = &lineitem.FrequencyCap{Max: 3, WindowSeconds: 86400} }
	f := &fakeFreq{counts: map[string]int64{"capped": 3}}
	e := engine(f, li("capped", 4, capped), li("fallback", 8))
	if got := pick(t, e, req(func(r *Request) { r.UserKey = "u1" })); got != "fallback" {
		t.Fatalf("want fallback, got %s", got)
	}
	f.counts["capped"] = 2
	if got := pick(t, e, req(func(r *Request) { r.UserKey = "u1" })); got != "capped" {
		t.Fatalf("want capped under limit, got %s", got)
	}
	// No user key: caps cannot be checked and Redis is not called.
	calls := f.calls
	if got := pick(t, e, req()); got != "capped" {
		t.Fatalf("want capped without user key, got %s", got)
	}
	if f.calls != calls {
		t.Fatal("frequency store called without a user key")
	}
}

func TestFrequencyErrorFailsClosedForCappedOnly(t *testing.T) {
	var logged error
	f := &fakeFreq{err: errors.New("redis down")}
	e := New(f, func(err error) { logged = err })
	e.SetSnapshot(lineitem.Snapshot{LineItems: []lineitem.LineItem{
		li("capped", 4, func(l *lineitem.LineItem) { l.FrequencyCap = &lineitem.FrequencyCap{Max: 3, WindowSeconds: 60} }),
		li("uncapped", 8),
	}})
	if got := pick(t, e, req(func(r *Request) { r.UserKey = "u1" })); got != "uncapped" {
		t.Fatalf("want uncapped, got %s", got)
	}
	if logged == nil {
		t.Fatal("frequency error not reported")
	}
}

func TestCreativeRotationIsStablePerRequest(t *testing.T) {
	e := engine(nil, li("a", 8, func(l *lineitem.LineItem) {
		l.Creatives = []lineitem.Creative{
			{ID: "c1", Width: 300, Height: 250}, {ID: "c2", Width: 300, Height: 250}, {ID: "big", Width: 728, Height: 90},
		}
	}))
	seen := map[string]bool{}
	for _, rid := range []string{"r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8"} {
		d := e.Decide(context.Background(), req(func(r *Request) { r.RequestID = rid }))
		again := e.Decide(context.Background(), req(func(r *Request) { r.RequestID = rid }))
		if d.Creative.ID != again.Creative.ID {
			t.Fatal("same request id gave different creatives")
		}
		if d.Creative.ID == "big" {
			t.Fatal("wrong-size creative served")
		}
		seen[d.Creative.ID] = true
	}
	if !seen["c1"] || !seen["c2"] {
		t.Fatalf("rotation did not use both creatives: %v", seen)
	}
}
