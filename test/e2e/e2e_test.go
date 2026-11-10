//go:build e2e

// Package e2e builds the decision service and event collector, runs them against an in-memory
// Redis, and follows real ads through serve -> impression -> goal reached -> frequency cap.
// Kafka is pointed at an unreachable address: event publishing is asynchronous, so serving and
// counting must keep working without it.
//
//	go test -tags e2e ./test/e2e/
package e2e

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/alicebob/miniredis/v2"

	"github.com/mrasheed-cse/adcp-platform/pkg/lineitem"
)

const secret = "e2e-secret-0123456789abcdef0123456789"

type adResp struct {
	RequestID     string `json:"request_id"`
	LineItemID    string `json:"line_item_id"`
	ImpressionURL string `json:"impression_url"`
	ClickURL      string `json:"click_url"`
}

func TestServeCountAndCap(t *testing.T) {
	mr := miniredis.RunT(t)
	now := time.Now().UTC()
	publish(t, mr, lineitem.Snapshot{Version: 1, GeneratedAt: now, LineItems: []lineitem.LineItem{
		{ID: "li_goal", Priority: lineitem.PrioritySponsorship, CPM: 5,
			Start: now.Add(-24 * time.Hour), End: now.Add(24 * time.Hour), Placements: []string{"home_top"},
			Goal: lineitem.Goal{Impressions: 2},
			Creatives: []lineitem.Creative{{ID: "cr_goal", Type: "image", Width: 300, Height: 250,
				ImageURL: "https://cdn.test/goal.png", LandingURL: "https://brand.test/offer"}}},
		{ID: "li_house", Priority: lineitem.PriorityHouse, CPM: 0,
			Start: now.Add(-24 * time.Hour), End: now.Add(24 * time.Hour), Placements: []string{"home_top"},
			FrequencyCap: &lineitem.FrequencyCap{Max: 1, WindowSeconds: 3600},
			Creatives: []lineitem.Creative{{ID: "cr_house", Type: "html", Width: 300, Height: 250,
				HTML: "<b>House ad</b>", LandingURL: "https://publisher.test/subscribe"}}},
	}})

	bin := t.TempDir()
	build(t, bin, "adserver-decision")
	build(t, bin, "event-collector")
	adPort, colPort := freePort(t), freePort(t)
	colBase := fmt.Sprintf("http://127.0.0.1:%d", colPort)
	start(t, filepath.Join(bin, "event-collector"), "COLLECTOR_ADDR=127.0.0.1:"+fmt.Sprint(colPort),
		"REDIS_ADDR="+mr.Addr(), "TOKEN_SECRET="+secret, "KAFKA_BROKERS=127.0.0.1:1")
	start(t, filepath.Join(bin, "adserver-decision"), "ADSERVER_ADDR=127.0.0.1:"+fmt.Sprint(adPort),
		"REDIS_ADDR="+mr.Addr(), "TOKEN_SECRET="+secret, "COLLECTOR_BASE_URL="+colBase, "REFRESH_INTERVAL=100ms")
	adBase := fmt.Sprintf("http://127.0.0.1:%d", adPort)
	waitOK(t, adBase+"/readyz")
	waitOK(t, colBase+"/healthz")

	adURL := adBase + "/v1/ad?pub=pub_1&placement=home_top&w=300&h=250&uid=user-42&country=BD&device=mobile"

	// 1. The sponsorship line item serves until its 2-impression goal is reached.
	for i := 0; i < 2; i++ {
		ad := eventually(t, adURL, func(a *adResp, code int) bool { return code == 200 && a.LineItemID == "li_goal" })
		pixel(t, ad.ImpressionURL)
		pixel(t, ad.ImpressionURL) // a repeated pixel must not count twice
	}
	if got := mr.HGet(lineitem.DeliveredKey, "li_goal"); got != "2" {
		t.Fatalf("li_goal delivered = %q, want 2", got)
	}

	// 2. Goal reached: the house ad takes over. Its click redirects to the signed landing page.
	house := eventually(t, adURL, func(a *adResp, code int) bool { return code == 200 && a.LineItemID == "li_house" })
	noFollow := &http.Client{CheckRedirect: func(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }}
	resp, err := noFollow.Get(house.ClickURL)
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	if resp.StatusCode != http.StatusFound || resp.Header.Get("Location") != "https://publisher.test/subscribe" {
		t.Fatalf("click: %d -> %q", resp.StatusCode, resp.Header.Get("Location"))
	}

	// 3. After one house impression this user hits the frequency cap: no fill for them...
	pixel(t, house.ImpressionURL)
	eventually(t, adURL, func(_ *adResp, code int) bool { return code == http.StatusNoContent })

	// ...but a different user still gets the house ad.
	other := strings.Replace(adURL, "uid=user-42", "uid=user-43", 1)
	eventually(t, other, func(a *adResp, code int) bool { return code == 200 && a.LineItemID == "li_house" })

	// 4. A forged pixel is rejected and not counted.
	r, err := http.Get(colBase + "/i?t=forged.token")
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	if r.StatusCode != http.StatusBadRequest {
		t.Fatalf("forged pixel: %d", r.StatusCode)
	}
}

