# Architecture

Three layers plus shared services:

- **Conventional programmatic stack:** DSP (campaigns, bidder, pacing, ML bid pricing),
  exchange/SSP (OpenRTB auction, Prebid Server, floors, deals, ads.txt), ad server
  (line items, decisioning, forecasting, rendering).
- **AdCP agentic layer:** AdCP buyer agent, TMP router (serve-time Context and Identity Match),
  AdCP sales agent (sells inventory to agents, writes deals into the ad server).
- **Shared platform services:** Kafka -> Flink -> ClickHouse event pipeline, Aerospike/Redis state,
  identity and consent, fraud/IVT filtering, billing and payouts, accounts and roles, observability.

## Language split

See `adr/0001-language-split.md`. In short: Go for anything on the live ad path, Java 21 + Spring Boot
for the control plane, Python for ML training, TypeScript/Angular for admin UIs and ad tags.

## Roadmap

| Phase | Months | Scope |
|---|---|---|
| 1 | 0-10 | Foundations, ad server core, display and native ads, AdCP sales agent. Launch: a publisher sells direct campaigns and AdCP deals. |
| 2 | 6-20 | SSP/exchange (OpenRTB, Prebid), DSP and bidder, TMP router and buyer agent. |
| 3 | 14-30 | CTV/SSAI, retail media, ML bidding and floors, attribution, advanced fraud detection, certifications. |

## Milestone 1 data flow

```
trafficking-api (Java) --snapshot--> Redis <--refresh-- adserver-decision (Go) <--GET /v1/ad-- ad tag
                                       ^                        |
                                       |               signed impression/click URLs
                                  counters                      v
                                       +------------ event-collector (Go) --events--> Kafka --> ClickHouse
```

## Known simplifications in milestone 1

- Frequency caps use a fixed window starting at the user's first impression, not a sliding window.
- Delivered counts reach the decision service within the refresh interval (2 s), so a line item can
  overshoot its goal by roughly (impressions per second x 2 s). Fine for display; tighten before
  selling large guaranteed deals.
- Counters are updated directly in Redis by the collector. If Redis is down, events still reach Kafka
  (and ClickHouse) but real-time counters miss them.
- A single `TOKEN_SECRET` signs tokens; add key rotation (key id in the token) before production.
- Contracts are JSON Schema; Protobuf/gRPC arrives with the first synchronous Go<->Java calls (ADR 0002).
