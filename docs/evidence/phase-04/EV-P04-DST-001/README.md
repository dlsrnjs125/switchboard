# EV-P04-DST-001 — Authenticated Full-Snapshot Distribution

- **Status:** PASS
- **Phase:** Phase 4 — Distribution Plane
- **Git commit:** `4b8f03b9444642cc21b72ecc00d6ad49fd997a64`
- **Executed at:** 2026-09-21T03:54:00Z
- **Owner:** Switchboard maintainers
- **Related:** `INV-TEN-001`, `INV-SNP-001` through `INV-SNP-004`, `INV-MSG-001`, `INV-SDK-001` through `INV-SDK-004`, `INV-CRD-001`, ADR-002, ADR-004, ADR-005, ADR-010, ADR-012, `FM-DST-001`, `FM-SNP-001`, `FM-ORD-001`, `FM-GAP-001`, `FM-CRD-001`, `FM-TEN-001`, `FM-BKP-001`

## Claim

The Phase 4 Distribution Plane authenticates a service credential to one server-derived client application and environment scope, reconciles Kafka notifications against authoritative PostgreSQL current state, independently validates immutable Snapshot Schema v1 artifacts, and streams only monotonic full Snapshots over the Phase 0D gRPC contract. Duplicate, old, gapped, corrupt, conflicting, slow-client, reconnect, and revoked-credential paths do not disclose or regress configuration.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | macOS 26.6.2 (25G83), arm64 |
| CPU/memory | Apple M1 Pro, 16 GiB |
| JDK | OpenJDK 17.0.19 in an isolated compatibility-validation copy; repository toolchain remains Java 21 |
| Gradle | Wrapper 9.7.1 |
| Docker | Docker 29.5.3 |
| PostgreSQL | `postgres:18.6-alpine` Testcontainers image |
| Kafka | Spring Kafka 4.1.1 embedded KRaft broker |
| gRPC | grpc-java 1.83.1, Netty transport |
| Spring Boot | 4.1.1 |

## Topology and workload

- One Control Plane service instance constructed at the application boundary.
- One PostgreSQL 18.6 Testcontainers instance with Flyway V001 through V003.
- One embedded single-node KRaft Kafka broker and listener container.
- One Distribution repository, validator, immutable cache, reconciler, session registry, and Netty gRPC server.
- One authenticated gRPC test client plus negative wrong-scope, wrong-secret, and revoked-credential clients.
- Full Snapshot fixtures for versions 1, 3, 4, 7, and 9, including duplicate, old, gap, checksum-conflict, and corrupt payload cases.
- One full Control Plane Publish → Transactional Outbox → Kafka → PostgreSQL Reconciliation → Distribution Cache → gRPC Client path.

## Commands

```bash
./gradlew :services:distribution:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache --rerun-tasks
./gradlew build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

## Expected

- [x] A valid scoped credential receives only its environment's current full Snapshot.
- [x] Wrong secret, revoked credential, or wrong request scope receives no Snapshot.
- [x] Existing streams close with `PERMISSION_DENIED` when authoritative credential revalidation observes revocation.
- [x] Distribution validates schema version, relational metadata, semantic references, and RFC 8785 checksum before cache apply.
- [x] Duplicate event IDs and same-version/same-checksum artifacts are idempotent.
- [x] Old delivery cannot regress cache version; a version gap converges directly to current full state.
- [x] Same-version checksum or identity conflict and corrupt authoritative payload are rejected without replacing the active cache.
- [x] Reconnect at current version receives heartbeat; client-ahead state receives `RESYNC_REQUIRED` rather than a regressive Snapshot.
- [x] ACK accepts only the exact cached version/checksum.
- [x] A slow stream retains only the newest pending full Snapshot.
- [x] A live Snapshot applied during subscriber bootstrap is delivered and cannot be overwritten by the older bootstrap result.
- [x] Same-version/same-checksum/different-Snapshot-ID input is rejected while the active cache remains unchanged.
- [x] A real Control Plane publication reaches a test client through Outbox, Kafka, Distribution reconciliation, and gRPC.
- [x] Full multi-module build, contract validation, and Compose configuration pass.

## Observed

- Distribution suite: 13 tests, 0 failures.
- PostgreSQL reconciliation: duplicate, old, gap, same-version conflict, corrupt payload, and credential lifecycle scenarios PASS.
- gRPC wire integration: Subscribe, heartbeat, resync-required, ACK, scope denial, new-auth revocation denial, and existing-stream revocation closure PASS.
- Slow-client test: versions 1, 2, and 3 offered while not writable; exactly one message delivered when writable, containing version 3.
- Bootstrap race regression: version 11 broadcast during a version 10 bootstrap lookup was the only Snapshot delivered; the late version 10 result was ignored.
- Cache identity regression: version 10/checksum X/Snapshot ID B was rejected against active version 10/checksum X/Snapshot ID A, and A remained active.
- Kafka component path: broker record consumed, PostgreSQL current version 4 loaded, validated, and cached.
- Full E2E: Control Plane version 1 publication created an Outbox row, broker acknowledgement populated `published_at`, and the gRPC client received the exact version/checksum.
- Control Plane regression suite: 23 tests, 0 failures.
- Contract suite: 8 tests, 0 failures.
- Evaluation Core regression suite: 34 tests, 0 failures.
- Full build: 79 tasks completed successfully; configuration cache stored and reused.
- Docker Compose configuration validation exited successfully.

## Result

All predeclared Phase 4 component and end-to-end criteria passed in the recorded local environment. The result supports authenticated tenant-scoped delivery, authoritative reconciliation, independent Snapshot integrity validation, monotonic cache behavior, bounded per-stream Snapshot buffering, and complete publish-to-client full-Snapshot propagation for the recorded commit.

## Artifact paths

- `services/distribution/build/reports/tests/test/index.html`
- `services/distribution/src/test/java/io/github/dlsrnjs125/switchboard/distribution/PublishToGrpcE2ETest.java`
- `services/distribution/src/test/java/io/github/dlsrnjs125/switchboard/distribution/grpc/GrpcDistributionIntegrationTest.java`
- `services/distribution/src/test/java/io/github/dlsrnjs125/switchboard/distribution/grpc/SnapshotDistributionGrpcServiceTest.java`
- `services/distribution/src/test/java/io/github/dlsrnjs125/switchboard/distribution/messaging/KafkaNotificationE2ETest.java`
- `services/distribution/src/test/java/io/github/dlsrnjs125/switchboard/distribution/snapshot/SnapshotCacheTest.java`
- `services/distribution/src/test/java/io/github/dlsrnjs125/switchboard/distribution/snapshot/SnapshotCoordinatorIntegrationTest.java`
- `docs/architecture/distribution-dataflow.md`

## Limitations

- Local verification used JDK 17 because JDK 21 was unavailable. Java 21 GitHub Actions remains a required PR gate.
- The Kafka tests use an embedded single-node KRaft broker; broker outage/recovery, consumer rebalance, and multi-replica contention remain Phase 6 drills.
- PostgreSQL and gRPC run locally without injected latency, packet loss, TLS termination, or external load balancing.
- The credential-revocation bound is verified through explicit revalidation invocation rather than a wall-clock scheduling assertion; the configured default interval is five seconds.
- The replica-local event-ID window is bounded and not durable. Cross-replica/restart correctness relies on version/checksum reconciliation as designed.
- Fleet-scale reconnect, backpressure duration, heap bounds, propagation percentiles, and capacity limits are not measured here and remain Phase 9 evidence.
- SDK atomic apply, provider state, reconnect backoff/jitter, and disk LKG are Phase 5 responsibilities.
