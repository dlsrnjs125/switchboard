# EV-P09-PRP-001 — Publish Propagation Baseline

- Status: PASS
- Phase: Phase 9 — Performance & Operations Evidence
- Owner: Switchboard maintainers
- Related: `SLI-PRP-001`, `SLI-OBX-001`, `ADR-005`, `ADR-006`, `FM-KFK-001`

## Claim

The workload measures one real publication path from PostgreSQL transaction commit through Outbox relay, Kafka broker acknowledgement, Distribution authoritative reconciliation, Java Provider atomic apply and durable LKG write, and a server-accepted SDK ACK.

## Workload

- one PostgreSQL `18.6-alpine` Testcontainer;
- one Spring Embedded KRaft broker (Apache Kafka 4.2.1) and one topic/partition;
- one Control Plane service object, one Outbox relay, one Distribution process, and one Java Provider;
- five warm-up publications and 30 measured sequential publications;
- a new immutable revision and full Snapshot for every publication;
- 1 ms polling resolution for SDK apply and server ACK observations.

Each Draft Revision is created and committed before `publishStarted`. The Publish start boundary therefore covers only the Control Plane publication transaction and excludes revision-authoring latency.

The environment fingerprint distinguishes `kafka_project_baseline_image`, the Docker Compose baseline used elsewhere in the repository, from `kafka_test_runtime_version` and `kafka_test_mode`, which identify the Embedded Kafka runtime used by this workload.

## Command

```bash
make phase9-publish-evidence
```

## Immutable-commit observed result

| Stage | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| Publish start → transaction commit | 25.916 ms | 30.961 ms | 31.935 ms |
| Commit → broker ACK | 16.046 ms | 18.509 ms | 22.490 ms |
| Broker ACK → Distribution apply | 14.010 ms | 17.682 ms | 23.529 ms |
| Distribution apply → SDK apply observed | 10.351 ms | 15.552 ms | 18.514 ms |
| SDK apply observed → server ACK observed | 12.384 ms | 23.483 ms | 24.102 ms |
| Commit → SDK ACK observed | 53.813 ms | 68.103 ms | 76.369 ms |
| Publish start → SDK ACK observed | 80.378 ms | 99.064 ms | 101.595 ms |

## Boundary semantics

The SDK sends ACK only after validation, atomic in-memory apply, and durable LKG replacement. The terminal timestamp is the Distribution server's accepted ACK observation. `SDK apply observed` is an external 1 ms polling observation of the provider's active version, so its sub-stage values include observation delay; `commit → SDK ACK observed` is the preferred end-to-end SLI in this record.

## Result status

`PASS` at source commit `1542b1c29286cef4f02e083d9ff8fb35c7bf75ef`. The pre-artifact source fingerprint records `git_dirty_count=0` and `git-status.txt` records `CLEAN`; stage ordering, zero-error completion, checksum verification, and tamper-negative regression all pass.

## Candidate comparison

| Primary boundary | Candidate p99 | Immutable p99 | Change |
| --- | ---: | ---: | ---: |
| Publish start → transaction commit | 35.865 ms | 31.935 ms | -11.0% |
| Commit → SDK ACK observed | 65.448 ms | 76.369 ms | +16.7% |
| Publish start → SDK ACK observed | 91.925 ms | 101.595 ms | +10.5% |

The single-client local result remains within the same order of magnitude but shows visible tail variability at 30 samples. This comparison is an evidence reproducibility check, not a production SLO or an alert-calibration input by itself.

## Artifacts

- `artifacts/publish-propagation.json`
- `artifacts/environment.txt`
- `artifacts/git-status.txt`
- `artifacts/SHA256SUMS`

## Limitations

This is a single-client, single-process, single-partition local topology. It does not include HTTP/JWT ingress, multiple Distribution replicas, WAN latency, concurrent publications, broker quorum/storage faults, or reconnect storms. It is not a production SLO.
