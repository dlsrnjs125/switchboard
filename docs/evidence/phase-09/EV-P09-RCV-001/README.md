# EV-P09-RCV-001 — Timed Dependency and Process Recovery

- **Phase:** 9 — Performance & Operations Evidence
- **Status:** `PASS`
- **Evidence ID:** `EV-P09-RCV-001`
- **Git commit:** `4dd4c568b3dd6f2a7284b30fae3d3530bfb5b426`
- **Executed at:** `2026-09-28T12:12:13Z`
- **Related:** `FM-PG-001`, `FM-KFK-001`, `FM-DST-001`, `FM-CRD-001`, `SLI-OUT-001`, `SLI-RCV-001`, `SLI-OBX-001`, ADR-004, ADR-006, TRB-007, TRB-013
- **Command:** `make phase9-recovery-evidence`
- **Artifact path:** `docs/evidence/phase-09/EV-P09-RCV-001/artifacts/`

## Claim

Within the recorded local topology, PostgreSQL publication failures, ambiguous post-commit responses, Kafka/outbox delivery failure, Distribution process restart, and credential-store unavailability recover within predeclared experiment targets without version reuse, partial publication state, lost outbox intent, missing broker acknowledgement, or loss of SDK local evaluation continuity.

This is a local Testcontainers and in-process gRPC recovery envelope. It is not a production dependency HA, WAN, Kubernetes, multi-replica, or disaster-recovery claim.

## Workload

- 30 measured recovery cycles per scenario;
- PostgreSQL pre-commit network cut through Toxiproxy, followed by a successful atomic publication;
- injected post-commit response loss, followed by authoritative Snapshot/audit/outbox reconciliation and rejected blind retry;
- Kafka container pause during outbox delivery, followed after unpause through the unmodified persisted `next_attempt_at`, broker ACK, and persisted `published_at`;
- Distribution gRPC server stop/restart while the Java Provider continues LKG evaluation in `READY_STALE`;
- credential authentication dependency failure injected as `DataAccessResourceFailureException` at `DistributionRepository.authenticate`, followed by retry and `READY` convergence;
- p50/p95/p99/max recorded from demonstrated dependency readiness to the scenario-specific convergence condition.

## Expected

- every scenario records exactly 30 successful recovery samples with zero excluded errors;
- local PostgreSQL reconciliation, Distribution restart, and credential dependency recovery p95 remain at or below 5 seconds;
- Kafka/outbox recovery p95 remains at or below the 60-second `SLI-OBX-001` experiment target and every sample remains below five minutes;
- pre-commit failure leaves no new version or partial publication residue;
- ambiguous post-commit response resolves to exactly one Snapshot, audit event, and outbox intent, while blind retry is rejected;
- every failed outbox delivery retains its stable event ID and ends with broker acknowledgement plus persisted `published_at`;
- every Kafka cycle preserves the application's retry schedule without direct retry-state mutation;
- every credential cycle records at least one new unavailable response after that cycle's fault injection;
- every process/dependency outage transitions the Provider through `READY_STALE`, preserves local evaluation, and returns to the authoritative Snapshot version.

## Environment fingerprint

`artifacts/environment.txt` records clean source commit `4dd4c568b3dd6f2a7284b30fae3d3530bfb5b426`, matching index tree `54e571752c1c7b88087c519e2c4bc66c24df6a99`, host/container resources, and Java 21 image identity. `artifacts/git-status.txt` records `CLEAN`.

## Observed

All five scenarios completed 30 measured recovery cycles without an excluded sample, workload error, integrity violation, or incomplete cleanup.

| Scenario | Recovery boundary | p50 | p95 | p99 / max |
| --- | --- | ---: | ---: | ---: |
| PostgreSQL pre-commit | network restored → atomic publication records converge | 85.019 ms | 129.381 ms | 138.718 ms |
| PostgreSQL post-commit | ambiguous response → authoritative Snapshot/audit/outbox reconciliation | 36.657 ms | 40.284 ms | 40.638 ms |
| Kafka/outbox | broker unpaused → persisted retry becomes eligible → ACK and `published_at` | 2,041.225 ms | 2,070.396 ms | 2,079.577 ms |
| Distribution restart | gRPC serving → Provider `READY` at version 3 | 48.152 ms | 71.114 ms | 1,273.063 ms |
| Credential dependency | repository available → authenticated stream and Provider `READY` | 53.910 ms | 86.205 ms | 160.026 ms |

The pre-commit scenario produced 30 failed attempts with zero partial residue, then 30 successful publications ending at Snapshot version 30. The post-commit scenario reconciled all 30 committed operations and rejected all 30 blind retries. Kafka recovered all 30 stable event IDs with zero unpublished rows while preserving all 30 stored retry schedules and performing zero retry-state mutations; the interval includes the application's two-second first retry and a 25 ms experiment poll. Both runtime scenarios observed 30 `READY_STALE` transitions, preserved local evaluation, and returned to the authoritative version. Credential injection produced exactly one retryable unavailable response in each of 30 independently baselined cycles.

## Result

`PASS`. Every predeclared p95/max target and convergence invariant passed in the recorded local topology. The Distribution restart maximum contains one 1.273-second outlier, retained in the population; its p95 remained 71.114 ms and no sample was excluded.

## Integrity

The bundle contains `control-plane-recovery.json`, `runtime-recovery.json`, `environment.txt`, `git-status.txt`, and `SHA256SUMS`. Both raw JSON files record `workloadResult: pass`; this README owns lifecycle promotion. Phase 9 verification checks both results, clean commit/tree identity, checksums, secret guards, retry-state preservation, per-cycle credential fault proof, and tamper-negative behavior.

## Limitations

- PostgreSQL and Kafka run as single local containers; failover election, replication, storage recovery, and production connection pools are excluded.
- Post-commit response loss is injected with transaction synchronization after the real database commit.
- Credential dependency failure is injected at the repository boundary rather than by cutting the PostgreSQL network, so it validates retry/status/convergence behavior but not database socket recovery.
- Distribution restarts in-process on the same local port; Kubernetes scheduling, Service routing, Pod readiness, and multi-replica behavior remain separate gates.
- Provider reconnect backoff is 10–100 ms with zero jitter for repeatable local timing and is not the production default.
- Kafka uses the production two-second first retry delay but a 25 ms experiment poll, so production scheduler cadence is excluded.
