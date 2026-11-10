// Package events defines the ad events written to Kafka and loaded into ClickHouse.
// See contracts/ad-event.schema.json and infra/clickhouse/init.sql.
package events

const (
	TypeImpression = "impression"
	TypeClick      = "click"

	// Topic is the Kafka topic for all ad events.
	Topic = "ad-events"
)

// Event is one impression or click. TimestampMs is unix milliseconds (ClickHouse converts it).
type Event struct {
	EventID     string  `json:"event_id"`
	EventType   string  `json:"event_type"`
	TimestampMs int64   `json:"ts_ms"`
	RequestID   string  `json:"request_id"`
	LineItemID  string  `json:"line_item_id"`
	CreativeID  string  `json:"creative_id"`
	PlacementID string  `json:"placement_id"`
	PublisherID string  `json:"publisher_id"`
	UserKey     string  `json:"user_key"`
	Country     string  `json:"country"`
	Device      string  `json:"device"`
	CPM         float64 `json:"cpm"`
}
