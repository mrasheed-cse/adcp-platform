# adcp-sales-agent (Java 21 + Spring Boot) — milestone 1, step 5

An AdCP sales agent exposed as an MCP server (Streamable HTTP at `/mcp`, port 8084 in compose). AI buyer
agents discover inventory, book media buys and upload creatives. Every booking becomes real orders and
line items in the ad server (`services/trafficking-api`), so it actually serves.

## Tools

| Tool | What it does |
|---|---|
| `get_adcp_capabilities` | Protocols, tasks, pricing models, currencies |
| `get_products` | Products matching a brief |
| `list_creative_formats` | Display sizes, video, native |
| `create_media_buy` | Books packages; idempotent on `idempotency_key`; creates the order and line items in the ad server |
| `sync_creatives` | Uploads creatives and assigns them to packages; a package serves once it has a creative |
| `update_media_buy` | Pause, resume, cancel; follows through to the ad-server order |
| `get_media_buys` | Current state, including `provisioning` (`pending`, `provisioned`, `failed`) |
| `get_media_buy_delivery` | Real impressions from the ad server; spend = impressions x CPM, capped at budget |

`sync_creatives` is a simplified form of the AdCP task: `creatives` is a list of
`{creative_id, name, format_id, image_url | html, click_url}` and `assignments` maps a `creative_id` to the
`package_id`s it should run on. Video formats are rejected until the ad server supports video.

## How a booking reaches the ad server

```
create_media_buy ──> media buy saved in Postgres (desired state, dirty)
                     │
                     └─> Reconciler: advertiser -> placements -> order -> line item per package
                                     -> creatives attached -> line items with creatives activated
                                     -> order status follows the buy (active / paused / archived)
```

- The media buy in this service is the desired state; the ad server follows it.
- Every ad-server create carries an `external_ref` (`adcp:<media_buy_id>:<package_id>` and so on), so a
  retried sync never creates duplicates.
- Each tool call syncs immediately. If the ad server is down, the buy stays `provisioning: pending` with
  the error, and a background job retries every 15 s. If the ad server rejects the data, the buy becomes
  `provisioning: failed` with the reason and is not retried until the buyer changes it.
- A buyer change that arrives during a sync wins, and the buy is synced again.

Mapping: a guaranteed product becomes a standard-priority (8) line item, a non-guaranteed one becomes price
priority (12). The impression goal is budget / CPM x 1000, and the flight comes from the buy's start and end.

## Run

```bash
make up    # from the repo root; the agent runs on :8084 and talks to trafficking-api on :8083
```

Connect MCP Inspector (`npx @modelcontextprotocol/inspector`, Streamable HTTP, `http://localhost:8084/mcp`),
or add it to Claude as a custom connector through a public tunnel. Then:

1. `get_products` with brief `"news readers in Bangladesh"`.
2. `create_media_buy`:
   ```json
   {"brand": "acme.com", "start_time": "asap", "end_time": "2026-12-31T23:59:59Z",
    "packages": [{"product_id": "bd_news_display", "pricing_option_id": "cpm_usd_auction", "budget": 600}],
    "idempotency_key": "acme-news-2026-12-0001"}
   ```
3. `sync_creatives`:
   ```json
   {"creatives": [{"creative_id": "acme_banner_1", "name": "Banner", "format_id": "display_300x250",
                   "image_url": "https://placehold.co/300x250?text=ACME", "click_url": "https://example.com/acme"}],
    "assignments": {"acme_banner_1": ["<package_id from step 2>"]}}
   ```
4. Request an ad: `curl "http://localhost:8081/v1/ad?pub=pub_demo&placement=news_top&w=300&h=250&country=BD"`.
5. Fire the returned `impression_url`, then call `get_media_buy_delivery`.

Auth: set `ADCP_DEMO_TOKEN` (and `ADCP_SECOND_TOKEN`) to require bearer tokens; each token is one buyer account.

## Storage

Postgres schema `sales_agent` (Flyway): `media_buy` (unique account + idempotency key, version column for
optimistic locking, `dirty` flag for unsynced changes) and `creative`.

## Known gaps

- The service-to-service call to the trafficking API has no authentication yet (internal network only).
- With several instances, guard the retry job with ShedLock so two instances do not sync the same buy at once
  (harmless thanks to external references, but wasteful).
- Clicks in delivery reports are `null` until reporting reads them from ClickHouse.
- `sync_creatives` uses a simplified request shape; align it with the official AdCP schema when running
  the conformance storyboard.