func publish(t *testing.T, mr *miniredis.Miniredis, s lineitem.Snapshot) {
	t.Helper()
	raw, err := json.Marshal(s)
	if err != nil {
		t.Fatal(err)
	}
	mr.Set(lineitem.SnapshotKey, string(raw))
	mr.Set(lineitem.SnapshotVersionKey, fmt.Sprint(s.Version))
}

func build(t *testing.T, dir, svc string) {
	t.Helper()
	cmd := exec.Command("go", "build", "-o", filepath.Join(dir, svc), "../../services/"+svc)
	if out, err := cmd.CombinedOutput(); err != nil {
		t.Fatalf("build %s: %v\n%s", svc, err, out)
	}
}

func start(t *testing.T, path string, env ...string) {
	t.Helper()
	cmd := exec.Command(path)
	cmd.Env = append(os.Environ(), env...)
	logf, err := os.Create(filepath.Join(t.TempDir(), filepath.Base(path)+".log"))
	if err != nil {
		t.Fatal(err)
	}
	cmd.Stdout, cmd.Stderr = logf, logf
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() {
		_ = cmd.Process.Kill()
		_ = cmd.Wait()
		if t.Failed() {
			b, _ := os.ReadFile(logf.Name())
			t.Logf("%s log:\n%s", filepath.Base(path), b)
		}
	})
}

func freePort(t *testing.T) int {
	t.Helper()
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer l.Close()
	return l.Addr().(*net.TCPAddr).Port
}

func waitOK(t *testing.T, url string) {
	t.Helper()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	for ctx.Err() == nil {
		if r, err := http.Get(url); err == nil {
			r.Body.Close()
			if r.StatusCode == http.StatusOK {
				return
			}
		}
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("%s never became ready", url)
}

// eventually polls the ad endpoint until cond holds, allowing for the 100ms refresh interval.
func eventually(t *testing.T, url string, cond func(*adResp, int) bool) *adResp {
	t.Helper()
	deadline := time.Now().Add(5 * time.Second)
	var last string
	for time.Now().Before(deadline) {
		r, err := http.Get(url)
		if err != nil {
			t.Fatal(err)
		}
		var a adResp
		if r.StatusCode == http.StatusOK {
			_ = json.NewDecoder(r.Body).Decode(&a)
		}
		r.Body.Close()
		if cond(&a, r.StatusCode) {
			return &a
		}
		last = fmt.Sprintf("%d %+v", r.StatusCode, a)
		time.Sleep(50 * time.Millisecond)
	}
	t.Fatalf("condition not met for %s; last response %s", url, last)
	return nil
}

func pixel(t *testing.T, url string) {
	t.Helper()
	r, err := http.Get(url)
	if err != nil {
		t.Fatal(err)
	}
	r.Body.Close()
	if r.StatusCode != http.StatusOK || r.Header.Get("Content-Type") != "image/gif" {
		t.Fatalf("pixel %s: %d", url, r.StatusCode)
	}
}
