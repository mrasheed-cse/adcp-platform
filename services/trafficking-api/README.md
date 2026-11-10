# trafficking-api (Java 21 + Spring Boot) — milestone 1, step 2

Advertisers, orders, placements, creatives and line items, stored in Postgres (Flyway migrations).
After every committed change, on startup, and every 60 s, it publishes all servable line items to Redis
for the Go decision engine (`contracts/line-item-snapshot.schema.json`).

## Run

```bash
make up                    # from the repo root: Postgres, Redis, ... and this service on :8083
# or locally against the compose Postgres/Redis:
cd services/trafficking-api && mvn spring-boot:run
```

## Walk-through: from nothing to a served ad

```bash
API=http://localhost:8083/api/v1

ADV=$(curl -s -X POST $API/advertisers -H 'Content-Type: application/json' \
  -d '{"name":"Telco BD"}' | jq -r .id)

curl -s -X POST $API/placements -H 'Content-Type: application/json' \
  -d '{"id":"sports_top","name":"Sports page, top banner"}'

CR=$(curl -s -X POST $API/creatives -H 'Content-Type: application/json' -d "{
  \"advertiser_id\":\"$ADV\",\"name\":\"Cricket 300x250\",\"type\":\"image\",\"width\":300,\"height\":250,
  \"image_url\":\"https://placehold.co/300x250?text=Cricket\",\"landing_url\":\"https://example.com/cricket\"}" | jq -r .id)

ORD=$(curl -s -X POST $API/orders -H 'Content-Type: application/json' \
  -d "{\"advertiser_id\":\"$ADV\",\"name\":\"Cricket season\"}" | jq -r .id)

LI=$(curl -s -X POST $API/line-items -H 'Content-Type: application/json' -d "{
  \"order_id\":\"$ORD\",\"name\":\"Cricket sponsorship\",\"priority\":4,\"cpm\":4.0,
  \"start\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"end\":\"2027-01-01T00:00:00Z\",
  \"goal_impressions\":100000,\"frequency_cap\":{\"max\":5,\"window_seconds\":86400},
  \"countries\":[\"BD\"],\"placements\":[\"sports_top\"]}" | jq -r .id)

curl -s -X PUT $API/line-items/$LI/creatives/$CR
curl -s -X PUT $API/line-items/$LI/status -H 'Content-Type: application/json' -d '{"status":"active"}'

# Within ~2 seconds the decision engine serves it:
curl -s "http://localhost:8081/v1/ad?pub=pub_demo&placement=sports_top&w=300&h=250&country=BD"
curl -s $API/line-items/$LI/delivery
```

## Endpoints (`/api/v1`, JSON in snake_case)

| Method | Path | Notes |
|---|---|---|
| POST, GET | `/advertisers` | |
| POST, GET | `/orders` | `?advertiser_id=` filter |
| PUT | `/orders/{id}/status` | `active`, `paused`, `archived`; a paused order stops all its line items |
| POST, GET | `/placements` | id is the slug the ad tag sends, e.g. `sports_top` |
| POST, GET | `/creatives` | `image` needs `image_url`; `html` needs `html`; `landing_url` must be http(s) |
| POST, GET | `/line-items` | new line items start as `draft` |
| GET, PUT | `/line-items/{id}` | PUT replaces all fields; send `version` to avoid overwriting someone else's edit |
| PUT | `/line-items/{id}/status` | `draft -> active -> paused <-> active`, any -> `archived` |
| PUT, DELETE | `/line-items/{id}/creatives/{creativeId}` | attach / detach |
| GET | `/line-items/{id}/delivery` | delivered impressions from Redis, progress against goal |

Creates accept an optional `external_ref`. A repeated create with the same `external_ref` returns the
existing record with `200` instead of a duplicate (`201` when new). The AdCP sales agent relies on this
to retry safely.

Errors are RFC 9457 problem details. Validation errors list every invalid field:
`{"status":400,"detail":"Some fields are invalid","errors":[{"field":"end","message":"must be after start"}]}`.

## Rules

- Priority: 4 sponsorship, 8 standard, 12 price priority, 16 house (lower wins).
- Activation needs at least one creative and an end date in the future.
- Servable = line item `active`, order `active`, end in the future, at least one creative.
- Publishing uses a Redis Lua script shared with the Go seed tool: snapshot and version are written
  together, and an older version never replaces a newer one.

## Tests

`mvn verify` runs rule, snapshot-builder and publisher tests, plus `SnapshotContractTest`, which
round-trips `contracts/fixtures/line-item-snapshot.example.json` (the Go tests decode the same file).
