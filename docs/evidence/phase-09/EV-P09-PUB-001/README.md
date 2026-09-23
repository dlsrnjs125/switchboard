# EV-P09-PUB-001 — Control Plane Publish Transaction Baseline

- Status: PASS
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

The transaction timer stops immediately when the transaction wrapper returns after commit. The outbox-existence assertion and Snapshot-size query execute afterward and are excluded from every latency and throughput sample.

## Command

```bash
make phase9-publish-evidence
```

## Immutable-commit observed result

| Flags | Snapshot bytes | Transaction + outbox commit p50 / p95 / p99 | Sequential throughput |
| ---: | ---: | --- | ---: |
| 1 | 538 | 29.746 / 34.401 / 42.957 ms | 33.51 ops/s |
| 100 | 23,686 | 180.722 / 233.737 / 251.582 ms | 5.21 ops/s |
| 1,000 | 236,086 | 1,399.203 / 1,477.375 / 1,623.399 ms | 0.71 ops/s |

The retained JSON also contains compile, validation, and derived remaining-transaction-path percentiles. `remainingTransactionPathLatencyMicros` is calculated as transaction wall time minus the directly timed compile and validation calls; it includes tenant/project/environment and revision lookups, lock acquisition, PostgreSQL reads, Snapshot/audit/outbox writes, deferred constraints, and commit overhead rather than claiming a persistence-only or single SQL statement duration.

## Result status

`PASS` at source commit `1542b1c29286cef4f02e083d9ff8fb35c7bf75ef`. The pre-artifact source fingerprint records `git_dirty_count=0` and `git-status.txt` records `CLEAN`; the workload assertions, raw-result schema, checksum verification, and tamper-negative regression all pass.

## Candidate comparison

| Flags | Candidate p99 | Immutable p99 | Change |
| ---: | ---: | ---: | ---: |
| 1 | 40.873 ms | 42.957 ms | +5.1% |
| 100 | 246.979 ms | 251.582 ms | +1.9% |
| 1,000 | 2,163.353 ms | 1,623.399 ms | -25.0% |

The lower 1,000-flag tail and smaller changes at 1/100 flags demonstrate run-to-run variability in this 30-sample local topology. This comparison has no production regression threshold and does not widen the claim beyond the recorded environment.

## Artifacts

- `artifacts/publish-transaction.json`
- `artifacts/environment.txt`
- `artifacts/git-status.txt`
- `artifacts/SHA256SUMS`

## Limitations

This is single-threaded sequential throughput in a local Testcontainer topology. It does not establish concurrent authoring capacity, HTTP/JWT overhead, connection-pool saturation, production storage latency, or broker propagation. Kafka-to-SDK timing is owned by `EV-P09-PRP-001`.
