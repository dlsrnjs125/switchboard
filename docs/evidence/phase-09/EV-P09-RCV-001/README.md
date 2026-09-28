# EV-P09-RCV-001 — Timed Dependency and Process Recovery

- **Phase:** 9 — Performance & Operations Evidence
- **Status:** `PLANNED`
- **Evidence ID:** `EV-P09-RCV-001`
- **Git commit:** pending clean-source capture
- **Executed at:** pending
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
- Kafka container pause during outbox delivery, followed by broker ACK and persisted `published_at` after unpause;
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
- every process/dependency outage transitions the Provider through `READY_STALE`, preserves local evaluation, and returns to the authoritative Snapshot version.

## Observed

Pending clean-source execution.

## Result

`PLANNED`. The harness and success criteria are versioned, but lifecycle promotion requires a clean immutable-source run, raw artifacts, checksum verification, and documentation of observed percentiles.

## Integrity

The final bundle will contain `control-plane-recovery.json`, `runtime-recovery.json`, `environment.txt`, `git-status.txt`, and `SHA256SUMS`. Raw JSON records only reproducible workload outcome; this README owns lifecycle promotion.

## Limitations

- PostgreSQL and Kafka run as single local containers; failover election, replication, storage recovery, and production connection pools are excluded.
- Post-commit response loss is injected with transaction synchronization after the real database commit.
- Credential dependency failure is injected at the repository boundary rather than by cutting the PostgreSQL network, so it validates retry/status/convergence behavior but not database socket recovery.
- Distribution restarts in-process on the same local port; Kubernetes scheduling, Service routing, Pod readiness, and multi-replica behavior remain separate gates.
- Provider reconnect backoff is 10–100 ms with zero jitter for repeatable local timing and is not the production default.
