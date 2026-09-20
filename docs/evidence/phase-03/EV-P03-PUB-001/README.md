# EV-P03-PUB-001 — Atomic Publication and Outbox Boundary

- **Status:** PASS
- **Phase:** Phase 3 — Publish / Snapshot / Outbox
- **Git commit:** `2342a663653f5f4892ae1ba3e5c4c6b860331733`
- **Executed at:** 2026-09-20T07:57:32Z
- **Owner:** Switchboard maintainers
- **Related:** `INV-PUB-001` through `INV-PUB-003`, `INV-SNP-001` through `INV-SNP-004`, `INV-RBK-001`, `INV-MSG-001`, ADR-005, ADR-006, ADR-007, ADR-012, `FM-PG-001`, `FM-KFK-001`, `FM-ORD-001`

## Claim

The Phase 3 Control Plane validates and creates one complete immutable Snapshot Schema v1 artifact and its state, history, audit, and outbox records atomically. Concurrent callers cannot reuse an environment version, rollback creates new history, and leased outbox delivery performs publisher I/O outside database transactions while marking an event published only after acknowledgement.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | macOS 26.6.2 (25G83), arm64 |
| CPU/memory | Apple M1 Pro, 16 GiB |
| JDK | OpenJDK 17.0.19 in an isolated compatibility-validation copy; repository toolchain remains Java 21 |
| Gradle | Wrapper 9.7.1 |
| Docker | Docker 29.5.3 |
| PostgreSQL | `postgres:18.6-alpine` Testcontainers image |
| Spring Boot | 4.1.1 |
| Testcontainers | 2.0.5 |

## Topology and workload

- One Control Plane test process and one isolated PostgreSQL 18.6 container.
- Flyway V001 through V003 applied from an empty database.
- Synthetic tenant, project, production environment, two boolean flags, multiple revisions, and weighted rollout data.
- Two concurrent database transactions publish with the same expected version.
- Runtime validator rejects one structurally and semantically invalid compiled snapshot inside the publication transaction.
- Publisher boundary observes no active database transaction, fails once, then acknowledges the retry with the same event ID.
- No external Kafka broker or Distribution consumer participates in this evidence run.

## Commands

```bash
./gradlew :services:control-plane:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

## Expected

- [x] Exactly one concurrent publish with the same expected version succeeds.
- [x] A stale expected version returns conflict and creates no new side effects.
- [x] A pre-commit failure leaves no environment state, published revision transition, snapshot, entry, audit, or outbox residue.
- [x] Every successful publication creates a complete Snapshot Schema v1 payload whose relational and document checksums match.
- [x] Runtime Snapshot Schema v1 structural, semantic, and canonical-checksum validation occurs before persistence; rejection rolls back every publication side effect.
- [x] Snapshot flags, rules, conditions, variants, and allocations have deterministic order; revisions created from V002 onward preserve authored allocation order.
- [x] Historical snapshots, snapshot entries, and published revisions reject mutation.
- [x] A snapshot version cannot be reused with a different checksum.
- [x] Rollback selects a historical published revision and creates a higher version with traceable audit history.
- [x] Cross-tenant publication is concealed and creates no publication side effect.
- [x] Publisher failure leaves the outbox row pending; retry uses the stable event ID; acknowledgement precedes `published_at`.
- [x] Claim and completion use short database transactions; publisher I/O observes no active database transaction and released leases are cleared.
- [x] The complete multi-module build and contract validation pass.

## Observed

- Control Plane suite: 23 tests, 0 failures.
- Phase 3 publication integration scenarios: 10 tests, 0 failures.
- Contract suite: 8 tests, 0 failures.
- Evaluation Core regression suite: 34 tests, 0 failures.
- Full build: 73 tasks completed successfully with configuration cache stored.
- Concurrent publication: one `SUCCESS`, one `ENVIRONMENT_VERSION_CONFLICT`, one committed snapshot/outbox pair.
- Transaction failure and runtime contract rejection: environment version returned to `0`, zero rows remained in all publication-side tables, and the revision remained `DRAFT`.
- Outbox retry: publisher calls observed no active database transaction; the first attempt remained pending with its lease cleared, and the second acknowledged attempt used the same UUID, cleared its lease, and populated `published_at`.
- Docker Compose configuration validation exited successfully.

## Result

All criteria exercised by the PostgreSQL and publisher-boundary integration tests passed. The result supports runtime contract validity, atomicity, monotonic versioning, immutable history, deterministic compilation, short-lived outbox locks, and durable retry intent for the recorded code and environment.

## Artifact paths

- `services/control-plane/build/reports/tests/test/index.html`
- `services/control-plane/src/test/java/io/github/dlsrnjs125/switchboard/controlplane/PublicationIntegrationTest.java`
- `services/control-plane/src/main/java/io/github/dlsrnjs125/switchboard/controlplane/application/SnapshotCompiler.java`
- `services/control-plane/src/main/java/io/github/dlsrnjs125/switchboard/controlplane/application/SnapshotValidator.java`
- `services/control-plane/src/main/java/io/github/dlsrnjs125/switchboard/controlplane/publication/OutboxRelay.java`
- `services/control-plane/src/main/resources/db/migration/V002__preserve_rollout_allocation_order.sql`
- `services/control-plane/src/main/resources/db/migration/V003__lease_outbox_delivery.sql`

## Limitations

- Local verification used JDK 17 because JDK 21 was unavailable. Java 21 remains a CI gate.
- Kafka failure was injected at the publisher boundary. This run does not prove real-broker recovery time, topic configuration, ordering, consumer deduplication, or end-to-end propagation.
- Multi-replica relay contention, long-duration retry behavior, throughput, retention, and operational alerting were not measured.
- V001 did not store authored allocation order. The V002 backfill is deterministic only; affected pre-V002 development/test drafts must be recreated. No published snapshot existed before Phase 3.
- Distribution and SDK consumers are not implemented yet; consumer-side version/checksum reconciliation remains Phase 4 and Phase 5 scope.
