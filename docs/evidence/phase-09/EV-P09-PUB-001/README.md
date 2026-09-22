# EV-P09-PUB-001 — Control Plane Publish Transaction Baseline

- Status: PLANNED
- Phase: Phase 9 — Performance & Operations Evidence
- Owner: Switchboard maintainers
- Related: `SLI-PRP-001`, `SLI-OBX-001`, `INV-006`, `ADR-006`, `ADR-008`

## Claim

The workload measures the actual PostgreSQL publication transaction that advances the environment version and atomically commits the immutable Snapshot, snapshot entries, audit event, and outbox intent. It separates Snapshot compile and validation time from the remaining persistence/commit boundary for 1, 100, and 1,000 flags.

## Workload

- PostgreSQL `18.6-alpine` Testcontainer;
- 1, 100, and 1,000 published Boolean flags;
- five warm-up publications and 30 measured publications per flag count;
- one sequential publisher with no think time;
- every measured operation verifies a committed outbox row and environment version advance;
- Snapshot payload bytes are queried from the committed `jsonb` value.

## Command

```bash
make phase9-publish-evidence
```

## Preliminary observed result

| Flags | Snapshot bytes | Transaction + outbox commit p50 / p95 / p99 | Sequential throughput |
| ---: | ---: | --- | ---: |
| 1 | 538 | 41.294 / 50.649 / 53.079 ms | 23.76 ops/s |
| 100 | 23,686 | 195.152 / 263.299 / 279.387 ms | 4.87 ops/s |
| 1,000 | 236,086 | 1,476.007 / 1,626.257 / 2,139.466 ms | 0.67 ops/s |

The retained JSON also contains compile, validation, and derived remaining-transaction-path percentiles. `remainingTransactionPathLatencyMicros` is calculated as transaction wall time minus the directly timed compile and validation calls; it includes tenant/project/environment and revision lookups, lock acquisition, PostgreSQL reads, Snapshot/audit/outbox writes, deferred constraints, and commit overhead rather than claiming a persistence-only or single SQL statement duration.

## Result status

The workload and raw-result schema pass locally. The record remains `PLANNED` until rerun on an immutable commit and its environment fingerprint and checksum manifest are preserved from that run.

## Artifacts

- `artifacts/publish-transaction.json`
- `artifacts/environment.txt`
- `artifacts/git-status.txt`
- `artifacts/SHA256SUMS`

## Limitations

This is single-threaded sequential throughput in a local Testcontainer topology. It does not establish concurrent authoring capacity, HTTP/JWT overhead, connection-pool saturation, production storage latency, or broker propagation. Kafka-to-SDK timing is owned by `EV-P09-PRP-001`.
