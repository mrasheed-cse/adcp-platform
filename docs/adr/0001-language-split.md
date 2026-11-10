# ADR 0001: Language split

Status: accepted (2026-11-02)

## Decision

- **Go** for the hot path: anything answering live ad traffic under a 10-100 ms budget at high request
  rates (ad decisioning, event collection, exchange auction, Prebid Server, DSP bidder, TMP router,
  SSAI, retail sponsored-products API).
- **Java 21 + Spring Boot** for the control plane: transactional business logic and APIs (trafficking,
  campaign management, AdCP sales and buyer agents, billing, accounts, reporting API). Flink jobs in Java.
- **Python** only for ML training; models are exported (ONNX or tree models) and scored inside Go.
- **TypeScript + Angular** for admin UIs; TypeScript compiled to a small script for ad tags.

Java is not mandatory everywhere: where a service sits on the live ad path, Go is preferred.

## Why

Go gives predictable latency with little tuning and cheap concurrency for fan-out. Java and Spring Boot
are the team's strongest stack for correctness-heavy business logic. Angular matches existing experience.
