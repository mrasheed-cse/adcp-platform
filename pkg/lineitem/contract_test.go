package lineitem

import (
	"bytes"
	"encoding/json"
	"os"
	"testing"
	"time"
)

// TestContractFixture decodes the shared example snapshot with unknown fields rejected, so a field
// renamed on the Java side (or here) fails this test. The Java trafficking API checks the same file.
func TestContractFixture(t *testing.T) {
	raw, err := os.ReadFile("../../contracts/fixtures/line-item-snapshot.example.json")
	if err != nil {
		t.Fatal(err)
	}
	dec := json.NewDecoder(bytes.NewReader(raw))
	dec.DisallowUnknownFields()
	var s Snapshot
	if err := dec.Decode(&s); err != nil {
		t.Fatalf("fixture does not match the Go struct: %v", err)
	}
	if s.Version != 42 || !s.GeneratedAt.Equal(time.Date(2026, 11, 2, 12, 0, 0, 0, time.UTC)) || len(s.LineItems) != 2 {
		t.Fatalf("unexpected snapshot header %+v", s)
	}
	li := s.LineItems[0]
	if li.ID != "li_0a1b2c3d4e5f" || li.AdvertiserID != "adv_222222222222" || li.Priority != PrioritySponsorship ||
		li.CPM != 4.25 || li.Goal.Impressions != 100000 || li.FrequencyCap == nil || li.FrequencyCap.WindowSeconds != 86400 ||
		li.Targeting.Countries[0] != "BD" || len(li.Targeting.Devices) != 2 ||
		li.Creatives[0].ImageURL == "" || li.Creatives[0].LandingURL != "https://brand.example.com/cricket" {
		t.Fatalf("line item 0 decoded wrong: %+v", li)
	}
	house := s.LineItems[1]
	if house.FrequencyCap != nil || house.Goal.Impressions != 0 || house.Creatives[0].Type != "html" || house.Creatives[0].HTML == "" {
		t.Fatalf("line item 1 decoded wrong: %+v", house)
	}
}
