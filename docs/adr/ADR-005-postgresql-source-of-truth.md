# ADR-005: Use PostgreSQL as the source of truth

- Status: Accepted
- Date: 2026-09-18
- Foundation: Source of truth, Publication boundary, `INV-PUB-002`, `INV-SNP-001`, `INV-SNP-002`

## Context

Authoring, optimistic publication, immutable history, audit, and outbox creation require a transactional authority. Kafka arrival order and disposable caches cannot safely answer what is current.

## Decision

PostgreSQL is authoritative for management state, environment state, published revisions, snapshots, audit events, and outbox events. Kafka and SDK caches are derived delivery state. Distribution may read committed snapshots from PostgreSQL for bootstrap and recovery.

## Alternatives considered

- **Kafka as authority** — rejected because management queries and multi-aggregate transactions become harder.
- **Redis as authority** — rejected because durability and relational integrity are primary requirements.
- **Separate authoring and snapshot databases in MVP** — rejected because distributed consistency is premature.

## Trade-offs

- **Benefit:** transactional consistency, constraints, queryability, and recoverable history.
- **Cost:** database availability limits management writes and snapshot bootstrap capacity.

## Consequences

- Database constraints reinforce tenant and version invariants.
- Read replicas or artifact stores remain derived and rebuildable.
- Kafka consumers reconcile using snapshot version, not message order.

## Revisit conditions

- Proven scale, regional isolation, or retention requirements exceed a single PostgreSQL authority and a replication design is available.
