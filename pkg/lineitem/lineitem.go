// Package lineitem defines the line-item snapshot the trafficking API (Java) publishes to Redis
// and the decision engine (Go) serves from. The JSON shape is the contract between the two:
// see contracts/line-item-snapshot.schema.json.
package lineitem

import "time"

// Priority tiers, lower number wins. Mirrors common ad-server practice.
const (
	PrioritySponsorship   = 4
	PriorityStandard      = 8
	PriorityPricePriority = 12
	PriorityHouse         = 16
)

// Redis keys shared by the trafficking API, decision engine and event collector.
const (
	// SnapshotKey holds the JSON-encoded Snapshot of all active line items.
	SnapshotKey = "adserver:snapshot"
	// SnapshotVersionKey holds Snapshot.Version so readers can skip unchanged snapshots.
	SnapshotVersionKey = "adserver:snapshot:version"
	// SnapshotSequenceKey is INCR'd by every publisher to get the next version, so publishers
	// (trafficking API, seed tool) never reuse or go back in versions.
	SnapshotSequenceKey = "adserver:snapshot:seq"
	// DeliveredKey is a hash: field = line item id, value = impressions delivered so far.
	DeliveredKey = "adserver:delivered"
)

// FrequencyKey is the per-user, per-line-item impression counter used for frequency capping.
func FrequencyKey(lineItemID, userKey string) string {
	return "adserver:fc:" + lineItemID + ":" + userKey
}

type Creative struct {
	ID         string `json:"id"`
	Type       string `json:"type"` // "image" or "html"
	Width      int    `json:"width"`
	Height     int    `json:"height"`
	ImageURL   string `json:"image_url,omitempty"`
	HTML       string `json:"html,omitempty"`
	LandingURL string `json:"landing_url"`
}

type Targeting struct {
	Countries []string `json:"countries,omitempty"` // ISO 3166-1 alpha-2; empty = all
	Devices   []string `json:"devices,omitempty"`   // "desktop", "mobile", "tablet"; empty = all
}

type Goal struct {
	Impressions int64 `json:"impressions"` // 0 = no goal (e.g. house ads)
}

type FrequencyCap struct {
	Max           int64 `json:"max"`
	WindowSeconds int64 `json:"window_seconds"`
}

type LineItem struct {
	ID           string        `json:"id"`
	OrderID      string        `json:"order_id"`
	AdvertiserID string        `json:"advertiser_id"`
	Name         string        `json:"name"`
	Priority     int           `json:"priority"`
	CPM          float64       `json:"cpm"` // USD per 1000 impressions
	Start        time.Time     `json:"start"`
	End          time.Time     `json:"end"`
	Placements   []string      `json:"placements"`
	Targeting    Targeting     `json:"targeting"`
	Goal         Goal          `json:"goal"`
	FrequencyCap *FrequencyCap `json:"frequency_cap,omitempty"`
	Creatives    []Creative    `json:"creatives"`
}

// Snapshot is the full set of servable line items at one point in time.
type Snapshot struct {
	Version     int64      `json:"version"`
	GeneratedAt time.Time  `json:"generated_at"`
	LineItems   []LineItem `json:"line_items"`
}
