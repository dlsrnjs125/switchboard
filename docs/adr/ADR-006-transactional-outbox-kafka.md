# ADR-006: Use a transactional outbox with Kafka notification

- Status: Accepted
- Date: 2026-09-18
- Foundation: OutboxEvent, Reliable Messaging, `INV-PUB-002`, `INV-PUB-003`, `INV-MSG-001`, `INV-MSG-002`

## Context

Publication must atomically change authoritative state and request downstream delivery. Direct database-plus-broker dual writes can lose or announce uncommitted changes.

## Decision

Publication stores an `OutboxEvent` in the same PostgreSQL transaction as environment state, snapshot, and audit records. An independent relay publishes the event to Kafka at least once. Event identity and snapshot version make producers and consumers idempotent.

## Alternatives considered

- **Publish directly inside the transaction** — rejected because broker failure and database rollback cannot be atomic.
- **Best-effort publish after commit** — rejected because a process crash can permanently lose notification.
- **Distributed transaction** — rejected due to coupling and operational complexity.

## Trade-offs

- **Benefit:** no committed publication loses its delivery intent.
- **Cost:** duplicate delivery, relay lag, retention, and poison-event handling are explicit concerns.

## Consequences

- Publish may succeed while Kafka is unavailable; delivery resumes from the outbox.
- Consumers never treat Kafka arrival order as authoritative.
- Metrics cover outbox age, attempts, failures, and propagation latency.

## Revisit conditions

- A proven transactional messaging mechanism provides equal recovery and audit properties with lower operational cost.
