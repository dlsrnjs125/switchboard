# Architecture Decision Records

Architecture Decision Records (ADRs) capture the choices that constrain Switchboard's APIs, data model, runtime, and operations. Phase 0B foundation documents define canonical terms and invariants; ADRs explain why the architecture preserves them.

## Status lifecycle

`Proposed` → `Accepted` → `Superseded` or `Deprecated`

Accepted ADRs are changed by creating a new ADR, not by silently rewriting the original decision. Corrections that do not change the decision may be applied in place.

## Index

| ADR | Decision | Status |
| --- | --- | --- |
| [ADR-001](ADR-001-local-evaluation.md) | Local evaluation by default | Accepted |
| [ADR-002](ADR-002-control-data-plane-separation.md) | Separate control and data planes | Accepted |
| [ADR-003](ADR-003-openfeature-provider.md) | OpenFeature Provider as application contract | Accepted |
| [ADR-004](ADR-004-grpc-streaming-distribution.md) | gRPC streaming distribution | Accepted |
| [ADR-005](ADR-005-postgresql-source-of-truth.md) | PostgreSQL as source of truth | Accepted |
| [ADR-006](ADR-006-transactional-outbox-kafka.md) | Transactional outbox with Kafka notification | Accepted |
| [ADR-007](ADR-007-immutable-revisions-snapshots.md) | Immutable revisions and snapshots | Accepted |
| [ADR-008](ADR-008-sha256-deterministic-rollout.md) | SHA-256 deterministic rollout | Accepted |
| [ADR-009](ADR-009-last-known-good-ready-stale.md) | Last Known Good and `READY_STALE` policy | Accepted |
| [ADR-010](ADR-010-multi-tenant-authorization-boundary.md) | Server-enforced multi-tenant authorization | Accepted |
| [ADR-011](ADR-011-monorepo-deployable-modules.md) | Monorepo with deployable module boundaries | Accepted |
| [ADR-012](ADR-012-full-snapshot-first.md) | Full snapshot first, delta later | Accepted |
| [ADR-013](ADR-013-secret-management-out-of-scope.md) | Secret management outside Switchboard | Accepted |
| [ADR-014](ADR-014-ofrep-extension-strategy.md) | OFREP as a compatible extension | Accepted |

## Authoring rules

- Copy [the template](template.md) and allocate the next sequential number.
- Use canonical terms from the [glossary](../foundation/glossary.md).
- Identify affected [domain boundaries](../foundation/domain-boundaries.md) and invariant IDs from [the invariant catalog](../foundation/invariants.md).
- Record rejected alternatives and the conditions that would justify revisiting the decision.
- Link a superseding ADR in both records.
