// Package decision picks which line item and creative to serve for an ad request.
//
// Order of selection:
//  1. Eligibility: placement, creative size, flight dates, country and device targeting,
//     impression goal not yet reached, frequency cap not yet reached.
//  2. Ranking: priority tier (lower wins), then line items on or behind schedule before ones
//     ahead of schedule, then the one furthest behind schedule, then highest CPM, then id.
//
// The engine holds the line-item snapshot and delivered counts in memory (refreshed in the
// background), so a decision costs at most one Redis round trip, for frequency caps.
package decision

import (
	"context"
	"hash/fnv"
	"sort"
	"strings"
	"sync/atomic"
	"time"

	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

// aheadTolerance lets a line item run up to 10% ahead of even pacing before it is deprioritized.
const aheadTolerance = 1.10

// FrequencyCounter returns how many impressions a user has seen per line item in the current window.
type FrequencyCounter interface {
	Counts(ctx context.Context, userKey string, lineItemIDs []string) (map[string]int64, error)
}

type Request struct {
	RequestID   string
	PublisherID string
	PlacementID string
	Width       int
	Height      int
	Country     string // ISO alpha-2, upper case
	Device      string // desktop | mobile | tablet
	UserKey     string // pseudonymous; empty when unknown
	Now         time.Time
}

type Decision struct {
	LineItem *lineitem.LineItem
	Creative lineitem.Creative
}

type index struct {
	version     int64
	byPlacement map[string][]*lineitem.LineItem
}

type Engine struct {
	idx       atomic.Pointer[index]
	delivered atomic.Pointer[map[string]int64]
	freq      FrequencyCounter
	onFreqErr func(error)
}

func New(freq FrequencyCounter, onFreqErr func(error)) *Engine {
	e := &Engine{freq: freq, onFreqErr: onFreqErr}
	e.idx.Store(&index{byPlacement: map[string][]*lineitem.LineItem{}})
	empty := map[string]int64{}
	e.delivered.Store(&empty)
	return e
}

// SetSnapshot atomically replaces the servable line items.
func (e *Engine) SetSnapshot(s lineitem.Snapshot) {
	by := make(map[string][]*lineitem.LineItem)
	for i := range s.LineItems {
		li := &s.LineItems[i]
		for _, p := range li.Placements {
			by[p] = append(by[p], li)
		}
	}
	e.idx.Store(&index{version: s.Version, byPlacement: by})
}

// SnapshotVersion is the version currently being served (0 before the first load).
func (e *Engine) SnapshotVersion() int64 { return e.idx.Load().version }

// SetDelivered atomically replaces delivered impression counts per line item.
func (e *Engine) SetDelivered(m map[string]int64) { e.delivered.Store(&m) }

type candidate struct {
	li       *lineitem.LineItem
	creative lineitem.Creative
	ahead    bool
	behind   float64
}

// Decide returns nil when nothing is eligible (no fill).
func (e *Engine) Decide(ctx context.Context, req Request) *Decision {
	items := e.idx.Load().byPlacement[req.PlacementID]
	delivered := *e.delivered.Load()

	cands := make([]candidate, 0, len(items))
	var capped []string
	for _, li := range items {
		if req.Now.Before(li.Start) || !req.Now.Before(li.End) {
			continue
		}
		if !matches(li.Targeting.Countries, req.Country) || !matches(li.Targeting.Devices, req.Device) {
			continue
		}
		cr, ok := pickCreative(li.Creatives, req.Width, req.Height, req.RequestID)
		if !ok {
			continue
		}
		c := candidate{li: li, creative: cr}
		if goal := li.Goal.Impressions; goal > 0 {
			done := delivered[li.ID]
			if done >= goal {
				continue
			}
			elapsed := req.Now.Sub(li.Start).Seconds() / li.End.Sub(li.Start).Seconds()
			expected := float64(goal) * elapsed
			c.behind = expected - float64(done)
			c.ahead = float64(done) > expected*aheadTolerance+1
		}
		if li.FrequencyCap != nil && li.FrequencyCap.Max > 0 && req.UserKey != "" {
			capped = append(capped, li.ID)
		}
		cands = append(cands, c)
	}
	if len(cands) == 0 {
		return nil
	}

	if len(capped) > 0 {
		counts, err := e.freq.Counts(ctx, req.UserKey, capped)
		if err != nil {
			// Fail closed for capped line items only: never overserve a capped campaign.
			if e.onFreqErr != nil {
				e.onFreqErr(err)
			}
			counts = nil
		}
		kept := cands[:0]
		for _, c := range cands {
			fc := c.li.FrequencyCap
			if fc != nil && fc.Max > 0 && req.UserKey != "" {
				if counts == nil || counts[c.li.ID] >= fc.Max {
					continue
				}
			}
			kept = append(kept, c)
		}
		cands = kept
		if len(cands) == 0 {
			return nil
		}
	}

	sort.SliceStable(cands, func(i, j int) bool {
		a, b := cands[i], cands[j]
		if a.li.Priority != b.li.Priority {
			return a.li.Priority < b.li.Priority
		}
		if a.ahead != b.ahead {
			return !a.ahead
		}
		if a.behind != b.behind {
			return a.behind > b.behind
		}
		if a.li.CPM != b.li.CPM {
			return a.li.CPM > b.li.CPM
		}
		return a.li.ID < b.li.ID
	})
	best := cands[0]
	return &Decision{LineItem: best.li, Creative: best.creative}
}

func matches(allowed []string, value string) bool {
	if len(allowed) == 0 {
		return true
	}
	for _, a := range allowed {
		if strings.EqualFold(a, value) {
			return true
		}
	}
	return false
}

// pickCreative rotates evenly among creatives of the requested size, keyed by request id.
func pickCreative(all []lineitem.Creative, w, h int, requestID string) (lineitem.Creative, bool) {
	var fit []lineitem.Creative
	for _, c := range all {
		if c.Width == w && c.Height == h {
			fit = append(fit, c)
		}
	}
	if len(fit) == 0 {
		return lineitem.Creative{}, false
	}
	hsh := fnv.New32a()
	hsh.Write([]byte(requestID))
	return fit[int(hsh.Sum32()%uint32(len(fit)))], true
}
