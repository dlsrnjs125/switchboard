# Phase 4 — Distribution Plane

## Outcome

Phase 4 turns committed PostgreSQL Snapshots and Kafka freshness notifications into authenticated, monotonic gRPC full-Snapshot delivery. The Data Plane independently validates every authoritative artifact and retains the prior valid cache when integrity or ordering checks fail.

## Implemented scope

- Spring Boot Distribution deployable with gRPC and HTTP Actuator health surfaces.
- Phase 0D protobuf-generated server stubs for Subscribe, ACK, NACK, and RESYNC.
- Metadata Bearer service-credential authentication with server-derived client application and environment scope.
- Active, expired, revoked, archived-application, wrong-secret, and wrong-scope denial boundaries.
- PostgreSQL current-Snapshot bootstrap and recovery repository.
- Independent Snapshot Schema v1 structural, semantic, metadata, and RFC 8785 checksum validation.
- Immutable per-environment in-memory cache with monotonic version and same-version Snapshot identity/checksum rules.
- Kafka `SNAPSHOT_PUBLISHED` consumer with bounded stable event-ID deduplication and PostgreSQL reconciliation.
- Full-Snapshot reconnect, heartbeat, `RESYNC_REQUIRED`, ACK, NACK, and explicit RESYNC behavior.
- One-pending-Snapshot slow-client coalescing and independent stream sessions.
- Five-second default credential revalidation, revocation message, and bounded stream closure.
- gRPC health service, Actuator health probes, and graceful stream/server shutdown.

## Reconciliation rules

| Input | Result |
| --- | --- |
| Duplicate event ID | No second logical apply |
| Notification below PostgreSQL current | Load and retain/apply current authority |
| Candidate below active cache | Ignore; cache never regresses |
| Same version and checksum | Idempotent |
| Same version and different Snapshot ID | Reject as integrity violation |
| Same version and different checksum | Reject as integrity violation |
| Version gap | Apply validated current full Snapshot directly |
| Corrupt/incompatible authoritative Snapshot | Reject and keep prior cache |

Kafka is not queried on the SDK evaluation path and is not a source of truth. A consumer restart may forget event IDs, but replay remains safe because version/checksum application is idempotent.

## gRPC behavior

- `Subscribe`: authenticate, enforce exact application/project/environment scope, register the session before bootstrap reconciliation, compare `lastAppliedSnapshotVersion`, then send a full Snapshot, heartbeat, or `RESYNC_REQUIRED`.
- Bootstrap/live-update overlap is monotonic: a concurrently broadcast newer Snapshot cannot be missed or overwritten by an older bootstrap result.
- `Acknowledge`: accept only an exact cached version/checksum pair in the authenticated scope.
- `Reject`: accept a scoped NACK, reload current PostgreSQL state, and coalesce a full Snapshot onto matching streams.
- `RequestResync`: reload and send the current full Snapshot without delta replay.
- Revocation: deny new authentication immediately from authoritative state and close existing streams within the configured revalidation interval.

## Verification

```bash
./gradlew :services:distribution:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache --rerun-tasks
docker compose -f infra/docker/docker-compose.yml config --quiet
```

The test suite uses PostgreSQL 18.6, an embedded KRaft Kafka broker, and actual Netty gRPC channels. It covers credential authentication and revocation, cross-scope concealment, race-safe bootstrap streaming, reconnect heartbeat/resync decisions, ACK matching, duplicate/out-of-order/gap/identity/checksum conflict reconciliation, corrupt authoritative artifact rejection, event delivery through Kafka, and slow-client full-Snapshot coalescing.

## Troubleshooting

- [TRB-006 — gRPC subscription consistency races](../../troubleshooting/TRB-006-grpc-subscription-consistency-races.md)
- [TRB-013 — Credential dependency failure status](../../troubleshooting/TRB-013-credential-dependency-failure-status.md)

## Deferred boundaries

- Control Plane client-application and credential administration runtime endpoints remain a separate management implementation; Phase 4 verifies Distribution against the approved relational credential contract.
- Transport TLS/mTLS termination and production certificate management belong to deployment/security hardening.
- The event-ID deduplication window is replica-local and bounded; version/checksum reconciliation is the cross-replica correctness mechanism.
- Real broker outage/recovery, multi-replica consumer rebalance, process-kill recovery, and prolonged credential-store outage drills belong to Phase 6.
- Metrics, trace correlation, and alert policy belong to Phase 7.
- Fleet-scale reconnect, slow-client, memory, and propagation capacity evidence belongs to Phase 9.
- SDK atomic application, provider state, reconnect backoff/jitter, and disk LKG belong to Phase 5.
