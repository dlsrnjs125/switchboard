# Phase 3 — Atomic Snapshot Publishing

## Outcome

Phase 3 turns an authorized flag revision selection into one immutable, contract-valid full environment snapshot. PostgreSQL remains the source of truth, and every state, history, audit, and delivery-intent write commits in one transaction.

## Implemented scope

- OpenAPI v1 publish, rollback, and current-snapshot endpoints.
- Environment row lock plus `expectedEnvironmentVersion` compare-and-swap serialization.
- First-publication transition from `DRAFT` to immutable `PUBLISHED`.
- EnvironmentFlagState upsert for publish, enable, disable, and rollback selections.
- Full environment Snapshot Schema v1 compiler with deterministic ordering.
- RFC 8785 canonical JSON and SHA-256 checksum generation.
- Atomic ConfigurationSnapshot, SnapshotEntry, AuditEvent, and OutboxEvent creation.
- Historical published-revision rollback through a new monotonically increasing snapshot version.
- `FOR UPDATE SKIP LOCKED` outbox claiming, stable event IDs, bounded retry delay, and broker-ACK-before-`published_at` semantics.
- Kafka producer adapter keyed by stable event ID.
- Flyway V002 `allocation_order`, preserving the authored weighted-rollout bucket ranges during relational round trips.

## Publication transaction

1. Resolve the caller's tenant scope and require project-maintainer publication authority.
2. Lock the tenant-qualified environment and reject a stale expected version.
3. Advance `current_snapshot_version` with a compare-and-swap update.
4. Mark a selected draft revision `PUBLISHED` on first use.
5. Upsert the selected revision and enabled state into EnvironmentFlagState.
6. Read every current flag selection and compile a complete Snapshot Schema v1 document.
7. Canonicalize the unsigned document with RFC 8785, calculate SHA-256, and attach the checksum.
8. Insert the immutable snapshot, entries, audit event, and pending outbox event.
9. Commit. Kafka is never called from this publication transaction.

Any exception rolls the whole transaction back. A stale expected version returns `ENVIRONMENT_VERSION_CONFLICT` with no revision, state, snapshot, audit, or outbox side effect.

## Outbox delivery

The relay claims eligible rows with `FOR UPDATE SKIP LOCKED`. It publishes the immutable payload through `SnapshotEventPublisher` and updates `published_at` only after the publisher returns a broker acknowledgement. Failure increments `attempt_count`, records `last_error`, and schedules a bounded exponential retry. A crash after acknowledgement but before the database update may produce a duplicate; the stable event ID is retained for downstream deduplication.

## Verification

```bash
./gradlew :services:control-plane:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

The PostgreSQL integration suite covers atomic full snapshot creation, Phase 0D JSON Schema validation, canonical checksum reproduction, allocation-order preservation, one-winner concurrent publication, stale-version side-effect isolation, transaction rollback, snapshot/history immutability, version non-reuse, historical rollback, cross-tenant concealment, and outbox failure/retry/ack transitions.

## Deferred boundaries

- The relay is disabled by default and must be enabled with `SWITCHBOARD_OUTBOX_RELAY_ENABLED=true` in a deployment that provides Kafka bootstrap configuration.
- A real-broker outage/recovery drill, propagation timing, relay multi-replica soak, and consumer deduplication evidence belong to Phase 4, Phase 6, and Phase 9.
- Distribution cache application, same-version checksum rejection at the consumer, gRPC delivery, SDK atomic replacement, and LKG remain later-phase responsibilities.
