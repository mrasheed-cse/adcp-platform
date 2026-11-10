# AdCP Platform

A full-stack ad-tech platform: ad server, SSP/exchange, DSP, and an AdCP (Ad Context Protocol) agentic layer.
See `docs/architecture.md` for the target architecture and roadmap.

**Current milestone (1): serve one display ad end to end** — a booked line item becomes a served
impression that shows up in a report.

| Step | What | Language | Status |
|---|---|---|---|
| 1 | Monorepo skeleton, contracts, local stack, CI | — | Done |
| 2 | Ad server trafficking API (`services/trafficking-api`) | Java + Spring Boot | Done (first full Maven run in CI) |
| 3 | Ad decision engine (`services/adserver-decision`) | Go | Done |
| 4 | Event collector (`services/event-collector`) | Go | Done |
| 5 | AdCP sales agent (`services/adcp-sales-agent`): Postgres, books real line items | Java + Spring Boot | Done (first full Maven run in CI) |
| 6 | Display ad tag + Angular admin screen | TypeScript / Angular | Next |

## Layout

```
contracts/        JSON Schemas shared by Go and Java services
pkg/              Shared Go packages (line-item model, signed tracking tokens, events)
services/
  adserver-decision/   Go   picks the ad for each request          :8081
  event-collector/     Go   impression/click pixels -> Redis + Kafka :8082
  trafficking-api/     Java orders, line items, creatives -> Redis   :8083
  adcp-sales-agent/    Java AdCP sales agent (MCP), books into the ad server :8084
web/
  admin/           Angular admin UI (step 6)
  ad-tag/          Display ad tag (step 6)
infra/            docker-compose, ClickHouse schema, Dockerfiles
test/e2e/         Black-box end-to-end test of the Go services
tools/seed/       Publishes sample line items without the trafficking API
docs/             Architecture and decision records
```

## How a request flows

1. The trafficking API publishes active line items to Redis (`adserver:snapshot`, see `contracts/line-item-snapshot.schema.json`).
2. The decision service keeps that snapshot and delivered counts in memory, refreshed every 2 s.
3. `GET /v1/ad` picks a line item (priority, targeting, pacing, frequency cap) and returns the creative
   plus impression and click URLs carrying an HMAC-signed token.
4. The event collector verifies the token, de-duplicates by request id, updates delivered and
   frequency counters in Redis, and publishes the event to Kafka.
5. ClickHouse consumes Kafka into `adtech.ad_events` for reporting.

## First-time setup

Requires Go 1.24+, Java 21 with Maven, and Docker.

```bash
go mod tidy          # once: records checksums for the Kafka client (see note below)
cp .env.example .env # then put a real secret in TOKEN_SECRET
make test            # Go unit tests
make test-java       # Java services (Maven)
make test-e2e        # end-to-end test, needs no Docker
```

> **Note on `go.sum`:** the initial code was written in a sandbox that could not reach `proxy.golang.org`,
> `golang.org/x` or `gopkg.in`. Redis dependencies were fetched from GitHub; the Kafka client
> (`segmentio/kafka-go`) was compiled against a local copy of its interface. Run `go mod tidy` once and
> commit the result; CI then verifies every checksum against the Go checksum database (`go mod verify`).

## Run locally

```bash
make up      # Postgres, Redis, Kafka, ClickHouse and all services
make seed    # optional: sample line items without using the trafficking API
make demo    # request an ad
```

To create real line items by hand, follow `services/trafficking-api/README.md`; to have an AI agent
book them, follow `services/adcp-sales-agent/README.md`.
Then open the `impression_url` from the response and query ClickHouse at http://localhost:8123
(user/password `adtech`): `SELECT * FROM adtech.ad_events FINAL`.

## Ad request

```
GET /v1/ad?pub=<publisher>&placement=<placement>&w=300&h=250[&uid=<user id>][&country=BD][&device=mobile]
```

- `200` with JSON (`contracts/ad-response.schema.json`), `204` when there is no ad, `400` on bad input.
- Country falls back to the `CF-IPCountry` / `X-Country` header; device falls back to the User-Agent.
- The raw `uid` is never stored or logged: it becomes a pseudonymous HMAC key.
