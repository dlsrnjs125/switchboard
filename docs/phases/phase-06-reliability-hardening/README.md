# Phase 6 — Reliability Hardening

## Outcome

Phase 6 turns the Phase 0F failure model into repeatable executable drills. The implementation injects faults at real PostgreSQL, Kafka, gRPC, filesystem, credential, and consumer boundaries, then verifies safety during the fault and authoritative convergence after cleanup.

## Implemented scope

- PostgreSQL 18.6 traffic routed through Toxiproxy with bounded JDBC connect/socket timeouts and a pre-commit connection-cut drill.
- Kafka 4.3.1 container pause/unpause after PostgreSQL publication commit, with outbox retry and broker-acknowledged completion.
- Expired outbox claim-lease reclaim and stale-claim completion rejection.
- Actual gRPC Distribution server stop/restart while the Java Provider performs 1,000 local LKG evaluations.
- Distribution session admission limit with `RESOURCE_EXHAUSTED` before authoritative Snapshot loading.
- Corrupt Snapshot rejection at Distribution and SDK boundaries followed by valid higher-version recovery.
- Duplicate/out-of-order/gap/conflict reconciliation and Kafka consumer stop/start convergence.
- Credential revoke enforcement, old authentication rejection, and same-scope rotated credential recovery.
- Disk LKG temporary-file fsync, atomic rename, parent-directory fsync, and interrupted-temporary-artifact restart coverage.
- One-command scenario harness: `./infra/reliability/phase-06-drill.sh all` or `make reliability`.

## Recovery-complete contract

Recovery is complete only when all applicable checks pass:

1. The failed dependency or process accepts new work.
2. PostgreSQL still contains one complete authoritative publication record set with no version reuse.
3. The committed outbox intent is broker-acknowledged and has `published_at` persisted.
4. Distribution cache version and checksum match PostgreSQL.
5. The SDK active Snapshot and disk LKG match the accepted full Snapshot.
6. Provider state returns from `READY_STALE` to `READY` without a partial or regressive observation.
7. Fault injection is removed and no container, proxy toxic, lease, or test process remains uncontrolled.

## Capacity protection

`switchboard.distribution.maximum-sessions` defaults to 10,000 and can be configured with `SWITCHBOARD_DISTRIBUTION_MAXIMUM_SESSIONS`. Admission occurs before PostgreSQL-backed Snapshot bootstrap so rejected reconnects do not amplify database load. Per-client buffering remains one coalesced latest full Snapshot.

## Verification

```bash
make reliability
./gradlew clean build --configuration-cache --rerun-tasks
./gradlew build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

The detailed failure-to-test mapping is in [failure-to-evidence-matrix.md](../../testing/failure-to-evidence-matrix.md), operating steps are in [runbook.md](../../operations/runbook.md), and recorded results are in [EV-P06-REL-001](../../evidence/phase-06/EV-P06-REL-001/README.md).

## Deliberate limits

- The PR gate uses one PostgreSQL container, one Kafka broker, one Distribution process, and a small client envelope; fleet capacity belongs to Phase 9.
- PostgreSQL fault injection proves a pre-commit network cut. A deliberately ambiguous connection loss after server commit requires a dedicated external transaction proxy drill.
- Distribution restart uses a real listening gRPC server lifecycle in one test JVM, not an OS-level `SIGKILL` or Kubernetes pod eviction.
- Kafka pause is a broker-process availability fault, not a multi-broker quorum or storage-loss experiment.
- Metrics, traces, dashboards, and alert validation belong to Phase 7.
