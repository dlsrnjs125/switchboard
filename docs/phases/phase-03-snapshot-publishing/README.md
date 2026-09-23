# Phase 3 — Atomic Snapshot Publishing

## Outcome

Phase 3 turns an authorized flag revision selection into one immutable, contract-valid full environment snapshot. PostgreSQL remains the source of truth, and every state, history, audit, and delivery-intent write commits in one transaction.

## Implemented scope

- OpenAPI v1 publish, rollback, and current-snapshot endpoints.
- Environment row lock plus `expectedEnvironmentVersion` compare-and-swap serialization.
- First-publication transition from `DRAFT` to immutable `PUBLISHED`.
- EnvironmentFlagState upsert for publish, enable, disable, and rollback selections.
- Full environment Snapshot Schema v1 compiler with deterministic ordering.
- Runtime Snapshot Schema v1 structural, semantic, and checksum validation before persistence.
- RFC 8785 canonical JSON and SHA-256 checksum generation.
- Atomic ConfigurationSnapshot, SnapshotEntry, AuditEvent, and OutboxEvent creation.
- Historical published-revision rollback through a new monotonically increasing snapshot version.
- Short-transaction leased outbox claiming, stable event IDs, bounded broker waits and retries, and broker-ACK-before-`published_at` semantics.
- Kafka producer adapter keyed by stable event ID.
- Flyway V002 `allocation_order`, preserving authored weighted-rollout bucket ranges for revisions created from V002 onward.
- Flyway V003 outbox claim leases, keeping Kafka network I/O outside database transactions and row-lock windows.

## Publication transaction

1. Resolve the caller's tenant scope and require project-maintainer publication authority.
2. Lock the tenant-qualified environment and reject a stale expected version.
3. Advance `current_snapshot_version` with a compare-and-swap update.
4. Mark a selected draft revision `PUBLISHED` on first use.
5. Upsert the selected revision and enabled state into EnvironmentFlagState.
6. Read every current flag selection and compile a complete Snapshot Schema v1 document.
7. Canonicalize the unsigned document with RFC 8785, calculate SHA-256, and attach the checksum.
8. Validate the complete artifact against Snapshot Schema v1, semantic invariants, and its canonical checksum.
9. Insert the immutable snapshot, entries, audit event, and pending outbox event.
10. Commit. Kafka is never called from this publication transaction.

Any exception rolls the whole transaction back. A stale expected version returns `ENVIRONMENT_VERSION_CONFLICT` with no revision, state, snapshot, audit, or outbox side effect.

## Outbox delivery

The relay claims one eligible row at a time with `FOR UPDATE SKIP LOCKED`, writes a one-minute lease, and commits that short transaction before calling `SnapshotEventPublisher`. Broker acknowledgement is bounded by `switchboard.outbox.publish-timeout` (default 10 seconds). A second short transaction marks success or records a bounded exponential retry and clears the lease. Expired leases are reclaimable after a process crash. A crash after acknowledgement but before the success update may produce a duplicate; the stable event ID is retained for downstream deduplication.

## V002 upgrade note

V001 did not persist weighted-allocation authored order, so V002 cannot reconstruct it for pre-existing rows. The lexicographic backfill is deterministic but is not claimed to preserve the author's original bucket ranges. Development and test databases must recreate affected pre-V002 draft rollout revisions after migration. Phase 3 is the first publication phase, so no previously published rollout semantics are migrated. Revisions created from V002 onward persist and preserve authored order.

## Verification

```bash
./gradlew :services:control-plane:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

The PostgreSQL integration suite covers atomic full snapshot creation, runtime Phase 0D schema/semantic/checksum validation with full rollback on rejection, canonical checksum reproduction, post-V002 allocation-order preservation, one-winner concurrent publication, stale-version side-effect isolation, transaction rollback, snapshot/history immutability, version non-reuse, historical rollback, cross-tenant concealment, and transaction-free publisher I/O plus outbox failure/retry/ack transitions.

## Troubleshooting

- [TRB-004 — Snapshot contract validation](../../troubleshooting/TRB-004-snapshot-contract-validation.md)
- [TRB-005 — Outbox lease ownership](../../troubleshooting/TRB-005-outbox-lease-ownership.md)

## Deferred boundaries

- The relay is disabled by default and must be enabled with `SWITCHBOARD_OUTBOX_RELAY_ENABLED=true` in a deployment that provides Kafka bootstrap configuration.
- A real-broker outage/recovery drill, propagation timing, relay multi-replica soak, and consumer deduplication evidence belong to Phase 4, Phase 6, and Phase 9.
- Distribution cache application, same-version checksum rejection at the consumer, gRPC delivery, SDK atomic replacement, and LKG remain later-phase responsibilities.
