# ADR 0002: Contracts between services

Status: accepted (2026-11-02)

## Decision

In milestone 1, Go and Java services do not call each other directly. They meet at Redis (line-item
snapshot) and Kafka (ad events). Both contracts are JSON, described by JSON Schemas in `contracts/`.

When the first synchronous Go<->Java call appears (expected in Phase 2), introduce Protobuf + gRPC in
`contracts/proto/` and generate Go and Java code from it. AdCP and OpenRTB keep their official JSON schemas.

## Rules

- A field is never renamed or removed without a new version; add fields instead.
- The trafficking API writes the snapshot first and the version key second.
- Event ids are deterministic (`<type>:<request_id>`) so duplicates collapse downstream.
