# ADR-009: Retain Last Known Good and expose READY_STALE

- Status: Accepted
- Date: 2026-09-18
- Foundation: LKG, `READY_STALE`, `ERROR`, `INV-SDK-001`–`INV-SDK-004`

## Context

Distribution and network failures must not remove already validated configuration. At the same time, operators and applications need to know when freshness is no longer confirmed.

## Decision

The SDK atomically retains the latest compatible, checksum-valid, successfully applied snapshot as LKG in memory and durable disk cache. Loss of freshness while LKG remains usable transitions `READY` to `READY_STALE`; local evaluation continues. Invalid updates are NACKed without replacing LKG.

## Alternatives considered

- **Fail closed on disconnect** — rejected because platform failure would become application failure.
- **Use any last received payload** — rejected because receipt is not successful validation and application.
- **Hide staleness** — rejected because it prevents safe operations.

## Trade-offs

- **Benefit:** availability and explicit degradation without silent corruption.
- **Cost:** disk protection, freshness policy, and stale telemetry require design and testing.

## Consequences

- `READY_STALE` means evaluation-ready but remotely stale.
- With no valid bootstrap or LKG, the provider remains `NOT_READY`.
- Phase 5 fixes max-staleness policy and exact `ERROR` recovery/OpenFeature mapping.

## Revisit conditions

- A regulated use case requires bounded staleness or fail-closed behavior; expose it as explicit policy rather than changing the default silently.
