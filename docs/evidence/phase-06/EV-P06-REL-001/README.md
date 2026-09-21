# EV-P06-REL-001 — Dependency and Recovery Failure Drills

- **Status:** PASS
- **Phase:** Phase 6 — Reliability Hardening
- **Git commit:** `0d10623ab4622ac164d0600bc361aac10e68dcba`
- **Executed at:** 2026-09-21T05:46:16Z
- **Owner:** Switchboard maintainers
- **Related:** `FM-CP-001`, `FM-PG-001`, `FM-KFK-001`, `FM-DST-001`, `FM-SNP-001`, `FM-ORD-001`, `FM-GAP-001`, `FM-CRD-001`, `FM-TEN-001`, `FM-SDK-001`, `FM-RCN-001`, `FM-BKP-001`, ADR-001, ADR-005, ADR-006, ADR-009, ADR-012

## Claim

Within the declared single-node local envelope, injected dependency, process, ordering, credential, cache, reconnect, and backpressure failures preserve authoritative history, monotonic full-Snapshot state, bounded delivery/session state, and local LKG evaluation. Recovery is verified by state convergence, not process health alone.

## Environment fingerprint

| Item | Value |
| --- | --- |
| Host/OS/arch | macOS Darwin 25.6.0, arm64 |
| CPU/memory | Apple M1 Pro, 16 GiB |
| JDK | OpenJDK 17.0.19 in isolated compatibility-validation copy; repository toolchain remains Java 21 |
| Gradle | Wrapper 9.7.1 |
| Docker / Compose | Engine 29.5.3 / Compose 5.1.4 |
| PostgreSQL | 18.6 Alpine Testcontainer behind Toxiproxy 2.12.0 |
| Kafka | Apache Kafka 4.3.1 single-node KRaft container / embedded KRaft component broker |
| Distribution / SDK | Real localhost gRPC server/channel; one provider and one LKG file |

## Topology and workload

- One PostgreSQL container; Toxiproxy cuts every application connection before a publish transaction can commit. A separate deterministic transaction callback drops the application response immediately after a successful server commit.
- One Kafka container; broker process is paused for the first relay attempt and unpaused for recovery.
- One actual Distribution gRPC server stopped and restarted on the same port.
- One Java Provider performs 1,000 local evaluations while Distribution is stopped.
- Admission envelope is one allowed session plus one rejected concurrent session; authoritative Snapshot load count remains one.
- Kafka consumer processes a duplicated stable event, stops, restarts in the same group, and converges from Snapshot version 4 to 6.
- Outbox lease envelope is one crashed claim, one early reclaim rejection, one expired reclaim, and stale-token completion rejection.
- Disk LKG restart includes a valid active file plus an orphaned partial temporary artifact and an injected parent-directory fsync failure after atomic rename.

## Commands

```bash
./infra/reliability/phase-06-drill.sh all
./gradlew :sdk:java-openfeature-provider:test :services:control-plane:test :services:distribution:test --no-configuration-cache --rerun-tasks
./gradlew clean build --configuration-cache --rerun-tasks
./gradlew build --configuration-cache
docker compose -f infra/docker/docker-compose.yml config --quiet
```

## Expected

- [x] PostgreSQL pre-commit network loss leaves zero publication residue and recovery creates version 1 without reuse.
- [x] PostgreSQL post-commit response loss is reconciled by tenant-qualified authoritative re-read; blind retry creates no duplicate and the next write uses version 2.
- [x] Kafka outage leaves the committed outbox pending; recovery records broker ACK and `published_at`.
- [x] Expired relay lease is reclaimable and a stale claim token cannot complete delivery.
- [x] Distribution stop immediately makes the Provider stale while 1,000 local evaluations continue; restart returns it to ready.
- [x] Corrupt, stale, duplicate, gapped, or conflicting state never replaces a newer valid cache/LKG.
- [x] Credential revoke closes existing streams and blocks new authentication; rotation restores only the original scope.
- [x] Reconnect delays remain capped/jittered and admission rejects excess sessions before PostgreSQL Snapshot load.
- [x] Slow-client pending state is bounded to one latest full Snapshot.
- [x] Disk replacement fsyncs file content and parent-directory metadata; a post-rename fsync failure keeps memory and restart-visible versions aligned until redelivery confirms durability.
- [x] Every injected fault is removed during cleanup.

## Observed

- SDK Provider suite: 19 tests, 0 failures.
- Control Plane suite: 27 tests, 0 failures.
- Distribution suite: 18 tests, 0 failures.
- PostgreSQL cut drill completed in 4.334 seconds of JUnit test time with zero residual flag state, Snapshot, entry, audit, or outbox row before recovery.
- Post-commit response-loss drill observed a caller-side failure after the committed version 1 record set became authoritative; re-read found matching Snapshot/audit/outbox state, blind retry was rejected, and the next publication created version 2.
- Kafka pause/recovery drill completed in 3.393 seconds of JUnit test time; attempt count advanced from 1 failed attempt to 2 and `published_at` was present only after the recovered broker record was consumed.
- Distribution restart/LKG continuity drill completed in 6.549 seconds of JUnit test time; version 3 evaluated successfully 1,000 times during `READY_STALE` and returned to `READY` after restart.
- Duplicate/consumer-restart drill completed in 5.552 seconds of JUnit test time; two unique accepted versions produced exactly two logical applies and final cache version/checksum matched version 6.
- Admission returned gRPC `RESOURCE_EXHAUSTED` at the configured bound and the rejected request caused no second authoritative Snapshot load.
- Corrupt Distribution and SDK candidates retained prior version/checksum; a later valid higher version restored current state.
- Post-rename parent-directory fsync failure kept the active memory Snapshot and restart-visible LKG on the same version, exposed `READY_STALE`, NACKed durability uncertainty, rejected same-version heartbeat promotion with a resync request, and ACKed only after redelivery confirmed directory durability.
- Rotated credential authenticated only for its bound project/environment and the raw secret was not stored.
- Full multi-module build and configuration-cache reuse are recorded by the commands above; Java 21 CI remains the PR gate.

## Result

All predeclared safety and convergence assertions passed for the recorded local topology. The result supports Phase 6 reliability behavior for single-node dependency outages and a small reconnect/backpressure envelope. It does not establish production capacity, multi-node consensus behavior, or a quantified operational SLO.

## Artifact paths

- `services/control-plane/build/test-results/test/`
- `services/distribution/build/test-results/test/`
- `sdk/java-openfeature-provider/build/test-results/test/`
- `infra/reliability/phase-06-drill.sh`
- `docs/testing/failure-to-evidence-matrix.md`
- `docs/operations/runbook.md`

## Limitations

- The post-commit drill uses a deterministic Spring transaction callback fault immediately after successful commit; it validates ambiguous-response reconciliation but does not inject packet loss between PostgreSQL COMMIT and its wire acknowledgement.
- Kafka uses one local broker and a process pause, not a multi-broker quorum, disk corruption, or data-loss scenario.
- Distribution restart is a real gRPC listener stop/start within one JVM, not `SIGKILL`, container eviction, or Kubernetes rolling update.
- The admission and slow-client checks prove bounds and isolation logic, not 100–1,000-client throughput or recovery percentiles.
- JUnit testcase durations include setup/assertions and are not production recovery-time objectives.
- Filesystem fsync calls and restart behavior are covered, but sudden host power loss is not injected.
- Telemetry completeness, alerts, dashboards, and trace correlation remain Phase 7 scope.
